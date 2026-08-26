package io.mo.glassmic.ui.diag

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.R
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.diag.AudioPipelineProbe
import io.mo.glassmic.data.runtime.DecisionRecord
import io.mo.glassmic.data.runtime.HookActivity
import io.mo.glassmic.proto.LogLevel
import io.mo.glassmic.proto.PlaybackPolicy
import io.mo.glassmic.proto.ScopeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    onBack: () -> Unit,
    vm: DiagnosticViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose {
            vm.restoreInitialState()
        }
    }

    LaunchedEffect(state.exportedUri) {
        val uri = state.exportedUri ?: return@LaunchedEffect
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "导出诊断包")) }
        vm.consumeExport()
    }

    LaunchedEffect(state.exportError) {
        state.exportError?.let { snackbar.showSnackbar(it); vm.consumeExport() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::refreshAll) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(snackbarData = it) } }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ============ 1. 全链路健康自检 ============
            item {
                DiagSection(stringResource(R.string.diag_section_health)) {
                    // LSPosed 注入
                    val hookActivityText = when (state.hook.activity) {
                        HookActivity.ACTIVE -> "活跃 (API ${state.hook.api})"
                        HookActivity.STALE -> "已加载 (无近期事件)"
                        HookActivity.NEVER_PINGED -> "未检测到注入"
                    }
                    val hookColor = when (state.hook.activity) {
                        HookActivity.ACTIVE -> Color(0xFF34C759)
                        HookActivity.STALE -> Color(0xFFFFB020)
                        HookActivity.NEVER_PINGED -> Color(0xFFE5484D)
                    }
                    DiagRow(
                        label = stringResource(R.string.diag_health_lsposed),
                        value = hookActivityText,
                        valueColor = hookColor,
                        statusDotColor = hookColor,
                        subtitle = if (state.hook.lastPingMs > 0) "最后心跳: ${formatTimestamp(state.hook.lastPingMs)}" else "请确认已在 LSPosed 启用并勾选「系统框架」与目标"
                    )

                    // 前台服务与运行态
                    val serviceOk = state.runtimeServiceEnabled && state.config.globalSwitch && state.bootGateUnlocked && !state.safeModeActive
                    val serviceText = when {
                        state.safeModeActive -> "安全模式生效中"
                        !state.bootGateUnlocked -> "重启保护待解锁"
                        !state.config.globalSwitch -> "总开关关闭"
                        !state.runtimeServiceEnabled -> "前台服务未启动"
                        else -> "正常运行中"
                    }
                    val serviceColor = if (serviceOk) Color(0xFF34C759) else Color(0xFFE5484D)
                    DiagRow(
                        label = stringResource(R.string.diag_health_service),
                        value = serviceText,
                        valueColor = serviceColor,
                        statusDotColor = serviceColor,
                        subtitle = if (serviceOk) "运行态正常，可响应 PCM 跨进程传输" else "服务未就绪，录音请求将回退到真麦"
                    )

                    // 生效范围
                    val scopeText = when (state.config.scopeMode) {
                        ScopeMode.GLOBAL -> "全系统模式"
                        ScopeMode.WHITELIST -> "白名单 (${state.config.whitelistCount} 个)"
                        ScopeMode.BLACKLIST -> "黑名单 (${state.config.blacklistCount} 个)"
                        else -> "全系统"
                    }
                    DiagRow(
                        label = stringResource(R.string.diag_health_scope),
                        value = scopeText,
                        valueColor = Color(0xFF34C759),
                        statusDotColor = Color(0xFF34C759),
                        subtitle = "若目标 App 没声音，请确认包名在生效清单内"
                    )

                    // 音频源就绪
                    val hasAudio = state.config.currentAudioId.isNotBlank() || state.audioInfo.displayName != "—"
                    val sourceColor = if (hasAudio) Color(0xFF34C759) else Color(0xFFFFB020)
                    DiagRow(
                        label = stringResource(R.string.diag_health_source),
                        value = state.audioInfo.displayName,
                        valueColor = sourceColor,
                        statusDotColor = sourceColor,
                        subtitle = if (hasAudio) "音源类型: ${state.audioInfo.sourceType.name}" else "未选定音频，建议前往音频库导入"
                    )
                }
            }

            // ============ 2. 设备与运行环境 ============
            item {
                DiagSection(stringResource(R.string.diag_section_device)) {
                    DiagRow(
                        label = stringResource(R.string.diag_device_model),
                        value = state.deviceEnv.model,
                        subtitle = state.deviceEnv.osVersion
                    )
                    DiagRow(
                        label = stringResource(R.string.diag_device_abi),
                        value = "arm64-v8a (64位)",
                        valueColor = Color(0xFF34C759),
                        subtitle = "设备支持: ${state.deviceEnv.abiList.take(32)}…"
                    )
                    val pssMb = state.deviceEnv.memoryPssKb / 1024.0
                    val heapMb = state.deviceEnv.heapUsedKb / 1024.0
                    val maxMb = state.deviceEnv.heapMaxKb / 1024.0
                    val ratioPct = (state.deviceEnv.heapRatio * 100).toInt()
                    DiagRow(
                        label = stringResource(R.string.diag_device_memory),
                        value = "PSS: %.1f MB".format(pssMb),
                        subtitle = "Java 堆: %.1f / %.1f MB (%d%%)".format(heapMb, maxMb, ratioPct)
                    )
                    DiagRow(
                        label = stringResource(R.string.diag_device_visibility),
                        value = if (state.deviceEnv.visibilityCompat) "已开启 (兼容高版本ROM)" else "关闭 (默认)",
                        valueColor = if (state.deviceEnv.visibilityCompat) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        subtitle = "若模块无效果，可在设置页开启此开关并授权 Root"
                    )
                }
            }

            // ============ 3. Native Hook 框架支持 ============
            item {
                DiagSection(stringResource(R.string.diag_section_native_hooks)) {
                    DiagRow(
                        label = stringResource(R.string.diag_hook_aaudio),
                        value = "AAudioStream_read",
                        valueColor = Color(0xFF34C759),
                        subtitle = "ShadowHook NDK 原生层拦截"
                    )
                    DiagRow(
                        label = stringResource(R.string.diag_hook_opensl),
                        value = "slCreateEngine / BQ",
                        valueColor = Color(0xFF34C759),
                        subtitle = "OpenSL ES 录音缓冲队列 Hook"
                    )
                    DiagRow(
                        label = stringResource(R.string.diag_hook_audiorecord_native),
                        value = "AudioRecord::read",
                        valueColor = Color(0xFF34C759),
                        subtitle = "libaudioclient.so C++ 实例拦截"
                    )
                }
            }

            // ============ 4. 音源参数与推流管线 ============
            item {
                DiagSection(stringResource(R.string.diag_section_audio_detail)) {
                    DiagRow(
                        label = stringResource(R.string.diag_audio_source_type),
                        value = state.audioInfo.sourceType.name,
                        valueColor = Color(0xFF007AFF)
                    )
                    DiagRow(
                        label = stringResource(R.string.diag_audio_name),
                        value = state.audioInfo.displayName
                    )
                    if (state.audioInfo.sampleRate > 0) {
                        DiagRow(
                            label = stringResource(R.string.diag_audio_format),
                            value = "${state.audioInfo.sampleRate} Hz · ${if (state.audioInfo.channels == 2) "立体声" else "单声道"}",
                            subtitle = "MIME: ${state.audioInfo.mimeType}"
                        )
                    }
                    if (state.audioInfo.durationMs > 0 || state.audioInfo.sizeBytes > 0) {
                        DiagRow(
                            label = stringResource(R.string.diag_audio_duration_size),
                            value = "%.1f 秒 · %s".format(state.audioInfo.durationMs / 1000.0, formatBytes(state.audioInfo.sizeBytes))
                        )
                    }
                    val policyName = when (state.config.playbackPolicy) {
                        PlaybackPolicy.LOOP -> "循环播放"
                        PlaybackPolicy.SILENCE -> "播完静音"
                        PlaybackPolicy.REAL_MIC -> "切回真麦"
                        else -> "循环"
                    }
                    DiagRow(
                        label = "播放策略",
                        value = policyName
                    )
                }
            }

            // ============ 5. 音频推流自检与试听 ============
            item {
                DiagSection(stringResource(R.string.diag_section_probe)) {
                    DiagButtonRow(
                        label = stringResource(R.string.diag_probe_run),
                        busy = state.probing,
                        busyText = "正在拉取 PCM 数据…",
                        onClick = vm::runPipelineProbe
                    )

                    state.probeResult?.let { r ->
                        ProbeResultCard(
                            result = r,
                            isPlaying = state.auditionPlaying,
                            onToggleAudition = vm::toggleAudition
                        )
                    }

                    Text(
                        stringResource(R.string.diag_probe_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            // ============ 6. 实时拦截统计 ============
            item {
                DiagSection(stringResource(R.string.settings_section_intercept)) {
                    DiagRow(
                        label = stringResource(R.string.settings_intercept_total_reads),
                        value = "${state.stats.totalReads} 次",
                        isMonoValue = true
                    )
                    DiagRow(
                        label = stringResource(R.string.settings_intercept_total_bytes),
                        value = formatBytes(state.stats.totalBytes),
                        isMonoValue = true
                    )
                    DiagRow(
                        label = stringResource(R.string.settings_intercept_last_pkg),
                        value = state.stats.lastPackage ?: "—",
                        subtitle = if (state.stats.lastInterceptMs > 0) "最近调用: ${formatTimestamp(state.stats.lastInterceptMs)}" else "暂无 App 触发录音"
                    )
                    if (state.stats.lastSampleRate > 0) {
                        DiagRow(
                            label = stringResource(R.string.settings_intercept_format),
                            value = "${state.stats.lastSampleRate} Hz / ${state.stats.lastChannels} ch"
                        )
                    }
                }
            }

            // ============ 7. 最近拦截决策追踪 ============
            item {
                DiagSection(stringResource(R.string.diag_section_decisions)) {
                    if (state.decisions.isEmpty()) {
                        Text(
                            stringResource(R.string.diag_decisions_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    } else {
                        state.decisions.take(20).forEach { d ->
                            DecisionItemRow(d)
                        }
                    }
                    Text(
                        stringResource(R.string.diag_decisions_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            // ============ 8. 日志管理与诊断包 ============
            item {
                DiagSection(stringResource(R.string.diag_section_logs)) {
                    LogLevelPickerRow(state.config.logging.level, vm::setLogLevel)
                    DiagButtonRow(
                        label = stringResource(R.string.settings_export_diag),
                        busy = state.exporting,
                        busyText = stringResource(R.string.diag_exporting),
                        onClick = vm::exportDiagnostic
                    )
                    DiagButtonRow(
                        label = stringResource(R.string.settings_clear_log),
                        onClick = vm::clearLog
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

// ============ 对齐统一的子组件 ============

@Composable
private fun DiagSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(16.dp)
                )
                .padding(vertical = 4.dp)
        ) { content() }
    }
}

/**
 * 左右严格对齐的基础信息行：
 * - 左侧：标题 + 可选状态圆点 + 可选副标题（严格从 x=16dp 起始）
 * - 右侧：数值（严格在右边界对齐）
 */
@Composable
private fun DiagRow(
    label: String,
    value: String,
    subtitle: String? = null,
    statusDotColor: Color? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
    isMonoValue: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (statusDotColor != null) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusDotColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Normal
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(
                        start = if (statusDotColor != null) 16.dp else 0.dp,
                        top = 2.dp
                    )
                )
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            fontFamily = if (isMonoValue) FontFamily.Monospace else null,
            textAlign = TextAlign.End,
            modifier = Modifier.wrapContentWidth(Alignment.End)
        )
    }
}

@Composable
private fun ProbeResultCard(
    result: AudioPipelineProbe.Result,
    isPlaying: Boolean,
    onToggleAudition: () -> Unit
) {
    val statusColor = if (result.ok && result.rms >= 1.0) Color(0xFF34C759)
    else if (result.bytesRead > 0) Color(0xFFFFB020)
    else Color(0xFFE5484D)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                result.message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        // RMS 振幅进度条
        val rmsNorm = (result.rms / 10000.0).toFloat().coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { rmsNorm },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = statusColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "读取: ${result.bytesRead} 字节  ·  耗时: ${result.durationMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                "RMS: %.1f".format(result.rms),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = statusColor
            )
        }

        if (result.pcmData != null && result.pcmData.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onToggleAudition,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPlaying) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (isPlaying) stringResource(R.string.diag_probe_audition_stop) else stringResource(R.string.diag_probe_audition),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun DecisionItemRow(record: DecisionRecord) {
    val tagColor = when (record.result) {
        SourceType.FILE, SourceType.TTS -> Color(0xFF34C759)
        SourceType.SILENCE -> Color(0xFF007AFF)
        SourceType.REAL_MIC -> Color(0xFF8E8E93)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.callerPackage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(tagColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        record.result.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = tagColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                record.reasonDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            formatTimeOnly(record.timestamp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
    }
}

@Composable
private fun DiagButtonRow(
    label: String,
    busy: Boolean = false,
    busyText: String = "处理中…",
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (busy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        if (busy) {
            Text(
                busyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun LogLevelPickerRow(current: LogLevel, onSelect: (LogLevel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        stringResource(R.string.settings_log_off) to LogLevel.OFF,
        stringResource(R.string.settings_log_basic) to LogLevel.BASIC,
        stringResource(R.string.settings_log_verbose) to LogLevel.VERBOSE,
        stringResource(R.string.settings_log_debug) to LogLevel.DEBUG
    )
    val currentLabel = options.firstOrNull { it.second == current }?.first ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.settings_log_level),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    currentLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = 0.dp, y = 4.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                options.forEach { (label, value) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onSelect(value); expanded = false },
                        trailingIcon = {
                            if (value == current) {
                                Icon(
                                    Icons.Filled.Check, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timeMs: Long): String {
    if (timeMs <= 0) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
}

private fun formatTimeOnly(timeMs: Long): String {
    if (timeMs <= 0) return "—"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}
