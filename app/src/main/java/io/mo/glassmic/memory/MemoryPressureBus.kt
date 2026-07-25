package io.mo.glassmic.memory

import android.content.ComponentCallbacks2
import io.mo.glassmic.log.GlassLog
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 内存压力等级。按 ordinal 递增，可直接比较：`level >= MemoryPressure.CRITICAL`。
 */
enum class MemoryPressure {
    /** 轻度：UI 不可见，或系统刚开始吃紧。只放纯界面缓存，功能不受影响。 */
    LIGHT,

    /** 内存吃紧，丢掉「重建代价可接受」的缓存。 */
    MODERATE,

    /** 已超配额，除了正在出声的音频管线，能放的都放。 */
    CRITICAL,

    /** 系统即将查杀本进程，先备份现场再释放。 */
    KILL_IMMINENT;

    companion object {
        /** 把 [ComponentCallbacks2] 的 trim level 映射到本枚举；无需处理的返回 null。 */
        fun fromTrimLevel(level: Int): MemoryPressure? = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressure.CRITICAL
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> MemoryPressure.MODERATE
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> MemoryPressure.MODERATE
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryPressure.LIGHT
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressure.CRITICAL
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressure.MODERATE
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> MemoryPressure.LIGHT
            else -> null
        }
    }
}

/** 持有可回收内存的组件实现本接口，主动登记到 [MemoryPressureBus]。 */
interface MemoryReleasable {
    /**
     * 按 [level] 释放内存。**必须快速返回**（系统只给 3 秒回执窗口），
     * 不要在这里做 IO 或等锁。返回估算释放的字节数，仅用于日志。
     */
    fun onMemoryPressure(level: MemoryPressure): Long
}

/**
 * 内存压力总线。
 *
 * 用登记制而不是 Hilt 注入：回收方（PlaybackController 等）都是重量级单例，
 * 让 [FairMemoryController] 直接依赖它们会在进程启动时把整条音频管线提前创建出来。
 * 组件自己创建时登记，没被创建就说明它也没占内存。
 */
object MemoryPressureBus {

    private val listeners = CopyOnWriteArraySet<MemoryReleasable>()

    fun register(listener: MemoryReleasable) {
        listeners.add(listener)
    }

    fun unregister(listener: MemoryReleasable) {
        listeners.remove(listener)
    }

    /** 广播压力事件，返回各组件上报的释放字节总数。单个组件抛异常不影响其余组件。 */
    fun dispatch(level: MemoryPressure): Long {
        var freed = 0L
        listeners.forEach { listener ->
            freed += runCatching { listener.onMemoryPressure(level) }
                .onFailure { t ->
                    GlassLog.b("FairMem") { "${listener.javaClass.simpleName} 释放失败: ${t.message}" }
                }
                .getOrDefault(0L)
        }
        return freed
    }
}
