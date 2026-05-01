package com.glassapp.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val GlassDark = darkColorScheme(
    primary = Color(0xFFC9D4FF),
    onPrimary = Color(0xFF0B0B10),
    primaryContainer = Color(0xFF2A2F45),
    onPrimaryContainer = Color(0xFFE5EAFF),
    secondary = Color(0xFFB8F0FF),
    onSecondary = Color(0xFF0B0B10),
    tertiary = Color(0xFFFFD6E8),
    background = Color(0xFF050508),
    onBackground = Color(0xFFF2F4FF),
    surface = Color.Transparent,
    onSurface = Color(0xFFF2F4FF),
    surfaceVariant = Color(0x22FFFFFF),
    onSurfaceVariant = Color(0xFFD0D4E8),
    outline = Color(0x33FFFFFF),
)

private val GlassTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        letterSpacing = (-0.5).sp,
        lineHeight = 40.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        letterSpacing = (-0.2).sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        letterSpacing = (-0.2).sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = (-0.1).sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun GlassAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GlassDark,
        typography = GlassTypography,
        content = content,
    )
}
