package xyz.mikaelavpn.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas

// Единый вид кнопок: эти функции перекрывают material3.Button / OutlinedButton в пакете приложения.
@Composable
private fun Btn(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, filled: Boolean, border: BorderStroke?, content: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val fg = if (filled) cs.onPrimary else cs.onSurface
    Row(
        modifier.heightIn(min = 46.dp).alpha(if (enabled) 1f else 0.4f).clip(shape)
            .then(if (filled) Modifier.background(cs.primary) else Modifier.border(border ?: BorderStroke(1.dp, cs.outline), shape))
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            ProvideTextStyle(TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = fg)) { content() }
        }
    }
}

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape? = null, border: BorderStroke? = null, content: @Composable RowScope.() -> Unit) =
    Btn(onClick, modifier, enabled, true, border, content)

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape? = null, border: BorderStroke? = null, content: @Composable RowScope.() -> Unit) =
    Btn(onClick, modifier, enabled, false, border, content)

@Composable
fun Chip(text: String, sel: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(if (sel) cs.surfaceVariant else Color.Transparent)
        .border(1.dp, if (sel) cs.primary else cs.outline, shape).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(text, fontSize = 13.sp, maxLines = 1, color = cs.onSurface)
    }
}

@Composable
fun PowerIcon(color: Color, size: Int = 26) {
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.width; val sw = w * 0.1f
        drawArc(color, -60f, 300f, false, topLeft = Offset(w * 0.15f, w * 0.18f), size = Size(w * 0.7f, w * 0.7f),
            style = Stroke(sw, cap = StrokeCap.Round))
        drawLine(color, Offset(w / 2, w * 0.08f), Offset(w / 2, w * 0.5f), sw, StrokeCap.Round)
    }
}
