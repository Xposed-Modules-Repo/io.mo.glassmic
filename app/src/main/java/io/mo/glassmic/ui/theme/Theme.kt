package io.mo.glassmic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mo.glassmic.data.appearance.BackgroundStore
import io.mo.glassmic.data.appearance.WallpaperStatus
import io.mo.glassmic.proto.BackgroundMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.proto.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 当前是否启用液态玻璃效果——给所有 GlassSurface 用 */
val LocalGlassEnabled = compositionLocalOf { true }

/** 当前是否降低动画——给过渡/转场用 */
val LocalReduceMotion = compositionLocalOf { false }

// 字号阶梯对齐重设计稿：30 大标题 / 20 页标题 / 15 正文 / 13 辅助 / 12 说明
private val GlassTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
)

/** 让残留的 Material 组件（对话框、下拉菜单、输入框）也落在新 token 上。 */
private fun GlassTokens.toColorScheme(base: ColorScheme): ColorScheme = base.copy(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primarySoft,
    onPrimaryContainer = primaryInk,
    error = err,
    background = bgBase,
    onBackground = ink,
    surface = bgBase,
    onSurface = ink,
    onSurfaceVariant = ink2,
    surfaceVariant = fill,
    surfaceContainerLowest = sheet,
    surfaceContainerLow = sheet,
    surfaceContainer = sheet,
    surfaceContainerHigh = sheet,
    surfaceContainerHighest = sheet,
    outline = dash,
    outlineVariant = divider
)

data class GlassThemeState(
    val theme: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val glassEffect: Boolean = true,
    val reduceMotion: Boolean = false
)

@HiltViewModel
class ThemeViewModel @Inject constructor(
    configStore: ConfigStore,
    private val backgrounds: BackgroundStore
) : ViewModel() {
    private val appearance = configStore.flow.map { it.appearance }

    private val mode = appearance.map { it.backgroundMode }.distinctUntilChanged()

    /** 背景图：只在来源 / 图片 / 壁纸版本变化时重新解码，拖动模糊与遮罩滑块不会读盘。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val image: Flow<ImageBitmap?> = combine(
        appearance.map { it.backgroundMode to it.backgroundImagePath }.distinctUntilChanged(),
        backgrounds.wallpaperStatus,
        backgrounds.wallpaperVersion
    ) { (m, path), status, version -> Triple(m to path, status, version) }
        .distinctUntilChanged()
        .mapLatest { (src, status, _) ->
            val (m, path) = src
            when {
                m == BackgroundMode.BACKGROUND_IMAGE && path.isNotBlank() -> backgrounds.loadBitmap(backgrounds.file(path))
                m == BackgroundMode.BACKGROUND_WALLPAPER && status == WallpaperStatus.IMAGE ->
                    backgrounds.loadBitmap(backgrounds.wallpaperCacheFile())
                else -> null
            }
        }

    val backdrop: StateFlow<PageBackdrop> = combine(image, appearance, backgrounds.wallpaperColors) { img, a, colors ->
        val m = a.backgroundMode
        PageBackdrop(
            image = img,
            blur = if (a.hasBackgroundBlur()) a.backgroundBlur.coerceIn(0f, 1f) else PageBackdrop.DEFAULT_BLUR,
            dim = if (a.hasBackgroundDim()) a.backgroundDim.coerceIn(0f, 1f) else PageBackdrop.DEFAULT_DIM,
            glowColors = if (m == BackgroundMode.BACKGROUND_WALLPAPER && img == null) colors else emptyList()
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PageBackdrop())

    init {
        // 切到「手机壁纸」时立即读取一次
        viewModelScope.launch {
            mode.collect { if (it == BackgroundMode.BACKGROUND_WALLPAPER) backgrounds.refreshWallpaper() }
        }
    }

    /** 回到前台：若用「手机壁纸」，检查壁纸是否换过（未换则直接复用缓存）。 */
    fun onResume() {
        viewModelScope.launch {
            if (mode.first() == BackgroundMode.BACKGROUND_WALLPAPER) backgrounds.refreshWallpaper()
        }
    }

    val state: StateFlow<GlassThemeState> = configStore.flow
        .map { cfg ->
            GlassThemeState(
                theme = cfg.appearance.theme,
                glassEffect = cfg.appearance.glassEffect,
                reduceMotion = cfg.appearance.reduceMotion
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GlassThemeState())
}

@Composable
fun GlassMicTheme(content: @Composable () -> Unit) {
    val vm: ThemeViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val backdrop by vm.backdrop.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.onResume() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalPageBackdrop provides backdrop) {
        GlassThemeContent(state.theme, state.glassEffect, state.reduceMotion, content)
    }
}

/**
 * 不依赖 ViewModel 的主题外壳：主 App 与悬浮窗（Service 内的 ComposeView）共用，
 * 保证悬浮窗跟随同一套深浅色与液态玻璃设置。
 */
@Composable
fun GlassThemeContent(
    theme: ThemeMode,
    glassEffect: Boolean,
    reduceMotion: Boolean,
    content: @Composable () -> Unit
) {
    val isDark = when (theme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val tokens = remember(isDark, glassEffect) { glassTokens(isDark, glassEffect) }
    val scheme = remember(isDark, tokens) { tokens.toColorScheme(if (isDark) GlassDarkScheme else GlassLightScheme) }

    MaterialTheme(colorScheme = scheme, typography = GlassTypography) {
        CompositionLocalProvider(
            LocalGlassEnabled provides glassEffect,
            LocalReduceMotion provides reduceMotion,
            LocalGlassTokens provides tokens
        ) { content() }
    }
}
