package io.mo.glassmic.memory

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.log.GlassLog
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「公平运行内存」适配入口。
 *
 * 做三件事：
 * 1. 监听系统的预警（TRIM）/ 查杀（KILL）广播，释放内存并在 3 秒内回执；
 * 2. 把系统标准的 [android.content.ComponentCallbacks2] trim 回调接到同一条回收链路；
 * 3. 前台服务运行期间低频采样 PSS / Java 堆，逼近上限时提前自清，避免真的收到预警。
 *
 * 所有工作都在专用 HandlerThread 上执行——广播接收器就注册在这个 Handler 上，
 * onReceive 本身即在后台线程，符合文档「不要在 UI 线程取内存指标」的要求。
 *
 * 参考：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2304
 */
@Singleton
class FairMemoryController @Inject constructor(
    @ApplicationContext private val context: Context
) : IBinder.DeathRecipient {

    private val lock = Any()

    private var initialized = false
    private var handler: Handler? = null

    /** 已 linkToDeath 的回调 binder；系统换了新 binder 时重新挂载。 */
    private var linkedRemote: IBinder? = null

    /** 最近一次收到的系统通知，供诊断包展示。 */
    @Volatile
    var lastNotice: String? = null
        private set

    /** 最近一次回收结果，供诊断包展示。 */
    @Volatile
    var lastReclaim: String? = null
        private set

    @Volatile
    private var lastPssLimitKb: Int = 0

    @Volatile
    private var sampling = false

    @Volatile
    private var lastSelfTrimAt = 0L

    // ============ 初始化 ============

    /** 在 Application.onCreate 调用一次。重复调用无副作用。 */
    fun initialize() {
        synchronized(lock) {
            if (initialized) return
            val thread = HandlerThread(THREAD_NAME).apply { start() }
            val h = Handler(thread.looper)
            handler = h
            val filter = IntentFilter(FairMemoryProtocol.ACTION_TRIM).apply {
                addAction(FairMemoryProtocol.ACTION_KILL)
            }
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // 系统广播，必须显式声明 EXPORTED，否则 T+ 上注册即崩
                    context.registerReceiver(receiver, filter, null, h, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter, null, h)
                }
            }.onFailure {
                GlassLog.b(TAG) { "广播注册失败: ${it.message}" }
            }.isSuccess
            initialized = ok
            if (ok) GlassLog.b(TAG) { "公平运行内存：已监听 ${FairMemoryProtocol.ACTION_TRIM}" }
        }
    }

    // ============ 系统广播 ============

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val notice = FairMemoryProtocol.parse(intent) ?: return
            handleNotice(notice)
        }
    }

    private fun handleNotice(notice: FairMemoryNotice) {
        val startedAt = SystemClock.elapsedRealtime()
        lastNotice = "${notice.describe()} @${System.currentTimeMillis()}"
        if (notice.pssLimitKb > 0) lastPssLimitKb = notice.pssLimitKb
        GlassLog.b(TAG) { "收到系统内存通知 ${notice.describe()}" }

        var result = FairMemoryProtocol.RESULT_OK
        var note = ""
        runCatching {
            val freed = reclaim(notice.pressure, source = if (notice.isKill) "KILL" else "TRIM")
            note = "freed≈${freed / 1024}KB"
            // 查杀前先落盘现场：日志 + 一份 kill 记录，供下次启动/导诊断包时复盘。
            // 都是 KB 级 IO，配合 flush 超时上限，远在 3 秒回执窗口之内。
            if (notice.isKill) {
                backupBeforeKill(notice)
                note += " backup=ok"
            }
        }.onFailure {
            result = FairMemoryProtocol.RESULT_FAILED
            note = "failed: ${it.message}"
            GlassLog.b(TAG) { "内存回收异常: ${it.message}" }
        }

        val callback = notice.callback
        if (callback == null) {
            GlassLog.b(TAG) { "广播未携带 callback binder，无法回执" }
        } else {
            linkRemote(callback)
            reply(callback, notice, result, note)
        }

        val cost = SystemClock.elapsedRealtime() - startedAt
        lastReclaim = "${notice.describe()} → $note (${cost}ms)"
        if (cost > FairMemoryProtocol.REPLY_TIMEOUT_MS) {
            GlassLog.b(TAG) { "回执超时！耗时 ${cost}ms > ${FairMemoryProtocol.REPLY_TIMEOUT_MS}ms" }
        } else {
            GlassLog.b(TAG) { "已回执 result=$result $note 耗时=${cost}ms" }
        }
    }

    /**
     * 按文档给出的事务格式回执：notifyType / notifyId / result / extra。
     *
     * 用 FLAG_ONEWAY 单向调用，不读 reply parcel（oneway 调用的 reply 本来就是空的）。
     */
    private fun reply(callback: IBinder, notice: FairMemoryNotice, result: Int, note: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInt(notice.notifyType)
            data.writeInt(notice.notifyId)
            data.writeInt(result)
            data.writeBundle(FairMemoryProtocol.replyBundle(note))
            callback.transact(
                FairMemoryProtocol.TRANSACTION_EXCEPTION_REPLY,
                data,
                reply,
                IBinder.FLAG_ONEWAY
            )
        } catch (t: Throwable) {
            GlassLog.b(TAG) { "回执失败: ${t.message}" }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 挂 death recipient，binder 换了就重挂——避免一直握着上一次通知的死 binder。 */
    private fun linkRemote(callback: IBinder) {
        synchronized(lock) {
            if (linkedRemote === callback) return
            linkedRemote?.let { old -> runCatching { old.unlinkToDeath(this, 0) } }
            linkedRemote = runCatching {
                callback.linkToDeath(this, 0)
                callback
            }.getOrNull()
        }
    }

    override fun binderDied() {
        synchronized(lock) {
            linkedRemote?.let { runCatching { it.unlinkToDeath(this, 0) } }
            linkedRemote = null
        }
        GlassLog.b(TAG) { "系统回调 binder 已失效" }
    }

    // ============ 系统 trim 回调 ============

    /** 由 Application.onTrimMemory 转发（主线程调用，转到后台线程执行）。 */
    fun onSystemTrim(level: Int) {
        val pressure = MemoryPressure.fromTrimLevel(level) ?: return
        postReclaim(pressure, "onTrimMemory($level)")
    }

    /** 由 Application.onLowMemory 转发。 */
    fun onSystemLowMemory() {
        postReclaim(MemoryPressure.CRITICAL, "onLowMemory")
    }

    private fun postReclaim(pressure: MemoryPressure, source: String) {
        val posted = handler?.post { reclaim(pressure, source) } ?: false
        // Handler 还没建起来（initialize 之前）就地执行——回收本身不阻塞，几十微秒
        if (!posted) reclaim(pressure, source)
    }

    // ============ 回收 ============

    private fun reclaim(level: MemoryPressure, source: String): Long {
        val before = MemoryProbe.snapshot()
        // 环形日志缓冲：已落盘，内存副本可以随时裁掉
        val logFreed = when (level) {
            MemoryPressure.LIGHT -> 0L
            MemoryPressure.MODERATE -> GlassLog.trimRing(LOG_KEEP_ON_MODERATE)
            else -> GlassLog.trimRing(LOG_KEEP_ON_CRITICAL)
        }
        val componentFreed = MemoryPressureBus.dispatch(level)
        val freed = logFreed + componentFreed
        GlassLog.b(TAG) { "回收 level=$level src=$source freed≈${freed / 1024}KB before=$before" }
        return freed
    }

    // ============ 查杀前备份 ============

    private fun backupBeforeKill(notice: FairMemoryNotice) {
        val snapshot = MemoryProbe.snapshot()
        val json = JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("notice", notice.describe())
            put("notifyType", notice.notifyType)
            put("reason", notice.reason ?: "")
            put("pssKb", notice.pssKb)
            put("pssLimitKb", notice.pssLimitKb)
            put("heapAllocKb", notice.heapAllocKb)
            put("heapCapacityKb", notice.heapCapacityKb)
            put("selfPssKb", snapshot.pssKb)
            put("selfHeapUsedKb", snapshot.heapUsedKb)
            put("selfHeapMaxKb", snapshot.heapMaxKb)
        }
        runCatching { File(context.filesDir, KILL_RECORD_FILE).writeText(json.toString()) }
            .onFailure { GlassLog.b(TAG) { "查杀现场写入失败: ${it.message}" } }
        GlassLog.flush(LOG_FLUSH_TIMEOUT_MS)
    }

    /** 上一次被公平运行内存机制查杀的现场记录，没有则返回 null。 */
    fun lastKillRecord(): String? {
        val f = File(context.filesDir, KILL_RECORD_FILE)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    // ============ 低频自采样 ============

    /**
     * 开始低频采样（前台服务运行期间）。每 [SAMPLE_INTERVAL_MS] 一次，
     * 逼近系统给出的 PSS 上限或 Java 堆水位过高时提前自清，把预警广播挡在发生之前。
     */
    fun startSampling() {
        val h = handler ?: return
        synchronized(lock) {
            if (sampling) return
            sampling = true
        }
        h.postDelayed(sampleTask, SAMPLE_INTERVAL_MS)
        GlassLog.b(TAG) { "内存采样已开启，间隔 ${SAMPLE_INTERVAL_MS / 1000}s" }
    }

    fun stopSampling() {
        synchronized(lock) {
            if (!sampling) return
            sampling = false
        }
        handler?.removeCallbacks(sampleTask)
        GlassLog.b(TAG) { "内存采样已停止" }
    }

    private val sampleTask = object : Runnable {
        override fun run() {
            if (!sampling) return
            val snapshot = MemoryProbe.snapshot()
            GlassLog.v(TAG) { "采样 $snapshot" }

            val limit = lastPssLimitKb
            val pssHot = limit > 0 && snapshot.pssKb > limit * SELF_TRIM_RATIO
            val heapHot = snapshot.heapRatio > SELF_TRIM_RATIO
            val now = SystemClock.elapsedRealtime()
            if ((pssHot || heapHot) && now - lastSelfTrimAt > SELF_TRIM_COOLDOWN_MS) {
                lastSelfTrimAt = now
                GlassLog.b(TAG) { "水位偏高（$snapshot limit=${limit}KB），提前自清" }
                reclaim(MemoryPressure.MODERATE, source = "self-sample")
            }
            handler?.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    private companion object {
        const val TAG = "FairMem"
        const val THREAD_NAME = "GlassMic-FairMem"
        const val KILL_RECORD_FILE = "fair_memory_last_kill.json"
        const val LOG_KEEP_ON_MODERATE = 300
        const val LOG_KEEP_ON_CRITICAL = 50
        const val LOG_FLUSH_TIMEOUT_MS = 500L
        const val SAMPLE_INTERVAL_MS = 60_000L
        const val SELF_TRIM_RATIO = 0.85f
        const val SELF_TRIM_COOLDOWN_MS = 5 * 60_000L
    }
}
