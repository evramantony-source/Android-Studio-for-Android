package com.example.ai

import android.util.Log
import com.example.BuildConfig
import com.example.model.AiAttachment
import com.example.model.BuildDiagnostic
import com.example.model.JavaVersionOption
import com.example.model.ProjectFile
import com.example.model.ProjectLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

private const val TAG = "GeminiCodeAssistant"
const val GEMINI_MODEL_ID = "gemini-3.8-flash"
private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private const val MAX_AGENT_TURNS = 24
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 65_000
private const val UNARY_FALLBACK_READ_TIMEOUT_MS = 115_000
private const val ATTEMPT_TIMEOUT_MS = 190_000L
private const val TURN_TIMEOUT_MS = 420_000L
private const val STREAM_UI_THROTTLE_MS = 55L
private const val PRELOAD_CONTEXT_MAX_CHARS = 10_000

internal class GeminiApiException(
    val httpCode: Int,
    message: String,
    val retryAfterMs: Long? = null,
) : Exception(message)

class GeminiCodeAssistant {

    private val firebaseAiClient by lazy { FirebaseAiAgentClient() }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    @Volatile
    private var activeRunId: String? = null

    @Volatile
    private var userCancelledRunId: String? = null

    fun setMessages(restored: List<ChatMessage>) {
        _messages.value = restored
        if (activeRunId == null) {
            _uiState.value = AiUiState.Idle
        }
    }

    fun clearConversation() {
        activeRunId = null
        userCancelledRunId = null
        _messages.value = emptyList()
        _uiState.value = AiUiState.Idle
    }

    fun popLastPromptForRetry(): String? {
        val current = _messages.value
        val lastUserIdx = current.indexOfLast { it.isUser }
        if (lastUserIdx < 0) return null
        val prompt = current[lastUserIdx].text
        _messages.value = current.take(lastUserIdx)
        _uiState.value = AiUiState.Idle
        return prompt
    }

    fun cancelByUser() {
        val targetId = activeRunId
        userCancelledRunId = targetId
        markCancelled(targetMsgId = targetId, userInitiated = true)
    }

    fun markCancelled(
        targetMsgId: String? = activeRunId,
        userInitiated: Boolean = true,
    ) {
        val detailLabel = if (userInitiated) "Stopped by user" else "Interrupted before completion"
        _messages.value = _messages.value.map { msg ->
            if (!msg.isStreaming) return@map msg
            if (targetMsgId != null && msg.id != targetMsgId) return@map msg
            val updatedSteps = msg.steps.map { step ->
                if (step.status == AgentStepStatus.RUNNING) {
                    step.copy(status = AgentStepStatus.FAILED, detail = detailLabel)
                } else {
                    step
                }
            }
            val nextText = if (userInitiated) {
                msg.text.ifBlank { "Stopped by user." }
            } else {
                msg.text
            }
            msg.copy(
                isStreaming = false,
                steps = updatedSteps,
                text = nextText,
            )
        }
        if (targetMsgId == null || activeRunId == null || targetMsgId == activeRunId) {
            _uiState.value = if (userInitiated) {
                AiUiState.Idle
            } else {
                AiUiState.Error(
                    message = "Request was interrupted before finishing. Tap Retry to continue.",
                    isRetryable = true,
                )
            }
        }
    }

    suspend fun askGemini(
        userPrompt: String,
        apiKey: String,
        modelId: String = GEMINI_MODEL_ID,
        provider: AiProvider = AiProvider.GEMINI,
        thinkingMode: AiThinkingMode = AiThinkingMode.FAST,
        agentRole: AgentRole = AgentRole.GENERAL,
        customSystemInstruction: String = "",
        temperature: Float = 0.3f,
        projectName: String,
        packageName: String,
        projectLanguage: ProjectLanguage = ProjectLanguage.JAVA,
        javaVersion: JavaVersionOption = JavaVersionOption.JAVA_17,
        minSdkVersion: Int = 24,
        attachments: List<AiAttachment> = emptyList(),
        activeFile: ProjectFile?,
        allFiles: List<ProjectFile>,
        diagnostics: List<BuildDiagnostic>,
        toolExecutor: ProjectToolExecutor,
        githubConnected: Boolean = false,
        verifiedLiveModelIds: Set<String> = emptySet(),
        liveModelOptions: List<AiModelOption> = emptyList(),
        openRouterApiKey: String = "",
        allLiveModels: List<AiModelOption> = emptyList(),
        shouldPublishToUi: () -> Boolean = { true },
        onModelAutoCorrected: (failedModel: String, workingModel: String) -> Unit = { _, _ -> },
        onUsageRecorded: (provider: AiProvider, modelId: String, delta: TokenUsageDelta, toolCount: Int, failed: Boolean) -> Unit = { _, _, _, _, _ -> },
        onMessagesChanged: (List<ChatMessage>) -> Unit = {},
    ) {
        val rawTrimmed = userPrompt.trim()
        if (rawTrimmed.isEmpty() && attachments.isEmpty()) return
        val trimmed = rawTrimmed.ifEmpty {
            "Please inspect the attached image/file(s) and help implement what is shown."
        }

        val requestedModel = AiModelsCatalog.normalizeModelId(provider, modelId)
        val rawProviderKey = apiKey.trim()
        val fallbackOpenRouterKey = resolveOpenRouterApiKey(openRouterApiKey)
        val bridgeViaOpenRouter = (provider == AiProvider.OPENAI || provider == AiProvider.CLAUDE) &&
            (rawProviderKey.startsWith("sk-or-", ignoreCase = true) || (rawProviderKey.isEmpty() && fallbackOpenRouterKey.isNotEmpty()))
        val resolvedKey = when {
            bridgeViaOpenRouter -> if (rawProviderKey.startsWith("sk-or-", ignoreCase = true)) rawProviderKey else fallbackOpenRouterKey
            provider == AiProvider.GEMINI -> resolveApiKey(apiKey)
            provider == AiProvider.OPENROUTER -> resolveOpenRouterApiKey(apiKey)
            else -> rawProviderKey
        }

        if (provider != AiProvider.GEMINI && resolvedKey.isEmpty()) {
            _uiState.value = AiUiState.Error(
                "${provider.shortName} API key is not configured. Tap the Key icon above to add a ${provider.shortName} or OpenRouter key.",
                isRetryable = false,
            )
            return
        }

        val priorHistory = _messages.value
            .filter { !it.isStreaming && it.text.isNotBlank() }
            .takeLast(6)
        val includeGitHubTools = shouldIncludeGitHubTools(trimmed, priorHistory, githubConnected)

        val now = System.currentTimeMillis()
        val userMsg = ChatMessage(
            id = "u_$now",
            isUser = true,
            text = trimmed,
            attachmentSummaries = attachments.map { it.displayName },
        )
        val assistantMsgId = "a_${now + 1}"
        activeRunId = assistantMsgId
        userCancelledRunId = null

        val steps = mutableListOf<AgentStep>()
        val assistantPlaceholder = ChatMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            isStreaming = true,
            steps = emptyList(),
            providerName = provider.shortName,
            modelId = requestedModel,
        )

        var runMessages = _messages.value + userMsg + assistantPlaceholder
        fun syncUiMessages(forcePublish: Boolean = true) {
            if (shouldPublishToUi() && (forcePublish || _messages.value.any { it.id == assistantMsgId })) {
                _messages.value = runMessages
            }
        }
        syncUiMessages(forcePublish = true)
        onMessagesChanged(runMessages)
        if (shouldPublishToUi()) {
            _uiState.value = AiUiState.Streaming
        }

        val promptWithContext = buildPrompt(
            userPrompt = trimmed,
            projectName = projectName,
            packageName = packageName,
            projectLanguage = projectLanguage,
            minSdkVersion = minSdkVersion,
            attachments = attachments,
            activeFile = activeFile,
            allFiles = allFiles,
            diagnostics = diagnostics,
        )
        val imageAttachments = attachments.filter { it.isImage && it.base64Data.isNotBlank() }

        val accumulatedVisibleText = StringBuilder()
        var totalMessageTokens = 0L
        var effectiveModelInUse = requestedModel
        var lastUiEmitMs = 0L

        fun updateRunAssistantMessage(
            text: String,
            isStreaming: Boolean,
            stepList: List<AgentStep>,
            codeBlocks: List<SuggestedCodeBlock>,
            totalTokens: Long = 0L,
            modelUsed: String = effectiveModelInUse,
            forceEmit: Boolean = false,
        ) {
            val nowMs = System.currentTimeMillis()
            if (isStreaming && !forceEmit && nowMs - lastUiEmitMs < STREAM_UI_THROTTLE_MS) {
                return
            }
            lastUiEmitMs = nowMs
            runMessages = runMessages.map { msg ->
                if (msg.id == assistantMsgId) {
                    msg.copy(
                        text = text,
                        isStreaming = isStreaming,
                        steps = stepList,
                        codeBlocks = codeBlocks,
                        modelId = modelUsed,
                        totalTokens = if (totalTokens > 0L) totalTokens else msg.totalTokens,
                    )
                } else {
                    msg
                }
            }
            syncUiMessages(forcePublish = true)
        }

        fun addOrUpdateStep(step: AgentStep) {
            val idx = steps.indexOfFirst { it.id == step.id }
            if (idx >= 0) steps[idx] = step else steps.add(step)
            updateRunAssistantMessage(
                text = accumulatedVisibleText.toString(),
                isStreaming = true,
                stepList = steps.toList(),
                codeBlocks = emptyList(),
                totalTokens = totalMessageTokens,
                forceEmit = true,
            )
        }

        fun finalizeRunFailure(failureDetail: String, errorState: AiUiState.Error) {
            val finalizedSteps = steps.map { step ->
                if (step.status == AgentStepStatus.RUNNING) {
                    step.copy(status = AgentStepStatus.FAILED, detail = failureDetail)
                } else {
                    step
                }
            }
            val currentText = accumulatedVisibleText.toString()
            updateRunAssistantMessage(
                text = currentText,
                isStreaming = false,
                stepList = finalizedSteps,
                codeBlocks = extractCodeBlocks(currentText),
                totalTokens = totalMessageTokens,
                forceEmit = true,
            )
            onMessagesChanged(runMessages)
            if (shouldPublishToUi() && activeRunId == assistantMsgId) {
                _uiState.value = errorState
            }
        }

        try {
            when (provider) {
                AiProvider.GEMINI -> runGeminiWithFirebaseAndRestFallback(
                    requestedModel = requestedModel,
                    resolvedKey = resolvedKey,
                    thinkingMode = thinkingMode,
                    agentRole = agentRole,
                    customSystemInstruction = customSystemInstruction,
                    temperature = temperature,
                    projectLanguage = projectLanguage,
                    javaVersion = javaVersion,
                    minSdkVersion = minSdkVersion,
                    includeGitHubTools = includeGitHubTools,
                    verifiedLiveModelIds = verifiedLiveModelIds,
                    imageAttachments = imageAttachments,
                    priorHistory = priorHistory,
                    promptWithContext = promptWithContext,
                    steps = steps,
                    accumulatedVisibleText = accumulatedVisibleText,
                    addOrUpdateStep = ::addOrUpdateStep,
                    onModelAutoCorrected = onModelAutoCorrected,
                    onStreamText = { text, usedModel ->
                        effectiveModelInUse = usedModel
                        updateRunAssistantMessage(
                            text = text,
                            isStreaming = true,
                            stepList = steps.toList(),
                            codeBlocks = emptyList(),
                            totalTokens = totalMessageTokens,
                            modelUsed = usedModel,
                            forceEmit = false,
                        )
                    },
                    onFinalizeSuccess = { finalText, finalSteps, tokens, usedModel ->
                        effectiveModelInUse = usedModel
                        updateRunAssistantMessage(
                            text = finalText,
                            isStreaming = false,
                            stepList = finalSteps,
                            codeBlocks = extractCodeBlocks(finalText),
                            totalTokens = tokens,
                            modelUsed = usedModel,
                            forceEmit = true,
                        )
                        if (shouldPublishToUi() && activeRunId == assistantMsgId) {
                            _uiState.value = AiUiState.Idle
                        }
                    },
                    onFinalizeFailure = { detail, errorState ->
                        finalizeRunFailure(detail, errorState)
                    },
                    toolExecutor = toolExecutor,
                    onUsage = { usedModel, delta, toolCalls ->
                        effectiveModelInUse = usedModel
                        totalMessageTokens += delta.totalTokens
                        onUsageRecorded(provider, usedModel, delta, toolCalls, false)
                    },
                )
                AiProvider.OPENAI, AiProvider.OPENROUTER, AiProvider.CLAUDE -> {
                    if (provider == AiProvider.CLAUDE && !bridgeViaOpenRouter) {
                        val effectiveLiveModels = if (liveModelOptions.none { it.provider == AiProvider.CLAUDE }) {
                            withTimeoutOrNull(7_000L) {
                                AiModelsCatalog.fetchLiveModels(AiProvider.CLAUDE, resolvedKey).getOrDefault(emptyList())
                            }.orEmpty()
                        } else {
                            liveModelOptions
                        }
                        runClaudeLoop(
                            initialModel = requestedModel,
                            apiKey = resolvedKey,
                            thinkingMode = thinkingMode,
                            agentRole = agentRole,
                            customSystemInstruction = customSystemInstruction,
                            temperature = temperature,
                            includeGitHubTools = includeGitHubTools,
                            liveModelOptions = effectiveLiveModels,
                            projectLanguage = projectLanguage,
                            javaVersion = javaVersion,
                            minSdkVersion = minSdkVersion,
                            imageAttachments = imageAttachments,
                            priorHistory = priorHistory,
                            promptWithContext = promptWithContext,
                            steps = steps,
                            accumulatedVisibleText = accumulatedVisibleText,
                            addOrUpdateStep = ::addOrUpdateStep,
                            onModelAutoCorrected = onModelAutoCorrected,
                            onStreamText = { text, usedModel ->
                                effectiveModelInUse = usedModel
                                updateRunAssistantMessage(
                                    text = text,
                                    isStreaming = true,
                                    stepList = steps.toList(),
                                    codeBlocks = emptyList(),
                                    totalTokens = totalMessageTokens,
                                    modelUsed = usedModel,
                                    forceEmit = false,
                                )
                            },
                            onFinalizeSuccess = { finalText, finalSteps, tokens, usedModel ->
                                effectiveModelInUse = usedModel
                                updateRunAssistantMessage(
                                    text = finalText,
                                    isStreaming = false,
                                    stepList = finalSteps,
                                    codeBlocks = extractCodeBlocks(finalText),
                                    totalTokens = tokens,
                                    modelUsed = usedModel,
                                    forceEmit = true,
                                )
                                if (shouldPublishToUi() && activeRunId == assistantMsgId) {
                                    _uiState.value = AiUiState.Idle
                                }
                            },
                            toolExecutor = toolExecutor,
                            onUsage = { usedModel, delta, toolCalls ->
                                effectiveModelInUse = usedModel
                                totalMessageTokens += delta.totalTokens
                                onUsageRecorded(provider, usedModel, delta, toolCalls, false)
                            },
                        )
                    } else {
                        val loopProvider = if (bridgeViaOpenRouter) AiProvider.OPENROUTER else provider
                        val sourceLivePool = (allLiveModels + liveModelOptions).distinctBy { "${it.provider}_${it.id}" }
                        val effectiveLiveModels = when {
                            loopProvider == AiProvider.OPENROUTER && sourceLivePool.none { it.provider == AiProvider.OPENROUTER } ->
                                withTimeoutOrNull(8_000L) {
                                    AiModelsCatalog.fetchOpenRouterPublicModels(resolvedKey).getOrDefault(emptyList())
                                }.orEmpty()
                            loopProvider == AiProvider.OPENAI && sourceLivePool.none { it.provider == AiProvider.OPENAI } ->
                                withTimeoutOrNull(7_000L) {
                                    AiModelsCatalog.fetchLiveModels(AiProvider.OPENAI, resolvedKey).getOrDefault(emptyList())
                                }.orEmpty()
                            else -> sourceLivePool.filter { it.provider == loopProvider }
                        }
                        val effectiveInitialModel = if (bridgeViaOpenRouter) {
                            AiModelsCatalog.toOpenRouterSlug(provider, requestedModel, effectiveLiveModels)
                        } else {
                            requestedModel
                        }
                        if (bridgeViaOpenRouter) {
                            addOrUpdateStep(
                                AgentStep(
                                    id = "bridge_or",
                                    title = "Routing ${provider.shortName} via OpenRouter",
                                    detail = "Target slug: $effectiveInitialModel",
                                    status = AgentStepStatus.COMPLETED,
                                ),
                            )
                        }
                        runOpenAiLoop(
                            provider = loopProvider,
                            initialModel = effectiveInitialModel,
                            apiKey = resolvedKey,
                            thinkingMode = thinkingMode,
                            agentRole = agentRole,
                            customSystemInstruction = customSystemInstruction,
                            temperature = temperature,
                            includeGitHubTools = includeGitHubTools,
                            liveModelOptions = effectiveLiveModels,
                            projectLanguage = projectLanguage,
                            javaVersion = javaVersion,
                            minSdkVersion = minSdkVersion,
                            imageAttachments = imageAttachments,
                            priorHistory = priorHistory,
                            promptWithContext = promptWithContext,
                            steps = steps,
                            accumulatedVisibleText = accumulatedVisibleText,
                            addOrUpdateStep = ::addOrUpdateStep,
                            onModelAutoCorrected = { failed, working ->
                                if (!bridgeViaOpenRouter) {
                                    onModelAutoCorrected(failed, working)
                                }
                            },
                            onStreamText = { text, usedModel ->
                                effectiveModelInUse = usedModel
                                updateRunAssistantMessage(
                                    text = text,
                                    isStreaming = true,
                                    stepList = steps.toList(),
                                    codeBlocks = emptyList(),
                                    totalTokens = totalMessageTokens,
                                    modelUsed = usedModel,
                                    forceEmit = false,
                                )
                            },
                            onFinalizeSuccess = { finalText, finalSteps, tokens, usedModel ->
                                effectiveModelInUse = usedModel
                                updateRunAssistantMessage(
                                    text = finalText,
                                    isStreaming = false,
                                    stepList = finalSteps,
                                    codeBlocks = extractCodeBlocks(finalText),
                                    totalTokens = tokens,
                                    modelUsed = usedModel,
                                    forceEmit = true,
                                )
                                if (shouldPublishToUi() && activeRunId == assistantMsgId) {
                                    _uiState.value = AiUiState.Idle
                                }
                            },
                            toolExecutor = toolExecutor,
                            onUsage = { usedModel, delta, toolCalls ->
                                effectiveModelInUse = usedModel
                                totalMessageTokens += delta.totalTokens
                                onUsageRecorded(provider, usedModel, delta, toolCalls, false)
                            },
                        )
                    }
                }
            }
            onMessagesChanged(runMessages)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "$provider request timed out", e)
            val msg = "Timed out waiting for $effectiveModelInUse after ${TURN_TIMEOUT_MS / 1000}s. Try Fast mode or Retry."
            finalizeRunFailure("Timed out", AiUiState.Error(msg, isRetryable = true, isModelOverloaded = true))
            onUsageRecorded(provider, effectiveModelInUse, TokenUsageDelta(), 0, true)
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Socket timeout talking to $effectiveModelInUse", e)
            val msg = "Network timeout reaching $effectiveModelInUse (${e.message ?: "socket timeout"}). Check your internet connection and retry."
            finalizeRunFailure("Socket timeout", AiUiState.Error(msg, isRetryable = true, isModelOverloaded = true))
            onUsageRecorded(provider, effectiveModelInUse, TokenUsageDelta(), 0, true)
        } catch (e: CancellationException) {
            val stoppedByUser = userCancelledRunId == assistantMsgId
            val isStillCurrentRun = activeRunId == assistantMsgId
            val detail = when {
                stoppedByUser -> "Stopped by user"
                !isStillCurrentRun -> "Superseded by newer request"
                else -> "Interrupted before completion"
            }
            val cleanedSteps = steps.map { step ->
                if (step.status == AgentStepStatus.RUNNING) {
                    step.copy(status = AgentStepStatus.FAILED, detail = detail)
                } else {
                    step
                }
            }
            val finalText = if (stoppedByUser) {
                accumulatedVisibleText.toString().ifBlank { "Stopped by user." }
            } else {
                accumulatedVisibleText.toString()
            }
            runMessages = runMessages.map { msg ->
                if (msg.id == assistantMsgId) {
                    msg.copy(
                        isStreaming = false,
                        steps = cleanedSteps,
                        text = finalText,
                        totalTokens = if (totalMessageTokens > 0L) totalMessageTokens else msg.totalTokens,
                    )
                } else {
                    msg
                }
            }
            syncUiMessages(forcePublish = true)
            onMessagesChanged(runMessages)
            if (shouldPublishToUi() && isStillCurrentRun) {
                _uiState.value = if (stoppedByUser) {
                    AiUiState.Idle
                } else {
                    AiUiState.Error(
                        message = "Request was interrupted before finishing. Tap Retry to continue.",
                        isRetryable = true,
                    )
                }
            }
            throw e
        } catch (e: GeminiApiException) {
            Log.e(TAG, "${provider.shortName} API HTTP ${e.httpCode}: ${e.message}", e)
            onUsageRecorded(provider, effectiveModelInUse, TokenUsageDelta(), 0, true)
            val overloaded = e.httpCode == 503 || e.httpCode == 429 || e.httpCode == 529
            val errLower = e.message.orEmpty().lowercase()
            val isOpenRouterDataPolicy = provider == AiProvider.OPENROUTER &&
                (errLower.contains("data policy") ||
                    errLower.contains("free model publication") ||
                    errLower.contains("openrouter.ai/settings/privacy") ||
                    (e.httpCode == 404 && errLower.contains("privacy")))
            val friendly = when (e.httpCode) {
                400, 401, 403 -> "${provider.shortName} API error (${e.httpCode}): ${e.message}"
                402 -> if (provider == AiProvider.OPENROUTER) {
                    "OpenRouter credits or token limit reached (402): ${e.message}. Select a ':free' model or add OpenRouter credits."
                } else {
                    "${provider.shortName} billing error (402): ${e.message}"
                }
                404 -> when {
                    isOpenRouterDataPolicy ->
                        "OpenRouter Privacy Policy blocked free endpoints (404): Your account privacy settings reject free model prompt logging/publication. Enable \"Free model publication\" in https://openrouter.ai/settings/privacy or tap OpenRouter Privacy below, then retry."
                    provider == AiProvider.OPENROUTER ->
                        "No live OpenRouter endpoints were available for '$effectiveModelInUse' (404): ${e.message}. Tap Refresh Models or Switch Model."
                    else ->
                        "Model '$effectiveModelInUse' was not found for this ${provider.shortName} key (404): ${e.message}."
                }
                429 -> "${provider.shortName} rate limit or quota exceeded (429): ${e.message}"
                503, 529 -> "All tried ${provider.shortName} models are overloaded right now (${e.httpCode}): ${e.message}"
                else -> "${provider.shortName} API error (${e.httpCode}): ${e.message}"
            }
            finalizeRunFailure(
                "API error ${e.httpCode}",
                AiUiState.Error(
                    message = friendly,
                    isRetryable = e.httpCode !in listOf(401, 403),
                    isModelOverloaded = (overloaded || e.httpCode == 404) && !isOpenRouterDataPolicy,
                ),
            )
        } catch (e: UnknownHostException) {
            Log.e(TAG, "No network connection", e)
            onUsageRecorded(provider, effectiveModelInUse, TokenUsageDelta(), 0, true)
            finalizeRunFailure("Offline", AiUiState.Error("No internet connection. Check your network and retry."))
        } catch (e: Exception) {
            Log.e(TAG, "${provider.shortName} generation failed", e)
            val msg = e.localizedMessage?.ifBlank { null } ?: "Failed to reach ${provider.shortName} API."
            onUsageRecorded(provider, effectiveModelInUse, TokenUsageDelta(), 0, true)
            finalizeRunFailure(msg, AiUiState.Error(msg))
        } finally {
            if (activeRunId == assistantMsgId) {
                activeRunId = null
            }
            if (userCancelledRunId == assistantMsgId) {
                userCancelledRunId = null
            }
        }
    }

    private suspend fun runGeminiWithFirebaseAndRestFallback(
        requestedModel: String,
        resolvedKey: String,
        thinkingMode: AiThinkingMode,
        agentRole: AgentRole = AgentRole.GENERAL,
        customSystemInstruction: String = "",
        temperature: Float = 0.3f,
        projectLanguage: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
        includeGitHubTools: Boolean,
        verifiedLiveModelIds: Set<String>,
        imageAttachments: List<AiAttachment>,
        priorHistory: List<ChatMessage>,
        promptWithContext: String,
        steps: MutableList<AgentStep>,
        accumulatedVisibleText: StringBuilder,
        addOrUpdateStep: (AgentStep) -> Unit,
        onModelAutoCorrected: (failedModel: String, workingModel: String) -> Unit,
        onStreamText: (String, String) -> Unit,
        onFinalizeSuccess: (String, List<AgentStep>, Long, String) -> Unit,
        onFinalizeFailure: (String, AiUiState.Error) -> Unit,
        toolExecutor: ProjectToolExecutor,
        onUsage: (String, TokenUsageDelta, Int) -> Unit,
    ) {
        val systemText = buildSystemInstruction(
            modelId = requestedModel,
            projectLanguage = projectLanguage,
            javaVersion = javaVersion,
            minSdkVersion = minSdkVersion,
            agentRole = agentRole,
            customSystemInstruction = customSystemInstruction,
        )

        suspend fun runFirebasePath() {
            firebaseAiClient.runAgentLoop(
                requestedModel = requestedModel,
                thinkingMode = thinkingMode,
                projectLanguage = projectLanguage,
                javaVersion = javaVersion,
                minSdkVersion = minSdkVersion,
                includeGitHubTools = includeGitHubTools,
                imageAttachments = imageAttachments,
                priorHistory = priorHistory,
                promptWithContext = promptWithContext,
                systemInstructionText = systemText,
                steps = steps,
                accumulatedVisibleText = accumulatedVisibleText,
                addOrUpdateStep = addOrUpdateStep,
                onStreamText = onStreamText,
                onFinalizeSuccess = onFinalizeSuccess,
                onFinalizeFailure = onFinalizeFailure,
                toolExecutor = toolExecutor,
                executeTool = ::executeToolCall,
                describeTool = ::describeToolCall,
                onUsage = onUsage,
            )
        }

        suspend fun runRestPath(startModel: String) {
            runGeminiLoop(
                initialModel = startModel,
                apiKey = resolvedKey,
                thinkingMode = thinkingMode,
                agentRole = agentRole,
                customSystemInstruction = customSystemInstruction,
                temperature = temperature,
                projectLanguage = projectLanguage,
                javaVersion = javaVersion,
                minSdkVersion = minSdkVersion,
                includeGitHubTools = includeGitHubTools,
                verifiedLiveModelIds = verifiedLiveModelIds,
                imageAttachments = imageAttachments,
                priorHistory = priorHistory,
                promptWithContext = promptWithContext,
                steps = steps,
                accumulatedVisibleText = accumulatedVisibleText,
                addOrUpdateStep = addOrUpdateStep,
                onModelAutoCorrected = onModelAutoCorrected,
                onStreamText = onStreamText,
                onFinalizeSuccess = onFinalizeSuccess,
                onFinalizeFailure = onFinalizeFailure,
                toolExecutor = toolExecutor,
                onUsage = onUsage,
            )
        }

        val firebaseReady = FirebaseAiAgentClient.isFirebaseConfigured()
        val preferFirebaseFirst = firebaseReady && (
            resolvedKey.isEmpty() || FirebaseAiAgentClient.isFirebaseAiLogicModel(requestedModel)
            )

        // Rewrite retired model IDs (Google 404s / 403s them) to the live replacement before
        // the REST call. Every Firebase AI Logic model is also a valid REST model, so no
        // special-casing is needed beyond the retirement remap.
        fun fallbackRestModelFor(model: String): String =
            AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, model)

        if (!firebaseReady && resolvedKey.isEmpty()) {
            val msg = "No Gemini access configured. Tap the Key icon above and paste a Google AI Studio API key (free at aistudio.google.com/apikey), or add a GOOGLE_SERVICES_JSON GitHub secret to bundle built-in Firebase AI."
            onFinalizeFailure(msg, AiUiState.Error(msg, isRetryable = false))
            return
        }

        if (preferFirebaseFirst) {
            try {
                runFirebasePath()
                return
            } catch (fbErr: Exception) {
                if (fbErr is CancellationException && fbErr !is TimeoutCancellationException) throw fbErr
                if (resolvedKey.isEmpty()) throw fbErr
                Log.w(TAG, "Firebase AI path failed (${fbErr.message}); falling back to REST API key path", fbErr)
                steps.clear()
                accumulatedVisibleText.clear()
                addOrUpdateStep(
                    AgentStep(
                        id = "engine_fallback",
                        title = "Switching to REST API backup",
                        detail = "Firebase AI unavailable; using your Gemini API key...",
                        status = AgentStepStatus.COMPLETED,
                    ),
                )
                runRestPath(fallbackRestModelFor(requestedModel))
            }
        } else {
            try {
                runRestPath(fallbackRestModelFor(requestedModel))
                return
            } catch (restErr: Exception) {
                if (restErr is CancellationException && restErr !is TimeoutCancellationException) throw restErr
                if (!firebaseReady) throw restErr
                Log.w(TAG, "REST API key path failed (${restErr.message}); falling back to Firebase AI Logic", restErr)
                steps.clear()
                accumulatedVisibleText.clear()
                addOrUpdateStep(
                    AgentStep(
                        id = "engine_fallback",
                        title = "Switching to Firebase AI Logic",
                        detail = "API key busy/unavailable; using built-in Firebase AI...",
                        status = AgentStepStatus.COMPLETED,
                    ),
                )
                runFirebasePath()
            }
        }
    }

    private suspend fun runGeminiLoop(
        initialModel: String,
        apiKey: String,
        thinkingMode: AiThinkingMode,
        agentRole: AgentRole = AgentRole.GENERAL,
        customSystemInstruction: String = "",
        temperature: Float = 0.3f,
        projectLanguage: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
        includeGitHubTools: Boolean,
        verifiedLiveModelIds: Set<String>,
        imageAttachments: List<AiAttachment>,
        priorHistory: List<ChatMessage>,
        promptWithContext: String,
        steps: MutableList<AgentStep>,
        accumulatedVisibleText: StringBuilder,
        addOrUpdateStep: (AgentStep) -> Unit,
        onModelAutoCorrected: (failedModel: String, workingModel: String) -> Unit,
        onStreamText: (String, String) -> Unit,
        onFinalizeSuccess: (String, List<AgentStep>, Long, String) -> Unit,
        onFinalizeFailure: (String, AiUiState.Error) -> Unit,
        toolExecutor: ProjectToolExecutor,
        onUsage: (String, TokenUsageDelta, Int) -> Unit,
    ) {
        val conversationContents = buildInitialContents(priorHistory, promptWithContext, imageAttachments)
        var completed = false
        var cumulativeTokens = 0L
        var activeModel = initialModel
        var notifiedAutoCorrection = false

        for (turn in 0 until MAX_AGENT_TURNS) {
            coroutineContext.ensureActive()
            if (turn > 0) {
                delay(350L)
            }
            val turnStart = System.currentTimeMillis()
            val thinkStepId = "think_$turn"
            addOrUpdateStep(
                AgentStep(
                    id = thinkStepId,
                    title = if (turn == 0) "Connecting to $activeModel" else "Analyzing tool results ($activeModel)",
                    detail = "${thinkingMode.badge} • Waiting for Gemini...",
                    status = AgentStepStatus.RUNNING,
                ),
            )

            val turnPrefixLength = accumulatedVisibleText.length
            if (accumulatedVisibleText.isNotEmpty() && !accumulatedVisibleText.endsWith("\n")) {
                accumulatedVisibleText.append("\n\n")
            }
            val textBeforeTurn = accumulatedVisibleText.toString()
            val turnVisibleText = StringBuilder()

            val turnOutcome = try {
                withTimeout(TURN_TIMEOUT_MS) {
                    callGeminiWithRetryAndFailover(
                        primaryModelId = activeModel,
                        apiKey = apiKey,
                        thinkingMode = thinkingMode,
                        agentRole = agentRole,
                        customSystemInstruction = customSystemInstruction,
                        temperature = temperature,
                        projectLanguage = projectLanguage,
                        javaVersion = javaVersion,
                        minSdkVersion = minSdkVersion,
                        includeGitHubTools = includeGitHubTools,
                        verifiedLiveModelIds = verifiedLiveModelIds,
                        contents = conversationContents,
                        onModelAutoCorrected = { failedModel, resolvedModel ->
                            if (!notifiedAutoCorrection && turn == 0) {
                                notifiedAutoCorrection = true
                                onModelAutoCorrected(failedModel, resolvedModel)
                            }
                        },
                        onConnected = { connectedModel ->
                            activeModel = connectedModel
                            addOrUpdateStep(
                                AgentStep(
                                    id = thinkStepId,
                                    title = if (turn == 0) "Streaming from $connectedModel" else "Analyzing results ($connectedModel)",
                                    detail = "${thinkingMode.badge} • Streaming response...",
                                    status = AgentStepStatus.RUNNING,
                                ),
                            )
                        },
                        onStatusUpdate = { title, detail ->
                            addOrUpdateStep(
                                AgentStep(
                                    id = thinkStepId,
                                    title = title,
                                    detail = detail,
                                    status = AgentStepStatus.RUNNING,
                                ),
                            )
                        },
                        onTextDelta = { delta ->
                            turnVisibleText.append(delta)
                            accumulatedVisibleText.clear()
                            accumulatedVisibleText.append(textBeforeTurn)
                            accumulatedVisibleText.append(turnVisibleText)
                            onStreamText(accumulatedVisibleText.toString(), activeModel)
                        },
                    )
                }
            } catch (agentError: Exception) {
                if (agentError is CancellationException && agentError !is TimeoutCancellationException) {
                    throw agentError
                }
                val isAuthFailure = agentError is GeminiApiException && agentError.httpCode in listOf(401, 403)
                if (isAuthFailure) {
                    throw agentError
                }
                val completedToolStepsBeforeError = steps.any {
                    it.status == AgentStepStatus.COMPLETED && !it.id.startsWith("think_")
                }
                // "Try another way": direct compact non-tool unary generateContent rescue
                val rescue = tryCompactNonToolRescue(
                    apiKey = apiKey,
                    verifiedLiveModelIds = verifiedLiveModelIds,
                    projectLanguage = projectLanguage,
                    javaVersion = javaVersion,
                    minSdkVersion = minSdkVersion,
                    promptWithContext = promptWithContext,
                    imageAttachments = imageAttachments,
                    afterRateLimit = agentError is GeminiApiException && (agentError.httpCode == 429 || agentError.httpCode == 503),
                    onStatusUpdate = { title, detail ->
                        addOrUpdateStep(
                            AgentStep(
                                id = thinkStepId,
                                title = title,
                                detail = detail,
                                status = AgentStepStatus.RUNNING,
                            ),
                        )
                    },
                )
                if (rescue == null) {
                    if (completedToolStepsBeforeError) {
                        addOrUpdateStep(
                            AgentStep(
                                id = thinkStepId,
                                title = "Completed edits before quota limit",
                                detail = agentError.message ?: "Follow-up summary hit API limit",
                                status = AgentStepStatus.COMPLETED,
                                durationMs = System.currentTimeMillis() - turnStart,
                            ),
                        )
                        val partialSummary = accumulatedVisibleText.toString().trim().ifBlank {
                            "Completed your requested file updates. Tap **Build / Run** to test, or tap **Retry** if you want me to continue further."
                        }
                        onFinalizeSuccess(partialSummary, steps.toList(), cumulativeTokens, activeModel)
                        completed = true
                        break
                    }
                    throw agentError
                }

                // Auto-apply any `// file: path` blocks returned by direct rescue so non-coders get working files automatically
                val autoApplyBlocks = extractCodeBlocks(rescue.text).filter { !it.suggestedPath.isNullOrBlank() }
                if (autoApplyBlocks.isNotEmpty()) {
                    var anyWritten = false
                    for ((bIdx, block) in autoApplyBlocks.withIndex()) {
                        coroutineContext.ensureActive()
                        val targetPath = block.suggestedPath!!.trim()
                        val writeStepId = "rescue_write_${turn}_$bIdx"
                        val writeStart = System.currentTimeMillis()
                        addOrUpdateStep(
                            AgentStep(
                                id = writeStepId,
                                title = "Saving $targetPath",
                                detail = "Applying generated file...",
                                status = AgentStepStatus.RUNNING,
                                toolName = "write_file",
                            ),
                        )
                        val writeResult = toolExecutor.writeFile(targetPath, block.code)
                        if (writeResult.success) anyWritten = true
                        addOrUpdateStep(
                            AgentStep(
                                id = writeStepId,
                                title = "Saved $targetPath",
                                detail = writeResult.stepDetail,
                                status = if (writeResult.success) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED,
                                expandedOutput = writeResult.detailedOutput,
                                durationMs = System.currentTimeMillis() - writeStart,
                                toolName = "write_file",
                            ),
                        )
                    }
                    if (anyWritten) {
                        val buildStepId = "rescue_build_$turn"
                        val buildStart = System.currentTimeMillis()
                        addOrUpdateStep(
                            AgentStep(
                                id = buildStepId,
                                title = "Verifying APK build",
                                detail = "Compiling updated project...",
                                status = AgentStepStatus.RUNNING,
                                toolName = "build_apk",
                            ),
                        )
                        val buildResult = toolExecutor.buildApk(isRelease = false)
                        addOrUpdateStep(
                            AgentStep(
                                id = buildStepId,
                                title = "Build verification",
                                detail = buildResult.stepDetail,
                                status = if (buildResult.success) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED,
                                expandedOutput = buildResult.detailedOutput,
                                durationMs = System.currentTimeMillis() - buildStart,
                                toolName = "build_apk",
                            ),
                        )
                    }
                }

                if (rescue.text.isNotBlank()) {
                    accumulatedVisibleText.clear()
                    accumulatedVisibleText.append(textBeforeTurn)
                    accumulatedVisibleText.append(rescue.text)
                }
                rescue.outcome
            }

            if (turnOutcome.usedModel != activeModel) {
                activeModel = turnOutcome.usedModel
            }
            if (turnVisibleText.isEmpty() && accumulatedVisibleText.length == textBeforeTurn.length) {
                accumulatedVisibleText.setLength(turnPrefixLength)
            }

            val functionCalls = turnOutcome.functionCalls
            cumulativeTokens += turnOutcome.usage.totalTokens
            onUsage(activeModel, turnOutcome.usage, functionCalls.size)

            if (functionCalls.isEmpty()) {
                val toolCount = steps.count { it.status == AgentStepStatus.COMPLETED && !it.id.startsWith("think_") }
                val failoverSuffix = if (activeModel != initialModel) " (auto-switched from $initialModel)" else ""
                val completionStep = AgentStep(
                    id = thinkStepId,
                    title = if (turn == 0) "Responded via $activeModel" else "Completed task via $activeModel",
                    detail = (if (toolCount > 0) "$toolCount tool(s) • $cumulativeTokens tokens" else "$cumulativeTokens tokens") + failoverSuffix,
                    status = AgentStepStatus.COMPLETED,
                    durationMs = System.currentTimeMillis() - turnStart,
                )
                addOrUpdateStep(completionStep)

                val finalText = accumulatedVisibleText.toString().trim()
                if (finalText.isEmpty() && steps.none { !it.id.startsWith("think_") }) {
                    val reasonMsg = when {
                        !turnOutcome.blockReason.isNullOrBlank() -> "Response blocked by Gemini (${turnOutcome.blockReason})."
                        !turnOutcome.finishReason.isNullOrBlank() && turnOutcome.finishReason != "STOP" ->
                            "Gemini returned no text (finishReason: ${turnOutcome.finishReason})."
                        else -> "Gemini returned an empty response."
                    }
                    onFinalizeFailure(reasonMsg, AiUiState.Error(reasonMsg))
                } else {
                    val displayText = finalText.ifBlank { "All requested actions finished." }
                    onFinalizeSuccess(displayText, steps.toList(), cumulativeTokens, activeModel)
                }
                completed = true
                break
            }

            val failoverNote = if (activeModel != initialModel) " • $activeModel" else ""
            addOrUpdateStep(
                AgentStep(
                    id = thinkStepId,
                    title = "Planned ${functionCalls.size} action(s)$failoverNote",
                    detail = functionCalls.joinToString(", ") { it.name },
                    status = AgentStepStatus.COMPLETED,
                    durationMs = System.currentTimeMillis() - turnStart,
                ),
            )

            conversationContents.put(
                JSONObject().apply {
                    put("role", "model")
                    put("parts", turnOutcome.modelParts)
                },
            )

            val functionResponseParts = JSONArray()
            for ((callIdx, call) in functionCalls.withIndex()) {
                coroutineContext.ensureActive()
                val stepId = "tool_${turn}_$callIdx"
                val stepTitle = describeToolCall(call.name, call.args)
                val stepStart = System.currentTimeMillis()

                addOrUpdateStep(
                    AgentStep(
                        id = stepId,
                        title = stepTitle,
                        detail = "Executing ${call.name}...",
                        status = AgentStepStatus.RUNNING,
                        toolName = call.name,
                    ),
                )

                val toolResult = executeToolCall(call, toolExecutor)
                addOrUpdateStep(
                    AgentStep(
                        id = stepId,
                        title = stepTitle,
                        detail = toolResult.stepDetail,
                        status = if (toolResult.success) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED,
                        expandedOutput = toolResult.detailedOutput,
                        durationMs = System.currentTimeMillis() - stepStart,
                        toolName = call.name,
                    ),
                )

                functionResponseParts.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", call.name).put("response", toolResult.payload),
                    ),
                )
            }

            conversationContents.put(
                JSONObject().put("role", "user").put("parts", functionResponseParts),
            )
        }

        if (!completed) {
            val summary = accumulatedVisibleText.toString().trim()
                .ifBlank { buildCompletedStepsSummary(steps) }
            onFinalizeSuccess(summary, steps.toList(), cumulativeTokens, activeModel)
        }
    }

    private suspend fun runOpenAiLoop(
        provider: AiProvider = AiProvider.OPENAI,
        initialModel: String,
        apiKey: String,
        thinkingMode: AiThinkingMode,
        agentRole: AgentRole = AgentRole.GENERAL,
        customSystemInstruction: String = "",
        temperature: Float = 0.3f,
        includeGitHubTools: Boolean = true,
        liveModelOptions: List<AiModelOption> = emptyList(),
        projectLanguage: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
        imageAttachments: List<AiAttachment>,
        priorHistory: List<ChatMessage>,
        promptWithContext: String,
        steps: MutableList<AgentStep>,
        accumulatedVisibleText: StringBuilder,
        addOrUpdateStep: (AgentStep) -> Unit,
        onModelAutoCorrected: (failedModel: String, workingModel: String) -> Unit = { _, _ -> },
        onStreamText: (String, String) -> Unit,
        onFinalizeSuccess: (String, List<AgentStep>, Long, String) -> Unit,
        toolExecutor: ProjectToolExecutor,
        onUsage: (String, TokenUsageDelta, Int) -> Unit,
    ) {
        var activeModel = when {
            provider == AiProvider.OPENROUTER && liveModelOptions.isNotEmpty() -> {
                val resolved = AiModelsCatalog.resolveOpenRouterModelId(initialModel, liveModelOptions)
                if (!resolved.equals(initialModel, ignoreCase = true)) {
                    onModelAutoCorrected(initialModel, resolved)
                }
                resolved
            }
            provider == AiProvider.OPENAI && liveModelOptions.isNotEmpty() -> {
                val resolved = AiModelsCatalog.resolveOpenAiModelId(initialModel, liveModelOptions)
                if (!resolved.equals(initialModel, ignoreCase = true)) {
                    onModelAutoCorrected(initialModel, resolved)
                }
                resolved
            }
            else -> initialModel
        }
        val openAiMessages = JSONArray().apply {
            put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        buildSystemInstruction(
                            modelId = activeModel,
                            projectLanguage = projectLanguage,
                            javaVersion = javaVersion,
                            minSdkVersion = minSdkVersion,
                            agentRole = agentRole,
                            customSystemInstruction = customSystemInstruction,
                        ),
                    ),
            )
            priorHistory.forEach { msg ->
                put(JSONObject().put("role", if (msg.isUser) "user" else "assistant").put("content", msg.text))
            }
            if (imageAttachments.isEmpty()) {
                put(JSONObject().put("role", "user").put("content", promptWithContext))
            } else {
                val contentParts = JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", promptWithContext))
                    imageAttachments.forEach { img ->
                        put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put("url", "data:${img.mimeType};base64,${img.base64Data}"),
                                ),
                        )
                    }
                }
                put(JSONObject().put("role", "user").put("content", contentParts))
            }
        }

        val knownModels = (AiModelsCatalog.builtInOpenRouterModels + AiModelsCatalog.modelsFor(AiProvider.OPENAI) + liveModelOptions)
            .associateBy { it.id.lowercase() }
        val learnedCapabilities = mutableMapOf<String, OpenAiModelCapabilities>()

        suspend fun invokeOpenAiCompatibleCandidate(
            candidate: String,
            allowToolsThisCall: Boolean,
            allowTextToolFallback: Boolean,
            thinkStepId: String,
            textBeforeTurn: String,
            turnText: StringBuilder,
        ): ExternalTurnOutcome {
            val lower = candidate.lowercase()
            val tail = lower.substringAfter('/')
            val meta = knownModels[lower]
            val isDirectOpenAi = provider == AiProvider.OPENAI
            val baseline = learnedCapabilities[lower] ?: OpenAiModelCapabilities(
                useNativeTools = when {
                    isDirectOpenAi && (tail == "chatgpt-4o-latest" || tail.startsWith("o1-mini") || tail.startsWith("o1-preview")) -> false
                    provider == AiProvider.OPENROUTER -> meta?.supportsTools ?: true
                    else -> meta?.supportsTools ?: true
                },
                includeStreamOptions = true,
                includeTemperature = when {
                    isDirectOpenAi && (tail.startsWith("o1") || tail.startsWith("o3") || tail.startsWith("o4") || tail.startsWith("gpt-5")) -> false
                    else -> true
                },
                foldSystem = when {
                    isDirectOpenAi && (tail.startsWith("o1-mini") || tail.startsWith("o1-preview")) -> true
                    provider == AiProvider.OPENROUTER -> meta?.supportsSystemPrompt == false || lower.contains("gemma")
                    else -> false
                },
                stripImages = when {
                    tail.startsWith("o1-mini") || tail.startsWith("gpt-3.5") || tail == "gpt-4" || tail.startsWith("gpt-4-0") -> true
                    else -> false
                },
                useResponsesApi = isDirectOpenAi && ProviderApiClient.isResponsesOnlyOpenAiModel(candidate),
            )
            var cap = if (!allowToolsThisCall) baseline.copy(useNativeTools = false) else baseline

            var adaptAttempt = 0
            while (true) {
                coroutineContext.ensureActive()
                adaptAttempt++
                turnText.clear()
                accumulatedVisibleText.clear()
                accumulatedVisibleText.append(textBeforeTurn)

                val onConnected = {
                    activeModel = candidate
                    learnedCapabilities[lower] = cap
                    addOrUpdateStep(
                        AgentStep(
                            id = thinkStepId,
                            title = "Streaming from $candidate",
                            detail = "${thinkingMode.badge} • ${if (cap.useResponsesApi) "Responses API" else "Connected"}...",
                            status = AgentStepStatus.RUNNING,
                        ),
                    )
                }
                val onReasoning = {
                    addOrUpdateStep(
                        AgentStep(
                            id = thinkStepId,
                            title = "Reasoning ($candidate)",
                            detail = "${thinkingMode.badge} • Thinking...",
                            status = AgentStepStatus.RUNNING,
                        ),
                    )
                }
                val onDelta: (String) -> Unit = { delta ->
                    turnText.append(delta)
                    accumulatedVisibleText.clear()
                    accumulatedVisibleText.append(textBeforeTurn)
                    accumulatedVisibleText.append(turnText)
                    onStreamText(accumulatedVisibleText.toString(), candidate)
                }

                try {
                    val outcome = when {
                        provider == AiProvider.OPENROUTER -> ProviderApiClient.streamOpenRouter(
                            apiKey = apiKey,
                            modelId = candidate,
                            messages = openAiMessages,
                            thinkingMode = thinkingMode,
                            includeTools = cap.useNativeTools,
                            includeGitHubTools = includeGitHubTools,
                            allowTextToolCalls = allowTextToolFallback,
                            includeStreamOptions = cap.includeStreamOptions,
                            includeTemperature = cap.includeTemperature,
                            temperature = temperature,
                            maxTokens = cap.maxTokens ?: 8192,
                            useStreaming = cap.useStreaming,
                            allowOpenRouterDataCollectionFallback = cap.allowDataCollectionFallback,
                            foldSystemIntoUser = cap.foldSystem,
                            stripImages = cap.stripImages,
                            onConnected = onConnected,
                            onReasoningDelta = onReasoning,
                            onTextDelta = onDelta,
                        )
                        cap.useResponsesApi -> ProviderApiClient.streamOpenAiResponses(
                            apiKey = apiKey,
                            modelId = candidate,
                            messages = openAiMessages,
                            thinkingMode = thinkingMode,
                            includeGitHubTools = includeGitHubTools,
                            includeTools = cap.useNativeTools,
                            allowTextToolCalls = allowTextToolFallback,
                            includeTemperature = cap.includeTemperature,
                            temperature = temperature,
                            maxOutputTokens = cap.maxTokens,
                            useStreaming = cap.useStreaming,
                            stripImages = cap.stripImages,
                            onConnected = onConnected,
                            onReasoningDelta = onReasoning,
                            onTextDelta = onDelta,
                        )
                        else -> ProviderApiClient.streamOpenAi(
                            apiKey = apiKey,
                            modelId = candidate,
                            messages = openAiMessages,
                            thinkingMode = thinkingMode,
                            includeGitHubTools = includeGitHubTools,
                            includeTools = cap.useNativeTools,
                            allowTextToolCalls = allowTextToolFallback,
                            includeStreamOptions = cap.includeStreamOptions,
                            includeTemperature = cap.includeTemperature,
                            foldSystemIntoUser = cap.foldSystem,
                            stripImages = cap.stripImages,
                            temperature = temperature,
                            maxTokens = cap.maxTokens,
                            useStreaming = cap.useStreaming,
                            onConnected = onConnected,
                            onReasoningDelta = onReasoning,
                            onTextDelta = onDelta,
                        )
                    }
                    learnedCapabilities[lower] = cap
                    return outcome
                } catch (e: GeminiApiException) {
                    if (adaptAttempt < 8) {
                        val nextCap = nextOpenRouterAdaptation(
                            current = cap,
                            httpCode = e.httpCode,
                            errorMessage = e.message.orEmpty(),
                            hasImages = imageAttachments.isNotEmpty(),
                            isDirectOpenAi = isDirectOpenAi,
                        )
                        if (nextCap != null) {
                            cap = nextCap
                            learnedCapabilities[lower] = cap
                            val adaptDetail = when {
                                !nextCap.useStreaming -> "Switching $candidate to standard JSON mode (stream=false)..."
                                nextCap.maxTokens != null && nextCap.maxTokens != baseline.maxTokens -> "Reducing max_tokens to ${nextCap.maxTokens} for $candidate..."
                                nextCap.useResponsesApi && !baseline.useResponsesApi -> "Switching $candidate to OpenAI Responses API (/v1/responses)..."
                                !nextCap.useNativeTools && baseline.useNativeTools -> "Switching $candidate to text-mode tool calls..."
                                nextCap.allowDataCollectionFallback -> "Retrying $candidate with OpenRouter provider routing..."
                                nextCap.foldSystem -> "Folding system prompt for $candidate..."
                                !nextCap.includeStreamOptions -> "Retrying $candidate without stream_options..."
                                !nextCap.includeTemperature -> "Retrying $candidate with default sampling..."
                                else -> "Adapting request parameters for $candidate..."
                            }
                            addOrUpdateStep(
                                AgentStep(
                                    id = thinkStepId,
                                    title = "Adapting $candidate (${e.httpCode})",
                                    detail = adaptDetail,
                                    status = AgentStepStatus.RUNNING,
                                ),
                            )
                            continue
                        }
                    }
                    throw e
                } catch (e: SocketTimeoutException) {
                    throw e
                } catch (e: IOException) {
                    throw GeminiApiException(502, e.message ?: "Upstream stream connection dropped")
                }
            }
        }

        var totalTokens = 0L
        var completed = false

        for (turn in 0 until MAX_AGENT_TURNS) {
            coroutineContext.ensureActive()
            val turnStart = System.currentTimeMillis()
            val thinkStepId = "think_$turn"
            addOrUpdateStep(
                AgentStep(
                    id = thinkStepId,
                    title = if (turn == 0) "Connecting to ${provider.shortName} ($activeModel)" else "Analyzing tool output ($activeModel)",
                    detail = "${thinkingMode.badge} • Waiting for ${provider.displayName}...",
                    status = AgentStepStatus.RUNNING,
                ),
            )

            val turnPrefixLen = accumulatedVisibleText.length
            if (accumulatedVisibleText.isNotEmpty() && !accumulatedVisibleText.endsWith("\n")) {
                accumulatedVisibleText.append("\n\n")
            }
            val textBeforeTurn = accumulatedVisibleText.toString()
            val turnText = StringBuilder()

            val (usedModel, outcome) = withTimeout(TURN_TIMEOUT_MS) {
                callExternalWithRetryAndFailover(
                    provider = provider,
                    primaryModelId = activeModel,
                    liveModels = liveModelOptions,
                    onModelAutoCorrected = onModelAutoCorrected,
                    onStatusUpdate = { title, detail ->
                        addOrUpdateStep(AgentStep(id = thinkStepId, title = title, detail = detail, status = AgentStepStatus.RUNNING))
                    },
                ) { candidate ->
                    invokeOpenAiCompatibleCandidate(
                        candidate = candidate,
                        allowToolsThisCall = true,
                        allowTextToolFallback = true,
                        thinkStepId = thinkStepId,
                        textBeforeTurn = textBeforeTurn,
                        turnText = turnText,
                    )
                }
            }

            activeModel = usedModel
            accumulatedVisibleText.clear()
            accumulatedVisibleText.append(textBeforeTurn)
            if (outcome.text.isNotBlank()) {
                accumulatedVisibleText.append(outcome.text)
                onStreamText(accumulatedVisibleText.toString(), activeModel)
            } else {
                accumulatedVisibleText.setLength(turnPrefixLen)
                onStreamText(accumulatedVisibleText.toString(), activeModel)
            }
            totalTokens += outcome.usage.totalTokens
            onUsage(activeModel, outcome.usage, outcome.openAiToolCalls.size)

            if (outcome.openAiToolCalls.isEmpty()) {
                addOrUpdateStep(
                    AgentStep(
                        id = thinkStepId,
                        title = "Responded via $activeModel",
                        detail = "${outcome.usage.totalTokens} tokens",
                        status = AgentStepStatus.COMPLETED,
                        durationMs = System.currentTimeMillis() - turnStart,
                    ),
                )
                val finalText = accumulatedVisibleText.toString().trim()
                    .ifBlank { outcome.text.trim() }
                    .ifBlank { buildCompletedStepsSummary(steps) }
                onFinalizeSuccess(finalText, steps.toList(), totalTokens, activeModel)
                completed = true
                break
            }

            addOrUpdateStep(
                AgentStep(
                    id = thinkStepId,
                    title = "Planned ${outcome.openAiToolCalls.size} action(s)",
                    detail = outcome.openAiToolCalls.joinToString(", ") { it.name },
                    status = AgentStepStatus.COMPLETED,
                    durationMs = System.currentTimeMillis() - turnStart,
                ),
            )

            val assistantMsg = JSONObject().put("role", "assistant")
            assistantMsg.put("content", outcome.text)
            val tcArray = JSONArray()
            outcome.openAiToolCalls.forEach { tc ->
                tcArray.put(
                    JSONObject()
                        .put("id", tc.id)
                        .put("type", "function")
                        .put("function", JSONObject().put("name", tc.name).put("arguments", tc.argsJson)),
                )
            }
            assistantMsg.put("tool_calls", tcArray)
            openAiMessages.put(assistantMsg)

            for ((idx, tc) in outcome.openAiToolCalls.withIndex()) {
                coroutineContext.ensureActive()
                val argsObj = runCatching { JSONObject(tc.argsJson) }.getOrElse { JSONObject() }
                val stepId = "tool_${turn}_$idx"
                val stepTitle = describeToolCall(tc.name, argsObj)
                val stepStart = System.currentTimeMillis()
                addOrUpdateStep(
                    AgentStep(id = stepId, title = stepTitle, detail = "Running...", status = AgentStepStatus.RUNNING, toolName = tc.name),
                )
                val result = executeToolCall(ParsedFunctionCall(tc.name, argsObj), toolExecutor)
                addOrUpdateStep(
                    AgentStep(
                        id = stepId,
                        title = stepTitle,
                        detail = result.stepDetail,
                        status = if (result.success) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED,
                        expandedOutput = result.detailedOutput,
                        durationMs = System.currentTimeMillis() - stepStart,
                        toolName = tc.name,
                    ),
                )
                openAiMessages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", tc.id)
                        .put("content", result.payload.toString()),
                )
            }
        }

        if (!completed) {
            if (accumulatedVisibleText.toString().isBlank()) {
                openAiMessages.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "Provide a concise Markdown summary of the changes completed and the latest build result, without calling any tools."),
                )
                val finalStepId = "think_summary"
                val textBeforeSummary = accumulatedVisibleText.toString()
                val summaryTurnText = StringBuilder()
                runCatching {
                    withTimeout(30_000L) {
                        invokeOpenAiCompatibleCandidate(
                            candidate = activeModel,
                            allowToolsThisCall = false,
                            allowTextToolFallback = false,
                            thinkStepId = finalStepId,
                            textBeforeTurn = textBeforeSummary,
                            turnText = summaryTurnText,
                        )
                    }
                }.onSuccess { finalOutcome ->
                    totalTokens += finalOutcome.usage.totalTokens
                    onUsage(activeModel, finalOutcome.usage, 0)
                    addOrUpdateStep(
                        AgentStep(
                            id = finalStepId,
                            title = "Summarized via $activeModel",
                            detail = "${finalOutcome.usage.totalTokens} tokens",
                            status = AgentStepStatus.COMPLETED,
                        ),
                    )
                    if (accumulatedVisibleText.isBlank() && finalOutcome.text.isNotBlank()) {
                        accumulatedVisibleText.append(finalOutcome.text)
                    }
                }
            }
            val summary = accumulatedVisibleText.toString().trim()
                .ifBlank { buildCompletedStepsSummary(steps) }
            onFinalizeSuccess(summary, steps.toList(), totalTokens, activeModel)
        }
    }

    private suspend fun runClaudeLoop(
        initialModel: String,
        apiKey: String,
        thinkingMode: AiThinkingMode,
        agentRole: AgentRole = AgentRole.GENERAL,
        customSystemInstruction: String = "",
        temperature: Float = 0.3f,
        includeGitHubTools: Boolean = true,
        liveModelOptions: List<AiModelOption> = emptyList(),
        projectLanguage: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
        imageAttachments: List<AiAttachment>,
        priorHistory: List<ChatMessage>,
        promptWithContext: String,
        steps: MutableList<AgentStep>,
        accumulatedVisibleText: StringBuilder,
        addOrUpdateStep: (AgentStep) -> Unit,
        onModelAutoCorrected: (failedModel: String, workingModel: String) -> Unit = { _, _ -> },
        onStreamText: (String, String) -> Unit,
        onFinalizeSuccess: (String, List<AgentStep>, Long, String) -> Unit,
        toolExecutor: ProjectToolExecutor,
        onUsage: (String, TokenUsageDelta, Int) -> Unit,
    ) {
        var activeModel = if (liveModelOptions.isNotEmpty()) {
            val resolved = AiModelsCatalog.resolveClaudeModelId(initialModel, liveModelOptions)
            if (!resolved.equals(initialModel, ignoreCase = true)) {
                onModelAutoCorrected(initialModel, resolved)
            }
            resolved
        } else {
            initialModel
        }
        val systemText = buildSystemInstruction(
            modelId = activeModel,
            projectLanguage = projectLanguage,
            javaVersion = javaVersion,
            minSdkVersion = minSdkVersion,
            agentRole = agentRole,
            customSystemInstruction = customSystemInstruction,
        )
        val claudeMessages = JSONArray().apply {
            priorHistory.forEach { msg ->
                put(JSONObject().put("role", if (msg.isUser) "user" else "assistant").put("content", msg.text))
            }
            if (imageAttachments.isEmpty()) {
                put(JSONObject().put("role", "user").put("content", promptWithContext))
            } else {
                val contentBlocks = JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", promptWithContext))
                    imageAttachments.forEach { img ->
                        put(
                            JSONObject()
                                .put("type", "image")
                                .put(
                                    "source",
                                    JSONObject()
                                        .put("type", "base64")
                                        .put("media_type", img.mimeType)
                                        .put("data", img.base64Data),
                                ),
                        )
                    }
                }
                put(JSONObject().put("role", "user").put("content", contentBlocks))
            }
        }

        val learnedClaudeCaps = mutableMapOf<String, ClaudeModelCapabilities>()

        suspend fun invokeClaudeCandidate(
            candidate: String,
            thinkStepId: String,
            textBeforeTurn: String,
            turnText: StringBuilder,
        ): ExternalTurnOutcome {
            val lower = candidate.lowercase()
            val defaultMax = if (lower.startsWith("claude-3-opus") || lower.startsWith("claude-3-sonnet") || lower.startsWith("claude-3-haiku") || lower.startsWith("claude-2")) {
                4096
            } else {
                8192
            }
            var cap = learnedClaudeCaps[lower] ?: ClaudeModelCapabilities(maxTokens = defaultMax)
            var adaptAttempt = 0

            while (true) {
                coroutineContext.ensureActive()
                adaptAttempt++
                turnText.clear()
                accumulatedVisibleText.clear()
                accumulatedVisibleText.append(textBeforeTurn)

                try {
                    val outcome = ProviderApiClient.streamClaude(
                        apiKey = apiKey,
                        modelId = candidate,
                        systemInstruction = systemText,
                        messages = claudeMessages,
                        thinkingMode = thinkingMode,
                        maxTokens = cap.maxTokens,
                        includeTools = cap.useNativeTools,
                        allowTextToolCalls = true,
                        includeGitHubTools = includeGitHubTools,
                        includeSystem = cap.includeSystem,
                        includeTemperature = cap.includeTemperature,
                        temperature = temperature,
                        useStreaming = cap.useStreaming,
                        stripImages = cap.stripImages,
                        onConnected = {
                            activeModel = candidate
                            learnedClaudeCaps[lower] = cap
                            addOrUpdateStep(
                                AgentStep(
                                    id = thinkStepId,
                                    title = "Streaming from $candidate",
                                    detail = "${thinkingMode.badge} • Connected...",
                                    status = AgentStepStatus.RUNNING,
                                ),
                            )
                        },
                        onTextDelta = { delta ->
                            turnText.append(delta)
                            accumulatedVisibleText.clear()
                            accumulatedVisibleText.append(textBeforeTurn)
                            accumulatedVisibleText.append(turnText)
                            onStreamText(accumulatedVisibleText.toString(), candidate)
                        },
                    )
                    learnedClaudeCaps[lower] = cap
                    return outcome
                } catch (e: GeminiApiException) {
                    if (adaptAttempt < 7) {
                        val nextCap = nextClaudeAdaptation(
                            current = cap,
                            httpCode = e.httpCode,
                            errorMessage = e.message.orEmpty(),
                            hasImages = imageAttachments.isNotEmpty(),
                        )
                        if (nextCap != null) {
                            cap = nextCap
                            learnedClaudeCaps[lower] = cap
                            addOrUpdateStep(
                                AgentStep(
                                    id = thinkStepId,
                                    title = "Adapting $candidate (${e.httpCode})",
                                    detail = when {
                                        !nextCap.useStreaming -> "Switching $candidate to JSON mode (stream=false)..."
                                        !nextCap.useNativeTools -> "Switching $candidate to text-mode tool calls..."
                                        !nextCap.includeSystem -> "Folding system prompt for $candidate..."
                                        nextCap.maxTokens < 8192 -> "Adjusting max_tokens to ${nextCap.maxTokens}..."
                                        else -> "Adapting Claude request parameters..."
                                    },
                                    status = AgentStepStatus.RUNNING,
                                ),
                            )
                            continue
                        }
                    }
                    throw e
                } catch (e: SocketTimeoutException) {
                    throw e
                } catch (e: IOException) {
                    throw GeminiApiException(502, e.message ?: "Anthropic stream connection dropped")
                }
            }
        }

        var totalTokens = 0L
        var completed = false

        for (turn in 0 until MAX_AGENT_TURNS) {
            coroutineContext.ensureActive()
            val turnStart = System.currentTimeMillis()
            val thinkStepId = "think_$turn"
            addOrUpdateStep(
                AgentStep(
                    id = thinkStepId,
                    title = if (turn == 0) "Connecting to Claude ($activeModel)" else "Analyzing tool results ($activeModel)",
                    detail = "${thinkingMode.badge} • Waiting for Anthropic...",
                    status = AgentStepStatus.RUNNING,
                ),
            )

            val turnPrefixLen = accumulatedVisibleText.length
            if (accumulatedVisibleText.isNotEmpty() && !accumulatedVisibleText.endsWith("\n")) {
                accumulatedVisibleText.append("\n\n")
            }
            val textBeforeTurn = accumulatedVisibleText.toString()
            val turnText = StringBuilder()

            val (usedModel, outcome) = withTimeout(TURN_TIMEOUT_MS) {
                callExternalWithRetryAndFailover(
                    provider = AiProvider.CLAUDE,
                    primaryModelId = activeModel,
                    liveModels = liveModelOptions,
                    onModelAutoCorrected = onModelAutoCorrected,
                    onStatusUpdate = { title, detail ->
                        addOrUpdateStep(AgentStep(id = thinkStepId, title = title, detail = detail, status = AgentStepStatus.RUNNING))
                    },
                ) { candidate ->
                    invokeClaudeCandidate(
                        candidate = candidate,
                        thinkStepId = thinkStepId,
                        textBeforeTurn = textBeforeTurn,
                        turnText = turnText,
                    )
                }
            }

            activeModel = usedModel
            accumulatedVisibleText.clear()
            accumulatedVisibleText.append(textBeforeTurn)
            if (outcome.text.isNotBlank()) {
                accumulatedVisibleText.append(outcome.text)
                onStreamText(accumulatedVisibleText.toString(), activeModel)
            } else if (turnText.isEmpty()) {
                accumulatedVisibleText.setLength(turnPrefixLen)
            }
            totalTokens += outcome.usage.totalTokens
            onUsage(activeModel, outcome.usage, outcome.claudeToolUses.size)

            if (outcome.claudeToolUses.isEmpty()) {
                addOrUpdateStep(
                    AgentStep(
                        id = thinkStepId,
                        title = "Responded via $activeModel",
                        detail = "${outcome.usage.totalTokens} tokens",
                        status = AgentStepStatus.COMPLETED,
                        durationMs = System.currentTimeMillis() - turnStart,
                    ),
                )
                val finalText = accumulatedVisibleText.toString().trim().ifBlank { "All requested actions finished." }
                onFinalizeSuccess(finalText, steps.toList(), totalTokens, activeModel)
                completed = true
                break
            }

            addOrUpdateStep(
                AgentStep(
                    id = thinkStepId,
                    title = "Planned ${outcome.claudeToolUses.size} action(s)",
                    detail = outcome.claudeToolUses.joinToString(", ") { it.name },
                    status = AgentStepStatus.COMPLETED,
                    durationMs = System.currentTimeMillis() - turnStart,
                ),
            )

            claudeMessages.put(JSONObject().put("role", "assistant").put("content", outcome.claudeAssistantBlocks))
            val resultBlocks = JSONArray()
            for ((idx, tu) in outcome.claudeToolUses.withIndex()) {
                coroutineContext.ensureActive()
                val stepId = "tool_${turn}_$idx"
                val stepTitle = describeToolCall(tu.name, tu.input)
                val stepStart = System.currentTimeMillis()
                addOrUpdateStep(
                    AgentStep(id = stepId, title = stepTitle, detail = "Running...", status = AgentStepStatus.RUNNING, toolName = tu.name),
                )
                val result = executeToolCall(ParsedFunctionCall(tu.name, tu.input), toolExecutor)
                addOrUpdateStep(
                    AgentStep(
                        id = stepId,
                        title = stepTitle,
                        detail = result.stepDetail,
                        status = if (result.success) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED,
                        expandedOutput = result.detailedOutput,
                        durationMs = System.currentTimeMillis() - stepStart,
                        toolName = tu.name,
                    ),
                )
                resultBlocks.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", tu.id)
                        .put("content", result.payload.toString())
                        .put("is_error", !result.success),
                )
            }
            claudeMessages.put(JSONObject().put("role", "user").put("content", resultBlocks))
        }

        if (!completed) {
            val summary = accumulatedVisibleText.toString().trim()
                .ifBlank { buildCompletedStepsSummary(steps) }
            onFinalizeSuccess(summary, steps.toList(), totalTokens, activeModel)
        }
    }

    private fun buildCompletedStepsSummary(steps: List<AgentStep>): String {
        val toolSteps = steps.filter { it.toolName != null }
        if (toolSteps.isEmpty()) {
            return "Finished processing."
        }
        val succeeded = toolSteps.count { it.status == AgentStepStatus.COMPLETED }
        val latestBuild = toolSteps.lastOrNull { it.toolName == "build_apk" || it.toolName == "build_aab" }
        val edited = toolSteps.filter { it.toolName == "write_file" || it.toolName == "edit_file" }
            .map { it.title }
            .distinct()
            .takeLast(6)
        return buildString {
            appendLine("### Summary")
            appendLine("Completed $succeeded of ${toolSteps.size} tool action(s).")
            if (edited.isNotEmpty()) {
                appendLine()
                appendLine("### Changes Made")
                edited.forEach { appendLine("- $it") }
            }
            if (latestBuild != null) {
                appendLine()
                appendLine("### Build Status")
                appendLine("- **${latestBuild.title}:** ${latestBuild.detail}")
            }
        }.trim()
    }

    private suspend fun callExternalWithRetryAndFailover(
        provider: AiProvider,
        primaryModelId: String,
        liveModels: List<AiModelOption> = emptyList(),
        onModelAutoCorrected: (failedModel: String, workingModel: String) -> Unit = { _, _ -> },
        onStatusUpdate: (String, String) -> Unit,
        invoke: suspend (String) -> ExternalTurnOutcome,
    ): Pair<String, ExternalTurnOutcome> {
        val candidates = AiModelsCatalog.fallbackChain(provider, primaryModelId, liveModels)
        var primaryError: Throwable? = null
        var primaryGot404 = false
        var lastError: Throwable? = null

        fun recordFailure(candidate: String, err: Throwable) {
            if (primaryError == null && candidate == candidates.firstOrNull()) {
                primaryError = err
                if (err is GeminiApiException && err.httpCode == 404) {
                    primaryGot404 = true
                }
            }
            lastError = err
        }

        for ((index, candidate) in candidates.withIndex()) {
            val hasNextCandidate = index < candidates.lastIndex
            var attemptsOnCandidate = 0
            while (attemptsOnCandidate < 2) {
                coroutineContext.ensureActive()
                attemptsOnCandidate++
                try {
                    val outcome = invoke(candidate)
                    if (primaryGot404 && !candidate.equals(primaryModelId, ignoreCase = true)) {
                        onModelAutoCorrected(primaryModelId, candidate)
                    }
                    return candidate to outcome
                } catch (e: GeminiApiException) {
                    recordFailure(candidate, e)
                    if (e.httpCode == 401 || e.httpCode == 403) {
                        throw e
                    }
                    val canRetrySame = isTransientHttpStatus(e.httpCode) && attemptsOnCandidate == 1
                    if (canRetrySame) {
                        val waitMs = if (e.httpCode == 429) (e.retryAfterMs ?: 1000L) else 450L
                        onStatusUpdate(
                            "Retrying $candidate",
                            "HTTP ${e.httpCode} — quick retry before failover...",
                        )
                        delay(waitMs)
                        continue
                    }
                    val canFailover = shouldFailoverHttpStatus(e.httpCode) ||
                        e.httpCode in listOf(400, 404, 422) ||
                        (provider == AiProvider.OPENROUTER && e.httpCode == 402)
                    if (canFailover && hasNextCandidate) {
                        val nextModel = candidates[index + 1]
                        onStatusUpdate(
                            "Switching model (${e.httpCode})",
                            "$candidate returned ${e.httpCode} — trying $nextModel...",
                        )
                        break
                    }
                    throw e
                } catch (e: SocketTimeoutException) {
                    recordFailure(candidate, e)
                    if (hasNextCandidate) {
                        val nextModel = candidates[index + 1]
                        onStatusUpdate(
                            "Timeout on $candidate",
                            "Timed out — switching to $nextModel...",
                        )
                        break
                    }
                    throw e
                }
            }
        }
        val firstErr = primaryError
        val finalErr = lastError
        if (firstErr != null && finalErr != null && firstErr !== finalErr) {
            val code = (firstErr as? GeminiApiException)?.httpCode
                ?: (finalErr as? GeminiApiException)?.httpCode
                ?: 503
            val combinedMsg = "$primaryModelId failed (${firstErr.message}); fallback also failed (${finalErr.message})"
            throw GeminiApiException(code, combinedMsg)
        }
        throw firstErr ?: finalErr ?: GeminiApiException(503, "All ${provider.shortName} fallback models are currently unavailable.")
    }

    private suspend fun callGeminiWithRetryAndFailover(
        primaryModelId: String,
        apiKey: String,
        thinkingMode: AiThinkingMode,
        agentRole: AgentRole = AgentRole.GENERAL,
        customSystemInstruction: String = "",
        temperature: Float = 0.3f,
        projectLanguage: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
        includeGitHubTools: Boolean,
        verifiedLiveModelIds: Set<String>,
        contents: JSONArray,
        onModelAutoCorrected: (failedModel: String, workingModel: String) -> Unit,
        onConnected: (String) -> Unit,
        onStatusUpdate: (String, String) -> Unit,
        onTextDelta: (String) -> Unit,
    ): TurnStreamOutcome {
        val candidates = buildOrderedGeminiCandidates(primaryModelId, verifiedLiveModelIds).toMutableList()
        val attemptedModels = linkedSetOf<String>()
        var primaryError: Throwable? = null
        var primaryGot404 = false
        var lastError: Throwable? = null
        var discoveredLiveModels = verifiedLiveModelIds.isNotEmpty()

        fun recordError(model: String, error: Throwable) {
            if (primaryError == null && model == candidates.firstOrNull()) {
                primaryError = error
                if (error is GeminiApiException && error.httpCode == 404) {
                    primaryGot404 = true
                }
            }
            lastError = error
        }

        var index = 0
        while (index < candidates.size) {
            val candidate = candidates[index]
            index++
            if (!attemptedModels.add(candidate)) continue

            var hasNextCandidate = index < candidates.size
            var includeThinkingConfig = true
            var stripThoughts = false
            var attemptsOnCandidate = 0

            while (attemptsOnCandidate < 3) {
                coroutineContext.ensureActive()
                attemptsOnCandidate++
                val payloadContents = if (stripThoughts) stripThoughtPartsFromContents(contents) else contents
                val requestBody = buildRequestPayload(
                    contents = payloadContents,
                    modelId = candidate,
                    thinkingMode = thinkingMode,
                    includeThinkingConfig = includeThinkingConfig,
                    includeGitHubTools = includeGitHubTools,
                    projectLanguage = projectLanguage,
                    javaVersion = javaVersion,
                    minSdkVersion = minSdkVersion,
                    agentRole = agentRole,
                    customSystemInstruction = customSystemInstruction,
                    temperature = temperature,
                )
                try {
                    val outcome = withTimeout(ATTEMPT_TIMEOUT_MS) {
                        callModelWithSseFallback(
                            modelId = candidate,
                            apiKey = apiKey,
                            requestBody = requestBody,
                            onConnected = { onConnected(candidate) },
                            onFallback = {
                                onStatusUpdate(
                                    "Switching transport ($candidate)",
                                    "Trying direct generateContent response...",
                                )
                            },
                            onTextDelta = onTextDelta,
                        )
                    }
                    if (primaryGot404 && candidate != primaryModelId) {
                        onModelAutoCorrected(primaryModelId, candidate)
                    }
                    return outcome.copy(usedModel = candidate)
                } catch (e: GeminiApiException) {
                    recordError(candidate, e)
                    if (e.httpCode == 401 || e.httpCode == 403) {
                        throw e
                    }
                    if (e.httpCode == 400) {
                        if (includeThinkingConfig && buildThinkingConfigJson(candidate, thinkingMode) != null) {
                            includeThinkingConfig = false
                            continue
                        }
                        if (!stripThoughts) {
                            stripThoughts = true
                            continue
                        }
                        if (hasNextCandidate) {
                            val nextModel = candidates[index]
                            onStatusUpdate(
                                "Adjusting model ($candidate)",
                                "Switching to $nextModel...",
                            )
                            break
                        }
                        throw e
                    }

                    // On 404 (or exhausted candidates), immediately query /v1beta/models so we only try verified live models
                    if ((!discoveredLiveModels && e.httpCode == 404) ||
                        (!hasNextCandidate && !discoveredLiveModels && shouldFailoverHttpStatus(e.httpCode))
                    ) {
                        discoveredLiveModels = true
                        appendLiveGeminiCandidates(
                            apiKey = apiKey,
                            candidates = candidates,
                            insertAtIndex = index,
                            attemptedModels = attemptedModels,
                            onStatusUpdate = onStatusUpdate,
                        )
                        hasNextCandidate = index < candidates.size
                    }

                    if (shouldFailoverHttpStatus(e.httpCode) && hasNextCandidate) {
                        val nextModel = candidates[index]
                        val statusLabel = when (e.httpCode) {
                            404 -> "Model unavailable (404)"
                            429 -> "Rate limit (429) on $candidate"
                            else -> "High demand (${e.httpCode}) on $candidate"
                        }
                        onStatusUpdate(
                            statusLabel,
                            "Switching to $nextModel...",
                        )
                        if (isTransientHttpStatus(e.httpCode)) {
                            delay((e.retryAfterMs ?: 250L).coerceIn(200L, 1200L))
                        }
                        break
                    }

                    if (isTransientHttpStatus(e.httpCode) && attemptsOnCandidate <= 2) {
                        val waitMs = (e.retryAfterMs ?: 1800L).coerceIn(1000L, 4500L)
                        onStatusUpdate(
                            "High demand (${e.httpCode}) on $candidate",
                            "Retrying $candidate in ${waitMs / 1000.0}s...",
                        )
                        delay(waitMs)
                        continue
                    }

                    throw e
                } catch (e: IOException) {
                    val timeoutErr = if (e is SocketTimeoutException) e else SocketTimeoutException(e.message ?: "Network timeout")
                    recordError(candidate, timeoutErr)
                    if (!hasNextCandidate && !discoveredLiveModels) {
                        discoveredLiveModels = true
                        appendLiveGeminiCandidates(
                            apiKey = apiKey,
                            candidates = candidates,
                            insertAtIndex = index,
                            attemptedModels = attemptedModels,
                            onStatusUpdate = onStatusUpdate,
                        )
                        hasNextCandidate = index < candidates.size
                    }
                    if (hasNextCandidate) {
                        val nextModel = candidates[index]
                        onStatusUpdate(
                            "$candidate timed out",
                            "Switching to $nextModel...",
                        )
                        break
                    }
                    throw timeoutErr
                } catch (e: TimeoutCancellationException) {
                    coroutineContext.ensureActive()
                    val timeoutErr = SocketTimeoutException("$candidate timed out after ${ATTEMPT_TIMEOUT_MS / 1000}s")
                    recordError(candidate, timeoutErr)
                    if (!hasNextCandidate && !discoveredLiveModels) {
                        discoveredLiveModels = true
                        appendLiveGeminiCandidates(
                            apiKey = apiKey,
                            candidates = candidates,
                            insertAtIndex = index,
                            attemptedModels = attemptedModels,
                            onStatusUpdate = onStatusUpdate,
                        )
                        hasNextCandidate = index < candidates.size
                    }
                    if (hasNextCandidate) {
                        val nextModel = candidates[index]
                        onStatusUpdate(
                            "$candidate unresponsive",
                            "Auto-switching to $nextModel...",
                        )
                        break
                    }
                    throw timeoutErr
                }
            }
        }
        val firstErr = primaryError
        val finalErr = lastError
        if (firstErr != null && finalErr != null && firstErr !== finalErr) {
            val combinedMsg = "Selected model '$primaryModelId' failed (${firstErr.message}); fallback also failed (${finalErr.message}). Check API key & quota in Key settings."
            val code = (finalErr as? GeminiApiException)?.httpCode ?: (firstErr as? GeminiApiException)?.httpCode ?: 503
            throw GeminiApiException(code, combinedMsg)
        }
        throw finalErr ?: firstErr ?: GeminiApiException(503, "All Gemini models are unavailable or busy right now.")
    }

    private fun buildOrderedGeminiCandidates(
        primaryModelId: String,
        verifiedLiveModelIds: Set<String>,
    ): List<String> {
        val baseChain = AiModelsCatalog.fallbackChain(
            AiProvider.GEMINI,
            AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, primaryModelId),
        )
        val liveCoding = verifiedLiveModelIds.filter { AiModelsCatalog.isCodingCapableGeminiModel(it) }
        if (liveCoding.isEmpty()) return baseChain

        val ordered = linkedSetOf<String>()
        ordered.add(baseChain.first())
        val verifiedSet = liveCoding.toSet()
        // Prioritize proven models verified present on this API key
        baseChain.drop(1).filter { it in verifiedSet }.forEach { ordered.add(it) }
        liveCoding
            .sortedWith(
                compareBy<String>(
                    { !it.contains("gemini-4", ignoreCase = true) },
                    { !it.contains("gemini-3.8", ignoreCase = true) },
                    { !it.contains("gemini-3", ignoreCase = true) },
                    { !it.contains("flash", ignoreCase = true) },
                    { it.contains("preview", ignoreCase = true) || it.contains("exp", ignoreCase = true) },
                ).thenBy { it },
            )
            .take(4)
            .forEach { ordered.add(it) }
        return ordered.toList()
    }

    private suspend fun appendLiveGeminiCandidates(
        apiKey: String,
        candidates: MutableList<String>,
        insertAtIndex: Int,
        attemptedModels: Set<String>,
        onStatusUpdate: (String, String) -> Unit,
    ) {
        onStatusUpdate("Checking live Gemini models", "Finding verified models for your API key...")
        val live = AiModelsCatalog.fetchLiveModels(AiProvider.GEMINI, apiKey).getOrNull().orEmpty()
        val liveIds = live.map { it.id }.toSet()
        val ranked = live
            .map { it.id }
            .filter { id ->
                id !in attemptedModels &&
                    AiModelsCatalog.isCodingCapableGeminiModel(id)
            }
            .sortedWith(
                compareBy<String>(
                    { !it.contains("2.5-flash", ignoreCase = true) },
                    { !it.contains("2.0-flash", ignoreCase = true) },
                    { !it.contains("flash", ignoreCase = true) },
                    { it.contains("preview", ignoreCase = true) || it.contains("exp", ignoreCase = true) },
                ).thenBy { it },
            )

        if (liveIds.isNotEmpty() && insertAtIndex < candidates.size) {
            val remaining = candidates.subList(insertAtIndex, candidates.size)
            // Keep verified models ahead of unverified speculative names
            val verifiedRemaining = remaining.filter { it in liveIds }
            val extraVerified = ranked.filter { it !in candidates && it !in verifiedRemaining }.take(4)
            val reordered = (verifiedRemaining + extraVerified).distinct()
            remaining.clear()
            remaining.addAll(reordered)
        } else {
            val extra = ranked.filter { it !in candidates }.take(4)
            candidates.addAll(insertAtIndex.coerceAtMost(candidates.size), extra)
        }
    }

    private data class RescueResult(
        val text: String,
        val outcome: TurnStreamOutcome,
    )

    private suspend fun tryCompactNonToolRescue(
        apiKey: String,
        verifiedLiveModelIds: Set<String>,
        projectLanguage: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
        promptWithContext: String,
        imageAttachments: List<AiAttachment>,
        afterRateLimit: Boolean = false,
        onStatusUpdate: (String, String) -> Unit,
    ): RescueResult? {
        if (afterRateLimit) {
            onStatusUpdate(
                "Cooling down briefly",
                "Preparing direct lightweight Gemini request...",
            )
            delay(1200L)
        }
        val compactPrompt = buildString {
            appendLine("Direct code rescue mode (${projectLanguage.displayName}, Java ${javaVersion.badge}, API $minSdkVersion).")
            appendLine("Provide complete runnable code blocks labeled `// file: relative/path.ext` cleanly.")
            appendLine()
            append(promptWithContext.take(6_500))
        }
        val compactContents = buildInitialContents(
            history = emptyList(),
            latestPromptWithContext = compactPrompt,
            imageAttachments = imageAttachments,
        )
        val rescueBody = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "You are an Android coding assistant inside DroidForge Studio. Answer directly with concise Markdown and complete runnable code blocks marked `// file: <path>`.",
                        ),
                    ),
                ),
            )
            put("contents", compactContents)
            put("generationConfig", JSONObject().put("temperature", 0.3))
        }.toString()

        val liveFlash = verifiedLiveModelIds.filter {
            AiModelsCatalog.isCodingCapableGeminiModel(it) && it.contains("flash", ignoreCase = true)
        }
        // Last-resort rescue chain. Built from the shared live pool so it can never be
        // populated with models Google has retired.
        val rescueModels = (liveFlash + AiModelsCatalog.geminiFailoverPool)
            .filter { it.lowercase() !in AiModelsCatalog.retiredGeminiModelRemap }
            .distinct()

        for ((idx, candidate) in rescueModels.withIndex()) {
            coroutineContext.ensureActive()
            if (idx > 0) delay(750L)
            onStatusUpdate(
                "Direct chat fallback ($candidate)",
                "Switching to lightweight direct Gemini request...",
            )
            val captured = StringBuilder()
            val outcome = try {
                withTimeout(UNARY_FALLBACK_READ_TIMEOUT_MS + 10_000L) {
                    generateContentUnary(candidate, apiKey, rescueBody) { chunk ->
                        captured.append(chunk)
                    }
                }
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                null
            } catch (e: Exception) {
                if (e is GeminiApiException && e.httpCode in listOf(401, 403)) throw e
                null
            } ?: continue
            if (captured.isNotBlank()) {
                return RescueResult(
                    text = captured.toString(),
                    outcome = outcome.copy(functionCalls = emptyList(), usedModel = candidate),
                )
            }
        }
        return null
    }

    private suspend fun callModelWithSseFallback(
        modelId: String,
        apiKey: String,
        requestBody: String,
        onConnected: () -> Unit,
        onFallback: () -> Unit,
        onTextDelta: (String) -> Unit,
    ): TurnStreamOutcome {
        var receivedAnyPayload = false
        val sseOutcome = try {
            streamGenerateContent(
                modelId = modelId,
                apiKey = apiKey,
                requestBody = requestBody,
                onConnected = onConnected,
                onPayloadChunk = { receivedAnyPayload = true },
                onTextDelta = { delta ->
                    receivedAnyPayload = true
                    onTextDelta(delta)
                },
            )
        } catch (e: IOException) {
            if (receivedAnyPayload) throw e
            Log.w(TAG, "SSE failed before receiving payload for $modelId (${e.message}); trying unary generateContent", e)
            onFallback()
            return generateContentUnary(modelId, apiKey, requestBody, onTextDelta)
        }
        if (sseOutcome.modelParts.length() == 0 && sseOutcome.functionCalls.isEmpty()) {
            Log.w(TAG, "SSE returned 0 parts for $modelId; retrying unary generateContent")
            onFallback()
            return generateContentUnary(modelId, apiKey, requestBody, onTextDelta)
        }
        return sseOutcome
    }

    private suspend fun executeToolCall(
        call: ParsedFunctionCall,
        executor: ProjectToolExecutor,
    ): AgentToolResult {
        return try {
            when (call.name) {
                "list_files" -> executor.listFiles()
                "read_file" -> executor.readFile(call.args.optString("path"))
                "write_file" -> executor.writeFile(
                    path = call.args.optString("path"),
                    content = call.args.optString("content"),
                )
                "edit_file" -> executor.editFile(
                    path = call.args.optString("path"),
                    oldText = call.args.optString("old_text"),
                    newText = call.args.optString("new_text"),
                )
                "delete_file" -> executor.deleteFile(call.args.optString("path"))
                "search_files" -> executor.searchFiles(call.args.optString("query"))
                "build_apk" -> executor.buildApk(isRelease = call.args.optBoolean("release", false))
                "build_aab" -> executor.buildAab()
                "github_status" -> executor.githubStatus()
                "github_list_repos" -> executor.githubListRepos()
                "github_create_repo" -> executor.githubCreateRepo(
                    name = call.args.optString("name"),
                    description = call.args.optString("description"),
                    isPrivate = call.args.optBoolean("private", false),
                )
                "github_link_repo" -> executor.githubLinkRepo(
                    owner = call.args.optString("owner"),
                    repo = call.args.optString("repo"),
                    branch = call.args.optString("branch", "main"),
                )
                "github_list_branches" -> executor.githubListBranches(
                    owner = call.args.optString("owner"),
                    repo = call.args.optString("repo"),
                )
                "github_list_repo_files" -> executor.githubListRepoFiles(
                    owner = call.args.optString("owner"),
                    repo = call.args.optString("repo"),
                    branch = call.args.optString("branch"),
                )
                "github_read_file" -> executor.githubReadFile(
                    path = call.args.optString("path"),
                    owner = call.args.optString("owner"),
                    repo = call.args.optString("repo"),
                    branch = call.args.optString("branch"),
                )
                "github_upload_file" -> executor.githubUploadFile(
                    remotePath = call.args.optString("remote_path"),
                    localPath = call.args.optString("local_path"),
                    content = call.args.optString("content"),
                    commitMessage = call.args.optString("commit_message"),
                )
                "github_download_file" -> executor.githubDownloadFile(
                    remotePath = call.args.optString("remote_path"),
                    localPath = call.args.optString("local_path"),
                    owner = call.args.optString("owner"),
                    repo = call.args.optString("repo"),
                    branch = call.args.optString("branch"),
                )
                "github_pull_changes" -> executor.githubPullChanges()
                "github_push_changes" -> executor.githubPushChanges(
                    commitMessage = call.args.optString("commit_message"),
                )
                "github_trigger_cloud_build" -> executor.githubTriggerCloudBuild()
                "github_list_workflow_runs" -> executor.githubListWorkflowRuns()
                "github_get_workflow_logs" -> executor.githubGetWorkflowLogs(
                    runId = call.args.optLong("run_id", 0L),
                )
                "github_rerun_workflow" -> executor.githubRerunWorkflow(
                    runId = call.args.optLong("run_id", 0L),
                    failedOnly = call.args.optBoolean("failed_only", false),
                )
                else -> AgentToolResult(
                    success = false,
                    stepDetail = "Unknown tool '${call.name}'",
                    payload = JSONObject().put("error", "Unknown tool '${call.name}'"),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentToolResult(
                success = false,
                stepDetail = e.localizedMessage ?: "Tool execution failed",
                payload = JSONObject().put("error", e.localizedMessage ?: "Tool execution failed"),
            )
        }
    }

    private fun describeToolCall(name: String, args: JSONObject): String {
        val path = args.optString("path").ifBlank { args.optString("remote_path").ifBlank { "file" } }
        return when (name) {
            "list_files" -> "Listing project files"
            "read_file" -> "Reading $path"
            "write_file" -> "Writing $path"
            "edit_file" -> "Editing $path"
            "delete_file" -> "Deleting $path"
            "search_files" -> "Searching '${args.optString("query").take(28)}'"
            "build_apk" -> if (args.optBoolean("release", false)) "Building Release APK" else "Building Debug APK"
            "build_aab" -> "Building Signed App Bundle (.aab)"
            "github_status" -> "Checking GitHub repo & diff status"
            "github_list_repos" -> "Listing GitHub repositories"
            "github_create_repo" -> "Creating GitHub repository ${args.optString("name")}"
            "github_link_repo" -> "Linking GitHub repo ${args.optString("owner")}/${args.optString("repo")}"
            "github_list_branches" -> "Listing GitHub branches"
            "github_list_repo_files" -> "Browsing GitHub repo files"
            "github_read_file" -> "Reading GitHub file $path"
            "github_upload_file" -> "Uploading $path to GitHub"
            "github_download_file" -> "Downloading $path from GitHub"
            "github_pull_changes" -> "Pulling changes from GitHub"
            "github_push_changes" -> "Pushing changes to GitHub"
            "github_trigger_cloud_build" -> "Triggering GitHub Actions Cloud Build"
            "github_list_workflow_runs" -> "Fetching GitHub Actions runs"
            "github_get_workflow_logs" -> "Inspecting GitHub Actions logs"
            "github_rerun_workflow" -> "Rerunning GitHub Actions workflow"
            else -> "Running $name"
        }
    }

    private suspend fun streamGenerateContent(
        modelId: String,
        apiKey: String,
        requestBody: String,
        onConnected: () -> Unit,
        onPayloadChunk: () -> Unit = {},
        onTextDelta: (String) -> Unit,
    ): TurnStreamOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val endpoint = "$GEMINI_API_BASE/$modelId:streamGenerateContent?alt=sse"
        val bodyBytes = requestBody.toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            doOutput = true
            setFixedLengthStreamingMode(bodyBytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) Thread { runCatching { connection.disconnect() } }.start()
        }

        val assembledParts = mutableListOf<JSONObject>()
        val functionCalls = mutableListOf<ParsedFunctionCall>()
        var blockReason: String? = null
        var finishReason: String? = null
        var latestUsage = TokenUsageDelta()

        fun handlePayload(payload: String, status: Int) {
            val parsed = parseSsePayload(payload)
            if (parsed.errorMessage != null) {
                throw GeminiApiException(parsed.errorCode ?: status, parsed.errorMessage)
            }
            if (parsed.rawParts.isNotEmpty() || parsed.text.isNotEmpty() || !parsed.finishReason.isNullOrBlank()) {
                onPayloadChunk()
            }
            if (!parsed.blockReason.isNullOrBlank()) blockReason = parsed.blockReason
            if (!parsed.finishReason.isNullOrBlank()) finishReason = parsed.finishReason
            if (parsed.usage.totalTokens > 0L || parsed.usage.inputTokens > 0L) {
                latestUsage = parsed.usage
            }
            if (parsed.text.isNotEmpty()) {
                onTextDelta(parsed.text)
            }
            parsed.rawParts.forEach { rawPart ->
                appendRawPart(assembledParts, rawPart)
                val fc = rawPart.optJSONObject("functionCall")
                if (fc != null) {
                    val fnName = fc.optString("name")
                    if (fnName.isNotBlank()) {
                        functionCalls.add(ParsedFunctionCall(fnName, fc.optJSONObject("args") ?: JSONObject()))
                    }
                }
            }
        }

        try {
            connection.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errStream = connection.errorStream ?: connection.inputStream
                val rawError = errStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val parsedMsg = extractApiErrorMessage(rawError).ifBlank {
                    connection.responseMessage.orEmpty().ifBlank { "HTTP $status" }
                }
                throw GeminiApiException(status, parsedMsg, parseRetryAfterMs(connection.getHeaderField("Retry-After")))
            }
            onConnected()

            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                val dataBuffer = StringBuilder()
                while (true) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("data:") -> {
                            if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                            dataBuffer.append(line.removePrefix("data:").trimStart())
                        }
                        line.isBlank() -> {
                            if (dataBuffer.isNotEmpty()) {
                                val payload = dataBuffer.toString().trim()
                                dataBuffer.clear()
                                if (payload.isNotEmpty() && payload != "[DONE]") handlePayload(payload, status)
                            }
                        }
                    }
                }
                if (dataBuffer.isNotEmpty()) {
                    val payload = dataBuffer.toString().trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") handlePayload(payload, status)
                }
            }

            TurnStreamOutcome(
                modelParts = JSONArray().apply { assembledParts.forEach { put(it) } },
                functionCalls = functionCalls,
                blockReason = blockReason,
                finishReason = finishReason,
                usage = latestUsage.copy(latencyMs = System.currentTimeMillis() - startedAt),
                usedModel = modelId,
            )
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }

    private suspend fun generateContentUnary(
        modelId: String,
        apiKey: String,
        requestBody: String,
        onText: (String) -> Unit,
    ): TurnStreamOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val endpoint = "$GEMINI_API_BASE/$modelId:generateContent"
        val bodyBytes = requestBody.toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = UNARY_FALLBACK_READ_TIMEOUT_MS
            useCaches = false
            doOutput = true
            setFixedLengthStreamingMode(bodyBytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) Thread { runCatching { connection.disconnect() } }.start()
        }

        try {
            connection.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else (connection.errorStream ?: connection.inputStream)
            val rawResponse = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val parsedMsg = extractApiErrorMessage(rawResponse).ifBlank {
                    connection.responseMessage.orEmpty().ifBlank { "HTTP $status" }
                }
                throw GeminiApiException(status, parsedMsg, parseRetryAfterMs(connection.getHeaderField("Retry-After")))
            }
            val parsed = parseSsePayload(rawResponse)
            if (parsed.errorMessage != null) {
                throw GeminiApiException(parsed.errorCode ?: status, parsed.errorMessage)
            }
            if (parsed.text.isNotEmpty()) onText(parsed.text)

            val assembledParts = mutableListOf<JSONObject>()
            val functionCalls = mutableListOf<ParsedFunctionCall>()
            parsed.rawParts.forEach { rawPart ->
                appendRawPart(assembledParts, rawPart)
                val fc = rawPart.optJSONObject("functionCall")
                if (fc != null) {
                    val fnName = fc.optString("name")
                    if (fnName.isNotBlank()) {
                        functionCalls.add(ParsedFunctionCall(fnName, fc.optJSONObject("args") ?: JSONObject()))
                    }
                }
            }

            TurnStreamOutcome(
                modelParts = JSONArray().apply { assembledParts.forEach { put(it) } },
                functionCalls = functionCalls,
                blockReason = parsed.blockReason,
                finishReason = parsed.finishReason,
                usage = parsed.usage.copy(latencyMs = System.currentTimeMillis() - startedAt),
                usedModel = modelId,
            )
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }

    internal fun buildInitialContents(
        history: List<ChatMessage>,
        latestPromptWithContext: String,
        imageAttachments: List<AiAttachment> = emptyList(),
    ): JSONArray {
        val contentsArray = JSONArray()
        history.takeLast(6).forEach { msg ->
            val clipped = if (msg.text.length > 1_800) {
                msg.text.take(1_800) + "\n... [earlier message truncated]"
            } else {
                msg.text
            }
            contentsArray.put(
                JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "model")
                    put("parts", JSONArray().put(JSONObject().put("text", clipped)))
                },
            )
        }
        val latestParts = JSONArray().apply {
            put(JSONObject().put("text", latestPromptWithContext))
            imageAttachments.forEach { img ->
                if (img.isImage && img.base64Data.isNotBlank()) {
                    put(
                        JSONObject().put(
                            "inlineData",
                            JSONObject()
                                .put("mimeType", img.mimeType)
                                .put("data", img.base64Data),
                        ),
                    )
                }
            }
        }
        contentsArray.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", latestParts)
            },
        )
        return contentsArray
    }

    private fun buildSystemInstruction(
        modelId: String,
        projectLanguage: ProjectLanguage = ProjectLanguage.JAVA,
        javaVersion: JavaVersionOption = JavaVersionOption.JAVA_17,
        minSdkVersion: Int = 24,
        agentRole: AgentRole = AgentRole.GENERAL,
        customSystemInstruction: String = "",
    ): String {
        val langNote = if (projectLanguage == ProjectLanguage.KOTLIN) {
            "Active project language: KOTLIN (.kt files in src/). Write clean Kotlin Android SDK code (`class MainActivity : Activity()`, `override fun onCreate(savedInstanceState: Bundle?)`, `val`/`var`, `fun`, lambdas `{ ... }`, `object : ...`, string templates). Android Studio compiles `.kt` via its built-in Kotlin-to-Java transpiler + ECJ + Multi-DEX."
        } else {
            "Active project language: ${javaVersion.label} (.java files in src/). Every Java version 8 through 25 (8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25) is supported both offline (`ModernJavaTranspiler`) and in GitHub Cloud Builds (`var`, `\"\"\"` text blocks, `record`, `sealed`/`permits`, switch `->`/`yield`, pattern `instanceof`, `List.of`/`Set.of`/`Map.of`, `.repeat()`/`.lines()`, `IO.println`)."
        }
        val roleSection = if (agentRole != AgentRole.GENERAL && agentRole.defaultSystemFocus.isNotBlank()) {
            "\n\n=== Active Agent Role (${agentRole.label}) ===\n${agentRole.defaultSystemFocus}"
        } else {
            ""
        }
        val customSection = customSystemInstruction.trim().takeIf { it.isNotEmpty() }?.let {
            "\n\n=== Custom User System Instructions ===\n$it"
        }.orEmpty()
        return """
            You are an autonomous coding agent powered by $modelId inside Android Studio (mobile/tablet IDE for Java 8–25 & Kotlin), Multi-DEX APK/AAB builder, GitHub-connected workspace, and Live Interactive Android Emulator.
            $langNote
            Target minSdkVersion: API $minSdkVersion (Android ${com.example.model.AndroidMinSdk.fromApiLevel(minSdkVersion).androidVersion}), targetSdkVersion: 35.

            Execution rules (prioritize speed & accuracy):
            - Act autonomously WITHOUT asking permission before reading, creating, editing, or deleting files, or building/pushing.
            - Key project files are pre-loaded in the prompt. Jump straight to edit_file or write_file!
            - Batch independent tool calls together whenever possible.
            - Project & Build tools: list_files, read_file, write_file, edit_file, delete_file, search_files, build_apk, build_aab.
            - Full GitHub & Cloud CI tools: github_status, github_list_repos, github_create_repo, github_link_repo, github_list_branches, github_list_repo_files, github_read_file, github_upload_file, github_download_file, github_pull_changes, github_push_changes, github_trigger_cloud_build, github_list_workflow_runs, github_get_workflow_logs, github_rerun_workflow.
            - Live Emulator execution: Custom `View` subclasses overriding `onDraw(Canvas)` / `draw(Canvas)`, `SurfaceView` (`SurfaceHolder` `lockCanvas`/`unlockCanvasAndPost`), `onTouchEvent(MotionEvent)` (including `getPointerCount`/`getPointerId`), and `Handler.postDelayed` 60fps loops, plus XML layouts (`res/layout/*.xml` including XML-inflated custom Views & `@+id/*` -> `R.id.*`), `LinearLayout`/`FrameLayout`/`GridLayout`, `Button`, `TextView`, `EditText`, and `AlertDialog` execute live inside the Android Studio VM Emulator!
            - Offline & >5 GB App/Game Architecture: Supports Multi-DEX (`classes.dex`, `classes2.dex`...), `res/layout/*.xml`, `res/drawable/*`, `libs/*.jar`, native `.so` libraries under `lib/<abi>/` or `jniLibs/<abi>/`, and streaming 2-pass `.aab` signing (`build_aab`) + ZIP64 packaging. Both `src/`/`res/`/`AndroidManifest.xml` AND standard Gradle `app/src/main/...` layouts build locally.
            - Prefer classic Android SDK APIs (`android.app.Activity`, `android.view.*`, `android.widget.*`, `android.graphics.*`, `java.*`) unless building an external Gradle project for GitHub Cloud CI.
            - After modifying code or XML, call build_apk to verify compilation.

            Response formatting rules (CRITICAL):
            - Organize your explanation cleanly using short Markdown headings (`### Summary`, `### Changes Made`, `### Build Status`), concise bullet points (`- `), and backticked file paths or symbols (`` `MainActivity.kt` ``).
            - Keep paragraphs crisp and scannable on mobile screens.
        """.trimIndent() + roleSection + customSection
    }

    private fun shouldIncludeGitHubTools(
        prompt: String,
        history: List<ChatMessage>,
        githubConnected: Boolean,
    ): Boolean {
        if (githubConnected) return true
        val combined = buildString {
            append(prompt.lowercase())
            history.takeLast(3).forEach { append(' ').append(it.text.take(300).lowercase()) }
        }
        return listOf("github", "repo", "repository", "workflow", "actions", "cloud build", "commit", "push", "pull", "branch", "clone")
            .any { combined.contains(it) }
    }

    private fun parseRetryAfterMs(header: String?): Long? {
        val raw = header?.trim() ?: return null
        val seconds = raw.toLongOrNull() ?: return null
        return (seconds * 1000L).coerceIn(250L, 4500L)
    }

    internal fun buildRequestPayload(
        contents: JSONArray,
        modelId: String = GEMINI_MODEL_ID,
        thinkingMode: AiThinkingMode = AiThinkingMode.FAST,
        includeThinkingConfig: Boolean = true,
        includeGitHubTools: Boolean = true,
        projectLanguage: ProjectLanguage = ProjectLanguage.JAVA,
        javaVersion: JavaVersionOption = JavaVersionOption.JAVA_17,
        minSdkVersion: Int = 24,
        agentRole: AgentRole = AgentRole.GENERAL,
        customSystemInstruction: String = "",
        temperature: Float = 0.3f,
    ): String {
        val genConfig = JSONObject().put("temperature", temperature.coerceIn(0f, 1.5f).toDouble())
        if (includeThinkingConfig) {
            buildThinkingConfigJson(modelId, thinkingMode)?.let { cfg ->
                genConfig.put("thinkingConfig", cfg)
            }
        }
        return JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            buildSystemInstruction(
                                modelId = modelId,
                                projectLanguage = projectLanguage,
                                javaVersion = javaVersion,
                                minSdkVersion = minSdkVersion,
                                agentRole = agentRole,
                                customSystemInstruction = customSystemInstruction,
                            ),
                        ),
                    ),
                ),
            )
            put("contents", contents)
            put("tools", buildToolDeclarations(includeGitHubTools))
            put("generationConfig", genConfig)
        }.toString()
    }

    private fun buildToolDeclarations(includeGitHubTools: Boolean = true): JSONArray {
        val declarations = JSONArray().apply {
            ProviderApiClient.buildSharedToolSpecs()
                .filter { (name, _, _) -> includeGitHubTools || !name.startsWith("github_") }
                .forEach { (name, desc, schema) ->
                    val rawProps = schema.optJSONObject("properties")
                    val decl = JSONObject()
                        .put("name", name)
                        .put("description", desc)
                    if (rawProps != null && rawProps.length() > 0) {
                        val upperSchema = JSONObject(schema.toString()).apply {
                            put("type", "OBJECT")
                            optJSONObject("properties")?.let { props ->
                                props.keys().forEach { k ->
                                    props.optJSONObject(k)?.let { propObj ->
                                        val t = propObj.optString("type", "string").uppercase()
                                        propObj.put("type", t)
                                    }
                                }
                            }
                        }
                        decl.put("parameters", upperSchema)
                    }
                    put(decl)
                }
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", declarations))
    }

    internal fun buildPrompt(
        userPrompt: String,
        projectName: String,
        packageName: String,
        projectLanguage: ProjectLanguage = ProjectLanguage.JAVA,
        minSdkVersion: Int = 24,
        attachments: List<AiAttachment> = emptyList(),
        activeFile: ProjectFile?,
        allFiles: List<ProjectFile>,
        diagnostics: List<BuildDiagnostic>,
    ): String = buildString {
        appendLine("Project: $projectName (package: $packageName)")
        appendLine("Language: ${projectLanguage.displayName} • Min Android SDK: API $minSdkVersion")
        appendLine("Files: ${allFiles.joinToString(", ") { it.relativePath }}")
        if (activeFile != null) {
            appendLine("Currently open editor tab: ${activeFile.relativePath}")
        }
        if (diagnostics.isNotEmpty()) {
            appendLine()
            appendLine("=== Latest Compiler Diagnostics ===")
            diagnostics.take(15).forEach { d ->
                appendLine("${d.severity.name} ${d.filePath}:${d.line} -> ${d.message}")
            }
        }

        // Pre-inline key files so the agent can edit on Turn 1 instead of wasting 2-3 round trips.
        val priorityFiles = linkedListOfFilesForContext(activeFile, allFiles)
        var budget = PRELOAD_CONTEXT_MAX_CHARS
        if (priorityFiles.isNotEmpty()) {
            appendLine()
            appendLine("=== Pre-loaded Project Files ===")
            for (file in priorityFiles) {
                if (budget <= 400) break
                val snippet = if (file.content.length <= budget) {
                    file.content
                } else {
                    file.content.take(budget) + "\n... [truncated; call read_file for full file]"
                }
                appendLine("--- ${file.relativePath} ---")
                appendLine(snippet.trimEnd())
                budget -= snippet.length
            }
        }

        val textAttachments = attachments.filter { !it.isImage && it.textContent.isNotBlank() }
        if (textAttachments.isNotEmpty()) {
            appendLine()
            appendLine("=== User Attached Files ===")
            textAttachments.forEach { att ->
                appendLine("--- ${att.displayName} (${att.mimeType}, ${att.sizeBytes} bytes) ---")
                appendLine(att.textContent.take(12_000))
            }
        }
        val imageAttachments = attachments.filter { it.isImage }
        if (imageAttachments.isNotEmpty()) {
            appendLine()
            appendLine("Attached images (${imageAttachments.size}): ${imageAttachments.joinToString(", ") { it.displayName }}")
        }

        appendLine()
        appendLine("User request:")
        appendLine(userPrompt)
    }

    private fun linkedListOfFilesForContext(
        activeFile: ProjectFile?,
        allFiles: List<ProjectFile>,
    ): List<ProjectFile> {
        val seen = LinkedHashSet<String>()
        val ordered = mutableListOf<ProjectFile>()
        fun addIfPresent(file: ProjectFile?) {
            if (file != null && !file.isBinary && seen.add(file.relativePath)) {
                ordered.add(file)
            }
        }
        addIfPresent(activeFile)
        addIfPresent(allFiles.find { it.relativePath.endsWith("MainActivity.kt") })
        addIfPresent(allFiles.find { it.relativePath.endsWith("MainActivity.java") })
        addIfPresent(allFiles.find { it.relativePath == "AndroidManifest.xml" })
        addIfPresent(allFiles.find { it.relativePath == "res/values/strings.xml" })
        addIfPresent(allFiles.find { it.relativePath == "res/values/colors.xml" })
        allFiles.filter {
            !it.isBinary && (it.relativePath.endsWith(".kt") || it.relativePath.endsWith(".java") || it.relativePath.endsWith(".xml"))
        }
            .take(4)
            .forEach { addIfPresent(it) }
        return ordered
    }

    internal data class ParsedFunctionCall(
        val name: String,
        val args: JSONObject,
    )

    private data class TurnStreamOutcome(
        val modelParts: JSONArray,
        val functionCalls: List<ParsedFunctionCall>,
        val blockReason: String?,
        val finishReason: String?,
        val usage: TokenUsageDelta = TokenUsageDelta(),
        val usedModel: String = GEMINI_MODEL_ID,
    )

    internal data class SseParseResult(
        val text: String = "",
        val rawParts: List<JSONObject> = emptyList(),
        val blockReason: String? = null,
        val finishReason: String? = null,
        val errorMessage: String? = null,
        val errorCode: Int? = null,
        val usage: TokenUsageDelta = TokenUsageDelta(),
    )

    internal data class OpenAiModelCapabilities(
        val useNativeTools: Boolean = true,
        val includeStreamOptions: Boolean = true,
        val includeTemperature: Boolean = true,
        val allowDataCollectionFallback: Boolean = false,
        val foldSystem: Boolean = false,
        val stripImages: Boolean = false,
        val useResponsesApi: Boolean = false,
        val maxTokens: Int? = null,
        val useStreaming: Boolean = true,
    )

    internal data class ClaudeModelCapabilities(
        val maxTokens: Int = 8192,
        val useNativeTools: Boolean = true,
        val includeSystem: Boolean = true,
        val includeTemperature: Boolean = false,
        val stripImages: Boolean = false,
        val useStreaming: Boolean = true,
    )

    internal companion object {
        fun resolveApiKey(customApiKey: String): String {
            val custom = customApiKey.trim()
            if (custom.isNotEmpty()) return custom
            val builtIn = BuildConfig.GEMINI_API_KEY.trim()
            if (builtIn.isNotEmpty() && !builtIn.equals("GEMINI_API_KEY_DEFAULT_VALUE", ignoreCase = true)) {
                return builtIn
            }
            return ""
        }

        fun hasBuiltInApiKey(): Boolean {
            val builtIn = BuildConfig.GEMINI_API_KEY.trim()
            return builtIn.isNotEmpty() && !builtIn.equals("GEMINI_API_KEY_DEFAULT_VALUE", ignoreCase = true)
        }

        fun resolveOpenRouterApiKey(customApiKey: String): String {
            val custom = customApiKey.trim()
            if (custom.isNotEmpty()) return custom
            val builtIn = BuildConfig.OPENROUTER_API_KEY.trim()
            if (builtIn.isNotEmpty() && !builtIn.equals("OPENROUTER_API_KEY_DEFAULT_VALUE", ignoreCase = true)) {
                return builtIn
            }
            return ""
        }

        fun hasBuiltInOpenRouterApiKey(): Boolean {
            val builtIn = BuildConfig.OPENROUTER_API_KEY.trim()
            return builtIn.isNotEmpty() && !builtIn.equals("OPENROUTER_API_KEY_DEFAULT_VALUE", ignoreCase = true)
        }

        internal fun isTransientHttpStatus(httpCode: Int): Boolean {
            return httpCode == 408 ||
                httpCode == 429 ||
                httpCode == 500 ||
                httpCode == 502 ||
                httpCode == 503 ||
                httpCode == 504 ||
                httpCode == 529
        }

        internal fun shouldFailoverHttpStatus(httpCode: Int): Boolean {
            return httpCode == 404 || isTransientHttpStatus(httpCode)
        }

        internal fun buildThinkingConfigJson(
            modelId: String,
            mode: AiThinkingMode,
        ): JSONObject? {
            val id = modelId.lowercase()
            // Legacy 2.0 / 1.5 / 1.0 models do not support thinkingConfig
            if (id.contains("2.0") || id.contains("1.5") || id.contains("1.0")) {
                return null
            }
            // Gemini 2.5 series uses integer thinkingBudget (never thinkingLevel)
            if (id.contains("2.5")) {
                val isPro = id.contains("pro")
                val budget = when (mode) {
                    AiThinkingMode.FAST -> if (isPro) 256 else 0
                    AiThinkingMode.BALANCED -> if (isPro) 2048 else 1024
                    AiThinkingMode.DEEP -> 8192
                }
                return JSONObject().put("thinkingBudget", budget)
            }
            // Only verified Gemini 3 models use thinkingLevel presets; omit on unknown/custom model IDs so they use native defaults
            if (id.contains("gemini-3")) {
                val isPro = id.contains("pro")
                val level = when (mode) {
                    AiThinkingMode.FAST -> if (isPro) "low" else "minimal"
                    AiThinkingMode.BALANCED -> "medium"
                    AiThinkingMode.DEEP -> "high"
                }
                return JSONObject().put("thinkingLevel", level)
            }
            return null
        }

        internal fun stripThoughtPartsFromContents(contents: JSONArray): JSONArray {
            val cleaned = JSONArray()
            for (i in 0 until contents.length()) {
                val msg = contents.optJSONObject(i) ?: continue
                val copyMsg = JSONObject(msg.toString())
                val parts = copyMsg.optJSONArray("parts")
                if (parts != null) {
                    val newParts = JSONArray()
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        if (part.optBoolean("thought", false)) continue
                        val partCopy = JSONObject(part.toString())
                        partCopy.remove("thoughtSignature")
                        newParts.put(partCopy)
                    }
                    if (newParts.length() > 0) {
                        copyMsg.put("parts", newParts)
                    }
                }
                cleaned.put(copyMsg)
            }
            return cleaned
        }

        internal fun appendRawPart(target: MutableList<JSONObject>, incoming: JSONObject) {
            val copy = JSONObject(incoming.toString())
            val hasFc = copy.has("functionCall")
            val hasSig = copy.has("thoughtSignature")
            val isThought = copy.optBoolean("thought", false)
            val hasText = copy.has("text")

            if (!hasFc && !hasSig && hasText && target.isNotEmpty()) {
                val last = target.last()
                val lastCanMerge = !last.has("functionCall") &&
                    !last.has("thoughtSignature") &&
                    last.has("text") &&
                    last.optBoolean("thought", false) == isThought
                if (lastCanMerge) {
                    last.put("text", last.optString("text", "") + copy.optString("text", ""))
                    return
                }
            }
            target.add(copy)
        }

        internal fun nextOpenRouterAdaptation(
            current: OpenAiModelCapabilities,
            httpCode: Int,
            errorMessage: String,
            hasImages: Boolean,
            isDirectOpenAi: Boolean = false,
        ): OpenAiModelCapabilities? {
            if (httpCode !in listOf(400, 402, 404, 422)) return null
            val lower = errorMessage.lowercase()

            if (!isDirectOpenAi && (httpCode == 402 || lower.contains("can only afford") || lower.contains("more credits") || lower.contains("max_tokens"))) {
                val currentCap = current.maxTokens ?: 8192
                val affordMatch = Regex("""can only afford (\d+)""").find(lower)
                    ?: Regex("""(?:<=|maximum(?: of)?|at most|max_tokens[^\d]*)\s*(\d+)""").find(lower)
                val parsed = affordMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                val nextBudget = when {
                    parsed != null && parsed >= 512 && parsed < currentCap -> parsed
                    currentCap > 4096 -> 4096
                    currentCap > 2048 -> 2048
                    currentCap > 1024 -> 1024
                    else -> null
                }
                if (nextBudget != null && nextBudget < currentCap) {
                    return current.copy(maxTokens = nextBudget)
                }
                if (httpCode == 402) return null
            }

            if (current.useStreaming &&
                (lower.contains("verified to stream") ||
                    lower.contains("organization must be verified") ||
                    (lower.contains("stream") && (lower.contains("unsupported") || lower.contains("not support") || lower.contains("not allowed"))))
            ) {
                return current.copy(useStreaming = false, includeStreamOptions = false)
            }

            if (isDirectOpenAi && !current.useResponsesApi &&
                (lower.contains("v1/responses") || lower.contains("not a chat model") || lower.contains("responses api") || lower.contains("only supported in"))
            ) {
                return current.copy(useResponsesApi = true)
            }

            val isDataPolicy = !isDirectOpenAi && (
                lower.contains("data policy") ||
                    lower.contains("privacy") ||
                    lower.contains("free model publication") ||
                    lower.contains("zdr")
                )
            if (isDataPolicy) {
                return if (!current.allowDataCollectionFallback) {
                    current.copy(allowDataCollectionFallback = true)
                } else {
                    null
                }
            }

            if (hasImages && !current.stripImages &&
                (lower.contains("image") || lower.contains("vision") || lower.contains("multimodal") || lower.contains("image_url"))
            ) {
                return current.copy(stripImages = true)
            }
            if (current.useNativeTools &&
                (lower.contains("tool") || lower.contains("function"))
            ) {
                return current.copy(useNativeTools = false)
            }
            if (!current.foldSystem &&
                (lower.contains("developer instruction") || lower.contains("system"))
            ) {
                return current.copy(foldSystem = true)
            }
            if (current.includeStreamOptions &&
                (lower.contains("stream_options") || lower.contains("include_usage"))
            ) {
                return current.copy(includeStreamOptions = false)
            }
            if (current.includeTemperature &&
                (lower.contains("temperature") || lower.contains("sampling") || lower.contains("unsupported value"))
            ) {
                return current.copy(includeTemperature = false)
            }

            if (httpCode == 404 || lower.contains("no endpoints") || lower.contains("unsupported parameter") || lower.contains("invalid parameter")) {
                if (isDirectOpenAi && httpCode == 404 && !current.useResponsesApi) return current.copy(useResponsesApi = true)
                if (current.useNativeTools) return current.copy(useNativeTools = false)
                if (current.includeStreamOptions) return current.copy(includeStreamOptions = false)
                if (current.useStreaming) return current.copy(useStreaming = false, includeStreamOptions = false)
                if (current.includeTemperature) return current.copy(includeTemperature = false)
                if (!isDirectOpenAi && !current.allowDataCollectionFallback) return current.copy(allowDataCollectionFallback = true)
                if (!current.foldSystem) return current.copy(foldSystem = true)
                if (hasImages && !current.stripImages) return current.copy(stripImages = true)
            }
            return null
        }

        internal fun nextClaudeAdaptation(
            current: ClaudeModelCapabilities,
            httpCode: Int,
            errorMessage: String,
            hasImages: Boolean,
        ): ClaudeModelCapabilities? {
            if (httpCode !in listOf(400, 422)) return null
            val lower = errorMessage.lowercase()

            if (current.useStreaming &&
                lower.contains("stream") &&
                (lower.contains("unsupported") || lower.contains("not support") || lower.contains("verified"))
            ) {
                return current.copy(useStreaming = false)
            }
            if (lower.contains("max_tokens")) {
                val limitMatch = Regex("""(?:<=|maximum(?: of)?|at most|max(?:imum)?)\s*(\d+)""").find(lower)
                val parsedLimit = limitMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1024, 8192) ?: 4096
                if (parsedLimit < current.maxTokens) {
                    return current.copy(maxTokens = parsedLimit)
                } else if (current.maxTokens > 4096) {
                    return current.copy(maxTokens = 4096)
                }
            }
            if (hasImages && !current.stripImages &&
                (lower.contains("image") || lower.contains("vision") || lower.contains("media_type"))
            ) {
                return current.copy(stripImages = true)
            }
            if (current.useNativeTools &&
                (lower.contains("tool") || lower.contains("input_schema"))
            ) {
                return current.copy(useNativeTools = false)
            }
            if (current.includeSystem && lower.contains("system")) {
                return current.copy(includeSystem = false)
            }
            if (current.includeTemperature && lower.contains("temperature")) {
                return current.copy(includeTemperature = false)
            }
            if (httpCode == 400) {
                if (current.useStreaming) return current.copy(useStreaming = false)
                if (current.useNativeTools) return current.copy(useNativeTools = false)
                if (current.maxTokens > 4096) return current.copy(maxTokens = 4096)
            }
            return null
        }

        internal fun extractApiErrorMessage(rawBody: String): String {
            return runCatching {
                val obj = JSONObject(rawBody)
                val err = obj.optJSONObject("error") ?: return@runCatching ""
                val msg = err.optString("message").trim()
                val metaObj = err.optJSONObject("metadata")
                val metaRaw = metaObj?.optString("raw").orEmpty().trim()
                val providerName = metaObj?.optString("provider_name").orEmpty().trim()
                buildString {
                    append(msg)
                    if (metaRaw.isNotBlank() && !msg.contains(metaRaw, ignoreCase = true)) {
                        if (isNotEmpty()) append(" — ")
                        append(metaRaw)
                    }
                    if (providerName.isNotBlank() && !msg.contains(providerName, ignoreCase = true)) {
                        append(" [").append(providerName).append(']')
                    }
                }
            }.getOrDefault("").ifBlank { rawBody.take(240) }
        }

        internal fun parseSsePayload(jsonPayload: String): SseParseResult {
            return runCatching {
                val root = JSONObject(jsonPayload)
                val err = root.optJSONObject("error")
                if (err != null) {
                    val code = if (err.has("code")) err.optInt("code") else null
                    return SseParseResult(
                        errorMessage = err.optString("message", "Unknown API error"),
                        errorCode = code,
                    )
                }

                val usageObj = root.optJSONObject("usageMetadata")
                val usage = if (usageObj != null) {
                    val input = usageObj.optLong("promptTokenCount", 0L)
                    val output = usageObj.optLong("candidatesTokenCount", 0L)
                    val thoughts = usageObj.optLong("thoughtsTokenCount", 0L)
                    val total = usageObj.optLong("totalTokenCount", input + output + thoughts)
                    TokenUsageDelta(
                        inputTokens = input,
                        outputTokens = output,
                        thinkingTokens = thoughts,
                        totalTokens = total,
                    )
                } else {
                    TokenUsageDelta()
                }

                val promptFeedback = root.optJSONObject("promptFeedback")
                val blockReason = promptFeedback?.optString("blockReason")?.takeIf { it.isNotBlank() }

                val candidates = root.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return SseParseResult(blockReason = blockReason, usage = usage)
                }

                val firstCandidate = candidates.optJSONObject(0)
                val finishReason = firstCandidate?.optString("finishReason")?.takeIf { it.isNotBlank() }
                val parts = firstCandidate?.optJSONObject("content")?.optJSONArray("parts")
                val textBuilder = StringBuilder()
                val rawPartsList = mutableListOf<JSONObject>()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        rawPartsList.add(JSONObject(part.toString()))
                        if (part.optBoolean("thought", false)) continue
                        val partText = part.optString("text", "")
                        if (partText.isNotEmpty()) textBuilder.append(partText)
                    }
                }
                SseParseResult(
                    text = textBuilder.toString(),
                    rawParts = rawPartsList,
                    blockReason = blockReason,
                    finishReason = finishReason,
                    usage = usage,
                )
            }.getOrElse {
                SseParseResult()
            }
        }

        internal fun extractCodeBlocks(markdown: String): List<SuggestedCodeBlock> {
            val regex = Regex("""```([a-zA-Z0-9_+-]*)\n([\s\S]*?)```""")
            return regex.findAll(markdown).mapNotNull { match ->
                val lang = match.groupValues[1].lowercase().ifBlank { "java" }
                val rawCode = match.groupValues[2].trimEnd()
                if (rawCode.isBlank()) return@mapNotNull null

                val firstLine = rawCode.lineSequence().firstOrNull()?.trim().orEmpty()
                val pathHint = when {
                    firstLine.startsWith("// file:") -> firstLine.removePrefix("// file:").trim()
                    firstLine.startsWith("<!-- file:") -> firstLine.removePrefix("<!-- file:").removeSuffix("-->").trim()
                    else -> null
                }
                val cleanedCode = if (pathHint != null) {
                    rawCode.substringAfter('\n', rawCode).trimStart('\n')
                } else {
                    rawCode
                }
                SuggestedCodeBlock(language = lang, suggestedPath = pathHint, code = cleanedCode)
            }.toList()
        }
    }
}
