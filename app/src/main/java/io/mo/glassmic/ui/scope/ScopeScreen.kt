package io.mo.glassmic.ui.scope

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mo.glassmic.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopeScreen(
    onBack: () -> Unit,
    vm: ScopeViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 当用户从 LSPosed 管理器切换回 GlassMic 时，自动触发静默同步
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.syncFromManager(silent = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            val msg = when (event) {
                is ScopeEvent.Prompted -> context.getString(R.string.scope_event_prompted, event.label)
                is ScopeEvent.Granted -> context.getString(R.string.scope_event_granted, event.label)
                is ScopeEvent.Denied -> context.getString(R.string.scope_event_denied, event.label)
                is ScopeEvent.Unsupported -> context.getString(R.string.scope_event_unsupported, event.label)
                is ScopeEvent.Removed -> context.getString(R.string.scope_event_removed, event.label)
                is ScopeEvent.Synced -> context.getString(R.string.scope_event_synced, event.count)
            }
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scope_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.syncFromManager(silent = false) }) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = stringResource(R.string.scope_sync_tooltip)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(snackbarData = it) } }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            // LSPosed 动态服务连接状态提示卡
            LsposedServiceStatusCard(isBound = state.isLsposedServiceBound)

            // App 选择器（搜索、系统应用开关、应用列表）
            AppPicker(
                state = state,
                onQuery = vm::setQuery,
                onToggleSystem = vm::setShowSystemApps,
                onToggleApp = vm::toggleApp
            )
        }
    }
}

@Composable
private fun LsposedServiceStatusCard(isBound: Boolean) {
    if (isBound) return
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(
                Color(0xFFFFB020).copy(alpha = 0.12f),
                RoundedCornerShape(12.dp)
            )
            .padding(14.dp)
    ) {
            Text(
                stringResource(R.string.scope_lsposed_service_unbound),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB45309),
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val opened = openLSPosedManager(ctx)
                    if (!opened) {
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.scope_open_lsposed_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.scope_open_lsposed))
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(
    state: ScopeUiState,
    onQuery: (String) -> Unit,
    onToggleSystem: (Boolean) -> Unit,
    onToggleApp: (String) -> Unit
) {
    // 搜索框
    OutlinedTextField(
        value = state.query,
        onValueChange = onQuery,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Default.Close, contentDescription = "清除")
                }
            }
        },
        placeholder = { Text(stringResource(R.string.scope_search_hint)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )

    // 显示系统应用 + 已选数量
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.scope_selected_count, state.whitelist.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f)
        )
        Text(
            stringResource(R.string.scope_show_system_apps),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = state.showSystemApps, onCheckedChange = onToggleSystem)
    }

    // 列表
    if (state.loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.scope_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        return
    }

    val visible = state.filteredApps
    if (visible.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.scope_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(visible, key = { it.packageName }) { app ->
            AppRow(
                app = app,
                checked = app.packageName in state.whitelist,
                onToggle = { onToggleApp(app.packageName) }
            )
        }
    }
}

/** 尝试启动 LSPosed 管理器；同时兼容老的 EdXposed 入口。 */
private fun openLSPosedManager(ctx: Context): Boolean {
    val candidates = listOf(
        "org.lsposed.manager",
        "io.github.lsposed.manager",
        "de.robv.android.xposed.installer"
    )
    val pm = ctx.packageManager
    for (pkg in candidates) {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(intent) }.isSuccess) return true
    }
    // 退路：尝试用通用 action 唤起
    val action = Intent("android.intent.action.MAIN")
    action.addCategory("de.robv.android.xposed.category.MODULE_SETTINGS")
    action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { ctx.startActivity(action); true }.getOrDefault(false)
}

@Composable
private fun AppRow(
    app: AppItem,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                app.packageName + if (app.isSystem) "  ·  系统" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}
