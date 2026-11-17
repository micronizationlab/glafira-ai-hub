package com.micronizationlab.glafira.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

private val IcqBlack = Color(0xFF000000)
private val IcqGreen = Color(0xFF7EC850)
private val IcqGray = Color(0xFF888888)

private val GlafiraColorScheme = darkColorScheme(
    primary = IcqGreen,
    onPrimary = IcqBlack,
    secondary = IcqGreen,
    onSecondary = IcqBlack,
    background = IcqBlack,
    onBackground = IcqGreen,
    surface = IcqBlack,
    onSurface = IcqGreen,
    surfaceVariant = Color(0xFF151515),
    onSurfaceVariant = IcqGray
)

private val GlafiraTypography = Typography(
    defaultFontFamily = FontFamily.Monospace
)

@Composable
fun GlafiraAiHubTheme(content: ) -> Unit {
    MaterialTheme(
        colorScheme = GlafiraColorScheme,
        typography = GlafiraTypography,
        content = content
    )
}
