package io.mo.glassmic.memory

import android.os.Debug

/**
 * 进程内存采样。
 *
 * 文档明确要求：**不要**在 UI 线程、高频定时器、onDraw 里调用；只在后台线程低频抓取。
 * 因此这里只用轻量的 [Debug.getPss]（读 /proc/self/statm 级别开销），
 * 不用 `Debug.getMemoryInfo()`（要遍历 smaps，几十毫秒起步）。
 */
object MemoryProbe {

    data class Snapshot(
        val pssKb: Long,
        val heapUsedKb: Long,
        val heapMaxKb: Long
    ) {
        /** Java 堆使用率 0..1。 */
        val heapRatio: Float
            get() = if (heapMaxKb <= 0) 0f else heapUsedKb.toFloat() / heapMaxKb

        override fun toString(): String =
            "pss=${pssKb}KB heap=${heapUsedKb}/${heapMaxKb}KB(${(heapRatio * 100).toInt()}%)"
    }

    fun snapshot(): Snapshot {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1024
        val max = rt.maxMemory() / 1024
        // getPss 在个别 ROM 上可能抛/返回 0，取不到就按 0 记，不影响其余逻辑
        val pss = runCatching { Debug.getPss() }.getOrDefault(0L)
        return Snapshot(pssKb = pss, heapUsedKb = used, heapMaxKb = max)
    }
}
