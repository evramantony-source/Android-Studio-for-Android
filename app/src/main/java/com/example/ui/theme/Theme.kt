package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.model.IdeAccentPreset
import com.example.model.IdeThemePreset
import com.example.model.UiDensity

val LocalUiDensity = staticCompositionLocalOf { UiDensity.STANDARD }
val LocalIdeThemePreset = staticCompositionLocalOf { IdeThemePreset.DARCULA }

private val StudioShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

private fun buildColorScheme(
    preset: IdeThemePreset,
    accent: IdeAccentPreset,
): ColorScheme {
    val primaryColor = Color(accent.primaryHex)
    val secondaryColor = Color(accent.secondaryHex)

    if (!preset.isDark) {
        val lightPrimary = when (accent) {
            IdeAccentPreset.ANDROID_GREEN -> Color(0xFF007B43)
            IdeAccentPreset.JETBRAINS_BLUE -> Color(0xFF2B60D6)
            IdeAccentPreset.CYAN_FORGE -> ForgeCyanDeep
            IdeAccentPreset.KOTLIN_PURPLE -> Color(0xFF6D4AFF)
            IdeAccentPreset.AMBER_GOLD -> Color(0xFFB45309)
        }
        return lightColorScheme(
            primary = lightPrimary,
            onPrimary = Color.White,
            primaryContainer = lightPrimary.copy(alpha = 0.14f),
            onPrimaryContainer = Color(0xFF0F172A),
            secondary = Color(0xFF2B60D6),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE0EAFF),
            onSecondaryContainer = Color(0xFF102A63),
            tertiary = Color(0xFF6D4AFF),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFEDE9FE),
            onTertiaryContainer = Color(0xFF2E1065),
            background = DaylightBg,
            onBackground = DaylightText,
            surface = DaylightSurface,
            onSurface = DaylightText,
            surfaceVariant = DaylightSurfaceHigh,
            onSurfaceVariant = DaylightTextMuted,
            surfaceContainerLow = DaylightSurface,
            surfaceContainer = DaylightSurfaceHigh,
            surfaceContainerHigh = DaylightSurfaceHighest,
            outline = DaylightOutline,
            outlineVariant = Color(0xFFE2E5EB),
            error = Color(0xFFD93025),
            onError = Color.White,
            errorContainer = Color(0xFFFDE8E6),
            onErrorContainer = Color(0xFF7F1D1D),
        )
    }

    data class DarkPalette(
        val bg: Color,
        val surf: Color,
        val surfHigh: Color,
        val surfHighest: Color,
        val outline: Color,
        val text: Color,
        val muted: Color,
    )

    val (bg, surf, surfHigh, surfHighest, outline, text, muted) = when (preset) {
        IdeThemePreset.DARCULA -> DarkPalette(
            DarculaBg, DarculaSurface, DarculaSurfaceHigh, DarculaSurfaceHighest,
            DarculaOutline, DarculaText, DarculaTextMuted,
        )
        IdeThemePreset.ISLANDS_MIDNIGHT -> DarkPalette(
            IslandsBg, IslandsSurface, IslandsSurfaceHigh, IslandsSurfaceHighest,
            IslandsOutline, IslandsText, IslandsTextMuted,
        )
        IdeThemePreset.NORD_STUDIO -> DarkPalette(
            NordBg, NordSurface, NordSurfaceHigh, NordSurfaceHighest,
            NordOutline, NordText, NordTextMuted,
        )
        IdeThemePreset.HIGH_CONTRAST -> DarkPalette(
            HighContrastBg, HighContrastSurface, HighContrastSurfaceHigh, HighContrastSurfaceHighest,
            HighContrastOutline, HighContrastText, HighContrastTextMuted,
        )
        IdeThemePreset.INTELLIJ_LIGHT -> DarkPalette(
            DarculaBg, DarculaSurface, DarculaSurfaceHigh, DarculaSurfaceHighest,
            DarculaOutline, DarculaText, DarculaTextMuted,
        )
    }

    return darkColorScheme(
        primary = primaryColor,
        onPrimary = Color(0xFF07130D),
        primaryContainer = primaryColor.copy(alpha = 0.18f),
        onPrimaryContainer = primaryColor.copy(alpha = 0.96f),
        secondary = secondaryColor,
        onSecondary = Color(0xFF061529),
        secondaryContainer = secondaryColor.copy(alpha = 0.18f),
        onSecondaryContainer = secondaryColor.copy(alpha = 0.96f),
        tertiary = StudioKotlinPurple,
        onTertiary = Color(0xFF140B2D),
        tertiaryContainer = StudioKotlinPurple.copy(alpha = 0.18f),
        onTertiaryContainer = Color(0xFFEDE9FE),
        background = bg,
        onBackground = text,
        surface = surf,
        onSurface = text,
        surfaceVariant = surfHigh,
        onSurfaceVariant = muted,
        surfaceContainerLow = bg,
        surfaceContainer = surf,
        surfaceContainerHigh = surfHigh,
        surfaceContainerHighest = surfHighest,
        outline = outline,
        outlineVariant = outline.copy(alpha = 0.55f),
        error = ForgeCoral,
        onError = Color(0xFF2D0A0E),
        errorContainer = Color(0xFF45161B),
        onErrorContainer = Color(0xFFFFD6DA),
    )
}

@Composable
fun AndroidStudioTheme(
    themePreset: IdeThemePreset = IdeThemePreset.DARCULA,
    accentPreset: IdeAccentPreset = IdeAccentPreset.ANDROID_GREEN,
    uiDensity: UiDensity = UiDensity.STANDARD,
    content: @Composable () -> Unit,
) {
    val colorScheme = buildColorScheme(themePreset, accentPreset)
    val syntaxColors = syntaxColorsFor(themePreset)

    CompositionLocalProvider(
        LocalSyntaxColors provides syntaxColors,
        LocalUiDensity provides uiDensity,
        LocalIdeThemePreset provides themePreset,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DroidForgeTypography,
            shapes = StudioShapes,
            content = content,
        )
    }
}

@Composable
fun DroidForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    AndroidStudioTheme(
        themePreset = if (darkTheme) IdeThemePreset.DARCULA else IdeThemePreset.INTELLIJ_LIGHT,
        accentPreset = IdeAccentPreset.ANDROID_GREEN,
        uiDensity = UiDensity.STANDARD,
        content = content,
    )
}
