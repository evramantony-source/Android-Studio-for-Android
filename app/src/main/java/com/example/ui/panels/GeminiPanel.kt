package com.example.ui.panels

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.AgentRole
import com.example.ai.AgentStep
import com.example.ai.AgentStepStatus
import com.example.ai.AiModelOption
import com.example.ai.AiModelsCatalog
import com.example.ai.AiProvider
import com.example.ai.AiThinkingMode
import com.example.ai.AiUiState
import com.example.ai.AiUsageSummary
import com.example.ai.ChatMessage
import com.example.ai.FirebaseAiAgentClient
import com.example.ai.GeminiCodeAssistant
import com.example.ai.ProjectChatThread
import com.example.ai.SuggestedCodeBlock
import com.example.model.AiAttachment
import com.example.model.BuildDiagnostic
import com.example.model.DiagnosticSeverity
import com.example.model.EditorSettings
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun GeminiPanel(
    activeFileName: String?,
    activeProvider: AiProvider,
    thinkingMode: AiThinkingMode = AiThinkingMode.FAST,
    providerApiKeys: Map<AiProvider, String>,
    providerModels: Map<AiProvider, String>,
    liveModels: List<AiModelOption>,
    isFetchingModels: Boolean,
    aiUsageSummary: AiUsageSummary,
    chatThreads: List<ProjectChatThread>,
    activeThreadId: String,
    diagnostics: List<BuildDiagnostic>,
    messages: List<ChatMessage>,
    pendingAttachments: List<AiAttachment> = emptyList(),
    aiState: AiUiState,
    editorSettings: EditorSettings = EditorSettings(),
    threadMessagesMap: Map<String, List<ChatMessage>> = emptyMap(),
    threadUiStates: Map<String, AiUiState> = emptyMap(),
    runningThreadIds: Set<String> = emptySet(),
    showSplitSideChat: Boolean = false,
    onToggleSplitSideChat: (() -> Unit)? = null,
    onSelectProvider: (AiProvider) -> Unit,
    onSelectThinkingMode: (AiThinkingMode) -> Unit = {},
    onSelectModel: (AiProvider, String) -> Unit,
    onSaveProviderConfig: (AiProvider, String, String) -> Unit,
    onRefreshLiveModels: (AiProvider) -> Unit,
    onResetAiUsage: () -> Unit,
    onSelectChatThread: (String) -> Unit,
    onCreateSideChat: (String) -> Unit,
    onRenameChatThread: (String, String) -> Unit,
    onDeleteChatThread: (String) -> Unit,
    onLaunchRoleAgent: (AgentRole, String?) -> Unit = { _, _ -> },
    onUpdateThreadProviderModel: (String, AiProvider, String) -> Unit = { _, _, _ -> },
    onUpdateThreadSystemAndTemp: (String, String, Float) -> Unit = { _, _, _ -> },
    onSendPromptToThread: ((String, String) -> Unit)? = null,
    onStopThread: (String) -> Unit = {},
    onRetryThread: (String) -> Unit = {},
    onClearThreadChat: (String) -> Unit = {},
    onOpenCustomization: (() -> Unit)? = null,
    onPickPhoto: () -> Unit = {},
    onCaptureCamera: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onSendPrompt: (String) -> Unit,
    onRetryGemini: () -> Unit,
    onStopGemini: () -> Unit,
    onApplyCode: (SuggestedCodeBlock) -> Unit,
    onClearChat: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    isMaximized: Boolean = false,
    onToggleMaximize: (() -> Unit)? = null,
) {
    var promptInput by remember { mutableStateOf("") }
    var showKeysDialog by remember { mutableStateOf(false) }
    var showModelMenuDialog by remember { mutableStateOf(false) }
    var showUsageDialog by remember { mutableStateOf(false) }
    var showThreadTuneDialog by remember { mutableStateOf(false) }
    var showHeaderMoreMenu by remember { mutableStateOf(false) }
    var renameThreadTarget by remember { mutableStateOf<ProjectChatThread?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val activeThreadMeta = remember(chatThreads, activeThreadId) {
        chatThreads.find { it.id == activeThreadId }
    }
    val effectiveProvider = remember(activeThreadMeta, activeProvider) {
        activeThreadMeta?.providerName
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
            ?: activeProvider
    }
    val currentModelId = remember(activeThreadMeta, effectiveProvider, providerModels) {
        val raw = activeThreadMeta?.modelId?.takeIf { it.isNotBlank() } ?: providerModels[effectiveProvider]
        AiModelsCatalog.normalizeModelId(effectiveProvider, raw)
    }
    val effectiveMessages = threadMessagesMap[activeThreadId] ?: activeThreadMeta?.messages ?: messages
    val effectiveAiState = threadUiStates[activeThreadId] ?: aiState
    val isActiveThreadRunning = (activeThreadId in runningThreadIds) || (effectiveAiState is AiUiState.Streaming)
    val effectiveTemperature = activeThreadMeta?.temperature ?: editorSettings.aiTemperature
    val effectiveSystemInstruction = activeThreadMeta?.customSystemInstruction?.takeIf { it.isNotBlank() }
        ?: editorSettings.customSystemInstruction

    val isFirebaseReady = remember { FirebaseAiAgentClient.isFirebaseConfigured() }
    val resolvedOpenRouterKey = remember(providerApiKeys) {
        GeminiCodeAssistant.resolveOpenRouterApiKey(providerApiKeys[AiProvider.OPENROUTER].orEmpty())
    }
    val hasDirectProviderKey = when (effectiveProvider) {
        AiProvider.GEMINI -> isFirebaseReady ||
            GeminiCodeAssistant.resolveApiKey(providerApiKeys[AiProvider.GEMINI].orEmpty()).isNotEmpty()
        AiProvider.OPENROUTER -> resolvedOpenRouterKey.isNotEmpty()
        else -> providerApiKeys[effectiveProvider].orEmpty().isNotBlank()
    }
    val isUsingOpenRouterBridge = effectiveProvider in setOf(AiProvider.OPENAI, AiProvider.CLAUDE) &&
        providerApiKeys[effectiveProvider].orEmpty().isBlank() &&
        resolvedOpenRouterKey.isNotEmpty()
    val hasEffectiveKey = hasDirectProviderKey || isUsingOpenRouterBridge

    LaunchedEffect(effectiveMessages.size, effectiveMessages.lastOrNull()?.steps?.size) {
        if (effectiveMessages.isNotEmpty()) {
            listState.animateScrollToItem(effectiveMessages.lastIndex)
        }
    }

    LaunchedEffect(effectiveMessages.lastOrNull()?.text?.length) {
        if (effectiveMessages.isNotEmpty() && effectiveAiState is AiUiState.Streaming) {
            listState.scrollToItem(effectiveMessages.lastIndex)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showModelMenuDialog = true }
                        .testTag("open_model_menu_button"),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${activeThreadMeta?.agentRole?.badge ?: effectiveProvider.shortName} • ${effectiveProvider.shortName}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isActiveThreadRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Choose AI model",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = when {
                            isUsingOpenRouterBridge -> "$currentModelId • via OpenRouter"
                            hasEffectiveKey -> currentModelId
                            else -> "$currentModelId • Key needed"
                        },
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasEffectiveKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { showKeysDialog = true },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("gemini_api_key_button"),
                ) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = "Configure AI provider keys",
                        tint = if (hasEffectiveKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(19.dp),
                    )
                }
                if (onToggleSplitSideChat != null) {
                    IconButton(
                        onClick = onToggleSplitSideChat,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("toggle_split_side_chat_button"),
                    ) {
                        Icon(
                            Icons.Default.Forum,
                            contentDescription = "Toggle split side chat",
                            tint = if (showSplitSideChat) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                if (onToggleMaximize != null) {
                    IconButton(
                        onClick = onToggleMaximize,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("gemini_maximize_button"),
                    ) {
                        Icon(
                            imageVector = if (isMaximized) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                            contentDescription = if (isMaximized) "Restore AI panel" else "Maximize AI panel",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Box {
                    IconButton(
                        onClick = { showHeaderMoreMenu = true },
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("gemini_more_menu_button"),
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More AI options", modifier = Modifier.size(19.dp))
                    }
                    DropdownMenu(
                        expanded = showHeaderMoreMenu,
                        onDismissRequest = { showHeaderMoreMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Tune Temp & System Prompt") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = if (effectiveSystemInstruction.isNotBlank()) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            onClick = {
                                showHeaderMoreMenu = false
                                showThreadTuneDialog = true
                            },
                            modifier = Modifier.testTag("thread_tune_button"),
                        )
                        DropdownMenuItem(
                            text = { Text("AI Usage & Tokens") },
                            leadingIcon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                            onClick = {
                                showHeaderMoreMenu = false
                                showUsageDialog = true
                            },
                            modifier = Modifier.testTag("ai_usage_button"),
                        )
                        if (onOpenCustomization != null) {
                            DropdownMenuItem(
                                text = { Text("Customize UI & Studio") },
                                leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                onClick = {
                                    showHeaderMoreMenu = false
                                    onOpenCustomization()
                                },
                                modifier = Modifier.testTag("ai_open_customization_button"),
                            )
                        }
                        if (effectiveMessages.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Clear Active Chat") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    showHeaderMoreMenu = false
                                    onClearThreadChat(activeThreadId)
                                    onClearChat()
                                },
                                modifier = Modifier.testTag("gemini_clear_button"),
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("gemini_close_button"),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close AI panel", modifier = Modifier.size(19.dp))
                }
            }

            // Provider Switcher Bar (Gemini / ChatGPT / Claude / OpenRouter) + Model Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AiProvider.entries.forEach { provider ->
                    val ready = when (provider) {
                        AiProvider.GEMINI -> isFirebaseReady ||
                            GeminiCodeAssistant.resolveApiKey(providerApiKeys[AiProvider.GEMINI].orEmpty()).isNotEmpty()
                        AiProvider.OPENROUTER -> resolvedOpenRouterKey.isNotEmpty()
                        AiProvider.OPENAI, AiProvider.CLAUDE ->
                            providerApiKeys[provider].orEmpty().isNotBlank() || resolvedOpenRouterKey.isNotEmpty()
                    }
                    FilterChip(
                        selected = effectiveProvider == provider,
                        onClick = {
                            val chosenModel = AiModelsCatalog.normalizeModelId(
                                provider,
                                providerModels[provider],
                            )
                            onUpdateThreadProviderModel(activeThreadId, provider, chosenModel)
                            if (activeThreadId == "main") {
                                onSelectProvider(provider)
                            }
                        },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (ready) MaterialTheme.colorScheme.secondary
                                            else MaterialTheme.colorScheme.outline,
                                        ),
                                )
                                Text(provider.shortName, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier.testTag("provider_chip_${provider.name.lowercase()}"),
                    )
                }
                AssistChip(
                    onClick = { showModelMenuDialog = true },
                    label = { Text("Models", style = MaterialTheme.typography.labelSmall) },
                )
                AssistChip(
                    onClick = { showThreadTuneDialog = true },
                    label = {
                        Text(
                            text = "T:${String.format(Locale.US, "%.1f", effectiveTemperature)}",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.testTag("ai_temperature_pill"),
                )
                FilterChip(
                    selected = thinkingMode == AiThinkingMode.FAST,
                    onClick = {
                        val allModes = AiThinkingMode.entries
                        val next = allModes[(thinkingMode.ordinal + 1) % allModes.size]
                        onSelectThinkingMode(next)
                    },
                    label = { Text(thinkingMode.badge, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("ai_speed_mode_chip"),
                )
                AssistChip(
                    onClick = { showUsageDialog = true },
                    label = {
                        Text(
                            text = "${aiUsageSummary.totalTokens} tok • ${formatUsd(aiUsageSummary.totalEstimatedCostUsd)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(14.dp))
                    },
                    modifier = Modifier.testTag("inline_ai_usage_pill"),
                )
            }

            // 3 Integrated Parallel Role Agents Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    AgentRole.FEATURE_BUILDER to Icons.Default.Build,
                    AgentRole.ERROR_FIXER to Icons.Default.BugReport,
                    AgentRole.GITHUB_WATCHER to Icons.Default.CloudUpload,
                ).forEach { (role, icon) ->
                    val roleThread = chatThreads.find { it.agentRole == role }
                    val isRoleRunning = roleThread?.id?.let { it in runningThreadIds } == true
                    val isSelected = roleThread?.id == activeThreadId
                    Surface(
                        onClick = {
                            if (roleThread != null) {
                                onSelectChatThread(roleThread.id)
                            } else {
                                onLaunchRoleAgent(role, null)
                            }
                        },
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("role_agent_chip_${role.name.lowercase()}"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (isRoleRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                )
                            }
                            Text(
                                text = role.badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Run ${role.label}",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { onLaunchRoleAgent(role, null) },
                            )
                        }
                    }
                }
            }

            // Persistent Per-Project Chat Threads + Side Chats Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                chatThreads.forEach { thread ->
                    val selected = thread.id == activeThreadId
                    val isThreadRunning = thread.id in runningThreadIds
                    val threadMsgCount = (threadMessagesMap[thread.id] ?: thread.messages).size
                    Surface(
                        onClick = { onSelectChatThread(thread.id) },
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("chat_thread_chip_${thread.id}"),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = if (thread.isMain) 10.dp else 4.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (isThreadRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.8.dp,
                                )
                            }
                            Text(
                                text = if (threadMsgCount > 0 && !selected) {
                                    "${thread.title} ($threadMsgCount)"
                                } else {
                                    thread.title
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!thread.isMain) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Rename ${thread.title}",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { renameThreadTarget = thread },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Delete ${thread.title}",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { onDeleteChatThread(thread.id) },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                AssistChip(
                    onClick = { onCreateSideChat("") },
                    label = { Text("Side Chat", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Default.AddComment, contentDescription = null, modifier = Modifier.size(14.dp))
                    },
                    modifier = Modifier.testTag("new_side_chat_button"),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Quick Action Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val errorDiagnosticsCount = remember(diagnostics) {
                    diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
                }
                if (errorDiagnosticsCount > 0) {
                    AssistChip(
                        onClick = {
                            onLaunchRoleAgent(
                                AgentRole.ERROR_FIXER,
                                "Inspect the $errorDiagnosticsCount build error(s), edit the broken files directly, and rebuild until it succeeds.",
                            )
                        },
                        label = { Text("Auto-Fix Errors ($errorDiagnosticsCount)") },
                        leadingIcon = {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
                AssistChip(
                    onClick = {
                        val prompt = "Inspect the project files and explain the app architecture."
                        onSendPromptToThread?.invoke(activeThreadId, prompt) ?: onSendPrompt(prompt)
                    },
                    label = { Text("Inspect Project") },
                )
                AssistChip(
                    onClick = {
                        val prompt = "Build a release APK using build_apk(release=true) and fix any issues."
                        onSendPromptToThread?.invoke(activeThreadId, prompt) ?: onSendPrompt(prompt)
                    },
                    label = { Text("Build Release APK") },
                )
                AssistChip(
                    onClick = {
                        val prompt = "Build a signed Android App Bundle using build_aab and summarize the bundle contents."
                        onSendPromptToThread?.invoke(activeThreadId, prompt) ?: onSendPrompt(prompt)
                    },
                    label = { Text("Build AAB") },
                )
            }

            // Messages List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!hasEffectiveKey) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Connect ${activeProvider.displayName} Key",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = "Add your ${activeProvider.shortName} API key (${activeProvider.keyHint}) to run $currentModelId autonomously on your project files.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Button(
                                    onClick = { showKeysDialog = true },
                                    modifier = Modifier.testTag("open_api_key_dialog_button"),
                                ) {
                                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Configure Keys")
                                }
                            }
                        }
                    }
                } else if (effectiveMessages.isEmpty()) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "${activeThreadMeta?.title ?: "${effectiveProvider.shortName} Agent"} ($currentModelId)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = activeThreadMeta?.agentRole?.shortHint
                                        ?: "Chats are saved per project and never reset. Run multiple Role Agents or Side Chats in parallel while editing code.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                items(effectiveMessages, key = { it.id }) { msg ->
                    ChatBubble(
                        message = msg,
                        activeFileName = activeFileName,
                        onApplyCode = onApplyCode,
                    )
                }

                if (effectiveAiState is AiUiState.Error) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Text(
                                        text = effectiveAiState.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                                val needsKeyAction = !hasEffectiveKey ||
                                    effectiveAiState.message.contains("key", ignoreCase = true) ||
                                    effectiveAiState.message.contains("App Check", ignoreCase = true) ||
                                    effectiveAiState.message.contains("401") ||
                                    effectiveAiState.message.contains("403")
                                val needsOpenRouterPrivacyAction = effectiveAiState.message.contains("openrouter.ai/settings/privacy", ignoreCase = true) ||
                                    effectiveAiState.message.contains("data policy", ignoreCase = true)
                                if (effectiveAiState.isRetryable || effectiveAiState.isModelOverloaded || needsKeyAction || needsOpenRouterPrivacyAction) {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (effectiveAiState.isRetryable) {
                                            AssistChip(
                                                onClick = {
                                                    onRetryThread(activeThreadId)
                                                    if (activeThreadId == "main") onRetryGemini()
                                                },
                                                label = { Text("Retry") },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                },
                                                modifier = Modifier.testTag("retry_gemini_button"),
                                            )
                                        }
                                        if (needsOpenRouterPrivacyAction) {
                                            AssistChip(
                                                onClick = {
                                                    runCatching {
                                                        context.startActivity(
                                                            Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/settings/privacy")),
                                                        )
                                                    }
                                                },
                                                label = { Text("OpenRouter Privacy") },
                                                leadingIcon = {
                                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                                },
                                                modifier = Modifier.testTag("openrouter_privacy_button"),
                                            )
                                        }
                                        if (effectiveAiState.isModelOverloaded) {
                                            AssistChip(
                                                onClick = { showModelMenuDialog = true },
                                                label = { Text("Switch Model") },
                                                modifier = Modifier.testTag("switch_model_button"),
                                            )
                                        }
                                        if (needsKeyAction) {
                                            AssistChip(
                                                onClick = { showKeysDialog = true },
                                                label = { Text("Configure Key") },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                                },
                                                modifier = Modifier.testTag("error_configure_key_button"),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Pending Attachments Strip
            if (pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pendingAttachments.forEach { att ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (att.isImage) Icons.Default.Image else Icons.Default.AttachFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = att.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove ${att.displayName}",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onRemoveAttachment(att.id) },
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }

            // Attachment Action Row (Photo Picker, Camera, SAF File Picker)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = onPickPhoto,
                    label = { Text("Photo", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    modifier = Modifier.testTag("ai_attach_photo_button"),
                )
                AssistChip(
                    onClick = onCaptureCamera,
                    label = { Text("Camera", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    modifier = Modifier.testTag("ai_attach_camera_button"),
                )
                AssistChip(
                    onClick = onPickFile,
                    label = { Text("Attach File", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    modifier = Modifier.testTag("ai_attach_file_button"),
                )
            }

            // Prompt Input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = {
                        Text(
                            "Ask ${activeThreadMeta?.title ?: effectiveProvider.shortName} (${effectiveProvider.shortName})...",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    maxLines = 3,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("gemini_prompt_input"),
                )
                val canSend = promptInput.isNotBlank() || pendingAttachments.isNotEmpty()
                if (isActiveThreadRunning) {
                    IconButton(
                        onClick = {
                            onStopThread(activeThreadId)
                            onStopGemini()
                        },
                        modifier = Modifier.testTag("gemini_stop_button"),
                    ) {
                        Icon(
                            Icons.Default.StopCircle,
                            contentDescription = "Stop AI agent",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (!hasEffectiveKey) {
                                showKeysDialog = true
                                return@IconButton
                            }
                            val p = promptInput.trim()
                            if (p.isNotEmpty() || pendingAttachments.isNotEmpty()) {
                                promptInput = ""
                                onSendPrompt(p)
                            }
                        },
                        enabled = canSend,
                        modifier = Modifier.testTag("gemini_send_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send prompt")
                    }
                }
            }
        }
    }

    if (showKeysDialog) {
        ProviderKeysDialog(
            initialProvider = effectiveProvider,
            providerApiKeys = providerApiKeys,
            providerModels = providerModels,
            onSaveConfig = onSaveProviderConfig,
            onDismiss = { showKeysDialog = false },
        )
    }

    if (showModelMenuDialog) {
        ModelMenuDialog(
            activeProvider = effectiveProvider,
            providerModels = providerModels + (effectiveProvider to currentModelId),
            liveModels = liveModels,
            isFetchingModels = isFetchingModels,
            onSelectModel = { prov, mdl ->
                onUpdateThreadProviderModel(activeThreadId, prov, mdl)
                if (activeThreadId == "main") {
                    onSelectModel(prov, mdl)
                }
            },
            onRefreshLiveModels = onRefreshLiveModels,
            onDismiss = { showModelMenuDialog = false },
        )
    }

    if (showThreadTuneDialog) {
        ThreadSystemTuneDialog(
            threadTitle = activeThreadMeta?.title ?: "Agent",
            initialSystemInstruction = effectiveSystemInstruction,
            initialTemperature = effectiveTemperature,
            onSave = { sys, temp ->
                onUpdateThreadSystemAndTemp(activeThreadId, sys, temp)
                showThreadTuneDialog = false
            },
            onDismiss = { showThreadTuneDialog = false },
        )
    }

    if (showUsageDialog) {
        AiUsageDialog(
            usageSummary = aiUsageSummary,
            onResetUsage = onResetAiUsage,
            onDismiss = { showUsageDialog = false },
        )
    }

    renameThreadTarget?.let { target ->
        var titleInput by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameThreadTarget = null },
            title = { Text("Rename Side Chat") },
            text = {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    singleLine = true,
                    label = { Text("Chat Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameChatThread(target.id, titleInput)
                        renameThreadTarget = null
                    },
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameThreadTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ThreadSystemTuneDialog(
    threadTitle: String,
    initialSystemInstruction: String,
    initialTemperature: Float,
    onSave: (String, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var sysInput by remember(initialSystemInstruction) { mutableStateOf(initialSystemInstruction) }
    var temp by remember(initialTemperature) { mutableStateOf(initialTemperature) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tune $threadTitle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Temperature", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = String.format(Locale.US, "%.2f", temp),
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = temp,
                    onValueChange = { v -> temp = (v * 10f).roundToInt() / 10f },
                    valueRange = 0f..1.5f,
                    steps = 14,
                )
                OutlinedTextField(
                    value = sysInput,
                    onValueChange = { sysInput = it },
                    label = { Text("System Instruction for $threadTitle") },
                    placeholder = { Text("e.g. Focus on Material 3 UI or strict bug-fixing...") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(sysInput, temp) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun SideChatSplitPane(
    threads: List<ProjectChatThread>,
    activeThreadId: String,
    splitThread: ProjectChatThread?,
    activeFileName: String?,
    onSelectSplitThread: (String) -> Unit,
    onCreateSideChat: () -> Unit,
    onSwitchToMainInput: (String) -> Unit,
    onApplyCode: (SuggestedCodeBlock) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    threadMessagesMap: Map<String, List<ChatMessage>> = emptyMap(),
    threadUiStates: Map<String, AiUiState> = emptyMap(),
    runningThreadIds: Set<String> = emptySet(),
    activeProvider: AiProvider = AiProvider.GEMINI,
    providerModels: Map<AiProvider, String> = emptyMap(),
    liveModels: List<AiModelOption> = emptyList(),
    isFetchingModels: Boolean = false,
    onSelectSplitProviderModel: (String, AiProvider, String) -> Unit = { _, _, _ -> },
    onRefreshLiveModels: (AiProvider) -> Unit = {},
    onSendPromptToThread: ((String, String) -> Unit)? = null,
    onStopThread: (String) -> Unit = {},
    onRetryThread: (String) -> Unit = {},
    isMaximized: Boolean = false,
    onToggleMaximize: (() -> Unit)? = null,
) {
    var splitInput by remember(splitThread?.id) { mutableStateOf("") }
    var showSplitModelPicker by remember { mutableStateOf(false) }

    val splitProvider = remember(splitThread, activeProvider) {
        splitThread?.providerName
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
            ?: activeProvider
    }
    val splitModelId = remember(splitThread, splitProvider, providerModels) {
        val raw = splitThread?.modelId?.takeIf { it.isNotBlank() } ?: providerModels[splitProvider]
        AiModelsCatalog.normalizeModelId(splitProvider, raw)
    }
    val splitMessages = splitThread?.let { threadMessagesMap[it.id] ?: it.messages }.orEmpty()
    val splitUiState = splitThread?.let { threadUiStates[it.id] } ?: AiUiState.Idle
    val isSplitRunning = splitThread?.id?.let { it in runningThreadIds } == true || splitUiState is AiUiState.Streaming

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = splitThread != null) { showSplitModelPicker = true },
                ) {
                    Icon(
                        Icons.Default.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = splitThread?.title ?: "Side Chat",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (isSplitRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.8.dp)
                            }
                        }
                        if (splitThread != null) {
                            Text(
                                text = "${splitProvider.shortName} • $splitModelId",
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onCreateSideChat,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("split_new_side_chat_button"),
                ) {
                    Icon(Icons.Default.AddComment, contentDescription = "New side chat", modifier = Modifier.size(18.dp))
                }
                if (onToggleMaximize != null) {
                    IconButton(
                        onClick = onToggleMaximize,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("split_chat_maximize_button"),
                    ) {
                        Icon(
                            imageVector = if (isMaximized) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                            contentDescription = if (isMaximized) "Restore side chat" else "Maximize side chat",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("close_split_side_chat_button"),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close split side chat", modifier = Modifier.size(18.dp))
                }
            }

            if (threads.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    threads.filter { it.id != activeThreadId }.forEach { thread ->
                        val running = thread.id in runningThreadIds
                        FilterChip(
                            selected = splitThread?.id == thread.id,
                            onClick = { onSelectSplitThread(thread.id) },
                            leadingIcon = if (running) {
                                { CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.6.dp) }
                            } else {
                                null
                            },
                            label = { Text(thread.title, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (splitThread == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Spin up a parallel Side Chat with its own model to brainstorm or fix while your Main Agent works.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onCreateSideChat) {
                            Text("Create Side Chat")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(splitMessages, key = { it.id }) { msg ->
                        ChatBubble(
                            message = msg,
                            activeFileName = activeFileName,
                            onApplyCode = onApplyCode,
                        )
                    }
                    if (splitUiState is AiUiState.Error) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = splitUiState.message,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    if (splitUiState.isRetryable) {
                                        AssistChip(
                                            onClick = { onRetryThread(splitThread.id) },
                                            label = { Text("Retry Side Chat") },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (onSendPromptToThread != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = splitInput,
                            onValueChange = { splitInput = it },
                            placeholder = {
                                Text(
                                    "Ask ${splitThread.title} (${splitProvider.shortName})...",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            maxLines = 3,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("split_chat_prompt_input"),
                        )
                        if (isSplitRunning) {
                            IconButton(
                                onClick = { onStopThread(splitThread.id) },
                                modifier = Modifier.testTag("split_chat_stop_button"),
                            ) {
                                Icon(
                                    Icons.Default.StopCircle,
                                    contentDescription = "Stop side chat",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val p = splitInput.trim()
                                    if (p.isNotEmpty()) {
                                        splitInput = ""
                                        onSendPromptToThread(splitThread.id, p)
                                    }
                                },
                                enabled = splitInput.isNotBlank(),
                                modifier = Modifier.testTag("split_chat_send_button"),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send side chat message")
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = { onSwitchToMainInput(splitThread.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Reply in ${splitThread.title}")
                        }
                    }
                }
            }
        }
    }

    if (showSplitModelPicker && splitThread != null) {
        ModelMenuDialog(
            activeProvider = splitProvider,
            providerModels = providerModels + (splitProvider to splitModelId),
            liveModels = liveModels,
            isFetchingModels = isFetchingModels,
            onSelectModel = { prov, mdl ->
                onSelectSplitProviderModel(splitThread.id, prov, mdl)
            },
            onRefreshLiveModels = onRefreshLiveModels,
            onDismiss = { showSplitModelPicker = false },
        )
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    activeFileName: String?,
    onApplyCode: (SuggestedCodeBlock) -> Unit,
) {
    val containerColor = if (message.isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val textColor = if (message.isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    var stepsExpanded by remember(message.id) { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = containerColor,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(if (message.isUser) 0.88f else 0.98f),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!message.isUser && (message.providerName.isNotBlank() || message.totalTokens > 0L)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = listOf(message.providerName, message.modelId)
                                .filter { it.isNotBlank() }
                                .joinToString(" • "),
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (message.totalTokens > 0L) {
                            Text(
                                text = "${message.totalTokens} tok",
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }

                if (message.steps.isNotEmpty()) {
                    AgentStepsCard(
                        steps = message.steps,
                        isRunning = message.isStreaming,
                        expanded = stepsExpanded,
                        onToggleExpanded = { stepsExpanded = !stepsExpanded },
                    )
                }

                if (message.attachmentSummaries.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        message.attachmentSummaries.forEach { name ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Default.AttachFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = textColor,
                                    )
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textColor,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                if (message.text.isEmpty() && message.isStreaming) {
                    if (message.steps.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Thinking & inspecting files...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (message.text.isNotEmpty()) {
                    MarkdownText(
                        markdown = message.text,
                        color = textColor,
                        isUser = message.isUser,
                    )
                }

                message.codeBlocks.forEach { block ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = block.suggestedPath ?: (activeFileName ?: block.language.uppercase()),
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = { onApplyCode(block) },
                                    modifier = Modifier.testTag("apply_gemini_code_button"),
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Apply")
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.background,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(8.dp),
                            ) {
                                Text(
                                    text = block.code.lines().take(8).joinToString("\n") +
                                        if (block.code.lines().size > 8) "\n..." else "",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentStepsCard(
    steps: List<AgentStep>,
    isRunning: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val activeStep = steps.lastOrNull { it.status == AgentStepStatus.RUNNING }
    val failedCount = steps.count { it.status == AgentStepStatus.FAILED }
    val totalMs = steps.sumOf { it.durationMs }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent_steps_card"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    isRunning && activeStep != null -> {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                    failedCount > 0 -> {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    else -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    text = if (isRunning && activeStep != null) {
                        activeStep.title
                    } else if (totalMs > 0L) {
                        "Build & Tool Steps (${steps.size}) • ${totalMs}ms"
                    } else {
                        "Build & Tool Steps (${steps.size})"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse steps" else "Expand steps",
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    steps.forEach { step ->
                        var detailExpanded by remember(step.id) { mutableStateOf(false) }
                        val hasInspectableOutput = step.expandedOutput.isNotBlank()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .let {
                                    if (hasInspectableOutput) {
                                        it.clickable { detailExpanded = !detailExpanded }
                                    } else {
                                        it
                                    }
                                },
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier.padding(top = 2.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    when (step.status) {
                                        AgentStepStatus.RUNNING -> CircularProgressIndicator(
                                            modifier = Modifier.size(13.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        AgentStepStatus.COMPLETED -> Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Completed",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        AgentStepStatus.FAILED -> Icon(
                                            Icons.Default.ErrorOutline,
                                            contentDescription = "Failed",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = step.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (step.durationMs > 0L) {
                                            Text(
                                                text = "${step.durationMs}ms",
                                                fontFamily = JetBrainsMonoFontFamily,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                    if (step.detail.isNotBlank()) {
                                        Text(
                                            text = if (hasInspectableOutput && !detailExpanded) {
                                                "${step.detail} (tap for details)"
                                            } else {
                                                step.detail
                                            },
                                            fontFamily = JetBrainsMonoFontFamily,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (step.status == AgentStepStatus.FAILED) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            AnimatedVisibility(visible = detailExpanded && hasInspectableOutput) {
                                Surface(
                                    color = MaterialTheme.colorScheme.background,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 22.dp),
                                ) {
                                    Text(
                                        text = step.expandedOutput,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.padding(8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
