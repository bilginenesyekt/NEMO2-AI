package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.data.model.ThemeMode

private val NemoDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = AbyssalNavy,
    primaryContainer = DeepCyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = BioluminescentTeal,
    onSecondary = AbyssalNavy,
    secondaryContainer = ObsidianSurfaceVariant,
    onSecondaryContainer = IceWhite,
    tertiary = CoralPulse,
    onTertiary = Color.White,
    tertiaryContainer = CoralContainer,
    onTertiaryContainer = CoralPulse,
    background = AbyssalNavy,
    onBackground = IceWhite,
    surface = ObsidianSurface,
    onSurface = IceWhite,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = SlateText,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    outline = SubtleBorderDark,
    outlineVariant = ObsidianSurfaceVariant
)

private val NemoLightColorScheme = lightColorScheme(
    primary = DeepOceanCyan,
    onPrimary = Color.White,
    primaryContainer = LightCyanContainer,
    onPrimaryContainer = DeepOceanCyan,
    secondary = DeepTeal,
    onSecondary = Color.White,
    secondaryContainer = MistSurfaceVariant,
    onSecondaryContainer = InkDark,
    tertiary = CoralAccentLight,
    onTertiary = Color.White,
    tertiaryContainer = LightCoralContainer,
    onTertiaryContainer = CoralAccentLight,
    background = AlabasterBg,
    onBackground = InkDark,
    surface = CrispWhite,
    onSurface = InkDark,
    surfaceVariant = MistSurfaceVariant,
    onSurfaceVariant = SlateInk,
    surfaceContainerHigh = MistSurfaceVariant,
    outline = SubtleBorderLight,
    outlineVariant = MistSurfaceVariant
)

val NemoShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun NemoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (useDarkTheme) NemoDarkColorScheme else NemoLightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !useDarkTheme
                    isAppearanceLightNavigationBars = !useDarkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NemoTypography,
        shapes = NemoShapes,
        content = content
    )
}
