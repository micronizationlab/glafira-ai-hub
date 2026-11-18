package com.micronizationlab.glafira

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared visual language for GLAFIRA AI HUB.
 * Keep screen-specific styling out of individual composables where possible.
 */
internal object GlafiraColors {
    val Background = Color(0xFFF5F6FA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFF1F3F8)
    val Primary = Color(0xFF4656C8)
    val PrimarySoft = Color(0xFFE9ECFF)
    val TextPrimary = Color(0xFF20243A)
    val TextSecondary = Color(0xFF74798C)
    val Outline = Color(0xFFE3E6EF)
    val UserBubble = Color(0xFFE9ECFF)
    val Success = Color(0xFF23966B)
    val Offline = Color(0xFFADB2C0)
    val Error = Color(0xFFC74755)
}

internal object GlafiraShape {
    val Control = 12.dp
    val Card = 16.dp
    val Dialog = 20.dp
    val Bubble = 16.dp
}
