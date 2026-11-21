package com.example.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.EditorSettings
import com.example.model.IdeAccentPreset
import com.example.model.IdeThemePreset
import com.example.model.UiDensity
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.util.Locale
import kotlin.math.roundToInt

private val PROMPT_GALLERY_PRESETS = listOf(
    "Clean MVVM" to "Prefer clean Kotlin/Java separation, immutable state helpers, small well-named methods, and concise comments.",
    "Material 3 Polish" to "Use modern Material Design 3 surfaces, balanced padding, accessible 48dp touch targets, and clear typography.",
    "60fps Game Engine" to "Optimize custom Canvas/SurfaceView rendering loops with delta-time physics, smooth particle effects, and zero frame allocations.",
    "Minimal Bug Fixer" to "Make the smallest surgical code fix possible to eliminate all compiler/runtime diagnostics cleanly without refactoring unrelated code.",
    "GitHub CI Ready" to "Ensure AndroidManifest, Gradle scripts, and GitHub Actions workflows stay valid and buildable locally and in CI.",
)

@Composable
fun UiCustomizationDialog(
    settings: EditorSettings,
    onUpdateSettings: ((EditorSettings) -> EditorSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var systemInstructionDraft by remember(settings.customSystemInstruction) {
        mutableStateOf(settings.customSystemInstruction)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("ui_customization_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Android Studio UI & AI Studio")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Theme Presets
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("IDE Theme Preset", style = MaterialTheme.typography.titleSmall)
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    IdeThemePreset.entries.forEach { preset ->
                        val selected = settings.themePreset == preset
                        Surface(
                            onClick = {
                                onUpdateSettings {
                                    it.copy(
                                        themePreset = preset,
                                        darkTheme = preset.isDark,
                                    )
                                }
                            },
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("theme_preset_${preset.name.lowercase()}"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.displayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    Text(
                                        text = preset.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected theme",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Accent Swatches
                Text("Studio Accent Color", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IdeAccentPreset.entries.forEach { accent ->
                        val selected = settings.accentPreset == accent
                        val swatchColor = Color(accent.primaryHex)
                        Surface(
                            onClick = {
                                onUpdateSettings { it.copy(accentPreset = accent) }
                            },
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("accent_preset_${accent.name.lowercase()}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                            shape = CircleShape,
                                        ),
                                )
                                Text(
                                    text = accent.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                // UI Density
                Text("Workspace Density", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UiDensity.entries.forEach { density ->
                        FilterChip(
                            selected = settings.uiDensity == density,
                            onClick = { onUpdateSettings { it.copy(uiDensity = density) } },
                            label = { Text(density.displayName, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ui_density_${density.name.lowercase()}"),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Heavy Code Editor Settings
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Code Editor Preferences", style = MaterialTheme.typography.titleSmall)
                }

                Column {
                    Text(
                        text = "Font Size: ${settings.fontSizeSp} sp",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = settings.fontSizeSp.toFloat(),
                        onValueChange = { v ->
                            onUpdateSettings { it.copy(fontSizeSp = v.roundToInt()) }
                        },
                        valueRange = 10f..22f,
                        steps = 11,
                    )
                }

                CustomizationSwitchRow(
                    label = "Show Line Numbers",
                    checked = settings.showLineNumbers,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(showLineNumbers = c) } },
                )
                CustomizationSwitchRow(
                    label = "Show File Breadcrumbs",
                    checked = settings.showBreadcrumbs,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(showBreadcrumbs = c) } },
                )
                CustomizationSwitchRow(
                    label = "Show Editor Status Bar",
                    checked = settings.showStatusBar,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(showStatusBar = c) } },
                )
                CustomizationSwitchRow(
                    label = "Highlight Matching Brackets",
                    checked = settings.highlightBrackets,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(highlightBrackets = c) } },
                )
                CustomizationSwitchRow(
                    label = "Word Wrap",
                    checked = settings.wordWrap,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(wordWrap = c) } },
                )
                CustomizationSwitchRow(
                    label = "Auto-Indent on Newline",
                    checked = settings.autoIndent,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(autoIndent = c) } },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Google AI Studio Parameters & System Instructions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text("AI Studio Run Settings", style = MaterialTheme.typography.titleSmall)
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Temperature",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f", settings.aiTemperature),
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = settings.aiTemperature,
                        onValueChange = { v ->
                            val snapped = (v * 10f).roundToInt() / 10f
                            onUpdateSettings { it.copy(aiTemperature = snapped) }
                        },
                        valueRange = 0f..1.5f,
                        steps = 14,
                        modifier = Modifier.testTag("ai_temperature_slider"),
                    )
                    Text(
                        text = "0.0 = deterministic code fixes • 0.3 = balanced • 1.0+ = creative design",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text("Prompt Gallery Presets", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PROMPT_GALLERY_PRESETS.forEach { (label, prompt) ->
                        AssistChip(
                            onClick = {
                                val next = if (systemInstructionDraft.isBlank()) {
                                    prompt
                                } else if (systemInstructionDraft.contains(prompt)) {
                                    systemInstructionDraft
                                } else {
                                    "${systemInstructionDraft.trim()}\n$prompt"
                                }
                                systemInstructionDraft = next
                                onUpdateSettings { it.copy(customSystemInstruction = next) }
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                        )
                    }
                }

                OutlinedTextField(
                    value = systemInstructionDraft,
                    onValueChange = { next ->
                        systemInstructionDraft = next
                        onUpdateSettings { it.copy(customSystemInstruction = next) }
                    },
                    label = { Text("Global System Instructions") },
                    placeholder = { Text("Optional instructions injected into AI agents...") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_system_instruction_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("ui_customization_done_button"),
            ) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun CustomizationSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
