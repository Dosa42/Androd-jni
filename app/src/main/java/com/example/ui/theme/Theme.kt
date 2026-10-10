package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ProxyDarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004D59),
    onPrimaryContainer = Color(0xFFBCEBFF),
    secondary = CyberEmerald,
    onSecondary = Color(0xFF00391A),
    secondaryContainer = Color(0xFF005228),
    onSecondaryContainer = Color(0xFFA7F7BA),
    tertiary = CyberAmber,
    onTertiary = Color(0xFF423700),
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = DarkCardBorder,
    error = CyberRose
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to sleek cyber telemetry theme
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ProxyDarkColorScheme,
        typography = Typography,
        content = content
    )
}
