package id.gatra.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

/** Logo Gatra (lambang elang dan pusaran) di dalam lingkaran putih agar terbaca di tema gelap. */
@Composable
fun GatraLogo(boxSize: Dp = 28.dp) {
    Box(
        Modifier.size(boxSize).clip(CircleShape).background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.logo_emblem),
            contentDescription = "Gatra",
            modifier = Modifier.fillMaxSize().padding(boxSize * 0.08f)
        )
    }
}

@Composable
fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GatraLogo(34.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            "Gatra",
            style = TextStyle(
                brush = G.Brand,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            )
        )
    }
}
