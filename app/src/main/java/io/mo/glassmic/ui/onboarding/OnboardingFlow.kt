package io.mo.glassmic.ui.onboarding

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mo.glassmic.R
import io.mo.glassmic.data.runtime.PermissionState
import io.mo.glassmic.data.runtime.PermissionStatus
import io.mo.glassmic.ui.common.CheckCircle
import io.mo.glassmic.ui.common.GlassCard
import io.mo.glassmic.ui.common.GlassPage
import io.mo.glassmic.ui.common.GroupCard
import io.mo.glassmic.ui.common.PrimaryButton
import io.mo.glassmic.ui.common.SoftButton
import io.mo.glassmic.ui.common.glass
import kotlinx.coroutines.launch

@Composable
fun OnboardingFlow(
    onCompleted: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsState()
    val toast = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 进入权限页、以及从系统设置授权回来时，自动重新检测
    LaunchedEffect(ui.step) {
        if (ui.step == OnboardingStep.Permissions) vm.recheck()
        if (ui.step == OnboardingStep.Done) vm.finish(onCompleted)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && vm.ui.value.step == OnboardingStep.Permissions) vm.recheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GlassPage(toast) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 两段进度条
            val t = glass
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f).height(4.dp).background(t.primary, RoundedCornerShape(2.dp)))
                Box(
                    Modifier.weight(1f).height(4.dp)
                        .background(if (ui.step != OnboardingStep.Disclaimer) t.primary else t.fill, RoundedCornerShape(2.dp))
                )
            }
            when (ui.step) {
                OnboardingStep.Disclaimer -> DisclaimerPage(
                    agreed = ui.disclaimerAgreed,
                    onToggle = vm::toggleAgree,
                    onContinue = vm::next
                )
                OnboardingStep.Permissions -> PermissionsPage(
                    permissions = ui.permissions,
                    checking = ui.checking,
                    onRecheck = vm::recheck,
                    onFinish = vm::next,
                    toast = toast
                )
                OnboardingStep.Done -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = t.primary)
                }
            }
        }
    }
}

@Composable
private fun StepHeader(step: Int, title: String, subtitle: String? = null) {
    val t = glass
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.onboarding_step, step, 2), fontSize = 13.sp, color = t.ink3)
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
        if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = t.ink2)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DisclaimerPage(
    agreed: Boolean,
    onToggle: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
    val t = glass
    StepHeader(1, stringResource(R.string.onboarding_disclaimer_title))

    val paragraphs = stringResource(R.string.onboarding_disclaimer_body)
        .split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
    GlassCard(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        radius = 26.dp,
        contentPadding = PaddingValues(20.dp)
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            paragraphs.forEachIndexed { i, p ->
                Text(p, fontSize = 14.sp, lineHeight = 24.sp, color = if (i == 0) t.ink else t.ink2)
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle(!agreed) }
            .padding(4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.padding(top = 1.dp)) { CheckCircle(agreed, size = 24.dp, rounded = 8.dp) }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.onboarding_disclaimer_agree), fontSize = 14.sp, lineHeight = 22.sp)
    }

    PrimaryButton(
        text = stringResource(R.string.onboarding_continue),
        onClick = onContinue,
        enabled = agreed,
        modifier = Modifier.fillMaxWidth()
    )
}

private enum class PermKey { Root, Notification, Overlay, File, Fgs, Safe }

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PermissionsPage(
    permissions: PermissionState,
    checking: Boolean,
    onRecheck: () -> Unit,
    onFinish: () -> Unit,
    toast: SnackbarHostState
) {
    val t = glass
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val required = stringResource(R.string.onboarding_tag_required)
    val optional = stringResource(R.string.onboarding_tag_optional)

    data class Row4(val key: PermKey, val title: String, val tag: String, val hint: String, val ok: Boolean)
    val rows = listOf(
        Row4(PermKey.Root, stringResource(R.string.perm_root_title), required, stringResource(R.string.perm_root_hint), permissions.root == PermissionStatus.GRANTED),
        Row4(PermKey.Notification, stringResource(R.string.perm_notif_title), required, stringResource(R.string.perm_notif_hint), permissions.notification == PermissionStatus.GRANTED),
        Row4(PermKey.Overlay, stringResource(R.string.perm_overlay_title), optional, stringResource(R.string.perm_overlay_hint), permissions.overlay == PermissionStatus.GRANTED),
        Row4(PermKey.File, stringResource(R.string.perm_file_title), required, stringResource(R.string.perm_file_hint), permissions.fileAccess == PermissionStatus.GRANTED),
        Row4(PermKey.Fgs, stringResource(R.string.perm_fgs_title), required, stringResource(R.string.perm_fgs_hint), permissions.foregroundService == PermissionStatus.GRANTED),
        Row4(PermKey.Safe, stringResource(R.string.perm_safemode_title), "", stringResource(R.string.perm_safemode_hint), permissions.safeModeOk)
    )
    val missing = rows.count { !it.ok }

    StepHeader(
        2,
        stringResource(R.string.onboarding_perm_title),
        if (missing > 0) stringResource(R.string.onboarding_perm_missing, missing) else stringResource(R.string.onboarding_perm_ready)
    )

    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        GroupCard(radius = 26.dp) {
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (bg, fg) = when {
                        r.ok -> t.okSoft to t.ok
                        r.tag == required -> t.errSoft to t.err
                        else -> t.warnSoft to t.warn
                    }
                    Box(Modifier.size(28.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                        Text(if (r.ok) "✓" else "!", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            if (r.tag.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Text(r.tag, fontSize = 11.sp, color = t.ink3)
                            }
                        }
                        Text(r.hint, fontSize = 12.sp, lineHeight = 17.sp, color = t.ink3)
                    }
                    if (!r.ok && r.key != PermKey.Safe) {
                        Spacer(Modifier.width(10.dp))
                        SoftButton(
                            stringResource(R.string.onboarding_grant),
                            onClick = { (context as? Activity)?.let { launchGrantIntent(it, r.key) } },
                            height = 34.dp,
                            background = t.primarySoft,
                            textColor = t.primaryInk
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            SoftButton(
                stringResource(if (checking) R.string.onboarding_checking else R.string.onboarding_retry),
                onClick = onRecheck,
                enabled = !checking,
                background = androidx.compose.ui.graphics.Color.Transparent,
                textColor = t.primaryInk
            )
        }
    }

    val ready = permissions.requiredGranted
    val needRequired = stringResource(R.string.onboarding_need_required)
    PrimaryButton(
        text = stringResource(R.string.onboarding_enter),
        onClick = { if (ready) onFinish() else scope.launch { toast.showSnackbar(needRequired) } },
        enabled = true,
        color = if (ready) t.primary else t.fill,
        contentColor = if (ready) androidx.compose.ui.graphics.Color.White else t.ink3,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun launchGrantIntent(activity: Activity, key: PermKey) {
    val pkgUri = android.net.Uri.parse("package:${activity.packageName}")
    val intent = when (key) {
        PermKey.Root -> Intent(Settings.ACTION_SETTINGS)  // 兜底，提示用户去 Magisk
        PermKey.Notification -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        PermKey.Overlay -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
        PermKey.File, PermKey.Fgs -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(pkgUri)
        PermKey.Safe -> Intent(Settings.ACTION_SETTINGS)
    }
    runCatching { activity.startActivity(intent) }
}
