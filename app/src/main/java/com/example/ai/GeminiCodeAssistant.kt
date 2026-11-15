package com.example.ai

import android.util.Log
import com.example.BuildConfig
import com.example.model.BuildDiagnostic
import com.example.model.ProjectFile
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
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

private const val TAG = "GeminiCodeAssistant"
const val GEMINI_MODEL_ID = "gemini-4-hydrogen"
private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private const val MAX_AGENT_TURNS = 12
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 180_000
private const val TURN_TIMEOUT_MS = 180_000L

internal class GeminiApiException(
    val httpCode: Int,
    message: String,
) : Exception(message)

class GeminiCodeAssistant {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    fun setMessages(restored: List<ChatMessage>) {
        _messages.value = restored
        _uiState.value = AiUiState.Idle
    }

    fun clearConversation() {
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

    fun markCancelled() {
        _messages.value = _messages.value.map { msg ->
            if (!msg.isStreaming) return@map msg
            val updatedSteps = msg.steps.map { step ->
                if (step.status == AgentStepStatus.RUNNING) {
                    step.copy(status = AgentStepStatus.FAILED, detail = "Stopped by user")
                } else {
                    step
                }
            }
            msg.copy(
                isStreaming = false,
                steps = updatedSteps,
                text = msg.text.ifBlank { "Stopped." },
            )
        }
        _uiState.value = AiUiState.Idle
    }

    suspend fun askGemini(
        userPrompt: String,
        apiKey: String,
        modelId: String = GEMINI_MODEL_ID,
        provider: AiProvider = AiProvider.GEMINI,
        projectName: String,
        packageName: String,
        activeFile: ProjectFile?,
        allFiles: List<ProjectFile>,
        diagnostics: List<BuildDiagnostic>,
        toolExecutor: ProjectToolExecutor,
        onUsageRecorded: (provider: AiProvider, modelId: String, delta: TokenUsageDelta, toolCount: Int, failed: Boolean) -> Unit = { _, _, _, _, _ -> },
        onMessagesChanged: (List<ChatMessage>) -> Unit = {},
    ) {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty()) return

        val activeModel = modelId.trim().removePrefix("models/").ifBlank { provider.defaultModelId }
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
            .takeLast(8)

        val userMsg = ChatMessage(
            id = "u_${System.currentTimeMillis()}",
            isUser = true,
            text = trimmed,
        )
        val assistantMsgId = "a_${System.currentTimeMillis()}"
        val steps = mutableListOf<AgentStep>()
        val assistantPlaceholder = ChatMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            isStreaming = true,
            steps = emptyList(),
            providerName = provider.shortName,
            modelId = activeModel,
        )

        _messages.value = _messages.value + userMsg + assistantPlaceholder
        onMessagesChanged(_messages.value)
        _uiState.value = AiUiState.Streaming

        val promptWithContext = buildPrompt(
            userPrompt = trimmed,
            projectName = projectName,
            packageName = packageName,
            activeFile = activeFile,
            allFiles = allFiles,
            diagnostics = diagnostics,
        )

        val accumulatedVisibleText = StringBuilder()
        var totalMessageTokens = 0L

        fun addOrUpdateStep(step: AgentStep) {
            val idx = steps.indexOfFirst { it.id == step.id }
            if (idx >= 0) steps[idx] = step else steps.add(step)
            updateAssistantMessage(
                id = assistantMsgId,
                text = accumulatedVisibleText.toString(),
                isStreaming = true,
                steps = steps.toList(),
                codeBlocks = emptyList(),
                totalTokens = totalMessageTokens,
            )
        }

        try {
            when (provider) {
                AiProvider.GEMINI -> runGeminiLoop(
                    activeModel = activeModel,
                    apiKey = resolvedKey,
                    priorHistory = priorHistory,
                    promptWithContext = promptWithContext,
                    assistantMsgId = assistantMsgId,
                    steps = steps,
                    accumulatedVisibleText = accumulatedVisibleText,
                    addOrUpdateStep = ::addOrUpdateStep,
                    toolExecutor = toolExecutor,
                    onUsage = { delta, toolCalls ->
                        totalMessageTokens += delta.totalTokens
                        onUsageRecorded(provider, activeModel, delta, toolCalls, false)
                    },
                )
                AiProvider.OPENAI -> runOpenAiLoop(
                    activeModel = activeModel,
                    apiKey = resolvedKey,
                    priorHistory = priorHistory,
                    promptWithContext = promptWithContext,
                    assistantMsgId = assistantMsgId,
                    steps = steps,
                    accumulatedVisibleText = accumulatedVisibleText,
                    addOrUpdateStep = ::addOrUpdateStep,
                    toolExecutor = toolExecutor,
                    onUsage = { delta, toolCalls ->
                        totalMessageTokens += delta.totalTokens
                        onUsageRecorded(provider, activeModel, delta, toolCalls, false)
                    },
                )
                AiProvider.CLAUDE -> runClaudeLoop(
                    activeModel = activeModel,
                    apiKey = resolvedKey,
                    priorHistory = priorHistory,
                    promptWithContext = promptWithContext,
                    assistantMsgId = assistantMsgId,
                    steps = steps,
                    accumulatedVisibleText = accumulatedVisibleText,
                    addOrUpdateStep = ::addOrUpdateStep,
                    toolExecutor = toolExecutor,
                    onUsage = { delta, toolCalls ->
                        totalMessageTokens += delta.totalTokens
                        onUsageRecorded(provider, activeModel, delta, toolCalls, false)
                    },
                )
            }
            onMessagesChanged(_messages.value)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "$provider request timed out", e)
            val msg = "Timed out waiting for $activeModel after ${TURN_TIMEOUT_MS / 1000}s."
            finalizeFailedSteps(assistantMsgId, steps, accumulatedVisibleText.toString(), msg, totalMessageTokens)
            onUsageRecorded(provider, activeModel, TokenUsageDelta(), 0, true)
            onMessagesChanged(_messages.value)
            _uiState.value = AiUiState.Error(msg)
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Socket timeout talking to $activeModel", e)
            val msg = "Network timeout reaching $activeModel (${e.message ?: "socket timeout"}). Check your internet connection or switch models."
            finalizeFailedSteps(assistantMsgId, steps, accumulatedVisibleText.toString(), "Socket timeout", totalMessageTokens)
            onUsageRecorded(provider, activeModel, TokenUsageDelta(), 0, true)
            onMessagesChanged(_messages.value)
            _uiState.value = AiUiState.Error(msg)
        } catch (e: CancellationException) {
            markCancelled()
            onMessagesChanged(_messages.value)
            throw e
        } catch (e: GeminiApiException) {
            Log.e(TAG, "${provider.shortName} API HTTP ${e.httpCode}: ${e.message}", e)
            finalizeFailedSteps(assistantMsgId, steps, accumulatedVisibleText.toString(), "API error ${e.httpCode}", totalMessageTokens)
            onUsageRecorded(provider, activeModel, TokenUsageDelta(), 0, true)
            onMessagesChanged(_messages.value)
            val overloaded = e.httpCode == 503 || e.httpCode == 429
            val friendly = when (e.httpCode) {
                400, 401, 403 -> "${provider.shortName} API error (${e.httpCode}): ${e.message}"
                404 -> "Model '$activeModel' was not found for this ${provider.shortName} key (404): ${e.message}."
                429 -> "${provider.shortName} rate limit or quota exceeded (429): ${e.message}"
                503 -> "$activeModel is temporarily overloaded (503): ${e.message}"
                else -> "${provider.shortName} API error (${e.httpCode}): ${e.message}"
            }
            _uiState.value = AiUiState.Error(
                message = friendly,
                isRetryable = e.httpCode !in listOf(400, 401, 403),
                isModelOverloaded = overloaded || e.httpCode == 404,
            )
        } catch (e: UnknownHostException) {
            Log.e(TAG, "No network connection", e)
            finalizeFailedSteps(assistantMsgId, steps, accumulatedVisibleText.toString(), "Offline", totalMessageTokens)
            onUsageRecorded(provider, activeModel, TokenUsageDelta(), 0, true)
            onMessagesChanged(_messages.value)
            _uiState.value = AiUiState.Error("No internet connection. Check your network and retry.")
        } catch (e: Exception) {
            Log.e(TAG, "${provider.shortName} generation failed", e)
            val msg = e.localizedMessage?.ifBlank { null } ?: "Failed to reach ${provider.shortName} API."
            finalizeFailedSteps(assistantMsgId, steps, accumulatedVisibleText.toString(), msg, totalMessageTokens)
            onUsageRecorded(provider, activeModel, TokenUsageDelta(), 0, true)
            onMessagesChanged(_messages.value)
            _uiState.value = AiUiState.Error(msg)
        }
    }

    private suspend fun runGeminiLoop(
        activeModel: String,
        apiKey: String,
        priorHistory: List<ChatMessage>,
        promptWithContext: String,
        assistantMsgId: String,
        steps: MutableList<AgentStep>,
        accumulatedVisibleText: StringBuilder,
        addOrUpdateStep: (AgentStep) -> Unit,
        toolExecutor: ProjectToolExecutor,
        onUsage: (TokenUsageDelta, Int) -> Unit,
    ) {
        val conversationContents = buildInitialContents(priorHistory, promptWithContext)
        var completed = false
        var cumulativeTokens = 0L

        for (turn in 0 until MAX_AGENT_TURNS) {
            coroutineContext.ensureActive()
            val turnStart = System.currentTimeMillis()
            val thinkStepId = "think_$turn"
            addOrUpdateStep(
                AgentStep(
                    id = thinkStepId,
                    title = if (turn == 0) "Sending request to $activeModel" else "Analyzing tool results",
                    detail = "Waiting for Gemini...",
                    status = AgentStepStatus.RUNNING,
                ),
            )

            val requestJson = buildRequestPayload(conversationContents, activeModel)
            val turnPrefixLength = accumulatedVisibleText.length
            if (accumulatedVisibleText.isNotEmpty() && !accumulatedVisibleText.endsWith("\n")) {
                accumulatedVisibleText.append("\n\n")
            }
            val textBeforeTurn = accumulatedVisibleText.toString()
            val turnVisibleText = StringBuilder()

            val turnOutcome = withTimeout(TURN_TIMEOUT_MS) {
                callModelWithRetryAndFallback(
                    modelId = activeModel,
                    apiKey = apiKey,
                    requestBody = requestJson,
                    onConnected = {
                        addOrUpdateStep(
                            AgentStep(
                                id = thinkStepId,
                                title = if (turn == 0) "Streaming from $activeModel" else "Analyzing tool results",
                                detail = "Connected, streaming response...",
                                status = AgentStepStatus.RUNNING,
                            ),
                        )
                    },
                    onRetryStatus = { detail ->
                        addOrUpdateStep(
                            AgentStep(
                                id = thinkStepId,
                                title = "Retrying $activeModel",
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
                        updateAssistantMessage(
                            id = assistantMsgId,
                            text = accumulatedVisibleText.toString(),
                            isStreaming = true,
                            steps = steps.toList(),
                            codeBlocks = emptyList(),
                        )
                    },
                )
            }

            if (turnVisibleText.isEmpty()) {
                accumulatedVisibleText.setLength(turnPrefixLength)
            }

            val functionCalls = turnOutcome.functionCalls
            cumulativeTokens += turnOutcome.usage.totalTokens
            onUsage(turnOutcome.usage, functionCalls.size)

            if (functionCalls.isEmpty()) {
                val toolCount = steps.count { it.status == AgentStepStatus.COMPLETED && !it.id.startsWith("think_") }
                addOrUpdateStep(
                    AgentStep(
                        id = thinkStepId,
                        title = if (turn == 0) "Responded via $activeModel" else "Completed task",
                        detail = if (toolCount > 0) "$toolCount tool(s) • $cumulativeTokens tokens" else "$cumulativeTokens tokens",
                        status = AgentStepStatus.COMPLETED,
                        durationMs = System.currentTimeMillis() - turnStart,
                    ),
                )

                val finalText = accumulatedVisibleText.toString().trim()
                if (finalText.isEmpty() && steps.none { !it.id.startsWith("think_") }) {
                    val reasonMsg = when {
                        !turnOutcome.blockReason.isNullOrBlank() -> "Response blocked by Gemini (${turnOutcome.blockReason})."
                        !turnOutcome.finishReason.isNullOrBlank() && turnOutcome.finishReason != "STOP" ->
                            "Gemini returned no text (finishReason: ${turnOutcome.finishReason})."
                        else -> "Gemini returned an empty response."
                    }
                    finalizeFailedSteps(assistantMsgId, steps, "", reasonMsg, cumulativeTokens)
                    _uiState.value = AiUiState.Error(reasonMsg)
                } else {
                    val displayText = finalText.ifBlank { "All requested actions finished." }
                    updateAssistantMessage(
                        id = assistantMsgId,
                        text = displayText,
                        isStreaming = false,
                        steps = steps.toList(),
                        codeBlocks = extractCodeBlocks(displayText),
                        totalTokens = cumulativeTokens,
                    )
                    _uiState.value = AiUiState.Idle
                }
                completed = true
                break
            }

            addOrUpdateStep(
                AgentStep(
                    id = thinkStepId,
                    title = "Planned ${functionCalls.size} action(s)",
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
            updateAssistantMessage(
                id = assistantMsgId,
                text = summary,
                isStreaming = false,
                steps = steps.toList(),
                codeBlocks = extractCodeBlocks(summary),
            )
            _uiState.value = AiUiState.Idle
        }
    }

    private suspend fun runOpenAiLoop(
        activeModel: String,
        apiKey: String,
        priorHistory: List<ChatMessage>,
        promptWithContext: String,
        assistantMsgId: String,
        steps: MutableList<AgentStep>,
        accumulatedVisibleText: StringBuilder,
        addOrUpdateStep: (AgentStep) -> Unit,
        toolExecutor: ProjectToolExecutor,
        onUsage: (TokenUsageDelta, Int) -> Unit,
    ) {
        val openAiMessages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", buildSystemInstruction(activeModel)))
            priorHistory.forEach { msg ->
                put(JSONObject().put("role", if (msg.isUser) "user" else "assistant").put("content", msg.text))
            }
            put(JSONObject().put("role", "user").put("content", promptWithContext))
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
                    title = if (turn == 0) "Sending to ChatGPT ($activeModel)" else "Analyzing tool output",
                    detail = "Waiting for OpenAI...",
                    status = AgentStepStatus.RUNNING,
                ),
            )

            val turnPrefixLen = accumulatedVisibleText.length
            if (accumulatedVisibleText.isNotEmpty() && !accumulatedVisibleText.endsWith("\n")) {
                accumulatedVisibleText.append("\n\n")
            }
            val textBeforeTurn = accumulatedVisibleText.toString()
            val turnText = StringBuilder()

            val outcome = withTimeout(TURN_TIMEOUT_MS) {
                ProviderApiClient.streamOpenAi(
                    apiKey = apiKey,
                    modelId = activeModel,
                    messages = openAiMessages,
                    onConnected = {
                        addOrUpdateStep(
                            AgentStep(
                                id = thinkStepId,
                                title = "Streaming from $activeModel",
                                detail = "Connected...",
                                status = AgentStepStatus.RUNNING,
                            ),
                        )
                    },
                    onTextDelta = { delta ->
                        turnText.append(delta)
                        accumulatedVisibleText.clear()
                        accumulatedVisibleText.append(textBeforeTurn)
                        accumulatedVisibleText.append(turnText)
                        updateAssistantMessage(
                            id = assistantMsgId,
                            text = accumulatedVisibleText.toString(),
                            isStreaming = true,
                            steps = steps.toList(),
                            codeBlocks = emptyList(),
                            totalTokens = totalTokens,
                        )
                    },
                )
            }

            if (turnText.isEmpty()) accumulatedVisibleText.setLength(turnPrefixLen)
            totalTokens += outcome.usage.totalTokens
            onUsage(outcome.usage, outcome.openAiToolCalls.size)

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
                updateAssistantMessage(
                    id = assistantMsgId,
                    text = finalText,
                    isStreaming = false,
                    steps = steps.toList(),
                    codeBlocks = extractCodeBlocks(finalText),
                    totalTokens = totalTokens,
                )
                _uiState.value = AiUiState.Idle
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
            updateAssistantMessage(assistantMsgId, summary, false, steps.toList(), extractCodeBlocks(summary), totalTokens)
            _uiState.value = AiUiState.Idle
        }
    }

    private suspend fun runClaudeLoop(
        activeModel: String,
        apiKey: String,
        priorHistory: List<ChatMessage>,
        promptWithContext: String,
        assistantMsgId: String,
        steps: MutableList<AgentStep>,
        accumulatedVisibleText: StringBuilder,
        addOrUpdateStep: (AgentStep) -> Unit,
        toolExecutor: ProjectToolExecutor,
        onUsage: (TokenUsageDelta, Int) -> Unit,
    ) {
        val systemText = buildSystemInstruction(activeModel)
        val claudeMessages = JSONArray().apply {
            priorHistory.forEach { msg ->
                put(JSONObject().put("role", if (msg.isUser) "user" else "assistant").put("content", msg.text))
            }
            put(JSONObject().put("role", "user").put("content", promptWithContext))
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
                    title = if (turn == 0) "Sending to Claude ($activeModel)" else "Analyzing tool results",
                    detail = "Waiting for Anthropic...",
                    status = AgentStepStatus.RUNNING,
                ),
            )

            val turnPrefixLen = accumulatedVisibleText.length
            if (accumulatedVisibleText.isNotEmpty() && !accumulatedVisibleText.endsWith("\n")) {
                accumulatedVisibleText.append("\n\n")
            }
            val textBeforeTurn = accumulatedVisibleText.toString()
            val turnText = StringBuilder()

            val outcome = withTimeout(TURN_TIMEOUT_MS) {
                ProviderApiClient.streamClaude(
                    apiKey = apiKey,
                    modelId = activeModel,
                    systemInstruction = systemText,
                    messages = claudeMessages,
                    onConnected = {
                        addOrUpdateStep(
                            AgentStep(
                                id = thinkStepId,
                                title = "Streaming from $activeModel",
                                detail = "Connected...",
                                status = AgentStepStatus.RUNNING,
                            ),
                        )
                    },
                    onTextDelta = { delta ->
                        turnText.append(delta)
                        accumulatedVisibleText.clear()
                        accumulatedVisibleText.append(textBeforeTurn)
                        accumulatedVisibleText.append(turnText)
                        updateAssistantMessage(
                            id = assistantMsgId,
                            text = accumulatedVisibleText.toString(),
                            isStreaming = true,
                            steps = steps.toList(),
                            codeBlocks = emptyList(),
                            totalTokens = totalTokens,
                        )
                    },
                )
            }

            if (turnText.isEmpty()) accumulatedVisibleText.setLength(turnPrefixLen)
            totalTokens += outcome.usage.totalTokens
            onUsage(outcome.usage, outcome.claudeToolUses.size)

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
                updateAssistantMessage(
                    id = assistantMsgId,
                    text = finalText,
                    isStreaming = false,
                    steps = steps.toList(),
                    codeBlocks = extractCodeBlocks(finalText),
                    totalTokens = totalTokens,
                )
                _uiState.value = AiUiState.Idle
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
            updateAssistantMessage(assistantMsgId, summary, false, steps.toList(), extractCodeBlocks(summary), totalTokens)
            _uiState.value = AiUiState.Idle
        }
    }

    private suspend fun callModelWithRetryAndFallback(
        modelId: String,
        apiKey: String,
        requestBody: String,
        onConnected: () -> Unit,
        onRetryStatus: (String) -> Unit,
        onTextDelta: (String) -> Unit,
    ): TurnStreamOutcome {
        val backoffMs = longArrayOf(1_500L, 3_000L, 5_000L)
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                return callModelWithSseFallback(
                    modelId = modelId,
                    apiKey = apiKey,
                    requestBody = requestBody,
                    onConnected = onConnected,
                    onFallback = { onRetryStatus("SSE timed out, trying standard request...") },
                    onTextDelta = onTextDelta,
                )
            } catch (e: GeminiApiException) {
                if (!isTransientHttpStatus(e.httpCode) || attempt >= backoffMs.size) throw e
                val waitMs = backoffMs[attempt++]
                onRetryStatus("High demand (${e.httpCode}) — retrying in ${waitMs / 1000.0}s ($attempt/${backoffMs.size})...")
                delay(waitMs)
            }
        }
    }

    private suspend fun callModelWithSseFallback(
        modelId: String,
        apiKey: String,
        requestBody: String,
        onConnected: () -> Unit,
        onFallback: () -> Unit,
        onTextDelta: (String) -> Unit,
    ): TurnStreamOutcome {
        var receivedAnyBytes = false
        return try {
            streamGenerateContent(
                modelId = modelId,
                apiKey = apiKey,
                requestBody = requestBody,
                onConnected = {
                    receivedAnyBytes = true
                    onConnected()
                },
                onTextDelta = { delta ->
                    receivedAnyBytes = true
                    onTextDelta(delta)
                },
            )
        } catch (e: SocketTimeoutException) {
            if (receivedAnyBytes) throw e
            Log.w(TAG, "SSE timed out before receiving response; retrying non-streaming generateContent", e)
            onFallback()
            generateContentUnary(modelId, apiKey, requestBody, onTextDelta)
        }
    }

    private fun finalizeFailedSteps(
        assistantMsgId: String,
        steps: MutableList<AgentStep>,
        currentText: String,
        failureDetail: String,
        totalTokens: Long,
    ) {
        val updated = steps.map { step ->
            if (step.status == AgentStepStatus.RUNNING) {
                step.copy(status = AgentStepStatus.FAILED, detail = failureDetail)
            } else {
                step
            }
        }
        updateAssistantMessage(
            id = assistantMsgId,
            text = currentText,
            isStreaming = false,
            steps = updated,
            codeBlocks = extractCodeBlocks(currentText),
            totalTokens = totalTokens,
        )
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
        val path = args.optString("path").ifBlank { "file" }
        return when (name) {
            "list_files" -> "Listing project files"
            "read_file" -> "Reading $path"
            "write_file" -> "Writing $path"
            "edit_file" -> "Editing $path"
            "delete_file" -> "Deleting $path"
            "search_files" -> "Searching '${args.optString("query").take(28)}'"
            "build_apk" -> if (args.optBoolean("release", false)) "Building Release APK" else "Building Debug APK"
            "build_aab" -> "Building Signed App Bundle (.aab)"
            else -> "Running $name"
        }
    }

    private suspend fun streamGenerateContent(
        modelId: String,
        apiKey: String,
        requestBody: String,
        onConnected: () -> Unit,
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
                throw GeminiApiException(status, parsedMsg)
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
            readTimeout = READ_TIMEOUT_MS
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
                throw GeminiApiException(status, parsedMsg)
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
            )
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }

    internal fun buildInitialContents(
        history: List<ChatMessage>,
        latestPromptWithContext: String,
    ): JSONArray {
        val contentsArray = JSONArray()
        history.forEach { msg ->
            contentsArray.put(
                JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "model")
                    put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
                },
            )
        }
        contentsArray.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", latestPromptWithContext)))
            },
        )
        return contentsArray
    }

    private fun buildSystemInstruction(modelId: String): String = """
        You are an autonomous coding agent powered by $modelId inside DroidForge Studio, an offline-capable Android Java IDE, APK/AAB builder, and live UI previewer.

        Execution rules:
        - Act autonomously WITHOUT asking the user for permission before reading, creating, editing, or deleting files, or building the APK/AAB.
        - You have full read and write access to EVERY file in the active project via your tools: list_files, read_file, write_file, edit_file, delete_file, search_files, build_apk, and build_aab.
        - Always inspect existing files with read_file / list_files before modifying unfamiliar code.
        - Prefer edit_file for targeted changes and write_file for creating new files or full rewrites. Changes are immediately saved and opened in the user's editor and live Emulator preview.
        - After making code or XML changes, call build_apk to verify compilation (ECJ + DEX + ApkSigner). If build_apk reports compiler errors, fix them immediately and re-run build_apk until it succeeds.
        - Do NOT use AndroidX, Jetpack Compose, Kotlin syntax, or external Gradle dependencies. Use classic Android SDK APIs (android.app.Activity, android.os.Bundle, android.view.*, android.widget.*, android.graphics.*, android.content.*, android.webkit.*, java.*).
        - Compiles Java 8 (.java), AndroidManifest.xml, res/values/strings.xml, and res/values/colors.xml into R.java and resources.arsc / resources.pb.
        - In your final summary, state clearly what files you changed and whether the build succeeded.
    """.trimIndent()

    internal fun buildRequestPayload(
        contents: JSONArray,
        modelId: String = GEMINI_MODEL_ID,
    ): String {
        return JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", buildSystemInstruction(modelId)))),
            )
            put("contents", contents)
            put("tools", buildToolDeclarations())
            put("generationConfig", JSONObject().put("temperature", 0.3))
        }.toString()
    }

    private fun buildToolDeclarations(): JSONArray {
        val declarations = JSONArray().apply {
            ProviderApiClient.buildSharedToolSpecs().forEach { (name, desc, schema) ->
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
                put(
                    JSONObject()
                        .put("name", name)
                        .put("description", desc)
                        .put("parameters", upperSchema),
                )
            }
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", declarations))
    }

    private fun buildPrompt(
        userPrompt: String,
        projectName: String,
        packageName: String,
        activeFile: ProjectFile?,
        allFiles: List<ProjectFile>,
        diagnostics: List<BuildDiagnostic>,
    ): String = buildString {
        appendLine("Project: $projectName (package: $packageName)")
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
        appendLine()
        appendLine("User request:")
        appendLine(userPrompt)
    }

    private fun updateAssistantMessage(
        id: String,
        text: String,
        isStreaming: Boolean,
        steps: List<AgentStep>,
        codeBlocks: List<SuggestedCodeBlock>,
        totalTokens: Long = 0L,
    ) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) {
                msg.copy(
                    text = text,
                    isStreaming = isStreaming,
                    steps = steps,
                    codeBlocks = codeBlocks,
                    totalTokens = if (totalTokens > 0L) totalTokens else msg.totalTokens,
                )
            } else {
                msg
            }
        }
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
            return httpCode == 500 || httpCode == 502 || httpCode == 503 || httpCode == 504
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
