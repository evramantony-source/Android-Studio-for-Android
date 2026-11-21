package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.model.IdeThemePreset

// Brand & Studio Accents
val StudioAndroidGreen = Color(0xFF3DDC84)
val StudioBlue = Color(0xFF3574F0)
val StudioKotlinPurple = Color(0xFF9E7BFF)
val ForgeCyan = Color(0xFF38BDF8)
val ForgeCyanDeep = Color(0xFF0284C7)
val ForgeEmerald = Color(0xFF34D399)
val ForgeEmeraldDeep = Color(0xFF059669)
val ForgeAmber = Color(0xFFFBBF24)
val ForgeCoral = Color(0xFFF75464)
val ForgeIndigo = Color(0xFF818CF8)

// Android Studio Darcula / New UI Palette
val DarculaBg = Color(0xFF1E1F22)
val DarculaSurface = Color(0xFF2B2D30)
val DarculaSurfaceHigh = Color(0xFF313335)
val DarculaSurfaceHighest = Color(0xFF3C3F41)
val DarculaOutline = Color(0xFF43454A)
val DarculaText = Color(0xFFDFE1E5)
val DarculaTextMuted = Color(0xFF8C919B)

// Islands Midnight Palette
val IslandsBg = Color(0xFF14161B)
val IslandsSurface = Color(0xFF1C1F26)
val IslandsSurfaceHigh = Color(0xFF232730)
val IslandsSurfaceHighest = Color(0xFF2C313D)
val IslandsOutline = Color(0xFF343B48)
val IslandsText = Color(0xFFE6EDF7)
val IslandsTextMuted = Color(0xFF94A3B8)

// Nord Studio Palette
val NordBg = Color(0xFF1E222A)
val NordSurface = Color(0xFF252A34)
val NordSurfaceHigh = Color(0xFF2E3440)
val NordSurfaceHighest = Color(0xFF3B4252)
val NordOutline = Color(0xFF4C566A)
val NordText = Color(0xFFECEFF4)
val NordTextMuted = Color(0xFF9BA4B5)

// High Contrast Palette
val HighContrastBg = Color(0xFF000000)
val HighContrastSurface = Color(0xFF0B0E14)
val HighContrastSurfaceHigh = Color(0xFF141923)
val HighContrastSurfaceHighest = Color(0xFF1D2432)
val HighContrastOutline = Color(0xFF475569)
val HighContrastText = Color(0xFFFFFFFF)
val HighContrastTextMuted = Color(0xFFCBD5E1)

// IntelliJ Light Surfaces
val DaylightBg = Color(0xFFF2F3F5)
val DaylightSurface = Color(0xFFFFFFFF)
val DaylightSurfaceHigh = Color(0xFFE8EAED)
val DaylightSurfaceHighest = Color(0xFFDADDE2)
val DaylightOutline = Color(0xFFC5C9D0)
val DaylightText = Color(0xFF0F172A)
val DaylightTextMuted = Color(0xFF5A6270)

// Backwards-compatible aliases
val ObsidianBg = DarculaBg
val ObsidianSurface = DarculaSurface
val ObsidianSurfaceHigh = DarculaSurfaceHigh
val ObsidianSurfaceHighest = DarculaSurfaceHighest
val ObsidianOutline = DarculaOutline
val ObsidianText = DarculaText
val ObsidianTextMuted = DarculaTextMuted

@Immutable
data class SyntaxColors(
    val editorBackground: Color,
    val gutterBackground: Color,
    val gutterText: Color,
    val activeLineBackground: Color,
    val errorLineBackground: Color,
    val plainText: Color,
    val keyword: Color,
    val type: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
    val xmlTag: Color,
    val xmlAttr: Color,
)

val DarculaSyntaxColors = SyntaxColors(
    editorBackground = Color(0xFF1E1F22),
    gutterBackground = Color(0xFF232528),
    gutterText = Color(0xFF6F737A),
    activeLineBackground = Color(0xFF26282E),
    errorLineBackground = Color(0x33F75464),
    plainText = Color(0xFFBCBEC4),
    keyword = Color(0xFFCF8E6D),
    type = Color(0xFF6FAFBD),
    string = Color(0xFF6AAB73),
    comment = Color(0xFF7A7E85),
    number = Color(0xFF2AACB8),
    annotation = Color(0xFFB3AE60),
    xmlTag = Color(0xFFD5B778),
    xmlAttr = Color(0xFFBABABA),
)

val IslandsSyntaxColors = SyntaxColors(
    editorBackground = Color(0xFF13161D),
    gutterBackground = Color(0xFF181C24),
    gutterText = Color(0xFF64748B),
    activeLineBackground = Color(0xFF1E2432),
    errorLineBackground = Color(0x33F87171),
    plainText = Color(0xFFE2E8F0),
    keyword = Color(0xFFFF7B72),
    type = Color(0xFF79C0FF),
    string = Color(0xFFA5D6FF),
    comment = Color(0xFF6E7681),
    number = Color(0xFF7EE787),
    annotation = Color(0xFFD2A8FF),
    xmlTag = Color(0xFF7EE787),
    xmlAttr = Color(0xFF79C0FF),
)

val NordSyntaxColors = SyntaxColors(
    editorBackground = Color(0xFF20252E),
    gutterBackground = Color(0xFF242A35),
    gutterText = Color(0xFF616E88),
    activeLineBackground = Color(0xFF2E3440),
    errorLineBackground = Color(0x33BF616A),
    plainText = Color(0xFFD8DEE9),
    keyword = Color(0xFF81A1C1),
    type = Color(0xFF8FBCBB),
    string = Color(0xFFA3BE8C),
    comment = Color(0xFF616E88),
    number = Color(0xFFB48EAD),
    annotation = Color(0xFFEBCB8B),
    xmlTag = Color(0xFF81A1C1),
    xmlAttr = Color(0xFF8FBCBB),
)

val HighContrastSyntaxColors = SyntaxColors(
    editorBackground = Color(0xFF000000),
    gutterBackground = Color(0xFF07090D),
    gutterText = Color(0xFF94A3B8),
    activeLineBackground = Color(0xFF161B26),
    errorLineBackground = Color(0x44FF4D4D),
    plainText = Color(0xFFFFFFFF),
    keyword = Color(0xFFFF9E64),
    type = Color(0xFF7DCFFF),
    string = Color(0xFF9ECE6A),
    comment = Color(0xFF737AA2),
    number = Color(0xFFBB9AF7),
    annotation = Color(0xFFE0AF68),
    xmlTag = Color(0xFF73DACA),
    xmlAttr = Color(0xFF7AA2F7),
)

val LightSyntaxColors = SyntaxColors(
    editorBackground = Color(0xFFFFFFFF),
    gutterBackground = Color(0xFFF3F4F6),
    gutterText = Color(0xFF787C84),
    activeLineBackground = Color(0xFFF5F8FF),
    errorLineBackground = Color(0x22DC2626),
    plainText = Color(0xFF080808),
    keyword = Color(0xFF0033B3),
    type = Color(0xFF00627A),
    string = Color(0xFF067D17),
    comment = Color(0xFF8C8C8C),
    number = Color(0xFF1750EB),
    annotation = Color(0xFF9E880D),
    xmlTag = Color(0xFF0033B3),
    xmlAttr = Color(0xFF174AD4),
)

val DarkSyntaxColors = DarculaSyntaxColors

fun syntaxColorsFor(preset: IdeThemePreset): SyntaxColors = when (preset) {
    IdeThemePreset.DARCULA -> DarculaSyntaxColors
    IdeThemePreset.ISLANDS_MIDNIGHT -> IslandsSyntaxColors
    IdeThemePreset.NORD_STUDIO -> NordSyntaxColors
    IdeThemePreset.HIGH_CONTRAST -> HighContrastSyntaxColors
    IdeThemePreset.INTELLIJ_LIGHT -> LightSyntaxColors
}

val LocalSyntaxColors = staticCompositionLocalOf { DarculaSyntaxColors }
