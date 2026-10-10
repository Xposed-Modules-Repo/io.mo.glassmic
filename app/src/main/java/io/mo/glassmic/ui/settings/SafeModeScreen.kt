package io.mo.glassmic.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.mo.glassmic.ui.common.GlassCard
import io.mo.glassmic.ui.common.GlassPage
import io.mo.glassmic.ui.common.MonoFamily
import io.mo.glassmic.ui.common.OutlineGlassButton
import io.mo.glassmic.ui.common.PrimaryButton
import io.mo.glassmic.ui.common.glass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.mo.glassmic.R
import io.mo.glassmic.core.model.SafeModeInfo
import io.mo.glassmic.core.model.SafeModeReason
import io.mo.glassmic.data.diag.DiagnosticBundler
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.data.runtime.SafeModeRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SafeModeViewModel @Inject constructor(
    private val repo: SafeModeRepository,
    private val runtime: RuntimeStateHolder,
    private val bundler: DiagnosticBundler
) : ViewModel() {
    val info: StateFlow<SafeModeInfo?> = repo.state

    fun exit(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.exit()
            runtime.setSafeMode(false)
            onDone()
        }
    }

    fun exportDiagnostic(onReady: (android.net.Uri) -> Unit, onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            runCatching { bundler.export() }
                .onSuccess { onReady(bundler.shareUri(it)) }
                .onFailure(onError)
        }
    }
}

@Composable
fun SafeModeScreen(
    onExitComplete: () -> Unit,
    vm: SafeModeViewModel = hiltViewModel()
) {
    val info by vm.info.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val t = glass

    GlassPage {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 52.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier.size(72.dp).background(t.errSoft, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.GppMaybe, null, tint = t.err, modifier = Modifier.size(36.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.safe_mode_title),
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 35.sp, letterSpacing = (-0.5).sp
                )
                Text(stringResource(R.string.safe_mode_subtitle), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = t.err)
            }
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        info?.reason?.let(::reasonText) ?: "原因：未知",
                        fontSize = 14.sp, lineHeight = 22.sp, color = t.ink2
                    )
                    info?.occurredAt?.takeIf { it > 0 }?.let { ts ->
                        Text(
                            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts)),
                            fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.safe_mode_exit_confirm),
                fontSize = 14.sp, lineHeight = 22.sp, color = t.ink2,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.weight(1f))
            OutlineGlassButton(
                text = stringResource(R.string.safe_mode_export_diag),
                height = 52.dp,
                onClick = {
                    vm.exportDiagnostic(
                        onReady = { uri ->
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.safe_mode_export_diag))) }
                        },
                        onError = { /* swallow; 日志已写 */ }
                    )
                }
            )
            PrimaryButton(
                text = stringResource(R.string.safe_mode_exit),
                onClick = { confirming = true },
                color = t.ink,
                contentColor = t.bgSolid,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.safe_mode_exit), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(stringResource(R.string.safe_mode_exit_confirm), fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    vm.exit(onExitComplete)
                }) { Text(stringResource(R.string.safe_mode_exit_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.library_cancel)) }
            }
        )
    }
}

private fun reasonText(r: SafeModeReason): String = "原因：" + when (r) {
    SafeModeReason.SYSTEM_UI_REPEATED_CRASH -> "30 秒内检测到系统界面异常 2 次以上"
    SafeModeReason.KEY_SYSTEM_PROC_CRASH -> "30 秒内检测到关键系统进程异常"
    SafeModeReason.MODULE_INIT_FAILURE -> "模块初始化连续失败"
    SafeModeReason.AUDIO_ENGINE_CONTINUOUS_FAILURE -> "音频引擎连续异常"
    SafeModeReason.LAST_BOOT_DID_NOT_EXIT_CLEANLY -> "上次未正常退出"
    SafeModeReason.USER_EMERGENCY_STOP -> "用户手动紧急停用"
}
