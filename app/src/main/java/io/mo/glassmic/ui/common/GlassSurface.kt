package io.mo.glassmic.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃容器（Box 版本）。材质与 [GlassCard] 相同，取自 [io.mo.glassmic.ui.theme.LocalGlassTokens]；
 * 只画底色、边框，不对内容图层做 blur，保证文字清晰。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val t = glass
    val shape = RoundedCornerShape(cornerRadius)
    CompositionLocalProvider(LocalContentColor provides t.ink) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(t.card)
                .border(BorderStroke(1.dp, t.border), shape),
            content = content
        )
    }
}
