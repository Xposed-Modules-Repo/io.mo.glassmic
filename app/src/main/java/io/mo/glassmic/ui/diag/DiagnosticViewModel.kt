package io.mo.glassmic.ui.diag

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.os.Build
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioClipEntity
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.diag.AudioPipelineProbe
import io.mo.glassmic.data.diag.DiagnosticBundler
import io.mo.glassmic.data.runtime.AudioInterceptStats
import io.mo.glassmic.data.runtime.AudioStatsRepository
import io.mo.glassmic.data.runtime.BootGateRepository
import io.mo.glassmic.data.runtime.DecisionRecord
import io.mo.glassmic.data.runtime.EffectiveSourceResolver
import io.mo.glassmic.data.runtime.HookActivity
import io.mo.glassmic.data.runtime.HookStatus
import io.mo.glassmic.data.runtime.HookStatusRepository
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.data.runtime.VisibilityCompatRepository
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.memory.MemoryProbe
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.LogLevel
import io.mo.glassmic.service.FloatingWindowService
import io.mo.glassmic.service.GlassForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceEnvInfo(
    val model: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val osVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val abiList: String = Build.SUPPORTED_ABIS.joinToString(", "),
    val memoryPssKb: Long = 0L,
    val heapUsedKb: Long = 0L,
    val heapMaxKb: Long = 0L,
    val heapRatio: Float = 0f,
    val visibilityCompat: Boolean = false
)

data class ActiveAudioInfo(
    val sourceType: SourceType = SourceType.REAL_MIC,
    val displayName: String = "—",
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val mimeType: String = "—"
)

data class DiagnosticUiState(
    val deviceEnv: DeviceEnvInfo = DeviceEnvInfo(),
    val config: AppConfig = AppConfig.getDefaultInstance(),
    val hook: HookStatus = HookStatus(HookActivity.NEVER_PINGED, 0L, null, 0),
    val stats: AudioInterceptStats = AudioInterceptStats(0L, 0L, 0L, null, 0, 0),
    val safeModeActive: Boolean = false,
    val bootGateUnlocked: Boolean = true,
    val runtimeServiceEnabled: Boolean = false,
    val audioInfo: ActiveAudioInfo = ActiveAudioInfo(),
    val decisions: List<DecisionRecord> = emptyList(),
    val probing: Boolean = false,
    val probeResult: AudioPipelineProbe.Result? = null,
    val auditionPlaying: Boolean = false,
    val exporting: Boolean = false,
    val exportedUri: Uri? = null,
    val exportError: String? = null
)

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore,
    private val hookStatusRepo: HookStatusRepository,
    private val audioStatsRepo: AudioStatsRepository,
    private val safeModeRepo: SafeModeRepository,
    private val bootGateRepo: BootGateRepository,
    private val runtimeStateHolder: RuntimeStateHolder,
    private val sourceResolver: EffectiveSourceResolver,
    private val audioDao: AudioDao,
    private val probe: AudioPipelineProbe,
    private val bundler: DiagnosticBundler,
    private val visibilityCompatRepo: VisibilityCompatRepository
) : ViewModel() {

    private val _probing = MutableStateFlow(false)
    private val _probeResult = MutableStateFlow<AudioPipelineProbe.Result?>(null)
    private val _auditionPlaying = MutableStateFlow(false)
    private val _exporting = MutableStateFlow(false)
    private val _exportedUri = MutableStateFlow<Uri?>(null)
    private val _exportError = MutableStateFlow<String?>(null)
    private val _decisions = MutableStateFlow<List<DecisionRecord>>(emptyList())
    private val _memorySnapshot = MutableStateFlow(MemoryProbe.snapshot())

    private var wasMasterEnabledInitially: Boolean? = null

    private var auditionTrack: AudioTrack? = null
    private var auditionJob: Job? = null

    val state: StateFlow<DiagnosticUiState> = combine(
        combine(configStore.flow, hookStatusRepo.flow, audioStatsRepo.flow, runtimeStateHolder.flow, audioDao.observeAllClips()) { cfg, hook, stats, rt, clips ->
            val clip = clips.firstOrNull { it.id == cfg.currentAudioId }
            val audioInfo = ActiveAudioInfo(
                sourceType = rt.currentSourceType,
                displayName = clip?.displayName ?: rt.currentSourceType.name,
                durationMs = clip?.durationMs ?: rt.durationMs,
                sizeBytes = clip?.sizeBytes ?: 0L,
                sampleRate = clip?.sampleRate ?: 0,
                channels = clip?.channels ?: 0,
                mimeType = clip?.mimeType ?: "audio/raw"
            )
            DiagnosticStateCore(cfg, hook, stats, rt.enabled, audioInfo)
        },
        combine(_probing, _probeResult, _auditionPlaying, _memorySnapshot) { prb, res, aud, mem ->
            ProbeAndMemory(prb, res, aud, mem)
        },
        combine(_exporting, _exportedUri, _exportError, _decisions) { exp, uri, err, dec ->
            ExportAndDecisions(exp, uri, err, dec)
        }
    ) { core, probeMem, expState ->
        val mem = probeMem.mem
        val devInfo = DeviceEnvInfo(
            memoryPssKb = mem.pssKb,
            heapUsedKb = mem.heapUsedKb,
            heapMaxKb = mem.heapMaxKb,
            heapRatio = mem.heapRatio,
            visibilityCompat = visibilityCompatRepo.isEnabled()
        )

        DiagnosticUiState(
            deviceEnv = devInfo,
            config = core.cfg,
            hook = core.hook,
            stats = core.stats,
            safeModeActive = safeModeRepo.isActive(),
            bootGateUnlocked = bootGateRepo.userEnabledAfterBoot(),
            runtimeServiceEnabled = core.serviceEnabled,
            audioInfo = core.audioInfo,
            decisions = expState.decisions,
            probing = probeMem.probing,
            probeResult = probeMem.probeResult,
            auditionPlaying = probeMem.auditionPlaying,
            exporting = expState.exporting,
            exportedUri = expState.exportedUri,
            exportError = expState.exportError
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DiagnosticUiState())

    init {
        viewModelScope.launch {
            val cfg = configStore.current()
            val wasRunning = cfg.globalSwitch && bootGateRepo.userEnabledAfterBoot() && !safeModeRepo.isActive()
            wasMasterEnabledInitially = wasRunning

            if (!wasRunning && !safeModeRepo.isActive()) {
                bootGateRepo.markEnabledForThisBoot()
                configStore.update { it.setGlobalSwitch(true) }
                GlassForegroundService.start(context)
            }
        }
        refreshAll()
    }

    fun restoreInitialState() {
        if (wasMasterEnabledInitially == false) {
            CoroutineScope(Dispatchers.IO).launch {
                configStore.update { it.setGlobalSwitch(false) }
                bootGateRepo.clear()
                GlassForegroundService.stop(context)
                FloatingWindowService.stop(context)
            }
        }
    }

    fun refreshAll() {
        _memorySnapshot.value = MemoryProbe.snapshot()
        refreshDecisions()
    }

    fun refreshDecisions() {
        _decisions.value = sourceResolver.getRecentDecisions()
    }

    // ============ 管线推流自检 ============
    fun runPipelineProbe() {
        if (_probing.value) return
        stopAudition()
        _probing.value = true
        _probeResult.value = null
        viewModelScope.launch {
            val res = probe.probe(durationMs = 1500)
            _probeResult.value = res
            _probing.value = false
            refreshAll()
        }
    }

    // ============ 试听推流数据 ============
    fun toggleAudition() {
        if (_auditionPlaying.value) {
            stopAudition()
        } else {
            val res = _probeResult.value ?: return
            val pcm = res.pcmData ?: return
            if (pcm.isEmpty()) return

            stopAudition()
            _auditionPlaying.value = true

            auditionJob = viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val chMask = if (res.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(res.sampleRate)
                                .setChannelMask(chMask)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(pcm.size)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                    auditionTrack = track
                    track.write(pcm, 0, pcm.size)
                    track.play()

                    val durationMs = (pcm.size.toLong() * 1000L) / (res.sampleRate * res.channels * 2)
                    kotlinx.coroutines.delay(durationMs + 200)
                }
                stopAudition()
            }
        }
    }

    fun stopAudition() {
        auditionJob?.cancel()
        auditionJob = null
        runCatching {
            auditionTrack?.stop()
            auditionTrack?.release()
        }
        auditionTrack = null
        _auditionPlaying.value = false
    }

    // ============ 日志级别 ============
    fun setLogLevel(level: LogLevel) = viewModelScope.launch {
        configStore.update { it.setLogging(it.logging.toBuilder().setLevel(level).setEnabled(level != LogLevel.OFF)) }
        GlassLog.level = when (level) {
            LogLevel.OFF -> io.mo.glassmic.core.model.LogLevel.OFF
            LogLevel.BASIC -> io.mo.glassmic.core.model.LogLevel.BASIC
            LogLevel.VERBOSE -> io.mo.glassmic.core.model.LogLevel.VERBOSE
            LogLevel.DEBUG -> io.mo.glassmic.core.model.LogLevel.DEBUG
            else -> io.mo.glassmic.core.model.LogLevel.BASIC
        }
        GlassLog.enabled = level != LogLevel.OFF
    }

    fun clearLog() {
        GlassLog.clear()
    }

    // ============ 诊断包导出 ============
    fun exportDiagnostic() = viewModelScope.launch {
        _exporting.value = true
        _exportError.value = null
        runCatching { bundler.export() }
            .onSuccess { _exportedUri.value = bundler.shareUri(it) }
            .onFailure { _exportError.value = it.message ?: "导出失败" }
        _exporting.value = false
    }

    fun consumeExport() {
        _exportedUri.value = null
        _exportError.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopAudition()
        restoreInitialState()
    }

    private data class DiagnosticStateCore(
        val cfg: AppConfig,
        val hook: HookStatus,
        val stats: AudioInterceptStats,
        val serviceEnabled: Boolean,
        val audioInfo: ActiveAudioInfo
    )

    private data class ProbeAndMemory(
        val probing: Boolean,
        val probeResult: AudioPipelineProbe.Result?,
        val auditionPlaying: Boolean,
        val mem: MemoryProbe.Snapshot
    )

    private data class ExportAndDecisions(
        val exporting: Boolean,
        val exportedUri: Uri?,
        val exportError: String?,
        val decisions: List<DecisionRecord>
    )
}
