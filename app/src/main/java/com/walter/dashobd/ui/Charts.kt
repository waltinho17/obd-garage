package com.walter.dashobd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walter.dashobd.obd.Sensor

/** Uma curva; cada série é desenhada na sua própria escala vertical (yMin..yMax). */
class ChartSeries(
    val sensor: Sensor,
    val color: Color,
    val points: List<Offset>,
    val yMin: Double,
    val yMax: Double,
)

@Composable
fun LineChart(
    series: List<ChartSeries>,
    xMin: Float,
    xMax: Float,
    xLabel: (Float) -> String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = DashColors.TextSecondary, fontSize = 10.sp)
    Canvas(modifier) {
        val bottom = 18.dp.toPx()
        val w = size.width
        val h = size.height - bottom
        val xRange = (xMax - xMin).takeIf { it > 0f } ?: 1f

        val grid = Color.White.copy(alpha = 0.08f)
        for (i in 0..4) {
            val y = h * i / 4f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
        }
        for (i in 0..4) {
            val x = w * i / 4f
            drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
            val text = xLabel(xMin + xRange * i / 4f)
            val layout = measurer.measure(text, labelStyle)
            val tx = (x - layout.size.width / 2f).coerceIn(0f, w - layout.size.width)
            drawText(layout, topLeft = Offset(tx, h + 3.dp.toPx()))
        }

        for (s in series) {
            if (s.points.size < 2) continue
            val yRange = (s.yMax - s.yMin).takeIf { it > 1e-9 } ?: 1.0
            val path = Path()
            s.points.forEachIndexed { i, p ->
                val x = (p.x - xMin) / xRange * w
                val y = (h * (1 - (p.y - s.yMin) / yRange)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, s.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(series: List<ChartSeries>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (s in series) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(s.color))
                Text(
                    "  ${s.sensor.label} ${s.sensor.format(s.yMin)} a ${s.sensor.format(s.yMax)} ${s.sensor.unit}",
                    color = DashColors.TextSecondary, fontSize = 11.sp, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}
