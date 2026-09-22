package io.mo.glassmic.xposed

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.bytedance.shadowhook.ShadowHook
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SourceType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AAudio 原生 hook 入口。
 *
 * 调用顺序：
 *   1. install(ctx, pkg)  ——
 *      a) ShadowHook.init（加载 libshadowhook.so）
 *      b) System.loadLibrary("glassmic_native")
 *      c) nativeInstall 安装 AAudioStream_read / callback 等 hook
 *      d) 启动 80ms 轮询线程：同步决策、读取 native 统计，并只在真实录音活跃时维护 PCM pipe
 *
 * 设计要点：
 * - audio thread 完全不调 Java，只读 native 端原子；避免 RT 线程跨 JNI/Binder 引发卡顿
 * - nativeDrainStats() 的 reads 是真实录音回调心跳；没有回调的子进程不应打开 PCM Provider
 * - 停止录音一段时间后主动关闭 fd，让 Publisher 及时移除无效 consumer
 */
object NativeAAudioHook {

    private const val TAG = "GlassMic-NativeAAudio"
    private const val POLL_INTERVAL_MS = 80L
    private const val PCM_OPEN_ACTIVITY_WINDOW_MS = 500L
    private const val PCM_CLOSE_IDLE_MS = 1_500L

    private val installed = AtomicBoolean(false)
    @Volatile private var pollerStarted = false

    // detachFd 后 PFD 句柄已转交 native，只能用这些字段标记 native 当前持有的配置。
    @Volatile private var pushedFdSr: Int = 0
    @Volatile private var pushedFdCh: Int = 0
    @Volatile private var hasPushedFd: Boolean = false

    fun install(ctx: Context, callerPackage: String): Boolean {
        if (!installed.compareAndSet(false, true)) return true

        // 1. shadowhook 初始化（含 dlopen 自身 .so）
        val initOk = runCatching {
            ShadowHook.init(
                ShadowHook.ConfigBuilder()
                    .setMode(ShadowHook.Mode.UNIQUE)
                    .setDebuggable(false)
                    .build()
            )
            true
        }.onFailure { android.util.Log.e(TAG, "ShadowHook.init failed: ${it.message}", it) }
            .getOrDefault(false)

        if (!initOk) {
            installed.set(false)
            return false
        }

        // 2. 加载我们自己的 native lib
        val libOk = runCatching { System.loadLibrary("glassmic_native") }
            .onFailure { android.util.Log.e(TAG, "loadLibrary glassmic_native failed: ${it.message}", it) }
            .isSuccess
        if (!libOk) {
            installed.set(false)
            return false
        }

        // 3. 安装 hook
        val rc = runCatching { nativeInstall() }
            .onFailure { android.util.Log.e(TAG, "nativeInstall throw: ${it.message}", it) }
            .getOrDefault(-1)
        if (rc != 0) {
            installed.set(false)
            return false
        }
        android.util.Log.i(TAG, "AAudio native hook installed in $callerPackage")

        // 4. 启动轮询线程
        startPoller(ctx, callerPackage)
        return true
    }

    private fun startPoller(ctx: Context, callerPackage: String) {
        if (pollerStarted) return
        pollerStarted = true
        thread(name = "GlassMic-AAudioPoller", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            var lastNativeCaptureAtMs = 0L

            while (true) {
                try {
                    // 4.1 先同步决策。FILE 模式即使尚未打开 pipe，真实回调仍会进入 native 并计数，
                    // 下一轮即可据此确认该进程真的在录音。
                    val src = XBridge.resolveSource(ctx, callerPackage)
                    val decisionCode = when (src) {
                        SourceType.REAL_MIC -> 0
                        SourceType.FILE, SourceType.TTS -> 1
                        SourceType.SILENCE -> 2
                    }
                    nativeSetDecision(decisionCode)

                    // 4.2 拉取统计，同时把 reads 当作录音活动心跳。
                    val stats = nativeDrainStats()
                    val now = SystemClock.uptimeMillis()
                    val reads = stats?.getOrNull(0)?.toInt() ?: 0
                    if (reads > 0) {
                        lastNativeCaptureAtMs = now
                    }
                    val captureAgeMs = if (lastNativeCaptureAtMs > 0L) {
                        (now - lastNativeCaptureAtMs).coerceAtLeast(0L)
                    } else {
                        Long.MAX_VALUE
                    }

                    // 4.3 只有目标进程最近确实发生过 native 录音回调，才创建 PCM Provider consumer。
                    // 旧逻辑仅凭 FILE 决策就打开 fd，会让王者等多进程应用产生长期空闲 consumer，
                    // Publisher 持续向没人读取的 pipe 写入并大量丢弃队列数据。
                    val needsPcm = src == SourceType.FILE || src == SourceType.TTS
                    when {
                        needsPcm && captureAgeMs <= PCM_OPEN_ACTIVITY_WINDOW_MS -> ensurePcmFd(ctx)
                        hasPushedFd && (!needsPcm || captureAgeMs > PCM_CLOSE_IDLE_MS) -> {
                            closePcmFd(
                                if (!needsPcm) "source=$src" else "capture idle ${captureAgeMs}ms"
                            )
                        }
                    }

                    // 4.4 上报统计——全欠载时 bytes=0 也必须上报，否则诊断会停留在旧数据。
                    if (stats != null && stats.size >= 4 && reads > 0) {
                        val bytes = stats[1]
                        val sampleRate = stats[2].toInt()
                        val channels = stats[3].toInt()
                        val diagnostics = if (stats.size >= 8) Bundle().apply {
                            putLong("underrun_reads", stats[4])
                            putLong("missing_frames", stats[5])
                            putLong("requested_frames", stats[6])
                            putLong("skipped_source_frames", stats.getOrElse(8) { 0L })
                            putLong("native_capture_age_ms", captureAgeMs)
                            putBoolean("pcm_fd_active", hasPushedFd)
                            putString("path", when (stats[7].toInt()) {
                                1 -> "AAudio.read"
                                2 -> "AAudio.callback"
                                3 -> "AudioRecord.native"
                                4 -> "OpenSL.callback"
                                else -> "unknown"
                            })
                        } else null
                        XBridge.reportInterceptBatch(
                            ctx,
                            callerPackage,
                            deltaReads = reads,
                            deltaBytes = bytes,
                            sampleRate = sampleRate,
                            channels = channels,
                            nativeDiagnostics = diagnostics
                        )
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "poller iter error: ${t.message}")
                }

                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    closePcmFd("poller interrupted")
                    return@thread
                }
            }
        }
    }

    private fun ensurePcmFd(ctx: Context) {
        // native 广播缓冲统一使用 48kHz mono；各录音 stream 再在 native 侧按目标格式转换。
        val wantSr = 48_000
        val wantCh = 1
        if (hasPushedFd && pushedFdSr == wantSr && pushedFdCh == wantCh) return

        val uri = Uri.parse("content://${Constants.PROVIDER_PCM}/stream?sr=$wantSr&ch=$wantCh")
        val pfd = runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")
        }.onFailure {
            android.util.Log.w(TAG, "open pcm pipe failed: ${it.message}")
        }.getOrNull() ?: run {
            closePcmFd("open provider failed")
            return
        }

        // detachFd 后 PFD 不再持有 fd 所有权，由 native 负责 close。
        val fd = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { pfd.detachFd() }.getOrDefault(-1)
        } else {
            runCatching { nativeDupFd(pfd.fileDescriptor) }.getOrDefault(-1)
        }
        runCatching { pfd.close() }
        if (fd < 0) {
            closePcmFd("detach/dup failed")
            return
        }

        nativeSetPcmFd(fd, wantSr, wantCh)
        hasPushedFd = true
        pushedFdSr = wantSr
        pushedFdCh = wantCh
        android.util.Log.i(TAG, "pcm fd opened on active capture: fd=$fd sr=$wantSr ch=$wantCh")
    }

    private fun closePcmFd(reason: String) {
        if (!hasPushedFd) {
            pushedFdSr = 0
            pushedFdCh = 0
            return
        }
        nativeSetPcmFd(-1, 0, 0)
        hasPushedFd = false
        pushedFdSr = 0
        pushedFdCh = 0
        android.util.Log.i(TAG, "pcm fd closed: $reason")
    }

    // =================== JNI ===================
    @JvmStatic private external fun nativeInstall(): Int
    @JvmStatic private external fun nativeSetDecision(decision: Int)
    @JvmStatic private external fun nativeSetPcmFd(fd: Int, sampleRate: Int, channels: Int)
    @JvmStatic private external fun nativeDrainStats(): LongArray?
    @JvmStatic private external fun nativeDupFd(fd: java.io.FileDescriptor): Int
}
