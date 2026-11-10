package id.gatra.app.ui.theme

import androidx.compose.foundation.clickable
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

/**
 * Palet Gatra: latar gelap netral dengan gradien pink - ungu - cyan (sesuai mockup).
 * Nama lama (Gold, Blue, ...) dipertahankan agar semua layar ikut berganti warna; artinya sekarang:
 * Gold = pink aksen utama, Blue = ungu, BlueSoft = ungu muda.
 */
object G {
    val Bg = Color(0xFF0F1117)
    val Surface = Color(0xFF171A22)
    val SurfaceHigh = Color(0xFF232731)
    val Outline = Color(0xFF30353F)

    val Pink = Color(0xFFFF3D8B)
    val Purple = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)
    val Red = Color(0xFFFF2D55)

    val Gold = Pink
    val GoldSoft = Color(0xFFFF8AB8)
    val OnGold = Color.White

    val Blue = Purple
    val BlueSoft = Color(0xFFA78BFA)
    val BlueDeep = Color(0xFF4F46E5)

    val Text = Color(0xFFF5F6FA)
    val Muted = Color(0xFF9AA0AE)

    val Brand = Brush.horizontalGradient(listOf(Pink, Purple, Cyan))
    val BrandButton = Brush.horizontalGradient(listOf(Pink, Purple))
}

private val scheme = darkColorScheme(
    primary = G.Pink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B1830),
    onPrimaryContainer = G.GoldSoft,
    secondary = G.Purple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2A2150),
    onSecondaryContainer = Color(0xFFDCD0FF),
    tertiary = G.Cyan,
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
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold)
)

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun GatraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}

/** Tulisan "Gatra" bergradien pink - ungu - cyan di bar atas (logo G hanya dipakai sebagai ikon aplikasi). */
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

/** Tombol utama bergradien pink - ungu (Subscribe, Putar semua). */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(G.BrandButton)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
