package io.mo.glassmic.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.R
import io.mo.glassmic.data.runtime.HookActivity
import io.mo.glassmic.proto.PlaybackPolicy
import io.mo.glassmic.root.PolicyPhase
import io.mo.glassmic.ui.common.AppIcon
import io.mo.glassmic.ui.common.Dot
import io.mo.glassmic.ui.common.GlassCard
import io.mo.glassmic.ui.common.GlassPage
import io.mo.glassmic.ui.common.GlassToggle
import io.mo.glassmic.ui.common.MonoFamily
import io.mo.glassmic.ui.common.Segmented
import io.mo.glassmic.ui.common.SoftButton
import io.mo.glassmic.ui.common.glass
import io.mo.glassmic.ui.theme.LocalReduceMotion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onOpenLibrary: () -> Unit,
    onOpenScope: () -> Unit,
    onOpenDiagnostic: () -> Unit,
    vm: HomeViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val policyStatus by vm.policyStatus.collectAsState()
    val t = glass

    GlassPage {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---- 标题 + 注入状态胶囊 ----
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    val brand = stringResource(R.string.home_brand)
                    Text(brand, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                    if (brand != "GlassMic") Text("GlassMic", fontSize = 13.sp, color = t.ink3, fontFamily = MonoFamily)
                }
                val (pillText, pillColor) = if (state.audioPolicyBackend) {
                    "AudioPolicy · " + policyStatus.label(LocalContext.current) to when (policyStatus.phase) {
                        PolicyPhase.ACTIVE -> t.ok
                        PolicyPhase.ERROR -> t.err
                        else -> t.warn
                    }
                } else {
                    "LSPosed · " + stringResource(
                        when (state.hookActivity) {
                            HookActivity.ACTIVE -> R.string.hook_pill_active
                            HookActivity.STALE -> R.string.hook_pill_stale
                            HookActivity.NEVER_PINGED -> R.string.hook_pill_never
                        }
                    ) to when (state.hookActivity) {
                        HookActivity.ACTIVE -> t.ok
                        HookActivity.STALE -> t.warn
                        HookActivity.NEVER_PINGED -> t.err
                    }
                }
                Row(
                    Modifier
                        .padding(start = 12.dp)
                        .widthIn(max = 220.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(t.card)
                        .border(BorderStroke(1.dp, t.border), RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenDiagnostic)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Dot(pillColor)
                    Spacer(Modifier.width(8.dp))
                    Text(pillText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // ---- 主开关 ----
            MicHero(state = state, onToggle = { vm.toggleMaster(!state.running) })

            // ---- 当前音源 ----
            if (state.running) {
                NowPlayingCard(
                    state = state,
                    onChangeSource = onOpenLibrary,
                    onTogglePause = vm::togglePause,
                    onSeek = vm::seekTo,
                    onPolicy = vm::setPolicy
                )
            }

            // ---- 生效范围 / 悬浮球 ----
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCard(
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 104.dp),
                    radius = 22.dp,
                    onClick = onOpenScope
                ) {
                    Text(stringResource(R.string.scope_title), fontSize = 13.sp, color = t.ink3)
                    Spacer(Modifier.height(10.dp))
                    Text(state.scopeLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.scopePackages.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row {
                            state.scopePackages.forEachIndexed { i, pkg ->
                                Box(
                                    Modifier
                                        .offset(x = (-4).dp * i)
                                        .border(BorderStroke(1.5.dp, t.ringBorder), RoundedCornerShape(8.dp))
                                        .padding(1.5.dp)
                                ) { AppIcon(pkg, size = 22.dp) }
                            }
                        }
                    }
                }
                GlassCard(
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 104.dp),
                    radius = 22.dp
                ) {
                    Text(stringResource(R.string.home_floating_ball), fontSize = 13.sp, color = t.ink3)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (state.floatingWindowVisible) R.string.home_on else R.string.home_off),
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                        )
                        GlassToggle(
                            checked = state.floatingWindowVisible,
                            enabled = state.running,
                            onCheckedChange = { vm.toggleFloating() }
                        )
                    }
                    Text(
                        stringResource(
                            when {
                                !state.running -> R.string.home_floating_hint_off
                                state.floatingWindowVisible -> R.string.home_floating_hint_on
                                else -> R.string.home_floating_hint_idle
                            }
                        ),
                        fontSize = 12.sp, color = t.ink3, lineHeight = 16.sp
                    )
                }
            }

            // ---- 劫持统计 ----
            GlassCard(radius = 22.dp, onClick = onOpenDiagnostic, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.home_field_intercept), fontSize = 13.sp, color = t.ink3)
                        if (state.interceptReads <= 0) {
                            Text(stringResource(R.string.intercept_none), fontSize = 14.sp, color = t.ink2)
                        } else {
                            Text(
                                stringResource(R.string.home_intercept_value, "%,d".format(state.interceptReads), formatBytes(state.interceptBytes)),
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFamily
                            )
                            if (state.interceptLastMs > 0) {
                                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(state.interceptLastMs))
                                Text(
                                    stringResource(R.string.home_intercept_last, time) +
                                        (state.interceptLastPkg?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = t.ink.copy(alpha = 0.4f))
                }
            }

            if (state.running) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(onClick = vm::restoreRealMic),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.home_restore_mic), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.err)
                }
            }
        }
    }
}

@Composable
private fun MicHero(state: HomeUiState, onToggle: () -> Unit) {
    val t = glass
    val reduce = LocalReduceMotion.current
    val spec = if (reduce) snap<Color>() else tween(300)
    val ring by animateColorAsState(if (state.running) t.primarySoft else Color.Transparent, spec, label = "ring")
    val core by animateColorAsState(if (state.running) t.primary else t.fill, spec, label = "core")
    val iconColor by animateColorAsState(if (state.running) Color.White else t.ink2, spec, label = "icon")

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        radius = 32.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 22.dp)
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(132.dp)
                    .clip(CircleShape)
                    .background(ring)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(104.dp)
                        .then(
                            if (state.running) Modifier.shadow(18.dp, CircleShape, ambientColor = t.primary, spotColor = t.primary)
                            else Modifier
                        )
                        .background(core, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Mic, stringResource(R.string.home_toggle_master), tint = iconColor, modifier = Modifier.size(40.dp))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (state.running) R.string.status_running else R.string.status_off),
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(
                        when {
                            state.running && state.audioPolicyBackend -> R.string.backend_status_hint
                            state.running -> R.string.status_using_virtual
                            else -> R.string.status_using_real_tap
                        }
                    ),
                    fontSize = 14.sp, color = t.ink2
                )
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    state: HomeUiState,
    onChangeSource: () -> Unit,
    onTogglePause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPolicy: (PlaybackPolicy) -> Unit
) {
    val t = glass
    val streamLabel = stringResource(
        when {
            state.paused -> R.string.home_paused
            state.isStreaming -> R.string.float_streaming_live
            else -> R.string.float_streaming_idle
        }
    )
    GlassCard(Modifier.fillMaxWidth(), radius = 28.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).background(t.primarySoft, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) { Text(state.sourceEmoji, fontSize = 22.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.sourceName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(state.groupPlainName, streamLabel).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp, color = t.ink3, maxLines = 1
                    )
                }
                Spacer(Modifier.width(8.dp))
                SoftButton(stringResource(R.string.float_change_source), onChangeSource)
            }

            if (state.hasFileSource) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SeekBar(
                        progress = state.progress.coerceIn(0f, 1f),
                        onSeek = { f -> if (state.durationMs > 0) onSeek((f * state.durationMs).toLong()) }
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatMs(state.positionMs), fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily, modifier = Modifier.weight(1f))
                        Text(formatMs(state.durationMs), fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.hasFileSource) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .shadow(10.dp, CircleShape, ambientColor = t.primary, spotColor = t.primary)
                            .background(t.primary, CircleShape)
                            .clip(CircleShape)
                            .clickable(onClick = onTogglePause),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            null, tint = Color.White, modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Segmented(
                    options = listOf(
                        PlaybackPolicy.LOOP to stringResource(R.string.library_policy_loop),
                        PlaybackPolicy.SILENCE to stringResource(R.string.library_policy_silence),
                        PlaybackPolicy.REAL_MIC to stringResource(R.string.policy_real_short)
                    ),
                    selected = if (state.playbackPolicy == PlaybackPolicy.UNRECOGNIZED) PlaybackPolicy.LOOP else state.playbackPolicy,
                    onSelect = onPolicy,
                    height = 38.dp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 6dp 轨道 + 16dp 白色滑块；点按或横向拖动跳转。 */
@Composable
private fun SeekBar(progress: Float, onSeek: (Float) -> Unit) {
    val t = glass
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(progress) }
    val shown = if (dragging) dragValue else progress
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; dragValue = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging = false; onSeek(dragValue) },
                    onDragCancel = { dragging = false }
                ) { change, _ ->
                    change.consume()
                    dragValue = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(6.dp).background(t.fill, RoundedCornerShape(3.dp)))
        Box(Modifier.width(w * shown).height(6.dp).background(t.primary, RoundedCornerShape(3.dp)))
        Box(
            Modifier
                .offset(x = (w * shown - 8.dp).coerceIn(0.dp, w - 16.dp))
                .size(16.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape)
        )
    }
}

private fun formatMs(ms: Long): String {
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
    else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
}
