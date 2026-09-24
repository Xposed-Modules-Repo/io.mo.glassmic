package io.mo.glassmic.xposed

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.mo.glassmic.core.Constants
import java.lang.reflect.Method

/**
 * GlassMic 的包可见性兼容 Hook（运行在 system_server）。
 *
 * Android 11+ 的 Package Visibility 可能让被注入的目标 App 看不到 [Constants.APP_PACKAGE]，
 * 进而无法通过 ContentResolver 访问 RuntimeProvider / PcmStreamProvider。旧实现只要目标包是
 * GlassMic 就无条件把 shouldFilterApplication() 改成 false，等价于“所有 App 都能看到 GlassMic”，
 * 会直接抵消 HMA（Hide My Applist）一类模块对 GlassMic 的隐藏结果。
 *
 * 现在改为最小放行：
 * 1. 先执行系统/其它模块原始链路；
 * 2. 只有结果为“应该隐藏”、被查询目标确实是 GlassMic 时才考虑介入；
 * 3. 再解析 shouldFilterApplication 的 callingSetting；
 * 4. 只有调用方包名存在于 App 同步过来的目标白名单里，才把结果改成 false。
 *
 * 因此未被 GlassMic 授权的检测 App、HMA 测试工具等不会得到任何额外可见性；而真正需要
 * RuntimeProvider/PcmStreamProvider 的目标 App 仍能在严格 ROM 上正常工作。
 */
object SystemVisibilityHook {

    private const val TAG = "GlassMic-Vis"
    private const val PREFS_REFRESH_MS = 500L
    private val SELF = Constants.APP_PACKAGE

    @Volatile private var installed = false

    /** remote preferences 由 XposedModule 提供，避免这里直接依赖模块实例。 */
    private var prefsProvider: (() -> SharedPreferences?)? = null
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var cachedAllowlist: Set<String> = emptySet()
    @Volatile private var cachedAt = 0L
    private val prefsLock = Any()

    fun install(
        api: XposedInterface,
        classLoader: ClassLoader,
        remotePrefs: () -> SharedPreferences?
    ): Boolean {
        synchronized(this) {
            prefsProvider = remotePrefs
            if (installed) return true

            val clazz = loadAppsFilterClass(classLoader)
            if (clazz == null) {
                api.log(Log.WARN, TAG, "AppsFilter class not found; skip")
                return false
            }

            val method = findShouldFilterMethod(clazz)
            if (method == null) {
                api.log(Log.WARN, TAG, "shouldFilterApplication not found on ${clazz.name}; skip")
                return false
            }

            // AOSP 11 至当前版本的结构均为：
            // [..., callingUid, callingSetting, targetPkgSetting, userId]
            // snapshot/Computer 参数可能出现在最前面，所以只按 targetPkgSetting 的相对位置定位调用方。
            val targetIdx = method.parameterTypes.indexOfLast {
                it.name.contains("PackageState") || it.name.contains("PackageSetting")
            }
            val callingSettingIdx = (targetIdx - 1).takeIf { idx ->
                idx >= 0 && !method.parameterTypes[idx].isPrimitive
            }

            if (targetIdx < 0 || callingSettingIdx == null) {
                api.log(
                    Log.WARN,
                    TAG,
                    "caller/target param not found, params=[${method.parameterTypes.joinToString { it.name }}]; skip"
                )
                return false
            }

            runCatching {
                api.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()

                        // 只在原始链路（包含其它模块，如 HMA）最终仍判定“隐藏”时才有必要介入。
                        if (result != true) return@intercept result

                        try {
                            val target = chain.getArg(targetIdx)
                            if (target == null || packageNameOf(target) != SELF) {
                                return@intercept result
                            }

                            val callerSetting = chain.getArg(callingSettingIdx)
                            if (callerAllowed(callerSetting)) {
                                // 仅对 GlassMic 明确授权的目标应用解除自身包可见性过滤。
                                return@intercept false
                            }
                        } catch (t: Throwable) {
                            api.log(Log.WARN, TAG, "visibility decision failed: ${t.message}", t)
                        }

                        // 未授权调用方永远保留系统/HMA 的原始结果。
                        result
                    }
            }.onFailure {
                api.log(Log.WARN, TAG, "hook shouldFilterApplication failed: ${it.message}", it)
                return false
            }

            installed = true
            api.log(
                Log.INFO,
                TAG,
                "scoped visibility allowlist installed: ${clazz.name}#${method.name} " +
                    "callerIdx=$callingSettingIdx targetIdx=$targetIdx pkg=$SELF"
            )
            return true
        }
    }

    private fun callerAllowed(callingSetting: Any?): Boolean {
        val allowlist = visibilityAllowlist()
        if (allowlist.isEmpty() || callingSetting == null) return false

        val callerPackages = packageNamesOf(callingSetting)
        return callerPackages.any { it in allowlist }
    }

    /**
     * LSPosed remote preferences 在部分版本里可能返回快照对象，因此和 VolumeKeyHook 一样，
     * 每次刷新都重新向模块获取实例；500ms 节流避免高频 PackageManager 查询造成额外 IPC。
     *
     * 任何读取异常都“失败关闭”：白名单视为空集合，而不是退回旧版全局放行。
     */
    private fun visibilityAllowlist(): Set<String> {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt < PREFS_REFRESH_MS) return cachedAllowlist

        synchronized(prefsLock) {
            val secondNow = SystemClock.uptimeMillis()
            if (secondNow - cachedAt < PREFS_REFRESH_MS) return cachedAllowlist

            val sp = runCatching { prefsProvider?.invoke() }.getOrNull() ?: prefs
            if (sp == null) {
                cachedAllowlist = emptySet()
                cachedAt = secondNow
                return cachedAllowlist
            }

            prefs = sp
            cachedAllowlist = runCatching {
                sp.getStringSet(Constants.KEY_VISIBILITY_ALLOWLIST, emptySet())
                    ?.asSequence()
                    ?.filter { it.isNotBlank() && it != SELF }
                    ?.toSet()
                    .orEmpty()
            }.getOrDefault(emptySet())
            cachedAt = secondNow
            return cachedAllowlist
        }
    }

    /**
     * 普通应用的 callingSetting 通常就是 PackageSetting，可直接 getPackageName()。
     * SharedUser 场景则尝试展开 getPackageStates()/getPackages()，只要其中任意包命中白名单即放行。
     */
    private fun packageNamesOf(setting: Any): Set<String> {
        packageNameOf(setting)?.let { return setOf(it) }

        for (methodName in arrayOf("getPackageStates", "getPackages")) {
            val value = runCatching {
                setting.javaClass.methods
                    .firstOrNull { it.name == methodName && it.parameterCount == 0 }
                    ?.invoke(setting)
            }.getOrNull() ?: continue

            val names = extractPackageNames(value)
            if (names.isNotEmpty()) return names
        }

        return emptySet()
    }

    private fun extractPackageNames(value: Any?): Set<String> {
        val items: Sequence<Any?> = when (value) {
            null -> emptySequence()
            is Map<*, *> -> value.values.asSequence()
            is Iterable<*> -> value.asSequence()
            is Array<*> -> value.asSequence()
            else -> sequenceOf(value)
        }

        return items.mapNotNull { item ->
            when (item) {
                null -> null
                is String -> item
                else -> packageNameOf(item)
            }
        }.filter { it.isNotBlank() }.toSet()
    }

    private fun packageNameOf(target: Any): String? {
        val method = target.javaClass.methods
            .firstOrNull { it.name == "getPackageName" && it.parameterCount == 0 }
            ?: target.javaClass.declaredMethods
                .firstOrNull { it.name == "getPackageName" && it.parameterCount == 0 }
                ?.also { runCatching { it.isAccessible = true } }

        return runCatching { method?.invoke(target) as? String }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun loadAppsFilterClass(cl: ClassLoader): Class<*>? =
        sequenceOf(
            "com.android.server.pm.AppsFilterBase",  // Android 14+
            "com.android.server.pm.AppsFilterImpl",  // 兜底
            "com.android.server.pm.AppsFilter"       // Android 11–13
        ).firstNotNullOfOrNull { name ->
            runCatching { cl.loadClass(name) }.getOrNull()?.takeIf { hasShouldFilter(it) }
        }

    private fun hasShouldFilter(clazz: Class<*>): Boolean =
        clazz.declaredMethods.any { it.name == "shouldFilterApplication" }

    private fun findShouldFilterMethod(clazz: Class<*>): Method? =
        clazz.declaredMethods
            .filter { it.name == "shouldFilterApplication" && it.returnType == Boolean::class.javaPrimitiveType }
            // 多个重载时优先参数最多的（含 PackageDataSnapshot / Computer 的新签名）。
            .maxByOrNull { it.parameterTypes.size }
}
