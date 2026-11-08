package id.gatra.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import id.gatra.app.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palet Gatra: malam biru pekat dengan aksen emas dan biru terang. */
object G {
    val Bg = Color(0xFF0A0E1A)
    val Surface = Color(0xFF111729)
    val SurfaceHigh = Color(0xFF1A2340)
    val Outline = Color(0xFF2A3558)

    val Gold = Color(0xFFF2B93B)
    val GoldSoft = Color(0xFFFFD36E)
    val OnGold = Color(0xFF241A00)

    val Blue = Color(0xFF4C8DFF)
    val BlueSoft = Color(0xFF8DB6FF)
    val BlueDeep = Color(0xFF1B3F9E)

    val Text = Color(0xFFEAF0FF)
    val Muted = Color(0xFF8F9BBB)

    val Brand = Brush.horizontalGradient(listOf(Gold, Blue))
}

private val scheme = darkColorScheme(
    primary = G.Gold,
    onPrimary = G.OnGold,
    primaryContainer = Color(0xFF3A2E0B),
    onPrimaryContainer = G.GoldSoft,
    secondary = G.Blue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF152A5C),
    onSecondaryContainer = Color(0xFFCFE0FF),
    tertiary = G.BlueSoft,
    background = G.Bg,
    onBackground = G.Text,
    surface = G.Surface,
    onSurface = G.Text,
    surfaceVariant = G.SurfaceHigh,
    onSurfaceVariant = G.Muted,
    outline = G.Outline,
    surfaceContainerLowest = G.Bg,
    surfaceContainerLow = G.Surface,
    surfaceContainer = G.Surface,
    surfaceContainerHigh = G.SurfaceHigh,
    surfaceContainerHighest = G.SurfaceHigh,
    error = Color(0xFFFF6B6B)
)

private val base = Typography()
private val typography = base.copy(
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold)
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun GatraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}

/**
 * Lambang Gatra untuk bar atas: bulatan biru berpusaran dengan huruf "G" krem di tengahnya.
 * Digambar langsung dengan kode (bukan gambar). Satuan gambar 46 x 46.
 */
@Composable
fun GatraLogo(boxSize: Dp = 38.dp) {
    Canvas(Modifier.size(boxSize)) {
        val u = size.minDimension / 46f
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(G.BlueSoft, G.Blue, G.BlueDeep),
                start = Offset(6f * u, 4f * u),
                end = Offset(40f * u, 44f * u)
            ),
            radius = 22f * u,
            center = Offset(23f * u, 23f * u)
        )
        // Garis pusaran tipis di dalam bulatan.
        drawArc(
            color = Color.White.copy(alpha = 0.40f), startAngle = 200f, sweepAngle = 120f, useCenter = false,
            topLeft = Offset(4f * u, 4f * u), size = Size(38f * u, 38f * u),
            style = Stroke(width = 1.4f * u, cap = StrokeCap.Round)
        )
        drawArc(
            color = Color.White.copy(alpha = 0.35f), startAngle = 20f, sweepAngle = 110f, useCenter = false,
            topLeft = Offset(6.5f * u, 6.5f * u), size = Size(33f * u, 33f * u),
            style = Stroke(width = 1.2f * u, cap = StrokeCap.Round)
        )
        drawArc(
            color = Color.White.copy(alpha = 0.25f), startAngle = 110f, sweepAngle = 80f, useCenter = false,
            topLeft = Offset(2.5f * u, 2.5f * u), size = Size(41f * u, 41f * u),
            style = Stroke(width = 1f * u, cap = StrokeCap.Round)
        )
        // Huruf G krem: cincin terbuka di kanan-atas + palang.
        val cream = Color(0xFFF7EFD6)
        drawArc(
            color = cream, startAngle = -45f, sweepAngle = -305f, useCenter = false,
            topLeft = Offset(13.5f * u, 13.5f * u), size = Size(19f * u, 19f * u),
            style = Stroke(width = 5f * u, cap = StrokeCap.Butt)
        )
        drawRect(color = cream, topLeft = Offset(23.5f * u, 20.5f * u), size = Size(9f * u, 5f * u))
    }
}

/** Logo di bar atas: lambang G, "GATRA" tebal miring emas-krem, "VIDEO" tipis abu-abu terang. */
@Composable
fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GatraLogo(38.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "GATRA",
            style = TextStyle(
                brush = Brush.horizontalGradient(listOf(Color(0xFFF7E7B4), Color(0xFFD9A93A))),
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                fontSize = 24.sp,
                letterSpacing = 0.5.sp
            )
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "VIDEO",
            color = Color(0xFFC4CAD8),
            fontWeight = FontWeight.Light,
            fontSize = 22.sp,
            letterSpacing = 1.sp
        )
    }
}
