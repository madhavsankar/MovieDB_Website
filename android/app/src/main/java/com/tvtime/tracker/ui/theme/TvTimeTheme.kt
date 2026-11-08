package com.tvtime.tracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TvTimePalette {
    val Background = Color(0xFF09090B)
    val SurfaceCard = Color(0xFF16161A)
    val SurfacePill = Color(0xFF1C1C21)
    val Border = Color(0xFF27272F)
    val Yellow = Color(0xFFFACC15)
    val OnYellow = Color(0xFF09090B)
    val GreenProgress = Color(0xFF22C55E)
    val CyanMovie = Color(0xFF38BDF8)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF9CA3AF)
    val Error = Color(0xFFF43F5E)
}

private val TvTimeTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontSize = 21.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.ExtraBold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Bold
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Medium
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
)

private val TvTimeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TvTimeTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = TvTimePalette.Yellow,
        onPrimary = TvTimePalette.OnYellow,
        secondary = TvTimePalette.CyanMovie,
        onSecondary = Color(0xFF082F49),
        background = TvTimePalette.Background,
        onBackground = TvTimePalette.TextPrimary,
        surface = TvTimePalette.SurfaceCard,
        onSurface = TvTimePalette.TextPrimary,
        surfaceVariant = TvTimePalette.SurfacePill,
        onSurfaceVariant = TvTimePalette.TextSecondary,
        outline = TvTimePalette.Border,
        error = TvTimePalette.Error,
        onError = Color.White
    )

    MaterialTheme(
        colorScheme = scheme,
        typography = TvTimeTypography,
        shapes = TvTimeShapes,
        content = content
    )
}

