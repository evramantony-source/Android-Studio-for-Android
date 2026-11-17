package com.example.ui.panels

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.AiModelOption
import com.example.ai.AiModelsCatalog
import com.example.ai.AiProvider
import com.example.ai.AiUsageSummary
import com.example.ai.GeminiCodeAssistant
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ProviderKeysDialog(
    initialProvider: AiProvider,
    providerApiKeys: Map<AiProvider, String>,
    providerModels: Map<AiProvider, String>,
    onSaveConfig: (provider: AiProvider, apiKey: String, modelId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedProvider by remember(initialProvider) { mutableStateOf(initialProvider) }
    var keyInputs by remember(providerApiKeys) {
        mutableStateOf(
            AiProvider.entries.associateWith { providerApiKeys[it].orEmpty() },
        )
    }
    var revealKey by remember { mutableStateOf(false) }

    val activeKey = keyInputs[selectedProvider].orEmpty()
    val hasBuiltInGemini = selectedProvider == AiProvider.GEMINI && GeminiCodeAssistant.hasBuiltInApiKey()
    val currentModel = AiModelsCatalog.normalizeModelId(selectedProvider, providerModels[selectedProvider])

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("AI Provider Keys")
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiProvider.entries.forEach { provider ->
                        val configured = when (provider) {
                            AiProvider.GEMINI -> GeminiCodeAssistant.resolveApiKey(keyInputs[provider].orEmpty()).isNotEmpty()
                            else -> keyInputs[provider].orEmpty().isNotBlank()
                        }
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = { selectedProvider = provider },
                            label = {
                                Text(
                                    text = if (configured) "${provider.shortName} • Ready" else provider.shortName,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier.testTag("provider_key_tab_${provider.name.lowercase()}"),
                        )
                    }
                }

                Text(
                    text = when {
                        selectedProvider == AiProvider.GEMINI && activeKey.isBlank() && hasBuiltInGemini ->
                            "Using the built-in GEMINI_API_KEY configured at build time. Paste a custom Google AI Studio key below to override it."
                        selectedProvider == AiProvider.GEMINI ->
                            "Google AI Studio API key for Gemini 2.5, Gemini 2.0, and Gemini 3 models. Saved locally on this device."
                        selectedProvider == AiProvider.OPENAI ->
                            "OpenAI API key (sk-...) for GPT-6, GPT-5, GPT-4.5, GPT-4.1, GPT-4o, o4-mini, o3, and o1 models."
                        else ->
                            "Anthropic API key (sk-ant-...) for Claude 5.5, 4.5, 4.1, 4, 3.7, 3.5, and 3.0 models."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = activeKey,
                    onValueChange = { next ->
                        keyInputs = keyInputs + (selectedProvider to next)
                    },
                    label = { Text("${selectedProvider.shortName} API Key (${selectedProvider.keyHint})") },
                    singleLine = true,
                    visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealKey = !revealKey }) {
                            Icon(
                                imageVector = if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealKey) "Hide API key" else "Show API key",
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("provider_api_key_input"),
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "Active ${selectedProvider.shortName} Model",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = currentModel,
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveConfig(selectedProvider, activeKey, currentModel)
                    onDismiss()
                },
                modifier = Modifier.testTag("save_provider_key_button"),
            ) {
                Text("Save ${selectedProvider.shortName}")
            }
        },
        dismissButton = {
            Row {
                if (activeKey.isNotBlank()) {
                    TextButton(
                        onClick = {
                            onSaveConfig(selectedProvider, "", currentModel)
                            keyInputs = keyInputs + (selectedProvider to "")
                        },
                    ) {
                        Text("Clear")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
    )
}

@Composable
fun ModelMenuDialog(
    activeProvider: AiProvider,
    providerModels: Map<AiProvider, String>,
    liveModels: List<AiModelOption>,
    isFetchingModels: Boolean,
    onSelectModel: (AiProvider, String) -> Unit,
    onRefreshLiveModels: (AiProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    var browsedProvider by remember(activeProvider) { mutableStateOf(activeProvider) }
    var searchQuery by remember { mutableStateOf("") }
    var generationFilter by remember(browsedProvider) { mutableStateOf("All") }
    var customModelInput by remember(browsedProvider, providerModels) {
        mutableStateOf(AiModelsCatalog.normalizeModelId(browsedProvider, providerModels[browsedProvider]))
    }

    val filterOptions = remember(browsedProvider) {
        when (browsedProvider) {
            AiProvider.GEMINI -> listOf("All", "2.5 GA", "2.0", "Gemini 3", "Live")
            AiProvider.OPENAI -> listOf("All", "GPT-6 / 5", "4.1 / 4o", "o-Series", "Live")
            AiProvider.CLAUDE -> listOf("All", "Claude 5", "Claude 4", "Claude 3", "Live")
        }
    }

    val allModels = remember(browsedProvider, liveModels, searchQuery, generationFilter) {
        val base = AiModelsCatalog.modelsFor(browsedProvider, liveModels)
        base.filter { option ->
            val matchesQuery = searchQuery.isBlank() ||
                option.id.contains(searchQuery, ignoreCase = true) ||
                option.label.contains(searchQuery, ignoreCase = true) ||
                option.description.contains(searchQuery, ignoreCase = true)

            val id = option.id.lowercase()
            val matchesGen = when (generationFilter) {
                "All" -> true
                "Live" -> option.isLiveFetched
                "2.5 GA" -> id.contains("2.5")
                "2.0" -> id.contains("2.0") || id.contains("1.5")
                "Gemini 3" -> id.contains("gemini-3")
                "GPT-6 / 5" -> id.startsWith("gpt-6") || id.startsWith("gpt-5")
                "4.1 / 4o" -> id.contains("4.1") || id.contains("4.5") || id.contains("4o") || id.startsWith("gpt-4") || id.startsWith("gpt-3.5")
                "o-Series" -> id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")
                "Claude 5" -> id.contains("-5-") || id.contains("5.5") || id.contains("fable-5")
                "Claude 4" -> id.contains("-4")
                "Claude 3" -> id.contains("-3")
                else -> true
            }
            matchesQuery && matchesGen
        }
    }
    val selectedModelId = AiModelsCatalog.normalizeModelId(browsedProvider, providerModels[browsedProvider])

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose AI Model", modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { onRefreshLiveModels(browsedProvider) },
                    enabled = !isFetchingModels,
                    modifier = Modifier.testTag("fetch_live_models_button"),
                ) {
                    if (isFetchingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Live API")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Provider tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiProvider.entries.forEach { provider ->
                        val count = AiModelsCatalog.modelsFor(provider, liveModels).size
                        FilterChip(
                            selected = browsedProvider == provider,
                            onClick = {
                                browsedProvider = provider
                                generationFilter = "All"
                                customModelInput = providerModels[provider].orEmpty().ifBlank { provider.defaultModelId }
                            },
                            label = { Text("${provider.shortName} ($count)", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("model_menu_provider_${provider.name.lowercase()}"),
                        )
                    }
                }

                // Generation / Family Filter Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    filterOptions.forEach { gen ->
                        FilterChip(
                            selected = generationFilter == gen,
                            onClick = { generationFilter = gen },
                            label = { Text(gen, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                // Filter search
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search ${browsedProvider.shortName} models...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Model List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(allModels, key = { "${it.provider.name}:${it.id}" }) { option ->
                        val isCurrent = browsedProvider == activeProvider && option.id == selectedModelId
                        Surface(
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectModel(option.provider, option.id)
                                    onDismiss()
                                }
                                .testTag("model_option_${option.id}"),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isCurrent) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Surface(
                                        color = if (option.isLiveFetched) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(
                                            text = option.badge,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    if (option.contextWindow.isNotBlank()) {
                                        Text(
                                            text = option.contextWindow,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (isCurrent) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = option.id,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (option.description.isNotBlank()) {
                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Custom model ID row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = customModelInput,
                        onValueChange = { customModelInput = it },
                        label = { Text("Custom model ID") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            if (customModelInput.isNotBlank()) {
                                onSelectModel(browsedProvider, customModelInput.trim())
                                onDismiss()
                            }
                        },
                    ) {
                        Text("Use")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
fun AiUsageDialog(
    usageSummary: AiUsageSummary,
    onResetUsage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val nf = remember { NumberFormat.getIntegerInstance() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("AI Usage, Cost & Quota")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Hero Stats Grid
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = "Cumulative Total Tokens",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    text = nf.format(usageSummary.totalTokens),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Est. API Spend",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    text = formatUsd(usageSummary.totalEstimatedCostUsd),
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            UsageMiniStat("Requests", nf.format(usageSummary.totalRequests))
                            UsageMiniStat("Tool Calls", nf.format(usageSummary.totalToolCalls))
                            UsageMiniStat("Failures", nf.format(usageSummary.totalFailedRequests))
                        }
                    }
                }

                // Token Breakdown
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        UsageMiniStat("Input", nf.format(usageSummary.totalInputTokens))
                        UsageMiniStat("Output", nf.format(usageSummary.totalOutputTokens))
                        UsageMiniStat("Thinking", nf.format(usageSummary.totalThinkingTokens))
                    }
                }

                Text("By Provider (Tokens, Cost & Live Quota)", style = MaterialTheme.typography.labelLarge)
                AiProvider.entries.forEach { provider ->
                    val stats = usageSummary.byProvider[provider]
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = provider.displayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (stats != null) {
                                        "${nf.format(stats.totalTokens)} tok • ${formatUsd(stats.estimatedCostUsd)}"
                                    } else {
                                        "0 tok • $0.00"
                                    },
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (stats == null || stats.requests == 0L) {
                                Text(
                                    text = "No requests recorded yet",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = "${stats.requests} req • ${stats.toolCalls} tool calls • ${stats.lastLatencyMs}ms last",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (stats.lastModelId.isNotBlank()) {
                                    Text(
                                        text = "Last: ${stats.lastModelId}",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                                if (stats.remainingQuotaNote.isNotBlank()) {
                                    Text(
                                        text = "Live Limit: ${stats.remainingQuotaNote}",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        }
                    }
                }

                if (usageSummary.byModel.isNotEmpty()) {
                    Text("Tokens by Model", style = MaterialTheme.typography.labelLarge)
                    usageSummary.byModel.entries
                        .sortedByDescending { it.value }
                        .take(10)
                        .forEach { (model, tokens) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = model,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${nf.format(tokens)} tok",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Official Account Usage Dashboards", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiProvider.entries.forEach { provider ->
                        AssistChip(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(provider.usageConsoleUrl)),
                                    )
                                }
                            },
                            label = { Text("${provider.shortName} Console", style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onResetUsage,
                enabled = usageSummary.totalTokens > 0 || usageSummary.totalRequests > 0,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset")
            }
        },
    )
}

fun formatUsd(amount: Double): String {
    return when {
        amount <= 0.0 -> "$0.00"
        amount < 0.01 -> String.format(Locale.US, "$%.4f", amount)
        else -> String.format(Locale.US, "$%.2f", amount)
    }
}

@Composable
private fun UsageMiniStat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontFamily = JetBrainsMonoFontFamily,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
