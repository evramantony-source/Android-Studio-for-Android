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
const val GEMINI_MODEL_ID = "gemini-2.5-flash"
private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private const val MAX_AGENT_TURNS = 12
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
        val resolvedKey = when (provider) {
            AiProvider.GEMINI -> resolveApiKey(apiKey)
            AiProvider.OPENAI, AiProvider.CLAUDE -> apiKey.trim()
        }

        if (resolvedKey.isEmpty()) {
            _uiState.value = AiUiState.Error(
                "${provider.shortName} API key is not configured. Tap the Key icon above to add your ${provider.displayName} key.",
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
                AiProvider.GEMINI -> runGeminiLoop(
                    initialModel = requestedModel,
                    apiKey = resolvedKey,
                    thinkingMode = thinkingMode,
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
                AiProvider.OPENAI -> runOpenAiLoop(
                    initialModel = requestedModel,
                    apiKey = resolvedKey,
                    thinkingMode = thinkingMode,
                    projectLanguage = projectLanguage,
                    javaVersion = javaVersion,
                    minSdkVersion = minSdkVersion,
                    imageAttachments = imageAttachments,
                    priorHistory = priorHistory,
                    promptWithContext = promptWithContext,
                    steps = steps,
                    accumulatedVisibleText = accumulatedVisibleText,
                    addOrUpdateStep = ::addOrUpdateStep,
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
                AiProvider.CLAUDE -> runClaudeLoop(
                    initialModel = requestedModel,
                    apiKey = resolvedKey,
                    thinkingMode = thinkingMode,
                    projectLanguage = projectLanguage,
                    javaVersion = javaVersion,
                    minSdkVersion = minSdkVersion,
                    imageAttachments = imageAttachments,
                    priorHistory = priorHistory,
                    promptWithContext = promptWithContext,
                    steps = steps,
                    accumulatedVisibleText = accumulatedVisibleText,
                    addOrUpdateStep = ::addOrUpdateStep,
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
            val friendly = when (e.httpCode) {
                400, 401, 403 -> "${provider.shortName} API error (${e.httpCode}): ${e.message}"
                404 -> "Model '$effectiveModelInUse' was not found for this ${provider.shortName} key (404): ${e.message}."
                429 -> "${provider.shortName} rate limit or quota exceeded (429): ${e.message}"
                503, 529 -> "All tried ${provider.shortName} models are overloaded right now (${e.httpCode}): ${e.message}"
                else -> "${provider.shortName} API error (${e.httpCode}): ${e.message}"
            }
            finalizeRunFailure(
                "API error ${e.httpCode}",
                AiUiState.Error(
                    message = friendly,
                    isRetryable = e.httpCode !in listOf(400, 401, 403),
                    isModelOverloaded = overloaded || e.httpCode == 404,
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

    private suspend fun runGeminiLoop(
        initialModel: String,
        apiKey: String,
        thinkingMode: AiThinkingMode,
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
                .ifBlank { "Stopped after reaching the maximum autonomous step limit ($MAX_AGENT_TURNS turns)." }
            onFinalizeSuccess(summary, steps.toList(), cumulativeTokens, activeModel)
        }
    }

    private suspend fun runOpenAiLoop(
        initialModel: String,
        apiKey: String,
        thinkingMode: AiThinkingMode,
        projectLanguage: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
        imageAttachments: List<AiAttachment>,
        priorHistory: List<ChatMessage>,
        promptWithContext: String,
        steps: MutableList<AgentStep>,
        accumulatedVisibleText: StringBuilder,
        addOrUpdateStep: (AgentStep) -> Unit,
        onStreamText: (String, String) -> Unit,
        onFinalizeSuccess: (String, List<AgentStep>, Long, String) -> Unit,
        toolExecutor: ProjectToolExecutor,
        onUsage: (String, TokenUsageDelta, Int) -> Unit,
    ) {
        var activeModel = initialModel
        val openAiMessages = JSONArray().apply {
            put(
                JSONObject()
                    .put("role", "system")
                    .put("content", buildSystemInstruction(activeModel, projectLanguage, javaVersion, minSdkVersion)),
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

        var totalTokens = 0L
        var completed = false

        for (turn in 0 until MAX_AGENT_TURNS) {
            coroutineContext.ensureActive()
            val turnStart = System.currentTimeMillis()
            val thinkStepId = "think_$turn"
            addOrUpdateStep(
                AgentStep(
                    id = thinkStepId,
                    title = if (turn == 0) "Connecting to ChatGPT ($activeModel)" else "Analyzing tool output ($activeModel)",
                    detail = "${thinkingMode.badge} • Waiting for OpenAI...",
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
                    provider = AiProvider.OPENAI,
                    primaryModelId = activeModel,
                    onStatusUpdate = { title, detail ->
                        addOrUpdateStep(AgentStep(id = thinkStepId, title = title, detail = detail, status = AgentStepStatus.RUNNING))
                    },
                ) { candidate ->
                    ProviderApiClient.streamOpenAi(
                        apiKey = apiKey,
                        modelId = candidate,
                        messages = openAiMessages,
                        thinkingMode = thinkingMode,
                        onConnected = {
                            activeModel = candidate
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
                }
            }

            activeModel = usedModel
            if (turnText.isEmpty()) accumulatedVisibleText.setLength(turnPrefixLen)
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
                val finalText = accumulatedVisibleText.toString().trim().ifBlank { "All requested actions finished." }
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
            if (outcome.text.isNotEmpty()) assistantMsg.put("content", outcome.text)
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
            val summary = accumulatedVisibleText.toString().trim().ifBlank { "Reached max step limit ($MAX_AGENT_TURNS turns)." }
            onFinalizeSuccess(summary, steps.toList(), totalTokens, activeModel)
        }
    }

    private suspend fun runClaudeLoop(
        initialModel: String,
        apiKey: String,
        thinkingMode: AiThinkingMode,
        projectLanguage: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
        imageAttachments: List<AiAttachment>,
        priorHistory: List<ChatMessage>,
        promptWithContext: String,
        steps: MutableList<AgentStep>,
        accumulatedVisibleText: StringBuilder,
        addOrUpdateStep: (AgentStep) -> Unit,
        onStreamText: (String, String) -> Unit,
        onFinalizeSuccess: (String, List<AgentStep>, Long, String) -> Unit,
        toolExecutor: ProjectToolExecutor,
        onUsage: (String, TokenUsageDelta, Int) -> Unit,
    ) {
        var activeModel = initialModel
        val systemText = buildSystemInstruction(activeModel, projectLanguage, javaVersion, minSdkVersion)
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
                    onStatusUpdate = { title, detail ->
                        addOrUpdateStep(AgentStep(id = thinkStepId, title = title, detail = detail, status = AgentStepStatus.RUNNING))
                    },
                ) { candidate ->
                    ProviderApiClient.streamClaude(
                        apiKey = apiKey,
                        modelId = candidate,
                        systemInstruction = systemText,
                        messages = claudeMessages,
                        thinkingMode = thinkingMode,
                        onConnected = {
                            activeModel = candidate
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
                }
            }

            activeModel = usedModel
            if (turnText.isEmpty()) accumulatedVisibleText.setLength(turnPrefixLen)
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
            val summary = accumulatedVisibleText.toString().trim().ifBlank { "Reached max step limit ($MAX_AGENT_TURNS turns)." }
            onFinalizeSuccess(summary, steps.toList(), totalTokens, activeModel)
        }
    }

    private suspend fun callExternalWithRetryAndFailover(
        provider: AiProvider,
        primaryModelId: String,
        onStatusUpdate: (String, String) -> Unit,
        invoke: suspend (String) -> ExternalTurnOutcome,
    ): Pair<String, ExternalTurnOutcome> {
        val candidates = AiModelsCatalog.fallbackChain(provider, primaryModelId)
        var lastError: Throwable? = null

        for ((index, candidate) in candidates.withIndex()) {
            val hasNextCandidate = index < candidates.lastIndex
            var attemptsOnCandidate = 0
            while (attemptsOnCandidate < 2) {
                coroutineContext.ensureActive()
                attemptsOnCandidate++
                try {
                    val outcome = invoke(candidate)
                    return candidate to outcome
                } catch (e: GeminiApiException) {
                    lastError = e
                    val canRetrySame = isTransientHttpStatus(e.httpCode) && attemptsOnCandidate == 1
                    if (canRetrySame) {
                        onStatusUpdate(
                            "Retrying $candidate",
                            "HTTP ${e.httpCode} — quick retry before failover...",
                        )
                        delay(400L)
                        continue
                    }
                    if (shouldFailoverHttpStatus(e.httpCode) && hasNextCandidate) {
                        val nextModel = candidates[index + 1]
                        onStatusUpdate(
                            "Switching model (${e.httpCode})",
                            "$candidate returned ${e.httpCode} — failing over to $nextModel...",
                        )
                        break
                    }
                    throw e
                } catch (e: SocketTimeoutException) {
                    lastError = e
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
        throw lastError ?: GeminiApiException(503, "All ${provider.shortName} fallback models are currently unavailable.")
    }

    private suspend fun callGeminiWithRetryAndFailover(
        primaryModelId: String,
        apiKey: String,
        thinkingMode: AiThinkingMode,
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
        val baseChain = AiModelsCatalog.fallbackChain(AiProvider.GEMINI, primaryModelId)
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
                    { !it.contains("2.5-flash", ignoreCase = true) },
                    { !it.contains("2.0-flash", ignoreCase = true) },
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
        val rescueModels = (liveFlash + listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-flash-latest",
            "gemini-2.0-flash-lite",
            "gemini-2.5-pro",
        )).distinct()

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
    ): String {
        val langNote = if (projectLanguage == ProjectLanguage.KOTLIN) {
            "Active project language: KOTLIN (.kt files in src/). Write clean Kotlin Android SDK code (`class MainActivity : Activity()`, `override fun onCreate(savedInstanceState: Bundle?)`, `val`/`var`, `fun`, lambdas `{ ... }`, `object : ...`, string templates). DroidForge compiles `.kt` via its built-in Kotlin-to-Java transpiler + ECJ + Multi-DEX."
        } else {
            "Active project language: ${javaVersion.label} (.java files in src/). Every Java version 8 through 25 (8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25) is supported both offline (`ModernJavaTranspiler`) and in GitHub Cloud Builds (`var`, `\"\"\"` text blocks, `record`, `sealed`/`permits`, switch `->`/`yield`, pattern `instanceof`, `List.of`/`Set.of`/`Map.of`, `.repeat()`/`.lines()`, `IO.println`)."
        }
        return """
            You are an autonomous coding agent powered by $modelId inside DroidForge Studio, an Android IDE (Java 8–25 & Kotlin), Multi-DEX APK/AAB builder, GitHub-connected workspace, and Live Interactive Android Emulator.
            $langNote
            Target minSdkVersion: API $minSdkVersion (Android ${com.example.model.AndroidMinSdk.fromApiLevel(minSdkVersion).androidVersion}), targetSdkVersion: 35.

            Execution rules (prioritize speed & accuracy):
            - Act autonomously WITHOUT asking permission before reading, creating, editing, or deleting files, or building/pushing.
            - Key project files are pre-loaded in the prompt. Jump straight to edit_file or write_file!
            - Batch independent tool calls together whenever possible.
            - Project & Build tools: list_files, read_file, write_file, edit_file, delete_file, search_files, build_apk, build_aab.
            - Full GitHub & Cloud CI tools: github_status, github_list_repos, github_create_repo, github_link_repo, github_list_branches, github_list_repo_files, github_read_file, github_upload_file, github_download_file, github_pull_changes, github_push_changes, github_trigger_cloud_build, github_list_workflow_runs, github_get_workflow_logs, github_rerun_workflow.
            - Live Emulator execution: Custom `View` subclasses overriding `onDraw(Canvas)` / `draw(Canvas)`, `SurfaceView` (`SurfaceHolder` `lockCanvas`/`unlockCanvasAndPost`), `onTouchEvent(MotionEvent)` (including `getPointerCount`/`getPointerId`), and `Handler.postDelayed` 60fps loops, plus XML layouts (`res/layout/*.xml` including XML-inflated custom Views & `@+id/*` -> `R.id.*`), `LinearLayout`/`FrameLayout`/`GridLayout`, `Button`, `TextView`, `EditText`, and `AlertDialog` execute live inside the DroidForge VM Emulator!
            - Offline & >5 GB App/Game Architecture: DroidForge supports Multi-DEX (`classes.dex`, `classes2.dex`...), `res/layout/*.xml`, `res/drawable/*`, `libs/*.jar`, native `.so` libraries under `lib/<abi>/` or `jniLibs/<abi>/`, and streaming 2-pass `.aab` signing (`build_aab`) + ZIP64 packaging. Remember Android OS `PackageInstaller` caps a single `.apk` at ~4 GB, whereas `.aab` bundles, Play Asset Delivery, Cloud Gradle CI (`github_trigger_cloud_build`), and runtime asset/JRE downloaders (e.g., PojavLauncher-style Minecraft Java Edition launchers with runtime OpenJDK + LWJGL + asset/version manifest fetchers & virtual controls) handle >5 GB payloads cleanly!
            - Prefer classic Android SDK APIs (`android.app.Activity`, `android.view.*`, `android.widget.*`, `android.graphics.*`, `java.*`) unless building an external Gradle project for GitHub Cloud CI.
            - After modifying code or XML, call build_apk to verify compilation.

            Response formatting rules (CRITICAL):
            - Organize your explanation cleanly using short Markdown headings (`### Summary`, `### Changes Made`, `### Build Status`), concise bullet points (`- `), and backticked file paths or symbols (`` `MainActivity.kt` ``).
            - Keep paragraphs crisp and scannable on mobile screens.
        """.trimIndent()
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
    ): String {
        val genConfig = JSONObject().put("temperature", 0.3)
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
                        JSONObject().put("text", buildSystemInstruction(modelId, projectLanguage, javaVersion, minSdkVersion)),
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

        internal fun extractApiErrorMessage(rawBody: String): String {
            return runCatching {
                val obj = JSONObject(rawBody)
                obj.optJSONObject("error")?.optString("message").orEmpty()
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
