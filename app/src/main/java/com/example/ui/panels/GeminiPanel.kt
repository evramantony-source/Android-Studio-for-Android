package com.example.ui.panels

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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.AgentStep
import com.example.ai.AgentStepStatus
import com.example.ai.AiModelOption
import com.example.ai.AiProvider
import com.example.ai.AiThinkingMode
import com.example.ai.AiUiState
import com.example.ai.AiUsageSummary
import com.example.ai.ChatMessage
import com.example.ai.GeminiCodeAssistant
import com.example.ai.ProjectChatThread
import com.example.ai.SuggestedCodeBlock
import com.example.model.AiAttachment
import com.example.model.BuildDiagnostic
import com.example.ui.theme.JetBrainsMonoFontFamily

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
) {
    var promptInput by remember { mutableStateOf("") }
    var showKeysDialog by remember { mutableStateOf(false) }
    var showModelMenuDialog by remember { mutableStateOf(false) }
    var showUsageDialog by remember { mutableStateOf(false) }
    var renameThreadTarget by remember { mutableStateOf<ProjectChatThread?>(null) }
    var isPanelReady by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        kotlinx.coroutines.yield()
        isPanelReady = true
    }

    val currentModelId = com.example.ai.AiModelsCatalog.normalizeModelId(activeProvider, providerModels[activeProvider])
    val hasEffectiveKey = remember(activeProvider, providerApiKeys) {
        when (activeProvider) {
            AiProvider.GEMINI -> true
            else -> providerApiKeys[activeProvider].orEmpty().isNotBlank()
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.steps?.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty() && aiState is AiUiState.Streaming) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxSize(),
    ) {
        if (!isPanelReady) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("gemini_panel_loading"),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Text(
                        text = "Loading ${activeProvider.shortName} Agent...",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = currentModelId,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            return@Surface
        }
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
                            text = "${activeProvider.shortName} Agent",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Choose AI model",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = if (hasEffectiveKey) currentModelId else "$currentModelId • Key needed",
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasEffectiveKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { showUsageDialog = true },
                    modifier = Modifier.testTag("ai_usage_button"),
                ) {
                    Icon(Icons.Default.Analytics, contentDescription = "AI usage and tokens")
                }
                IconButton(
                    onClick = { showKeysDialog = true },
                    modifier = Modifier.testTag("gemini_api_key_button"),
                ) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = "Configure AI provider keys",
                        tint = if (hasEffectiveKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                if (onToggleSplitSideChat != null) {
                    IconButton(
                        onClick = onToggleSplitSideChat,
                        modifier = Modifier.testTag("toggle_split_side_chat_button"),
                    ) {
                        Icon(
                            Icons.Default.Forum,
                            contentDescription = "Toggle split side chat",
                            tint = if (showSplitSideChat) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (messages.isNotEmpty()) {
                    IconButton(onClick = onClearChat, modifier = Modifier.testTag("gemini_clear_button")) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear active chat")
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.testTag("gemini_close_button")) {
                    Icon(Icons.Default.Close, contentDescription = "Close AI panel")
                }
            }

            // Provider Switcher Bar (Gemini / ChatGPT / Claude) + Model Pill
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
                        AiProvider.GEMINI -> GeminiCodeAssistant.resolveApiKey(providerApiKeys[AiProvider.GEMINI].orEmpty()).isNotEmpty()
                        else -> providerApiKeys[provider].orEmpty().isNotBlank()
                    }
                    FilterChip(
                        selected = activeProvider == provider,
                        onClick = { onSelectProvider(provider) },
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
                            Text(
                                text = if (thread.messages.isNotEmpty() && !selected) {
                                    "${thread.title} (${thread.messages.size})"
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
                if (diagnostics.isNotEmpty()) {
                    AssistChip(
                        onClick = {
                            onSendPrompt("Inspect the build errors, edit the broken files directly, and rebuild until it succeeds.")
                        },
                        label = { Text("Auto-Fix Errors (${diagnostics.size})") },
                        leadingIcon = {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
                AssistChip(
                    onClick = {
                        onSendPrompt("Inspect the project files and explain the app architecture.")
                    },
                    label = { Text("Inspect Project") },
                )
                AssistChip(
                    onClick = {
                        onSendPrompt("Build a release APK using build_apk(release=true) and fix any issues.")
                    },
                    label = { Text("Build Release APK") },
                )
                AssistChip(
                    onClick = {
                        onSendPrompt("Build a signed Android App Bundle using build_aab and summarize the bundle contents.")
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
                } else if (messages.isEmpty()) {
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
                                    text = "${activeProvider.shortName} Autonomous Agent ($currentModelId)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Chats are saved per project and never reset. Spin up Side Chats above for parallel questions or refactors, and tap any build/edit step to inspect full logs and diffs.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    ChatBubble(
                        message = msg,
                        activeFileName = activeFileName,
                        onApplyCode = onApplyCode,
                    )
                }

                if (aiState is AiUiState.Error) {
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
                                        text = aiState.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                                val needsKeyAction = !hasEffectiveKey ||
                                    aiState.message.contains("key", ignoreCase = true) ||
                                    aiState.message.contains("401") ||
                                    aiState.message.contains("403")
                                if (aiState.isRetryable || aiState.isModelOverloaded || needsKeyAction) {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (aiState.isRetryable) {
                                            AssistChip(
                                                onClick = onRetryGemini,
                                                label = { Text("Retry") },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                },
                                                modifier = Modifier.testTag("retry_gemini_button"),
                                            )
                                        }
                                        if (aiState.isModelOverloaded) {
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
                    placeholder = { Text("Ask ${activeProvider.shortName} to edit or build...") },
                    maxLines = 3,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("gemini_prompt_input"),
                )
                val isBusy = aiState is AiUiState.Streaming
                val canSend = promptInput.isNotBlank() || pendingAttachments.isNotEmpty()
                if (isBusy) {
                    IconButton(
                        onClick = onStopGemini,
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
            initialProvider = activeProvider,
            providerApiKeys = providerApiKeys,
            providerModels = providerModels,
            onSaveConfig = onSaveProviderConfig,
            onDismiss = { showKeysDialog = false },
        )
    }

    if (showModelMenuDialog) {
        ModelMenuDialog(
            activeProvider = activeProvider,
            providerModels = providerModels,
            liveModels = liveModels,
            isFetchingModels = isFetchingModels,
            onSelectModel = onSelectModel,
            onRefreshLiveModels = onRefreshLiveModels,
            onDismiss = { showModelMenuDialog = false },
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
) {
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
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = splitThread?.title ?: "Side Chat",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onCreateSideChat) {
                    Icon(Icons.Default.AddComment, contentDescription = "New side chat", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onClose) {
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
                        FilterChip(
                            selected = splitThread?.id == thread.id,
                            onClick = { onSelectSplitThread(thread.id) },
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
                            text = "Create a Side Chat to compare ideas or keep a separate scratchpad alongside your Main Agent.",
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
                    items(splitThread.messages, key = { it.id }) { msg ->
                        ChatBubble(
                            message = msg,
                            activeFileName = activeFileName,
                            onApplyCode = onApplyCode,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                                        horizontalArrangement = Arrangement.SpaceBetween,
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
