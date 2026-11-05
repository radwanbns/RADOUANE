package com.xraytunnel.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val ConnectedGreen = Color(0xFF22C55E)
val ConnectingAmber = Color(0xFFF59E0B)
val OnStatus = Color(0xFF0B1220)
val NpvGreen = Color(0xFF2E7D4F)
val NpvGreenSoft = Color(0xFFD8F0E0)
val NpvBg = Color(0xFFF3F7F4)

// Dark NPV-style greens
private val DarkBg = Color(0xFF0B1220)
private val DarkSurface = Color(0xFF121A2A)
private val DarkSurfaceVariant = Color(0xFF1A2438)
private val DarkGreen = Color(0xFF4ADE80)
private val DarkGreenContainer = Color(0xFF14532D)

@Composable
fun XrayTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = DarkGreen,
            onPrimary = Color(0xFF052E16),
            primaryContainer = DarkGreenContainer,
            onPrimaryContainer = Color(0xFFDCFCE7),
            secondary = DarkGreen,
            onSecondary = Color(0xFF052E16),
            secondaryContainer = DarkGreenContainer,
            onSecondaryContainer = Color(0xFFDCFCE7),
            tertiary = Color(0xFF86EFAC),
            onTertiary = Color(0xFF052E16),
            tertiaryContainer = DarkGreenContainer,
            onTertiaryContainer = Color(0xFFDCFCE7),
            background = DarkBg,
            onBackground = Color(0xFFE6EDF7),
            surface = DarkSurface,
            onSurface = Color(0xFFE6EDF7),
            surfaceVariant = DarkSurfaceVariant,
            onSurfaceVariant = Color(0xFF9DB0CA),
            surfaceTint = DarkGreen,
            inverseSurface = Color(0xFFE6EDF7),
            inverseOnSurface = DarkBg,
            outline = Color(0xFF334662),
            outlineVariant = Color(0xFF243044),
            error = Color(0xFFFF6B6B),
            surfaceContainerLowest = DarkBg,
            surfaceContainerLow = DarkSurface,
            surfaceContainer = DarkSurface,
            surfaceContainerHigh = DarkSurfaceVariant,
            surfaceContainerHighest = DarkSurfaceVariant
        )
    } else {
        lightColorScheme(
            primary = NpvGreen,
            onPrimary = Color.White,
            primaryContainer = NpvGreenSoft,
            onPrimaryContainer = Color(0xFF0F3D24),
            secondary = NpvGreen,
            onSecondary = Color.White,
            secondaryContainer = NpvGreenSoft,
            onSecondaryContainer = Color(0xFF0F3D24),
            tertiary = NpvGreen,
            onTertiary = Color.White,
            tertiaryContainer = NpvGreenSoft,
            onTertiaryContainer = Color(0xFF0F3D24),
            background = NpvBg,
            onBackground = Color(0xFF1A2E22),
            surface = Color.White,
            onSurface = Color(0xFF1A2E22),
            surfaceVariant = NpvGreenSoft,
            onSurfaceVariant = Color(0xFF4A6354),
            surfaceTint = NpvGreen,
            inverseSurface = Color(0xFF1A2E22),
            inverseOnSurface = Color.White,
            outline = Color(0xFFC5D9CC),
            outlineVariant = Color(0xFFD8F0E0),
            error = Color(0xFFD93025),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color.White,
            surfaceContainer = Color.White,
            surfaceContainerHigh = NpvGreenSoft,
            surfaceContainerHighest = NpvGreenSoft
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
