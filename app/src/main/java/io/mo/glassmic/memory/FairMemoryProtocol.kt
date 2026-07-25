package io.mo.glassmic.memory

import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * ITGSA（金标联盟）「公平运行内存」广播协议。
 *
 * 系统在应用内存接近/超出配额时下发预警广播（TRIM），在准备查杀前下发查杀广播（KILL）；
 * 两者走同一个 action，靠 common bundle 里的 `action` 字段区分。应用必须在 **3 秒内**
 * 通过 intent 里携带的 callback binder 回执，否则视为未适配。
 *
 * 参考：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2304
 */
internal object FairMemoryProtocol {

    /** 文档给出的唯一 action；KILL 也从这里进来。 */
    const val ACTION_TRIM = "itgsa.intent.action.TRIM"

    /**
     * 文档未列出独立的 KILL action，但描述里 TRIM / KILL 是两类广播。
     * 一并监听，万一某个厂商实现是分开发的也不会漏；重复也只是多一条不匹配的 filter。
     */
    const val ACTION_KILL = "itgsa.intent.action.KILL"

    /** 回执事务码，即 IBinder.FIRST_CALL_TRANSACTION。 */
    val TRANSACTION_EXCEPTION_REPLY: Int = IBinder.FIRST_CALL_TRANSACTION

    private const val KEY_COMMON = "common"
    private const val KEY_EXTRA = "extra"
    private const val KEY_NOTIFY_TYPE = "notifyType"
    private const val KEY_NOTIFY_ID = "notifyId"
    private const val KEY_REASON = "reason"
    private const val KEY_ACTION = "action"
    private const val KEY_CALLBACK = "callback"
    private const val KEY_HEAP_ALLOC = "heapAlloc"
    private const val KEY_HEAP_CAPACITY = "heapCapacity"
    private const val KEY_PSS = "pss"
    private const val KEY_PSS_LIMIT = "pssLimit"

    /** 回执里带回我们的处理说明，便于系统侧排查。 */
    const val KEY_REPLY = "reply"

    /** notifyType：物理内存（PSS）异常。 */
    const val NOTIFY_TYPE_PSS = 1000

    /** notifyType：Java 堆内存异常。 */
    const val NOTIFY_TYPE_JAVA_HEAP = 2000

    /** 回执结果码：处理成功。 */
    const val RESULT_OK = 0

    /** 回执结果码：处理失败（非 0 即失败，取 -1 作为通用失败）。 */
    const val RESULT_FAILED = -1

    /** 系统等待回执的超时时间。 */
    const val REPLY_TIMEOUT_MS = 3_000L

    fun matches(action: String?): Boolean = action == ACTION_TRIM || action == ACTION_KILL

    /** 解析广播。缺少 common 段视为无效广播；extra 段允许缺失（按 0 处理）。 */
    fun parse(intent: Intent): FairMemoryNotice? {
        if (!matches(intent.action)) return null
        val root = intent.extras ?: return null
        val common = root.getBundle(KEY_COMMON) ?: return null
        val extra = root.getBundle(KEY_EXTRA)
        return FairMemoryNotice(
            notifyType = common.getInt(KEY_NOTIFY_TYPE),
            notifyId = common.getInt(KEY_NOTIFY_ID),
            reason = common.getString(KEY_REASON),
            action = common.getString(KEY_ACTION) ?: intent.action,
            callback = common.getBinder(KEY_CALLBACK),
            heapAllocKb = extra?.getInt(KEY_HEAP_ALLOC) ?: 0,
            heapCapacityKb = extra?.getInt(KEY_HEAP_CAPACITY) ?: 0,
            pssKb = extra?.getInt(KEY_PSS) ?: 0,
            pssLimitKb = extra?.getInt(KEY_PSS_LIMIT) ?: 0
        )
    }

    fun replyBundle(note: String): Bundle = Bundle().apply { putString(KEY_REPLY, note) }
}

/** 一次公平运行内存通知的全部入参。 */
internal data class FairMemoryNotice(
    val notifyType: Int,
    val notifyId: Int,
    val reason: String?,
    val action: String?,
    val callback: IBinder?,
    val heapAllocKb: Int,
    val heapCapacityKb: Int,
    val pssKb: Int,
    val pssLimitKb: Int
) {

    /** 查杀广播：系统已经决定回收本进程，只留下备份数据的时间窗。 */
    val isKill: Boolean
        get() = action?.contains("KILL", ignoreCase = true) == true

    /** 已用/上限（0..1+），拿不到上限时返回 null。 */
    val usageRatio: Float?
        get() = when (notifyType) {
            FairMemoryProtocol.NOTIFY_TYPE_JAVA_HEAP ->
                if (heapCapacityKb > 0) heapAllocKb.toFloat() / heapCapacityKb else null
            else ->
                if (pssLimitKb > 0) pssKb.toFloat() / pssLimitKb else null
        }

    /** 该按多重的力度回收。查杀 > 超限 > 逼近上限。 */
    val pressure: MemoryPressure
        get() = when {
            isKill -> MemoryPressure.KILL_IMMINENT
            (usageRatio ?: 1f) >= 1f -> MemoryPressure.CRITICAL
            else -> MemoryPressure.MODERATE
        }

    fun describe(): String = buildString {
        append(if (isKill) "KILL" else "TRIM")
        append(" type=").append(notifyType)
        append(" id=").append(notifyId)
        if (!reason.isNullOrBlank()) append(" reason=").append(reason)
        if (notifyType == FairMemoryProtocol.NOTIFY_TYPE_JAVA_HEAP) {
            append(" heap=").append(heapAllocKb).append('/').append(heapCapacityKb).append("KB")
        } else {
            append(" pss=").append(pssKb).append('/').append(pssLimitKb).append("KB")
        }
    }
}
