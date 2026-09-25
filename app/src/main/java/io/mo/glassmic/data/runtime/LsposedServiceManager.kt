package io.mo.glassmic.data.runtime

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.libxposed.service.XposedServiceHelper.OnServiceListener
import io.mo.glassmic.core.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ScopeRequestResult {
    data class Prompted(val packageName: String) : ScopeRequestResult
    data class Granted(val packageName: String) : ScopeRequestResult
    data class Denied(val packageName: String) : ScopeRequestResult
    data class Failed(val packageName: String, val error: String) : ScopeRequestResult
    data class Unsupported(val packageName: String) : ScopeRequestResult
}

@Singleton
class LsposedServiceManager @Inject constructor(
    @ApplicationContext private val context: Context
) : OnServiceListener {

    private val tag = "LsposedServiceManager"

    private val _isBound = MutableStateFlow(false)
    val isBound: StateFlow<Boolean> = _isBound.asStateFlow()

    private val _frameworkScope = MutableStateFlow<List<String>?>(null)
    val frameworkScope: StateFlow<List<String>?> = _frameworkScope.asStateFlow()

    @Volatile
    private var xposedService: XposedService? = null

    private val remotePrefsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GlassMic-RemotePrefs").apply { isDaemon = true }
    }

    init {
        runCatching {
            XposedServiceHelper.registerListener(this)
        }.onFailure {
            Log.w(tag, "registerListener failed: ${it.message}")
        }
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        _isBound.value = true
        val scopeList = runCatching { service.scope }.getOrNull()
        _frameworkScope.value = scopeList
        Log.i(tag, "XposedService bound successfully, framework API: ${service.apiVersion}, scope: $scopeList")
        // 绑定前 App 侧可能已经写过本地 prefs（进程启动时 ConfigStore 先同步），这里补一次全量镜像。
        syncRemotePrefs()
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
            _isBound.value = false
            _frameworkScope.value = null
            Log.i(tag, "XposedService died")
        }
    }

    /**
     * 把本地 [Constants.REMOTE_PREFS] 整份镜像到 LSPosed remote preferences。
     *
     * Xposed 侧（system_server 里的 SystemVisibilityHook / VolumeKeyHook）通过 libxposed 的
     * `getRemotePreferences()` 读取，数据存在 LSPosed 框架里，**只能**经由 [XposedService] 写入；
     * App 自己 `getSharedPreferences(MODE_WORLD_READABLE)` 写的本地文件对它不可见。
     * 此前只写本地文件，导致 system_server 读到的可见性白名单恒为空，HMA 隐藏 GlassMic 的
     * App（微信、Telegram 等）拿不到 Provider。
     *
     * 本地文件仍是 App 侧的唯一数据源（UI 回显、token 校验都读它），这里只做单向镜像。
     * 涉及 binder IPC，统一丢到单线程执行，调用方可在任意线程调用。未绑定时直接跳过，
     * 等 [onServiceBind] 再补。
     */
    fun syncRemotePrefs() {
        if (xposedService == null) return
        remotePrefsExecutor.execute {
            val service = xposedService ?: return@execute
            runCatching {
                val local = context.getSharedPreferences(Constants.REMOTE_PREFS, Context.MODE_PRIVATE)
                val editor = service.getRemotePreferences(Constants.REMOTE_PREFS).edit()
                for ((key, value) in local.all) {
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                editor.commit()
            }.onFailure {
                Log.w(tag, "sync remote prefs failed: ${it.message}")
            }
        }
    }

    fun syncScope(): List<String>? {
        val scopeList = runCatching { xposedService?.scope }.getOrNull()
        _frameworkScope.value = scopeList
        return scopeList
    }

    fun requestScope(
        packageName: String,
        onResult: (ScopeRequestResult) -> Unit
    ) {
        val service = xposedService
        if (service == null) {
            onResult(ScopeRequestResult.Unsupported(packageName))
            return
        }

        runCatching {
            service.requestScope(listOf(packageName), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(packages: List<String>) {
                    onResult(ScopeRequestResult.Granted(packageName))
                }

                override fun onScopeRequestFailed(message: String) {
                    onResult(ScopeRequestResult.Failed(packageName, message))
                }
            })
        }.onFailure {
            Log.w(tag, "requestScope failed: ${it.message}")
            onResult(ScopeRequestResult.Failed(packageName, it.message ?: "Unknown error"))
        }
    }
}

