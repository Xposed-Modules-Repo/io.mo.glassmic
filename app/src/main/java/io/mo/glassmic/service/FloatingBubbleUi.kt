package io.mo.glassmic.service

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.glassmic.R
import io.mo.glassmic.proto.PlaybackPolicy
import io.mo.glassmic.ui.common.Dot
import io.mo.glassmic.ui.common.GlassTextField
import io.mo.glassmic.ui.common.GlassToggle
import io.mo.glassmic.ui.common.MonoFamily
import io.mo.glassmic.ui.common.Segmented
import io.mo.glassmic.ui.common.glass
import io.mo.glassmic.ui.common.liquidClickable
import io.mo.glassmic.ui.theme.LocalReduceMotion
import kotlinx.coroutines.flow.Flow
import java.io.File

// ============ 悬浮窗数据模型（不直接暴露 Room 实体）============

/**
 * 悬浮窗形态。MINI_BAR 为旧版迷你播放条，重设计后并入音频库面板，仅为兼容保留枚举值。
 */
enum class FloatMode { BALL, MINI_BAR, MENU, TTS, TTS_SETTINGS }

data class FloatGroupItem(val id: String, val emoji: String, val name: String)
data class FloatClipItem(val id: String, val name: String, val isCurrent: Boolean, val durationMs: Long = 0L)

/** 展开态面板的默认宽度（设计稿 390 宽屏幕左右各留 14）。窄屏按可用宽度收缩。 */
const val EXPANDED_PANEL_WIDTH_DP = 340
val EXPANDED_PANEL_WIDTH: Dp = EXPANDED_PANEL_WIDTH_DP.dp

private enum class PanelTab { LIBRARY, TTS }

/**
 * 悬浮窗根 UI：悬浮球 / 主面板（音频库 · 文字转语音）/ 文字转语音设置。
 * 所有位置/窗口管理由 Service 处理，这里只负责渲染与回调。
 */
@Composable
fun FloatingBubbleRoot(
    mode: FloatMode,
    panelMaxWidth: Dp,
    panelMaxHeight: Dp,
    activeFile: Boolean,
    paused: Boolean,
    isStreaming: Boolean = false,
    audioMonitorEnabled: Boolean = false,
    onToggleAudioMonitor: () -> Unit = {},
    playbackPolicy: PlaybackPolicy = PlaybackPolicy.SILENCE,
    onSetPlaybackPolicy: (PlaybackPolicy) -> Unit = {},
    positionMs: Long,
    durationMs: Long,
    currentName: String?,
    currentGroupId: String? = null,
    sizeDp: Dp,
    iconPath: String?,
    opacity: Float,
    groups: List<FloatGroupItem>,
    clipsProvider: (String) -> Flow<List<FloatClipItem>>,
    onBallTap: () -> Unit,
    onTogglePause: () -> Unit,
    onSeek: (Float) -> Unit,
    onOpenMenu: () -> Unit,
    onCollapse: () -> Unit,
    onSelectClip: (String) -> Unit,
    onOpenTts: () -> Unit,
    ttsGenerating: Boolean,
    ttsReady: Boolean,
    ttsFailed: Boolean,
    ttsPreviewing: Boolean = false,
    onGenerateTts: (String) -> Unit,
    onTogglePreviewTts: () -> Unit = {},
    onPlayTts: () -> Unit,
    ttsProgressBarEnabled: Boolean,
    ttsActive: Boolean,
    onOpenTtsSettings: () -> Unit,
    onToggleTtsProgressBar: (Boolean) -> Unit,
    ttsDelayMs: Int,
    onSetTtsDelay: (Int) -> Unit,
    ttsDelayRemainingMs: Long,
    onCancelDelayedTts: () -> Unit,
    onSeekTts: (Float) -> Unit,
    onCloseTtsSettings: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // TTS 草稿提到这一层：切 tab 会重建下方内容，草稿放在 TtsTab 内部会被清空。
    var ttsDraft by remember { mutableStateOf("") }
    // 音频库当前查看的分组，同理提到这一层；默认跟随当前音源所在分组。
    var browsingGroupId by remember { mutableStateOf<String?>(null) }

    CompositionLocalProvider(LocalContentColor provides glass.ink) {
        when (mode) {
            FloatMode.BALL -> Ball(
                sizeDp = sizeDp,
                iconPath = iconPath,
                opacity = opacity,
                streaming = isStreaming && !paused,
                onTap = onBallTap,
                onDragBy = onDragBy,
                onDragEnd = onDragEnd
            )

            FloatMode.MINI_BAR, FloatMode.MENU, FloatMode.TTS -> {
                val tab = if (mode == FloatMode.TTS) PanelTab.TTS else PanelTab.LIBRARY
                PanelShell(
                    maxWidth = panelMaxWidth,
                    maxHeight = if (tab == PanelTab.TTS) Dp.Infinity else panelMaxHeight,
                    onDragBy = onDragBy,
                    onDragEnd = onDragEnd,
                    header = {
                        Dot(if (isStreaming) glass.err else glass.ink3)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(
                                when {
                                    !(activeFile || ttsActive) -> R.string.float_streaming_idle
                                    paused -> R.string.home_paused
                                    isStreaming -> R.string.float_streaming_live
                                    else -> R.string.float_streaming_idle
                                }
                            ),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (tab == PanelTab.TTS) {
                            CircleIconButton(Icons.Rounded.Tune, stringResource(R.string.float_settings), onOpenTtsSettings)
                            Spacer(Modifier.width(10.dp))
                        }
                        CollapsePill(onCollapse)
                    }
                ) {
                    Segmented(
                        options = listOf(
                            PanelTab.LIBRARY to stringResource(R.string.float_tab_library),
                            PanelTab.TTS to stringResource(R.string.float_tab_tts)
                        ),
                        selected = tab,
                        onSelect = { if (it == PanelTab.TTS) onOpenTts() else onOpenMenu() },
                        height = 36.dp
                    )
                    if (tab == PanelTab.LIBRARY) {
                        LibraryTab(
                            activeFile = activeFile,
                            paused = paused,
                            currentName = currentName,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            onTogglePause = onTogglePause,
                            onSeek = onSeek,
                            groups = groups,
                            browsingGroupId = (browsingGroupId ?: currentGroupId)
                                ?.takeIf { id -> groups.any { it.id == id } } ?: groups.firstOrNull()?.id,
                            onBrowseGroup = { browsingGroupId = it },
                            clipsProvider = clipsProvider,
                            onSelectClip = onSelectClip,
                            playbackPolicy = playbackPolicy,
                            onSetPlaybackPolicy = onSetPlaybackPolicy
                        )
                    } else {
                        TtsTab(
                            text = ttsDraft,
                            onTextChange = { ttsDraft = it },
                            generating = ttsGenerating,
                            ready = ttsReady,
                            failed = ttsFailed,
                            previewing = ttsPreviewing,
                            onGenerate = onGenerateTts,
                            onPreview = onTogglePreviewTts,
                            onPlay = onPlayTts,
                            isStreaming = isStreaming,
                            audioMonitorEnabled = audioMonitorEnabled,
                            onToggleAudioMonitor = onToggleAudioMonitor,
                            progressBarEnabled = ttsProgressBarEnabled,
                            ttsActive = ttsActive,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            onSeek = onSeekTts,
                            delayRemainingMs = ttsDelayRemainingMs,
                            onCancelDelayed = onCancelDelayedTts
                        )
                    }
                }
            }

            FloatMode.TTS_SETTINGS -> PanelShell(
                maxWidth = panelMaxWidth,
                maxHeight = Dp.Infinity,
                onDragBy = onDragBy,
                onDragEnd = onDragEnd,
                header = {
                    CircleIconButton(
                        Icons.AutoMirrored.Rounded.ArrowBackIos,
                        stringResource(R.string.float_back),
                        onCloseTtsSettings,
                        iconOffset = 3.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.float_tts_settings_title),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    CollapsePill(onCollapse)
                }
            ) {
                TtsSettings(
                    progressBarEnabled = ttsProgressBarEnabled,
                    onToggleProgressBar = onToggleTtsProgressBar,
                    delayMs = ttsDelayMs,
                    onSetDelay = onSetTtsDelay
                )
            }
        }
    }
}

/** 拖动手柄：贴在面板标题栏上，让面板展开时也能整体拖动。 */
private fun Modifier.dragHandle(
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectDragGestures(onDragEnd = { onDragEnd() }) { change, drag ->
        change.consume()
        onDragBy(drag.x, drag.y)
    }
}

// ============ 面板外壳 ============

/**
 * 玻璃面板：30 圆角、sheet 材质（浮在任意 App 之上，用近不透明底保证可读）、
 * 发丝描边与投影；标题栏可拖动。
 */
@Composable
private fun PanelShell(
    maxWidth: Dp,
    maxHeight: Dp,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = glass
    val shape = RoundedCornerShape(30.dp)
    val width = minOf(EXPANDED_PANEL_WIDTH, (maxWidth - 20.dp).coerceAtLeast(240.dp))
    // 投影画在外层边距里：窗口是 WRAP_CONTENT，投影外溢会被裁掉
    Box(Modifier.padding(10.dp)) {
        Column(
            modifier = Modifier
                .width(width)
                .then(
                    if (maxHeight == Dp.Infinity) Modifier
                    else Modifier.heightIn(max = (maxHeight - 20.dp).coerceAtLeast(240.dp))
                )
                .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                .clip(shape)
                .background(t.sheet)
                .border(BorderStroke(1.dp, t.rim), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().dragHandle(onDragBy, onDragEnd),
                verticalAlignment = Alignment.CenterVertically,
                content = header
            )
            content()
        }
    }
}

@Composable
private fun CollapsePill(onClick: () -> Unit) {
    Box(
        Modifier
            .liquidClickable(pressed = 0.92f, onClick = onClick)
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(glass.fill)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(R.string.float_collapse), fontSize = 13.sp)
    }
}

@Composable
private fun CircleIconButton(icon: ImageVector, desc: String, onClick: () -> Unit, iconOffset: Dp = 0.dp) {
    val t = glass
    Box(
        Modifier.liquidClickable(pressed = 0.88f, onClick = onClick).size(34.dp).clip(CircleShape).background(t.fill),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = t.ink, modifier = Modifier.padding(start = iconOffset).size(17.dp))
    }
}

// ============ 悬浮球 ============

/**
 * 悬浮球：玻璃外圈 + 72% 内核。推流中内核为主色，否则为中性灰；自定义图标铺满内核。
 */
@Composable
private fun Ball(
    sizeDp: Dp,
    iconPath: String?,
    opacity: Float,
    streaming: Boolean,
    onTap: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val t = glass
    val reduceMotion = LocalReduceMotion.current
    val bitmap = remember(iconPath) {
        iconPath?.takeIf { File(it).exists() }
            ?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }
    }

    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = if (reduceMotion) snap() else tween(120, easing = FastOutSlowInEasing),
        label = "ballScale"
    )
    val inner = sizeDp * 0.72f
    // 外层留 6dp 给投影，窗口是 WRAP_CONTENT
    Box(
        modifier = Modifier
            .padding(6.dp)
            .size(sizeDp)
            .alpha(opacity.coerceIn(0.2f, 1f))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { onDragEnd() }) { change, drag ->
                    change.consume()
                    onDragBy(drag.x, drag.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onTap() }
                )
            }
            .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(CircleShape)
            .background(t.sheet)
            .background(t.bar)
            .border(BorderStroke(1.dp, t.rim), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(inner)
                .clip(CircleShape)
                .background(if (streaming) t.primary else if (t.isDark) Color(0xFF4A4B52) else Color(0xFF9A9BA2)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(inner).clip(CircleShape)
                )
            } else {
                Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(inner * 0.5f))
            }
        }
    }
}

// ============ 音频库 tab ============

@Composable
private fun ColumnScope.LibraryTab(
    activeFile: Boolean,
    paused: Boolean,
    currentName: String?,
    positionMs: Long,
    durationMs: Long,
    onTogglePause: () -> Unit,
    onSeek: (Float) -> Unit,
    groups: List<FloatGroupItem>,
    browsingGroupId: String?,
    onBrowseGroup: (String) -> Unit,
    clipsProvider: (String) -> Flow<List<FloatClipItem>>,
    onSelectClip: (String) -> Unit,
    playbackPolicy: PlaybackPolicy,
    onSetPlaybackPolicy: (PlaybackPolicy) -> Unit,
) {
    val t = glass
    // ---- 播放头：播放/暂停 + 名称 + 进度 + 时间 ----
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .liquidClickable(enabled = activeFile, pressed = 0.9f, onClick = onTogglePause)
                .size(48.dp)
                .clip(CircleShape)
                .background(if (activeFile) t.primary else t.fill),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (paused || !activeFile) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                null,
                tint = if (activeFile) Color.White else t.ink3,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                currentName ?: stringResource(R.string.float_no_source),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            ThinProgress(
                fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                onSeek = if (durationMs > 0) onSeek else null
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(formatMs(positionMs), fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily)
    }

    // ---- 分组切换（多于一个分组时显示） ----
    if (groups.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(groups, key = { it.id }) { g ->
                val on = g.id == browsingGroupId
                Text(
                    "${g.emoji} ${g.name}",
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) t.primaryInk else t.ink2,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) t.primarySoft else t.fill)
                        .clickable { onBrowseGroup(g.id) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }

    // ---- 片段列表 ----
    if (browsingGroupId == null) {
        PanelEmpty(stringResource(R.string.float_no_groups))
    } else {
        val clipsFlow = remember(browsingGroupId) { clipsProvider(browsingGroupId) }
        val clips by clipsFlow.collectAsState(initial = emptyList())
        if (clips.isEmpty()) {
            PanelEmpty(stringResource(R.string.float_no_clips_in_group))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 264.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(clips, key = { it.id }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (c.isCurrent) t.primarySoft else Color.Transparent)
                            .clickable { onSelectClip(c.id) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            c.name,
                            fontSize = 14.sp,
                            fontWeight = if (c.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (c.isCurrent) t.primaryInk else t.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (c.durationMs > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(formatMs(c.durationMs), fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily)
                        }
                    }
                }
            }
        }
    }

    // ---- 播放策略 ----
    Segmented(
        options = listOf(
            PlaybackPolicy.SILENCE to stringResource(R.string.float_policy_once),
            PlaybackPolicy.LOOP to stringResource(R.string.float_policy_loop),
            PlaybackPolicy.REAL_MIC to stringResource(R.string.float_policy_real)
        ),
        selected = if (playbackPolicy == PlaybackPolicy.UNRECOGNIZED) PlaybackPolicy.SILENCE else playbackPolicy,
        onSelect = onSetPlaybackPolicy,
        height = 32.dp,
        fontSize = 12.sp
    )
}

/** 4dp 细进度条，可选点按跳转。 */
@Composable
private fun ThinProgress(fraction: Float, onSeek: ((Float) -> Unit)?) {
    val t = glass
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .then(
                if (onSeek != null) Modifier.pointerInput(onSeek) {
                    detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
                } else Modifier
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(t.fill, RoundedCornerShape(2.dp)))
        Box(Modifier.width(maxWidth * fraction).height(4.dp).background(t.primary, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun PanelEmpty(text: String) {
    Text(
        text, fontSize = 13.sp, color = glass.ink3, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
    )
}

// ============ 文字转语音 tab（先生成后播放，可重复播放）============

@Composable
private fun TtsTab(
    text: String,
    onTextChange: (String) -> Unit,
    generating: Boolean,
    ready: Boolean,
    failed: Boolean,
    previewing: Boolean,
    onGenerate: (String) -> Unit,
    onPreview: () -> Unit,
    onPlay: () -> Unit,
    isStreaming: Boolean,
    audioMonitorEnabled: Boolean,
    onToggleAudioMonitor: () -> Unit,
    progressBarEnabled: Boolean,
    ttsActive: Boolean,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    delayRemainingMs: Long,
    onCancelDelayed: () -> Unit,
) {
    val t = glass
    val counting = delayRemainingMs > 0

    GlassTextField(
        value = text,
        onValueChange = onTextChange,
        singleLine = false,
        minLines = 3,
        placeholder = stringResource(R.string.float_tts_input_hint)
    )

    // 状态行：推流指示 + 耳返监听
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (isStreaming) t.err else t.ink3, 7.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                counting -> stringResource(R.string.float_tts_counting, formatSeconds(delayRemainingMs))
                isStreaming -> stringResource(R.string.float_streaming_live)
                else -> stringResource(R.string.float_streaming_idle)
            },
            fontSize = 12.sp,
            color = if (isStreaming) t.err else t.ink3,
            modifier = Modifier.weight(1f)
        )
        Box(
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(if (audioMonitorEnabled) t.primarySoft else t.fill)
                .clickable(onClick = onToggleAudioMonitor)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.float_audio_monitor),
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = if (audioMonitorEnabled) t.primaryInk else t.ink2
            )
        }
    }

    // 生成 / 试听 / 播放
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TtsButton(
            label = stringResource(if (generating) R.string.float_tts_generating else R.string.float_tts_generate),
            onClick = { onGenerate(text.trim()) },
            enabled = !generating && text.isNotBlank(),
            bold = true,
            modifier = Modifier.weight(1f)
        )
        TtsButton(
            label = stringResource(if (previewing) R.string.float_tts_preview_stop else R.string.float_tts_preview),
            onClick = onPreview,
            enabled = ready,
            modifier = Modifier.weight(1f)
        )
        // 延时倒计时中：按钮变成「1.5s 取消」，再点一次即取消本次播放
        TtsButton(
            label = if (counting) stringResource(R.string.float_tts_delay_countdown, formatSeconds(delayRemainingMs))
                    else stringResource(R.string.float_tts_play),
            onClick = { if (counting) onCancelDelayed() else onPlay() },
            enabled = ready || counting,
            background = when {
                counting -> t.err
                ready -> t.primary
                else -> t.fill
            },
            contentColor = if (ready || counting) Color.White else t.ink3,
            bold = true,
            modifier = Modifier.weight(1f)
        )
    }

    // 仅在失败时提示，正常流程不堆文字
    if (failed) {
        Text(stringResource(R.string.float_tts_generate_failed), fontSize = 12.sp, color = t.err)
    }

    // 进度条：开启后，生成就绪或正在播放时显示
    if (progressBarEnabled && (ready || ttsActive)) {
        val hasDuration = durationMs > 0
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ThinProgress(
                fraction = if (hasDuration) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                onSeek = if (hasDuration) onSeek else null
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    formatMs(if (hasDuration) positionMs else 0L),
                    fontSize = 11.sp, color = t.ink3, fontFamily = MonoFamily,
                    modifier = Modifier.weight(1f)
                )
                Text(formatMs(if (hasDuration) durationMs else 0L), fontSize = 11.sp, color = t.ink3, fontFamily = MonoFamily)
            }
        }
    }
}

@Composable
private fun TtsButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    background: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    bold: Boolean = false,
) {
    val t = glass
    Box(
        modifier
            .liquidClickable(enabled = enabled, pressed = 0.94f, onClick = onClick)
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(if (background == Color.Unspecified) t.fill else background)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                contentColor != Color.Unspecified -> contentColor
                enabled -> t.ink
                else -> t.ink3
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============ 文字转语音设置 ============

@Composable
private fun TtsSettings(
    progressBarEnabled: Boolean,
    onToggleProgressBar: (Boolean) -> Unit,
    delayMs: Int,
    onSetDelay: (Int) -> Unit,
) {
    val t = glass
    // 自定义延时输入展开后内容会变长，小屏上会顶出屏幕，这里给一个滚动兜底
    Column(
        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.float_tts_progress_bar), fontSize = 15.sp, modifier = Modifier.weight(1f))
            GlassToggle(progressBarEnabled, onToggleProgressBar)
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.float_tts_delay), fontSize = 15.sp)
            Text(stringResource(R.string.float_tts_delay_hint), fontSize = 12.sp, lineHeight = 17.sp, color = t.ink3)
        }
        TtsDelaySetting(delayMs, onSetDelay)
    }
}

/** 延时播放：关 / 0.5s / 1s / 2s 四个预设 + 自定义秒数输入。 */
@Composable
private fun TtsDelaySetting(delayMs: Int, onSetDelay: (Int) -> Unit) {
    val t = glass
    val presets = listOf(0, 500, 1_000, 2_000)
    // 当前值不在预设里 → 说明用的是自定义，输入框默认展开并回填
    var customOpen by remember(delayMs) { mutableStateOf(delayMs !in presets) }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { preset ->
            DelayChip(
                label = if (preset == 0) stringResource(R.string.float_tts_delay_off)
                        else stringResource(R.string.float_tts_delay_seconds, formatSeconds(preset.toLong())),
                selected = !customOpen && delayMs == preset,
                onClick = { customOpen = false; onSetDelay(preset) },
                modifier = Modifier.weight(1f)
            )
        }
        DelayChip(
            label = stringResource(R.string.float_tts_delay_custom),
            selected = customOpen,
            onClick = { customOpen = true },
            modifier = Modifier.weight(1.2f)
        )
    }
    if (customOpen) {
        // 只在合法（0 < 秒 ≤ 上限）时才写配置，输入过程中的中间态保持原值不动
        var raw by remember(delayMs) {
            mutableStateOf(if (delayMs > 0) formatSeconds(delayMs.toLong()) else "")
        }
        val maxSeconds = FloatingWindowService.MAX_TTS_DELAY_MS / 1000
        val invalid = raw.isNotBlank() && raw.toFloatOrNull().let { it == null || it <= 0f || it > maxSeconds }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GlassTextField(
                value = raw,
                onValueChange = { input ->
                    raw = input.filter { it.isDigit() || it == '.' }.take(6)
                    raw.toFloatOrNull()
                        ?.takeIf { it > 0f && it <= maxSeconds }
                        ?.let { onSetDelay((it * 1000).toInt()) }
                },
                placeholder = stringResource(R.string.float_tts_delay_custom_hint),
                mono = true,
                isError = invalid,
                suffix = stringResource(R.string.float_tts_delay_unit),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            if (invalid) {
                Text(stringResource(R.string.float_tts_delay_range, maxSeconds), fontSize = 12.sp, color = t.err)
            }
        }
    }
}

@Composable
private fun DelayChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = glass
    Box(
        modifier
            .liquidClickable(pressed = 0.92f, onClick = onClick)
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) t.primary else t.fill),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontFamily = MonoFamily,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else t.ink,
            maxLines = 1
        )
    }
}

/** 1500 → "1.5"，1000 → "1"：整秒不拖小数尾巴。 */
private fun formatSeconds(ms: Long): String {
    val seconds = ms / 1000.0
    return if (seconds % 1.0 == 0.0) seconds.toInt().toString() else "%.1f".format(seconds)
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}
