package com.micronizationlab.glafira.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Semantic design tokens for GLAFIRA Android.
 *
 * Keep platform-independent token names aligned with docs/design/DESIGN_TOKENS.md.
 * These values are foundations only; screens should consume tokens rather than
 * introduce new one-off brand or status colors.
 */
object GlafiraDesignTokens {
    object Colors {
        // Brand
        val brandPrimary = Color(0xFF78B83A)
        val brandPrimaryDark = Color(0xFF9AD45A)
        val brandAction = Color(0xFF3E6F20)
        val brandActionDark = Color(0xFF9AD45A)
        val brandOnAction = Color(0xFFFFFFFF)
        val brandOnActionDark = Color(0xFF14220A)
        val brandOnPrimary = Color(0xFF14220A)

        // Surfaces
        val surfaceCanvas = Color(0xFFF3F6F0)
        val surfaceCanvasDark = Color(0xFF151A17)
        val surfacePanel = Color(0xFFFFFFFF)
        val surfacePanelDark = Color(0xFF202722)
        val surfaceRaised = Color(0xFFFFFFFF)
        val surfaceRaisedDark = Color(0xFF29332C)

        // Text
        val textPrimary = Color(0xFF182019)
        val textPrimaryDark = Color(0xFFF1F5F0)
        val textSecondary = Color(0xFF59645B)
        val textSecondaryDark = Color(0xFFB5C0B6)
        val textDisabled = Color(0xFF6B746C)
        val textDisabledDark = Color(0xFFA0AAA1)

        // Controls and borders
        val borderSubtle = Color(0xFFDCE4DA)
        val borderSubtleDark = Color(0xFF3A473D)
        val controlOutline = Color(0xFF657365)
        val controlOutlineDark = Color(0xFF7E8D80)
        val focusRing = Color(0xFF245EAA)
        val focusRingDark = Color(0xFF8DBDFF)
        val controlDisabled = Color(0xFFA8B0A8)
        val controlDisabledDark = Color(0xFF56625A)

        // Semantic status colors. Status must also have text/icon semantics.
        val statusNeutral = Color(0xFF66736A)
        val statusNeutralDark = Color(0xFFA5B0A7)
        val statusSuccess = Color(0xFF238343)
        val statusSuccessDark = Color(0xFF62C77E)
        val statusWarning = Color(0xFFA96708)
        val statusWarningDark = Color(0xFFF0B34D)
        val statusDanger = Color(0xFFB4232C)
        val statusDangerDark = Color(0xFFFF747C)
        val statusInfo = Color(0xFF2868B2)
        val statusInfoDark = Color(0xFF79B6F5)
    }

    /** Spacing scale based on a 4dp unit. */
    object Spacing {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
    }

    object Shapes {
        val controlCorner = 10.dp
        val cardCorner = 14.dp
        val panelCorner = 18.dp
        val dialogCorner = 20.dp
    }

    object TouchTargets {
        /** Material accessibility baseline for interactive Android controls. */
        val minAndroid = 48.dp
    }
}
