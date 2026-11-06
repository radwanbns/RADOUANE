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

// Xray Tunnel — premium navy / electric-blue palette.
val ConnectedGreen = Color(0xFF22C55E)
val ConnectingAmber = Color(0xFFF59E0B)
val OnStatus = Color(0xFF071426)
val NpvGreen = Color(0xFF1677F2)
val NpvGreenSoft = Color(0xFFDCEBFF)
val NpvBg = Color(0xFFF4F7FC)

private val DarkBg = Color(0xFF071426)
private val DarkSurface = Color(0xFF0E1D33)
private val DarkSurfaceVariant = Color(0xFF172B46)
private val DarkBlue = Color(0xFF55A6FF)
private val DarkBlueContainer = Color(0xFF123D70)

@Composable
fun XrayTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = DarkBlue,
            onPrimary = Color(0xFF06172B),
            primaryContainer = DarkBlueContainer,
            onPrimaryContainer = Color(0xFFDCEBFF),
            secondary = Color(0xFF39C6E8),
            onSecondary = Color(0xFF06202A),
            secondaryContainer = Color(0xFF10475A),
            onSecondaryContainer = Color(0xFFD8F7FF),
            tertiary = Color(0xFF9A8CFF),
            onTertiary = Color(0xFF17103A),
            tertiaryContainer = Color(0xFF332B70),
            onTertiaryContainer = Color(0xFFE8E4FF),
            background = DarkBg,
            onBackground = Color(0xFFEAF2FF),
            surface = DarkSurface,
            onSurface = Color(0xFFEAF2FF),
            surfaceVariant = DarkSurfaceVariant,
            onSurfaceVariant = Color(0xFFA9BCD5),
            surfaceTint = DarkBlue,
            inverseSurface = Color(0xFFEAF2FF),
            inverseOnSurface = DarkBg,
            outline = Color(0xFF3B5878),
            outlineVariant = Color(0xFF263D59),
            error = Color(0xFFFF6B75),
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
            onPrimaryContainer = Color(0xFF0B315F),
            secondary = Color(0xFF078EAD),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFD9F5FA),
            onSecondaryContainer = Color(0xFF063B49),
            tertiary = Color(0xFF6756C8),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFEAE6FF),
            onTertiaryContainer = Color(0xFF2D236F),
            background = NpvBg,
            onBackground = Color(0xFF10243D),
            surface = Color.White,
            onSurface = Color(0xFF10243D),
            surfaceVariant = Color(0xFFEAF0F8),
            onSurfaceVariant = Color(0xFF53677F),
            surfaceTint = NpvGreen,
            inverseSurface = Color(0xFF10243D),
            inverseOnSurface = Color.White,
            outline = Color(0xFFB8C8DC),
            outlineVariant = Color(0xFFDCE5F0),
            error = Color(0xFFD93045),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF8FAFD),
            surfaceContainer = Color(0xFFF1F5FA),
            surfaceContainerHigh = Color(0xFFEAF0F8),
            surfaceContainerHighest = Color(0xFFE1EAF4)
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
