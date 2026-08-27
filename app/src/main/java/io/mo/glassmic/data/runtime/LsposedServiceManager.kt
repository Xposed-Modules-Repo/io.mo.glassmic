package io.mo.glassmic.data.runtime

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.libxposed.service.XposedServiceHelper.OnServiceListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
            _isBound.value = false
            _frameworkScope.value = null
            Log.i(tag, "XposedService died")
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

