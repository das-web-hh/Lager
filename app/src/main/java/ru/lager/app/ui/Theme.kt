package ru.lager.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object LagerColors {
    val Blue = Color(0xFF1C7ED6)
    val BlueDeep = Color(0xFF1563B8)
    val Orange = Color(0xFFFF9D43)
    val Green = Color(0xFF8CE99A)
    val Red = Color(0xFFFF8787)
    val Card = Color(0xF00F1F38)
    val Background = Brush.linearGradient(
        listOf(Color(0xFF0B1622), Color(0xFF112240), Color(0xFF0D1F35)),
    )
}

private val Scheme = darkColorScheme(
    primary = LagerColors.Blue,
    onPrimary = Color.White,
    secondary = LagerColors.Orange,
    background = Color(0xFF0B1622),
    onBackground = Color.White,
    surface = Color(0xFF0F1F38),
    onSurface = Color.White,
    error = LagerColors.Red,
)

@Composable
fun LagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
