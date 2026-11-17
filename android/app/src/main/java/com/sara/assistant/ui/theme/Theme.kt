package com.sara.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Sara is dark-first by design: a deep near-black canvas so the orb and glass panels read as
 * light sources. There is no light scheme on purpose — one look, done well.
 */
private val SaraViolet = Color(0xFF7C5CFF)
private val SaraCyan = Color(0xFF22D3EE)
private val SaraPink = Color(0xFFFF6BD6)

private val SaraColorScheme = darkColorScheme(
    primary = SaraViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A1F52),
    onPrimaryContainer = Color(0xFFE9E6FF),
    secondary = SaraCyan,
    onSecondary = Color(0xFF00222B),
    tertiary = SaraPink,
    background = Color(0xFF05060A),
    onBackground = Color(0xFFEDEDF5),
    surface = Color(0xFF0C0F17),
    onSurface = Color(0xFFEDEDF5),
    surfaceVariant = Color(0xFF161A26),
    onSurfaceVariant = Color(0xFFB9BDCC),
    outline = Color(0xFF3A3F52),
    error = Color(0xFFFF6B6B),
)

private val SaraTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun SaraTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SaraColorScheme,
        typography = SaraTypography,
        content = content,
    )
}

/** Accent gradient used by the orb, the glass cards and the primary button. */
object SaraGradients {
    val brand = listOf(SaraViolet, SaraCyan)
    val warm = listOf(SaraPink, SaraViolet)
}
