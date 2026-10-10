package io.mo.glassmic.service

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.glassmic.R
import io.mo.glassmic.ui.common.Dot
import io.mo.glassmic.ui.common.glass

/**
 * 实时波形悬浮窗内容。中心镜像柱状图，随 [samples] 滚动刷新。
 * 面板可整体拖动；显示与隐藏由设置里的开关控制。透明度由 [opacity] 控制。
 * 材质与主悬浮窗面板一致（sheet 底 + 发丝描边），跟随 App 深浅色。
 */
@Composable
fun WaveformOverlay(
    samples: FloatArray,
    opacity: Float,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val t = glass
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.padding(8.dp)) {
        Column(
            modifier = Modifier
                .alpha(opacity.coerceIn(0.15f, 1f))
                .widthIn(min = 200.dp, max = 260.dp)
                .shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(shape)
                .background(t.sheet)
                .border(BorderStroke(1.dp, t.border), shape)
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { onDragEnd() }) { change, drag ->
                        change.consume()
                        onDragBy(drag.x, drag.y)
                    }
                }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(t.primary, 6.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.waveform_title), color = t.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                val n = samples.size
                if (n == 0) return@Canvas
                val midY = size.height / 2f
                val barW = size.width / n
                val stroke = (barW * 0.6f).coerceAtLeast(1.5f)
                for (i in 0 until n) {
                    val amp = samples[i].coerceIn(0f, 1f)
                    val h = (amp * size.height * 0.92f).coerceAtLeast(stroke)
                    val x = i * barW + barW / 2f
                    drawLine(
                        color = t.primary,
                        start = Offset(x, midY - h / 2f),
                        end = Offset(x, midY + h / 2f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
