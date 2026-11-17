package id.gatra.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palet sama dengan versi Android: latar gelap, gradien pink - ungu - cyan. */
object G {
    val Bg = Color(0xFF0F1117)
    val Surface = Color(0xFF171A22)
    val SurfaceHigh = Color(0xFF232731)
    val Outline = Color(0xFF30353F)

    val Pink = Color(0xFFFF3D8B)
    val Purple = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)

    val Text = Color(0xFFF5F6FA)
    val Muted = Color(0xFF9AA0AE)

    val Brand = Brush.horizontalGradient(listOf(Pink, Purple, Cyan))
    val BrandButton = Brush.horizontalGradient(listOf(Pink, Purple))
}

private val scheme = darkColorScheme(
    primary = G.Pink,
    onPrimary = Color.White,
    secondary = G.Purple,
    onSecondary = Color.White,
    tertiary = G.Cyan,
    background = G.Bg,
    onBackground = G.Text,
    surface = G.Surface,
    onSurface = G.Text,
    surfaceVariant = G.SurfaceHigh,
    onSurfaceVariant = G.Muted,
    outline = G.Outline
)

@Composable
fun GatraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Wordmark() {
    Text(
        "Gatra",
        style = TextStyle(
            brush = G.Brand,
            fontWeight = FontWeight.Black,
            fontSize = 30.sp,
            letterSpacing = (-0.5).sp
        )
    )
}

/** Lambang segitiga putar bergradien dengan sudut membulat. */
@Composable
fun PlayMark(modifier: Modifier = Modifier, markSize: Dp = 20.dp) {
    Canvas(modifier.size(markSize)) {
        val w = markSize.toPx()
        val brush = Brush.linearGradient(
            colors = listOf(G.Pink, G.Purple, G.Cyan),
            start = Offset(0f, 0f),
            end = Offset(w, w)
        )
        val path = Path().apply {
            moveTo(w * 0.24f, w * 0.14f)
            lineTo(w * 0.24f, w * 0.86f)
            lineTo(w * 0.86f, w * 0.50f)
            close()
        }
        drawPath(path, brush, style = Fill)
        drawPath(path, brush, style = Stroke(width = w * 0.16f, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
}

@Composable
fun WordmarkWithPlay() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Wordmark()
        PlayMark(Modifier.padding(start = 2.dp).offset(y = (-7).dp), markSize = 20.dp)
    }
}
