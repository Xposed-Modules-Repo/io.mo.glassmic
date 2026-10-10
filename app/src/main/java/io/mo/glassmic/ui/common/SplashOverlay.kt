package io.mo.glassmic.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.glassmic.R

/** 回弹缓出：图标落定时轻微过冲一下。 */
private val BackOut = Easing { x ->
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val p = x - 1f
    1f + c3 * p * p * p + c1 * p * p
}

/**
 * 开屏动画（约 1.4s）：图标回弹浮现 → 两圈声波向外扩散 → 名称上浮淡入 → 整体淡出露出首页。
 * 动画值只在 graphicsLayer / Canvas 的绘制阶段读取，不会逐帧重组。
 */
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    val t = glass
    val clock = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        clock.animateTo(1f, tween(1100, easing = LinearEasing))
        exit.animateTo(1f, tween(300, easing = FastOutLinearInEasing))
        onFinished()
    }
    // 把总进度切成某一段的局部进度
    fun seg(start: Float, end: Float, easing: Easing = FastOutSlowInEasing) =
        easing.transform(((clock.value - start) / (end - start)).coerceIn(0f, 1f))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(t.bgBase)
            // 动画期间吞掉触摸，避免误点到下面的页面
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val base = 44.dp.toPx()
                    val stroke = Stroke(2.dp.toPx())
                    repeat(2) { i ->
                        val p = seg(0.18f + i * 0.2f, 0.78f + i * 0.2f, LinearEasing)
                        if (p <= 0f || p >= 1f) return@repeat
                        val grow = FastOutSlowInEasing.transform(p)
                        drawCircle(
                            color = t.primary.copy(alpha = 0.4f * (1f - p)),
                            radius = base + (size.minDimension / 2f - base) * grow,
                            style = stroke
                        )
                    }
                }
                val shape = RoundedCornerShape(26.dp)
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .graphicsLayer {
                            val s = 0.6f + 0.4f * seg(0f, 0.42f, BackOut) + 0.12f * exit.value
                            scaleX = s
                            scaleY = s
                            alpha = seg(0f, 0.25f)
                        }
                        .shadow(18.dp, shape, ambientColor = t.primary, spotColor = t.primary)
                        .clip(shape)
                        .background(Brush.linearGradient(listOf(Color(0xFF6A9BFF), t.primary))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.app_name),
                color = t.ink,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                modifier = Modifier.graphicsLayer {
                    val p = seg(0.3f, 0.68f)
                    alpha = p
                    translationY = (1f - p) * 12.dp.toPx()
                }
            )
        }
    }
}
