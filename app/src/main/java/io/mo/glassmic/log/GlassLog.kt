package io.mo.glassmic.log

import android.content.Context
import android.util.Log
import io.mo.glassmic.core.model.LogLevel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 全局分级日志。
 *
 * 设计：
 * - 环形内存缓冲 + 异步落盘
 * - 关闭时不产生任何 IO
 * - 默认脱敏（不写入用户路径、不写入 App 列表）
 */
object GlassLog {

    private const val TAG_PREFIX = "GlassMic/"
    private const val RING_CAPACITY = 2000
    private const val LOG_FILE_NAME = "glassmic.log"

    @Volatile
    var enabled: Boolean = true
    @Volatile
    var level: LogLevel = LogLevel.BASIC

    private val ring = ConcurrentLinkedDeque<LogEntry>()
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "GlassLog-IO").apply { isDaemon = true } }
    private val logFile = AtomicReference<File?>(null)
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        logFile.set(File(context.filesDir, LOG_FILE_NAME))
    }

    inline fun b(tag: String, msg: () -> String) = log(LogLevel.BASIC, tag, msg)
    inline fun v(tag: String, msg: () -> String) = log(LogLevel.VERBOSE, tag, msg)
    inline fun d(tag: String, msg: () -> String) = log(LogLevel.DEBUG, tag, msg)

    inline fun log(lv: LogLevel, tag: String, msg: () -> String) {
        if (!enabled) return
        if (lv.ordinal > level.ordinal) return
        write(lv, tag, msg())
    }

    @PublishedApi
    internal fun write(lv: LogLevel, tag: String, msg: String) {
        val entry = LogEntry(System.currentTimeMillis(), lv, tag, msg)
        // 1. 环形内存
        ring.addLast(entry)
        while (ring.size > RING_CAPACITY) ring.pollFirst()
        // 2. logcat 镜像
        when (lv) {
            LogLevel.BASIC -> Log.i(TAG_PREFIX + tag, msg)
            LogLevel.VERBOSE -> Log.d(TAG_PREFIX + tag, msg)
            LogLevel.DEBUG -> Log.v(TAG_PREFIX + tag, msg)
            else -> { /* OFF 不会到这里 */ }
        }
        // 3. 异步落盘
        val f = logFile.get() ?: return
        ioExecutor.execute {
            runCatching {
                f.appendText("${dateFmt.format(Date(entry.timestamp))} ${entry.level.name} [$tag] $msg\n")
            }
        }
    }

    fun dump(): String = buildString {
        ring.forEach { e ->
            append(dateFmt.format(Date(e.timestamp))).append(' ')
                .append(e.level.name).append(' ').append('[').append(e.tag).append(']').append(' ')
                .append(e.message).append('\n')
        }
    }

    fun clear() {
        ring.clear()
        ioExecutor.execute { logFile.get()?.writeText("") }
    }

    /**
     * 内存吃紧时裁掉环形缓冲，只保留最近 [keep] 条。返回估算释放的字节数。
     * 日志本身已异步落盘，裁掉的只是内存副本，不丢数据。
     */
    fun trimRing(keep: Int): Long {
        var freed = 0L
        while (ring.size > keep) {
            val e = ring.pollFirst() ?: break
            freed += ENTRY_OVERHEAD_BYTES + (e.message.length + e.tag.length) * 2L
        }
        return freed
    }

    /**
     * 等待已入队的落盘任务写完（单线程 FIFO，队尾任务跑完即代表前面都写完了）。
     * 用于查杀广播的现场备份，超时返回 false。
     */
    fun flush(timeoutMs: Long): Boolean = runCatching {
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute { latch.countDown() }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }.getOrDefault(false)

    /** LogEntry 对象头 + 时间戳 + 引用的粗略开销，只用于日志展示，不追求精确。 */
    private const val ENTRY_OVERHEAD_BYTES = 64L
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
)
