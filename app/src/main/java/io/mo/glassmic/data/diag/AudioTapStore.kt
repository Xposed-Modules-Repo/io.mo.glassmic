package io.mo.glassmic.data.diag

import android.content.Context
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * 调试"回放抓取"：保存 native hook 最终交给目标 App 的 PCM（见 glass_audio_tap.h）。
 *
 * - 只在日志级别为 DEBUG 时接受目标进程的 /tap 请求；抓取内容只有 FILE/TTS 注入的音频，不含真实麦克风。
 * - 原始记录落在 filesDir/audio_tap/ 下的 .gmtap 文件，不进入诊断包；由用户单独导出为 WAV + 索引。
 * - 写入由本进程控制上限，目标进程无法借此无限写盘。
 */
@Singleton
class AudioTapStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore
) {

    private companion object {
        const val DIR = "audio_tap"
        const val EXT = ".gmtap"
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        const val MAX_FILES = 30
        const val HEADER_BYTES = 56
        const val MAGIC = 0x50544D47
        const val MAX_EVENTS_PER_STREAM = 500
        // 相邻回调间隔超出上一块时长这么多，就视为目标 App 停止/卡顿取数。
        const val STALL_TOLERANCE_MS = 50L
    }

    private val dir: File get() = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun isEnabled(): Boolean = runCatching {
        runBlocking { configStore.current() }.logging.level == LogLevel.DEBUG
    }.getOrDefault(false)

    /** 由 PcmStreamProvider 在 binder 线程调用；返回 socketpair 写端交给目标进程。 */
    fun openSink(callerPackage: String, pid: Int): ParcelFileDescriptor {
        if (!isEnabled()) throw FileNotFoundException("audio tap disabled")
        prune()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val safePkg = callerPackage.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, "${safePkg}_${pid}_$ts$EXT")
        val pair = ParcelFileDescriptor.createSocketPair()
        val readSide = pair[0]
        thread(name = "GlassTapSink", isDaemon = true) {
            var written = 0L
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (written < MAX_FILE_BYTES) {
                            val n = input.read(buf)
                            if (n < 0) break
                            val keep = minOf(n.toLong(), MAX_FILE_BYTES - written).toInt()
                            out.write(buf, 0, keep)
                            written += keep
                        }
                    }
                }
            }
            if (written == 0L) target.delete()
            GlassLog.d("AudioTap") { "sink closed: ${target.name} bytes=$written" }
        }
        GlassLog.d("AudioTap") { "sink opened: ${target.name}" }
        return pair[1]
    }

    fun rawFiles(): List<File> =
        dir.listFiles { f -> f.name.endsWith(EXT) && f.length() > 0L }
            ?.sortedBy { it.lastModified() }
            .orEmpty()

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(EXT) }?.sortedByDescending { it.lastModified() }
            ?: return
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if (i >= MAX_FILES - 1 || total > MAX_TOTAL_BYTES) f.delete()
        }
    }

    /** 导出 WAV、逐记录时序 JSONL 和 index.json；旧版原始抓取也包含所需时间戳。 */
    suspend fun export(): File = withContext(Dispatchers.IO) {
        val raws = rawFiles()
        if (raws.isEmpty()) throw IllegalStateException("没有可导出的回放抓取，请先在 DEBUG 日志级别下复现问题")
        val outDir = File(context.filesDir, "diagnostics").apply { if (!exists()) mkdirs() }
        val work = File(context.cacheDir, "audio_tap_export").apply { deleteRecursively(); mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(outDir, "glassmic-tap-$ts.zip")
        val index = JSONArray()
        try {
            raws.forEach { raw -> index.put(convert(raw, work)) }
            ZipOutputStream(FileOutputStream(target)).use { zip ->
                zip.putNextEntry(ZipEntry("index.json"))
                zip.write(JSONObject().apply {
                    put("generated_at", System.currentTimeMillis())
                    put("note", "WAV 为交给目标 App 回调前的 PCM（转 PCM16），按样本拼接，不保留实际交付间隔；" +
                        "WAV 连贯不能排除时序问题。timing_file 为逐记录时间戳，timing 为间隔统计；" +
                        "gaps=GlassMic 补零；stalls=间隔超过上一块时长 50ms；tap_loss=抓取丢失，非交付断音。" +
                        "间隔偏差不等同于丢音，也可能是系统批量回调。wav_ms 为该流 WAV 内的位置。")
                    put("files", index)
                }.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                work.listFiles()?.sortedBy { it.name }?.forEach { wav ->
                    zip.putNextEntry(ZipEntry(wav.name))
                    FileInputStream(wav).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } finally {
            work.deleteRecursively()
        }
        GlassLog.b("AudioTap") { "回放抓取已导出: ${target.name} size=${target.length()}" }
        target
    }

    private class StreamState(
        val slot: Int,
        val segment: Int,
        val sampleRate: Int,
        val channels: Int,
        val path: Int,
        val srcFormat: Int,
        val wav: WavWriter
    ) {
        var frames = 0L
        var records = 0L
        var missingFrames = 0L
        val timing = TapTimingStats(sampleRate)
        var firstRealtimeMs = 0L
        val gaps = JSONArray()
        val stalls = JSONArray()
        val tapLoss = JSONArray()
        var openGap: JSONObject? = null

        fun wavMs(): Long = frames * 1000L / sampleRate
    }

    private fun convert(raw: File, work: File): JSONObject {
        val base = raw.name.removeSuffix(EXT)
        val streams = HashMap<Int, StreamState>()
        val finished = JSONArray()
        var lastSeq = -1L
        var totalLostRecords = 0L
        var truncated = false
        var firstRealtime = 0L
        var lastRealtime = 0L
        var segments = 0
        val timingFile = File(work, "$base.timing.jsonl")

        timingFile.bufferedWriter().use { timingWriter ->
            DataInputStream(BufferedInputStream(FileInputStream(raw), 256 * 1024)).use { input ->
                val head = ByteArray(HEADER_BYTES)
                val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                var payload = ByteArray(0)
                while (true) {
                    try {
                        input.readFully(head)
                    } catch (_: EOFException) {
                        break
                    }
                    hb.rewind()
                    val magic = hb.int
                    if (magic != MAGIC) { truncated = true; break }
                    hb.short // version
                    val headerBytes = hb.short.toInt() and 0xFFFF
                    val seq = hb.int.toLong() and 0xFFFFFFFFL
                    val slot = hb.short.toInt() and 0xFFFF
                    val path = hb.short.toInt() and 0xFFFF
                    val sr = hb.int
                    val ch = hb.short.toInt() and 0xFFFF
                    val fmt = hb.short.toInt() and 0xFFFF
                    val frames = hb.int
                    val missing = hb.int
                    val realtimeMs = hb.long
                    val monoNs = hb.long
                    val payloadBytes = hb.int
                    if (headerBytes > HEADER_BYTES) input.skipBytes(headerBytes - HEADER_BYTES)
                    if (payload.size < payloadBytes) payload = ByteArray(payloadBytes)
                    try {
                        input.readFully(payload, 0, payloadBytes)
                    } catch (_: EOFException) {
                        truncated = true
                        break
                    }
                    if (sr <= 0 || ch <= 0 || frames <= 0) continue
                    if (firstRealtime == 0L) firstRealtime = realtimeMs
                    lastRealtime = realtimeMs

                    // seq 是进程内全局计数，跳号说明抓取环形缓冲满或锁竞争丢了记录。
                    val lost = if (lastSeq >= 0) seq - lastSeq - 1 else 0L
                    lastSeq = seq
                    if (lost > 0) totalLostRecords += lost

                    var s = streams[slot]
                    if (s != null && (s.sampleRate != sr || s.channels != ch || s.path != path || s.srcFormat != fmt)) {
                        finished.put(finish(s))
                        s = null
                    }
                    if (s == null) {
                        val segment = segments++
                        val name = "${base}_s${slot}_seg$segment.wav"
                        s = StreamState(slot, segment, sr, ch, path, fmt, WavWriter(File(work, name), sr, ch))
                        s.firstRealtimeMs = realtimeMs
                        streams[slot] = s
                    }

                    if (lost > 0) addEvent(s.tapLoss, JSONObject().apply {
                        put("wav_ms", s.wavMs()); put("time", realtimeMs); put("lost_records", lost)
                    })
                    val interval = s.timing.observe(monoNs, frames, totalLostRecords)
                    timingWriter.write(JSONObject().apply {
                        put("seq", seq)
                        put("slot", slot)
                        put("segment", s.segment)
                        put("path", path)
                        put("sample_rate", sr)
                        put("channels", ch)
                        put("src_format", fmt)
                        put("wav_frame", s.frames)
                        put("frames", frames)
                        put("missing_frames", missing)
                        put("time", realtimeMs)
                        // Decimal string preserves nanoseconds in JavaScript JSON consumers.
                        put("monotonic_ns", monoNs.toString())
                        put("tap_lost_records_total", totalLostRecords)
                        put("interval_ns", interval?.actualNs ?: JSONObject.NULL)
                        put("expected_interval_ns", interval?.expectedNs ?: JSONObject.NULL)
                        put("interval_error_ns", interval?.errorNs ?: JSONObject.NULL)
                    }.toString())
                    timingWriter.newLine()
                    if (interval != null) {
                        if (interval.errorNs > STALL_TOLERANCE_MS * 1_000_000L) addEvent(s.stalls, JSONObject().apply {
                            put("wav_ms", s.wavMs()); put("time", realtimeMs)
                            put("interval_ms", interval.actualNs / 1_000_000.0)
                            put("expected_ms", interval.expectedNs / 1_000_000.0)
                        })
                    }
                    if (missing > 0) {
                        val gap = s.openGap
                        if (gap != null) {
                            gap.put("missing_ms", gap.getLong("missing_ms") + missing * 1000L / sr)
                            gap.put("records", gap.getInt("records") + 1)
                        } else {
                            val g = JSONObject().apply {
                                put("wav_ms", s.wavMs()); put("time", realtimeMs)
                                put("missing_ms", missing * 1000L / sr); put("records", 1)
                            }
                            if (addEvent(s.gaps, g)) s.openGap = g
                        }
                    } else {
                        s.openGap = null
                    }

                    s.wav.write(payload, payloadBytes)
                    s.frames += frames
                    s.records++
                    s.missingFrames += missing
                }
            }
        }
        streams.values.forEach { finished.put(finish(it)) }

        return JSONObject().apply {
            put("source", raw.name)
            put("timing_file", timingFile.name)
            put("first_record_time", firstRealtime)
            put("last_record_time", lastRealtime)
            put("tap_lost_records", totalLostRecords)
            put("truncated", truncated)
            put("streams", finished)
        }
    }

    private fun addEvent(list: JSONArray, e: JSONObject): Boolean {
        if (list.length() >= MAX_EVENTS_PER_STREAM) return false
        list.put(e)
        return true
    }

    private fun finish(s: StreamState): JSONObject {
        s.wav.close()
        return JSONObject().apply {
            put("wav", s.wav.file.name)
            put("slot", s.slot)
            put("segment", s.segment)
            put("path", when (s.path) {
                1 -> "AAudio.read"; 2 -> "AAudio.callback"; 3 -> "AudioRecord.native"; 4 -> "OpenSL.callback"
                else -> "unknown"
            })
            put("sample_rate", s.sampleRate)
            put("channels", s.channels)
            put("src_format", s.srcFormat)
            put("first_record_time", s.firstRealtimeMs)
            put("records", s.records)
            put("duration_ms", s.wavMs())
            put("missing_ms_total", s.missingFrames * 1000L / s.sampleRate)
            put("gaps", s.gaps)
            put("stalls", s.stalls)
            put("tap_loss", s.tapLoss)
            put("timing", JSONObject().apply {
                val t = s.timing
                put("intervals", t.intervals)
                put("excluded_intervals", t.excludedIntervals)
                put("min_interval_ms", if (t.intervals > 0) t.minIntervalNs / 1_000_000.0 else JSONObject.NULL)
                put("max_interval_ms", if (t.intervals > 0) t.maxIntervalNs / 1_000_000.0 else JSONObject.NULL)
                put("mean_interval_ms", t.meanIntervalNs / 1_000_000.0)
                put("mean_absolute_error_ms", t.meanAbsoluteErrorNs / 1_000_000.0)
                put("max_late_ms", t.maxLateNs / 1_000_000.0)
                put("max_early_ms", t.maxEarlyNs / 1_000_000.0)
                put("deviation_over_5ms", t.deviationOver5Ms)
                put("deviation_over_10ms", t.deviationOver10Ms)
                put("deviation_over_20ms", t.deviationOver20Ms)
                put("deviation_over_50ms", t.deviationOver50Ms)
            })
        }
    }

    private class WavWriter(val file: File, private val sampleRate: Int, private val channels: Int) {
        private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
        private var dataBytes = 0L

        fun write(data: ByteArray, len: Int) {
            raf.write(data, 0, len)
            dataBytes += len
        }

        fun close() {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt())
            h.put("WAVE".toByteArray()).put("fmt ".toByteArray())
            h.putInt(16).putShort(1).putShort(channels.toShort()).putInt(sampleRate)
            h.putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            h.put("data".toByteArray()).putInt(dataBytes.toInt())
            raf.seek(0)
            raf.write(h.array())
            raf.close()
        }
    }
}
