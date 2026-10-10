package io.mo.glassmic.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.PermissionChecker
import io.mo.glassmic.data.runtime.PermissionState
import io.mo.glassmic.data.runtime.PermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 两步引导：免责声明 → 权限清单（一页列出全部权限，逐项去授权）。 */
enum class OnboardingStep {
    Disclaimer,
    Permissions,
    Done
}

data class OnboardingUi(
    val step: OnboardingStep = OnboardingStep.Disclaimer,
    val disclaimerAgreed: Boolean = false,
    val permissions: PermissionState = PermissionState(),
    val checking: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val checker: PermissionChecker,
    private val configStore: ConfigStore
) : ViewModel() {

    private val _ui = MutableStateFlow(OnboardingUi())
    val ui: StateFlow<OnboardingUi> = _ui.asStateFlow()

    fun toggleAgree(agreed: Boolean) {
        _ui.update { it.copy(disclaimerAgreed = agreed) }
    }

    fun next() {
        _ui.update {
            it.copy(step = when (it.step) {
                OnboardingStep.Disclaimer -> if (it.disclaimerAgreed) OnboardingStep.Permissions else OnboardingStep.Disclaimer
                OnboardingStep.Permissions -> if (it.permissions.requiredGranted) OnboardingStep.Done else OnboardingStep.Permissions
                OnboardingStep.Done -> OnboardingStep.Done
            })
        }
    }

    fun recheck() {
        _ui.update { it.copy(checking = true) }
        viewModelScope.launch {
            val state = checker.checkAll()
            _ui.update { it.copy(checking = false, permissions = state) }
        }
    }

    fun finish(onComplete: () -> Unit) {
        viewModelScope.launch {
            configStore.update { b -> b.setOnboardingCompleted(true) }
            onComplete()
        }
    }
}

/** 必需权限：Root、通知、文件访问、前台服务。悬浮窗为可选，未授权时回退到通知 + 主界面控制。 */
val PermissionState.requiredGranted: Boolean
    get() = listOf(root, notification, fileAccess, foregroundService).all { it == PermissionStatus.GRANTED }
