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

@Composable
fun XrayTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF38BDF8),
        onPrimary = Color(0xFF00283B),
        primaryContainer = Color(0xFF0E3550),
        onPrimaryContainer = Color(0xFFD6EFFF),
        background = Color(0xFF0B1220),
        onBackground = Color(0xFFE6EDF7),
        surface = Color(0xFF0B1220),
        onSurface = Color(0xFFE6EDF7),
        surfaceVariant = Color(0xFF141F35),
        onSurfaceVariant = Color(0xFF9DB0CA),
        outline = Color(0xFF334662),
        error = Color(0xFFFF6B6B)
    ) else lightColorScheme(
        primary = Color(0xFF0284C7),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCF0FC),
        onPrimaryContainer = Color(0xFF05293C),
        background = Color(0xFFF2F6FB),
        onBackground = Color(0xFF0F1B2D),
        surface = Color(0xFFF2F6FB),
        onSurface = Color(0xFF0F1B2D),
        surfaceVariant = Color.White,
        onSurfaceVariant = Color(0xFF54647C),
        outline = Color(0xFFCBD6E4),
        error = Color(0xFFD93025)
    )
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
