package ru.lager.app.ui.win

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Цвета окон: переменные --md-* из warehouse.html (светлая и тёмная тема). */
@Immutable
class Md3Colors(
    val dark: Boolean,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val surface: Color,
    val surfaceLow: Color,
    val surfaceContainer: Color,
    val surfaceHigh: Color,
    val card: Color,
    val appbar: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val scrim: Color,
)

val Md3Light = Md3Colors(
    dark = false,
    primary = Color(0xFF2B5CB0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E4FF), onPrimaryContainer = Color(0xFF001B3F),
    secondaryContainer = Color(0xFFDCE3F1), onSecondaryContainer = Color(0xFF131B2B),
    surface = Color(0xFFF6F8FC), surfaceLow = Color(0xFFEFF2F8),
    surfaceContainer = Color(0xFFE9EDF5), surfaceHigh = Color(0xFFE1E6F0),
    card = Color.White, appbar = Color(0xFF23478F),
    onSurface = Color(0xFF171B22), onSurfaceVariant = Color(0xFF414853),
    outline = Color(0xFF727A88), outlineVariant = Color(0xFFC9CFDB),
    success = Color(0xFF146C3E), successContainer = Color(0xFFC9F1D8),
    warning = Color(0xFF8A5300), warningContainer = Color(0xFFFFE0A8),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFFBDAD6),
    scrim = Color(0x6B0F141E),
)

val Md3Dark = Md3Colors(
    dark = true,
    primary = Color(0xFFA9C7FF), onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF274B85), onPrimaryContainer = Color(0xFFD9E4FF),
    secondaryContainer = Color(0xFF2B3242), onSecondaryContainer = Color(0xFFDCE3F1),
    surface = Color(0xFF0F1114), surfaceLow = Color(0xFF14171B),
    surfaceContainer = Color(0xFF171A1F), surfaceHigh = Color(0xFF1E2229),
    card = Color(0xFF171A1F), appbar = Color(0xFF1A1D23),
    onSurface = Color(0xFFE3E5EA), onSurfaceVariant = Color(0xFFA6ACB6),
    outline = Color(0xFF6F7580), outlineVariant = Color(0xFF2A2F37),
    success = Color(0xFF6FD39A), successContainer = Color(0xFF123D26),
    warning = Color(0xFFE8B95E), warningContainer = Color(0xFF4A3300),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF5C1A16),
    scrim = Color(0x99000000),
)

val LocalMd3 = staticCompositionLocalOf { Md3Light }

object Md3 {
    val c: Md3Colors
        @Composable
        @ReadOnlyComposable
        get() = LocalMd3.current
}

@Composable
private fun anim(c: Color): Color =
    animateColorAsState(c, tween(300, easing = EmphasizedEasing), label = "md3").value

@Composable
private fun animatedColors(t: Md3Colors): Md3Colors = Md3Colors(
    dark = t.dark,
    primary = anim(t.primary),
    onPrimary = anim(t.onPrimary),
    primaryContainer = anim(t.primaryContainer),
    onPrimaryContainer = anim(t.onPrimaryContainer),
    secondaryContainer = anim(t.secondaryContainer),
    onSecondaryContainer = anim(t.onSecondaryContainer),
    surface = anim(t.surface),
    surfaceLow = anim(t.surfaceLow),
    surfaceContainer = anim(t.surfaceContainer),
    surfaceHigh = anim(t.surfaceHigh),
    card = anim(t.card),
    appbar = anim(t.appbar),
    onSurface = anim(t.onSurface),
    onSurfaceVariant = anim(t.onSurfaceVariant),
    outline = anim(t.outline),
    outlineVariant = anim(t.outlineVariant),
    success = anim(t.success),
    successContainer = anim(t.successContainer),
    warning = anim(t.warning),
    warningContainer = anim(t.warningContainer),
    error = anim(t.error),
    errorContainer = anim(t.errorContainer),
    scrim = anim(t.scrim),
)

@Composable
fun Md3Theme(dark: Boolean, content: @Composable () -> Unit) {
    val c = animatedColors(if (dark) Md3Dark else Md3Light)
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary,
            primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
            secondaryContainer = c.secondaryContainer, onSecondaryContainer = c.onSecondaryContainer,
            background = c.surface, onBackground = c.onSurface,
            surface = c.surface, onSurface = c.onSurface, onSurfaceVariant = c.onSurfaceVariant,
            surfaceContainer = c.card, surfaceContainerHigh = c.surfaceHigh,
            outline = c.outline, outlineVariant = c.outlineVariant,
            error = c.error, errorContainer = c.errorContainer,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary,
            primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
            secondaryContainer = c.secondaryContainer, onSecondaryContainer = c.onSecondaryContainer,
            background = c.surface, onBackground = c.onSurface,
            surface = c.surface, onSurface = c.onSurface, onSurfaceVariant = c.onSurfaceVariant,
            surfaceContainer = c.card, surfaceContainerHigh = c.surfaceHigh,
            outline = c.outline, outlineVariant = c.outlineVariant,
            error = c.error, errorContainer = c.errorContainer,
        )
    }
    CompositionLocalProvider(LocalMd3 provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
