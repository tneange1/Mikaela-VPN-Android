package xyz.mikaelavpn.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Линейные иконки, как в десктопной версии. kind: 0 дом, 1 глобус, 2 монитор, 3 ползунки, 4 пульс, 5 обновить, 6 телефон */
@Composable
fun Ico(kind: Int, color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val sw = w * 0.075f
        val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(w * x1, w * y1), Offset(w * x2, w * y2), sw, StrokeCap.Round)
        when (kind) {
            0 -> {
                drawPath(Path().apply {
                    moveTo(w * .14f, w * .46f); lineTo(w * .5f, w * .16f); lineTo(w * .86f, w * .46f)
                    lineTo(w * .86f, w * .84f); lineTo(w * .14f, w * .84f); close()
                }, color, style = st)
                drawPath(Path().apply { moveTo(w * .4f, w * .84f); lineTo(w * .4f, w * .6f); lineTo(w * .6f, w * .6f); lineTo(w * .6f, w * .84f) }, color, style = st)
            }
            1 -> {
                drawCircle(color, w * .36f, Offset(w / 2, w / 2), style = st)
                drawOval(color, Offset(w * .33f, w * .14f), Size(w * .34f, w * .72f), style = st)
                line(.14f, .5f, .86f, .5f)
            }
            2 -> {
                drawRoundRect(color, Offset(w * .12f, w * .16f), Size(w * .76f, w * .52f), CornerRadius(w * .06f), style = st)
                line(.5f, .68f, .5f, .84f); line(.32f, .84f, .68f, .84f)
            }
            3 -> {
                val ys = listOf(.36f, .64f, .44f)
                for (i in 0..2) {
                    val x = .25f + .25f * i
                    line(x, .14f, x, .86f)
                    drawCircle(color, w * .09f, Offset(w * x, w * ys[i]), style = st)
                }
            }
            4 -> drawPath(Path().apply {
                moveTo(w * .1f, w * .5f); lineTo(w * .3f, w * .5f); lineTo(w * .4f, w * .24f)
                lineTo(w * .58f, w * .78f); lineTo(w * .68f, w * .5f); lineTo(w * .9f, w * .5f)
            }, color, style = st)
            5 -> {
                drawArc(color, -40f, 290f, false, Offset(w * .17f, w * .17f), Size(w * .66f, w * .66f), style = st)
                line(.77f, .12f, .77f, .3f); line(.77f, .3f, .59f, .3f)
            }
            else -> {
                drawRoundRect(color, Offset(w * .28f, w * .1f), Size(w * .44f, w * .8f), CornerRadius(w * .08f), style = st)
                line(.44f, .78f, .56f, .78f)
            }
        }
    }
}
