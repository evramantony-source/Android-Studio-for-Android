package com.example.ai

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.example.model.AiAttachment
import com.example.model.JavaVersionOption
import com.example.model.ProjectLanguage
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

private const val FIREBASE_AI_TAG = "FirebaseAiAgentClient"
private const val FIREBASE_MAX_TURNS = 12
private const val FIREBASE_TURN_TIMEOUT_MS = 95_000L
private const val FIREBASE_RESCUE_TIMEOUT_MS = 60_000L

internal class FirebaseAiAgentClient {

    suspend fun runAgentLoop(
        requestedModel: String,
        thinkingMode: AiThinkingMode,
        projectLanguage: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
        includeGitHubTools: Boolean,
        imageAttachments: List<AiAttachment>,
        priorHistory: List<ChatMessage>,
        promptWithContext: String,
        systemInstructionText: String,
        steps: MutableList<AgentStep>,
        accumulatedVisibleText: StringBuilder,
        addOrUpdateStep: (AgentStep) -> Unit,
        onStreamText: (String, String) -> Unit,
        onFinalizeSuccess: (String, List<AgentStep>, Long, String) -> Unit,
        onFinalizeFailure: (String, AiUiState.Error) -> Unit,
        toolExecutor: ProjectToolExecutor,
        executeTool: suspend (GeminiCodeAssistant.ParsedFunctionCall, ProjectToolExecutor) -> AgentToolResult,
        describeTool: (String, JSONObject) -> String,
        onUsage: (String, TokenUsageDelta, Int) -> Unit,
    ) {
        val candidates = buildFirebaseModelCandidates(requestedModel)
        var activeModel = candidates.first()
        val historyContents = buildHistoryContents(priorHistory)
        val toolDeclarations = buildFirebaseToolDeclarations(includeGitHubTools)

        val initialUserContent = buildInitialUserContent(promptWithContext, imageAttachments)
        var cumulativeTokens = 0L
        var completed = false

        // Initialize model only when first turn begins
        var modelIdx = 0
        var chat = createGenerativeModel(
            modelName = activeModel,
            systemInstructionText = systemInstructionText,
            tools = listOf(Tool.functionDeclarations(toolDeclarations)),
        ).startChat(history = historyContents)
        var nextTurnInput: Content = initialUserContent

        for (turn in 0 until FIREBASE_MAX_TURNS) {
            coroutineContext.ensureActive()
            if (turn > 0) delay(250L)

            val turnStart = System.currentTimeMillis()
            val thinkStepId = "think_$turn"
            addOrUpdateStep(
                AgentStep(
                    id = thinkStepId,
                    title = if (turn == 0) "Connecting via Firebase AI ($activeModel)" else "Analyzing tool results ($activeModel)",
                    detail = "${thinkingMode.badge} • Streaming via Firebase AI Logic...",
                    status = AgentStepStatus.RUNNING,
                ),
            )

            val turnPrefixLen = accumulatedVisibleText.length
            if (accumulatedVisibleText.isNotEmpty() && !accumulatedVisibleText.endsWith("\n")) {
                accumulatedVisibleText.append("\n\n")
            }
            val textBeforeTurn = accumulatedVisibleText.toString()
            val turnText = StringBuilder()
            val collectedCalls = mutableListOf<FunctionCallPart>()
            var turnUsage = TokenUsageDelta()
            var turnSucceeded = false
            var lastTurnError: Throwable? = null

            // Retry across Firebase AI models if Turn 0 fails before receiving function calls/text
            while (!turnSucceeded) {
                coroutineContext.ensureActive()
                try {
                    withTimeout(FIREBASE_TURN_TIMEOUT_MS) {
                        val startedAt = System.currentTimeMillis()
                        chat.sendMessageStream(nextTurnInput).collect { chunk ->
                            val chunkText = chunk.text.orEmpty()
                            if (chunkText.isNotEmpty()) {
                                turnText.append(chunkText)
                                accumulatedVisibleText.clear()
                                accumulatedVisibleText.append(textBeforeTurn)
                                accumulatedVisibleText.append(turnText)
                                onStreamText(accumulatedVisibleText.toString(), activeModel)
                            }
                            if (chunk.functionCalls.isNotEmpty()) {
                                collectedCalls.addAll(chunk.functionCalls)
                            }
                            chunk.usageMetadata?.let { u ->
                                val inputTok = u.promptTokenCount.toLong()
                                val outputTok = (u.candidatesTokenCount ?: 0).toLong()
                                val thoughtTok = u.thoughtsTokenCount.toLong()
                                val totalTok = u.totalTokenCount.toLong().takeIf { it > 0L }
                                    ?: (inputTok + outputTok + thoughtTok)
                                turnUsage = TokenUsageDelta(
                                    inputTokens = inputTok,
                                    outputTokens = outputTok,
                                    thinkingTokens = thoughtTok,
                                    totalTokens = totalTok,
                                    latencyMs = System.currentTimeMillis() - startedAt,
                                )
                            }
                        }
                    }
                    turnSucceeded = true
                } catch (e: CancellationException) {
                    if (e !is TimeoutCancellationException) throw e
                    lastTurnError = e
                    Log.w(FIREBASE_AI_TAG, "Firebase AI timed out on $activeModel (turn $turn)", e)
                } catch (e: Exception) {
                    lastTurnError = e
                    Log.w(FIREBASE_AI_TAG, "Firebase AI call failed on $activeModel (turn $turn): ${e.message}", e)
                }

                if (!turnSucceeded) {
                    if (turn == 0 && turnText.isEmpty() && collectedCalls.isEmpty() && modelIdx + 1 < candidates.size) {
                        modelIdx++
                        activeModel = candidates[modelIdx]
                        addOrUpdateStep(
                            AgentStep(
                                id = thinkStepId,
                                title = "Switching Firebase model",
                                detail = "Trying $activeModel...",
                                status = AgentStepStatus.RUNNING,
                            ),
                        )
                        val switchedModel = createGenerativeModel(
                            modelName = activeModel,
                            systemInstructionText = systemInstructionText,
                            tools = listOf(Tool.functionDeclarations(toolDeclarations)),
                        )
                        chat = switchedModel.startChat(history = historyContents)
                        delay(300L)
                    } else {
                        break
                    }
                }
            }

            if (!turnSucceeded) {
                // Lightweight non-tool Firebase AI rescue pass
                val rescueResult = tryFirebaseNonToolRescue(
                    candidates = candidates,
                    promptWithContext = promptWithContext,
                    imageAttachments = imageAttachments,
                    projectLanguage = projectLanguage,
                    javaVersion = javaVersion,
                    minSdkVersion = minSdkVersion,
                    onStatus = { title, detail ->
                        addOrUpdateStep(AgentStep(id = thinkStepId, title = title, detail = detail, status = AgentStepStatus.RUNNING))
                    },
                )
                if (rescueResult != null) {
                    val (rescueModel, rescueText, rescueUsage) = rescueResult
                    activeModel = rescueModel
                    cumulativeTokens += rescueUsage.totalTokens
                    onUsage(activeModel, rescueUsage, 0)

                    val blocks = GeminiCodeAssistant.extractCodeBlocks(rescueText).filter { !it.suggestedPath.isNullOrBlank() }
                    var anyWritten = false
                    for ((bIdx, block) in blocks.withIndex()) {
                        coroutineContext.ensureActive()
                        val targetPath = block.suggestedPath!!.trim()
                        val writeId = "fb_rescue_write_${turn}_$bIdx"
                        val writeStart = System.currentTimeMillis()
                        addOrUpdateStep(
                            AgentStep(
                                id = writeId,
                                title = "Saving $targetPath",
                                detail = "Applying generated file...",
                                status = AgentStepStatus.RUNNING,
                                toolName = "write_file",
                            ),
                        )
                        val writeRes = toolExecutor.writeFile(targetPath, block.code)
                        if (writeRes.success) anyWritten = true
                        addOrUpdateStep(
                            AgentStep(
                                id = writeId,
                                title = "Saved $targetPath",
                                detail = writeRes.stepDetail,
                                status = if (writeRes.success) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED,
                                expandedOutput = writeRes.detailedOutput,
                                durationMs = System.currentTimeMillis() - writeStart,
                                toolName = "write_file",
                            ),
                        )
                    }
                    if (anyWritten) {
                        val buildId = "fb_rescue_build_$turn"
                        val buildStart = System.currentTimeMillis()
                        addOrUpdateStep(
                            AgentStep(
                                id = buildId,
                                title = "Verifying APK build",
                                detail = "Compiling updated project...",
                                status = AgentStepStatus.RUNNING,
                                toolName = "build_apk",
                            ),
                        )
                        val buildRes = toolExecutor.buildApk(isRelease = false)
                        addOrUpdateStep(
                            AgentStep(
                                id = buildId,
                                title = "Build verification",
                                detail = buildRes.stepDetail,
                                status = if (buildRes.success) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED,
                                expandedOutput = buildRes.detailedOutput,
                                durationMs = System.currentTimeMillis() - buildStart,
                                toolName = "build_apk",
                            ),
                        )
                    }

                    accumulatedVisibleText.clear()
                    accumulatedVisibleText.append(textBeforeTurn)
                    accumulatedVisibleText.append(rescueText)
                    addOrUpdateStep(
                        AgentStep(
                            id = thinkStepId,
                            title = "Responded via Firebase AI ($activeModel)",
                            detail = "${rescueUsage.totalTokens} tokens",
                            status = AgentStepStatus.COMPLETED,
                            durationMs = System.currentTimeMillis() - turnStart,
                        ),
                    )
                    onFinalizeSuccess(accumulatedVisibleText.toString().trim(), steps.toList(), cumulativeTokens, activeModel)
                    completed = true
                    break
                }

                val completedTools = steps.any { it.status == AgentStepStatus.COMPLETED && !it.id.startsWith("think_") }
                if (completedTools) {
                    addOrUpdateStep(
                        AgentStep(
                            id = thinkStepId,
                            title = "Completed edits via Firebase AI",
                            detail = lastTurnError?.localizedMessage ?: "Follow-up summary interrupted",
                            status = AgentStepStatus.COMPLETED,
                            durationMs = System.currentTimeMillis() - turnStart,
                        ),
                    )
                    val partial = accumulatedVisibleText.toString().trim().ifBlank {
                        "Completed your project file edits. Tap **Build / Run** to test."
                    }
                    onFinalizeSuccess(partial, steps.toList(), cumulativeTokens, activeModel)
                    completed = true
                    break
                }

                throw (lastTurnError ?: IllegalStateException("Firebase AI Logic request failed."))
            }

            if (turnText.isEmpty() && accumulatedVisibleText.length == textBeforeTurn.length) {
                accumulatedVisibleText.setLength(turnPrefixLen)
            }

            cumulativeTokens += turnUsage.totalTokens
            onUsage(activeModel, turnUsage, collectedCalls.size)

            if (collectedCalls.isEmpty()) {
                val toolCount = steps.count { it.status == AgentStepStatus.COMPLETED && !it.id.startsWith("think_") }
                addOrUpdateStep(
                    AgentStep(
                        id = thinkStepId,
                        title = if (turn == 0) "Responded via Firebase AI ($activeModel)" else "Completed via Firebase AI ($activeModel)",
                        detail = if (toolCount > 0) "$toolCount tool(s) • $cumulativeTokens tokens" else "$cumulativeTokens tokens",
                        status = AgentStepStatus.COMPLETED,
                        durationMs = System.currentTimeMillis() - turnStart,
                    ),
                )
                val finalText = accumulatedVisibleText.toString().trim()
                if (finalText.isEmpty() && steps.none { !it.id.startsWith("think_") }) {
                    onFinalizeFailure("Firebase AI returned an empty response.", AiUiState.Error("Firebase AI returned an empty response."))
                } else {
                    onFinalizeSuccess(finalText.ifBlank { "All requested actions finished." }, steps.toList(), cumulativeTokens, activeModel)
                }
                completed = true
                break
            }

            addOrUpdateStep(
                AgentStep(
                    id = thinkStepId,
                    title = "Planned ${collectedCalls.size} action(s) • Firebase AI",
                    detail = collectedCalls.joinToString(", ") { it.name },
                    status = AgentStepStatus.COMPLETED,
                    durationMs = System.currentTimeMillis() - turnStart,
                ),
            )

            val responseParts = mutableListOf<FunctionResponsePart>()
            for ((idx, fc) in collectedCalls.withIndex()) {
                coroutineContext.ensureActive()
                val argsJson = jsonMapToOrgJsonObject(fc.args)
                val stepId = "tool_${turn}_$idx"
                val stepTitle = describeTool(fc.name, argsJson)
                val stepStart = System.currentTimeMillis()

                addOrUpdateStep(
                    AgentStep(
                        id = stepId,
                        title = stepTitle,
                        detail = "Executing ${fc.name}...",
                        status = AgentStepStatus.RUNNING,
                        toolName = fc.name,
                    ),
                )

                val toolResult = executeTool(
                    GeminiCodeAssistant.ParsedFunctionCall(fc.name, argsJson),
                    toolExecutor,
                )
                addOrUpdateStep(
                    AgentStep(
                        id = stepId,
                        title = stepTitle,
                        detail = toolResult.stepDetail,
                        status = if (toolResult.success) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED,
                        expandedOutput = toolResult.detailedOutput,
                        durationMs = System.currentTimeMillis() - stepStart,
                        toolName = fc.name,
                    ),
                )
                responseParts.add(FunctionResponsePart(fc.name, orgJsonObjectToKotlinx(toolResult.payload)))
            }

            nextTurnInput = content("user") {
                responseParts.forEach { part(it) }
            }
        }

        if (!completed) {
            val summary = accumulatedVisibleText.toString().trim()
                .ifBlank { "Stopped after reaching the maximum autonomous step limit ($FIREBASE_MAX_TURNS turns)." }
            onFinalizeSuccess(summary, steps.toList(), cumulativeTokens, activeModel)
        }
    }

    private fun createGenerativeModel(
        modelName: String,
        systemInstructionText: String,
        tools: List<Tool>? = null,
    ): GenerativeModel {
        return Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                temperature = 0.3f
            },
            systemInstruction = content("system") {
                text(systemInstructionText)
            },
            tools = tools,
        )
    }

    private suspend fun tryFirebaseNonToolRescue(
        candidates: List<String>,
        promptWithContext: String,
        imageAttachments: List<AiAttachment>,
        projectLanguage: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
        onStatus: (String, String) -> Unit,
    ): Triple<String, String, TokenUsageDelta>? {
        val rescuePrompt = buildString {
            appendLine("Direct code generation mode (${projectLanguage.displayName}, Java ${javaVersion.badge}, API $minSdkVersion).")
            appendLine("Provide complete runnable code blocks labeled `// file: relative/path.ext`.")
            appendLine()
            append(promptWithContext.take(6_500))
        }
        val rescueInput = buildInitialUserContent(rescuePrompt, imageAttachments)
        val systemText = "You are an Android coding assistant inside DroidForge Studio. Answer directly with concise Markdown and complete runnable code blocks marked `// file: <path>`."

        for (candidate in candidates) {
            coroutineContext.ensureActive()
            onStatus("Direct Firebase AI ($candidate)", "Generating direct response...")
            val model = createGenerativeModel(
                modelName = candidate,
                systemInstructionText = systemText,
                tools = null,
            )
            val textBuf = StringBuilder()
            var usage = TokenUsageDelta()
            val ok = try {
                withTimeout(FIREBASE_RESCUE_TIMEOUT_MS) {
                    model.generateContentStream(rescueInput).collect { chunk ->
                        chunk.text?.let { textBuf.append(it) }
                        chunk.usageMetadata?.let { u ->
                            val inputTok = u.promptTokenCount.toLong()
                            val outputTok = (u.candidatesTokenCount ?: 0).toLong()
                            val thoughtTok = u.thoughtsTokenCount.toLong()
                            val totalTok = u.totalTokenCount.toLong().takeIf { it > 0L }
                                ?: (inputTok + outputTok + thoughtTok)
                            usage = TokenUsageDelta(
                                inputTokens = inputTok,
                                outputTokens = outputTok,
                                thinkingTokens = thoughtTok,
                                totalTokens = totalTok,
                            )
                        }
                    }
                }
                true
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                false
            } catch (e: Exception) {
                Log.w(FIREBASE_AI_TAG, "Firebase rescue failed on $candidate: ${e.message}", e)
                false
            }
            if (ok && textBuf.isNotBlank()) {
                return Triple(candidate, textBuf.toString(), usage)
            }
        }
        return null
    }

    private fun buildHistoryContents(priorHistory: List<ChatMessage>): List<Content> {
        return priorHistory.takeLast(6).map { msg ->
            val clipped = if (msg.text.length > 1_800) {
                msg.text.take(1_800) + "\n... [earlier message truncated]"
            } else {
                msg.text
            }
            content(if (msg.isUser) "user" else "model") {
                text(clipped)
            }
        }
    }

    private fun buildInitialUserContent(
        promptWithContext: String,
        imageAttachments: List<AiAttachment>,
    ): Content {
        return content("user") {
            text(promptWithContext)
            imageAttachments.forEach { att ->
                if (att.isImage && att.base64Data.isNotBlank()) {
                    val bytes = runCatching { Base64.decode(att.base64Data, Base64.DEFAULT) }.getOrNull()
                    val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                    if (bmp != null) {
                        image(bmp)
                    }
                }
            }
        }
    }

    private fun buildFirebaseToolDeclarations(includeGitHubTools: Boolean): List<FunctionDeclaration> {
        return ProviderApiClient.buildSharedToolSpecs()
            .filter { (name, _, _) -> includeGitHubTools || !name.startsWith("github_") }
            .map { (name, desc, schema) ->
                val rawProps = schema.optJSONObject("properties")
                val requiredArr = schema.optJSONArray("required")
                val requiredSet = buildSet {
                    if (requiredArr != null) {
                        for (i in 0 until requiredArr.length()) {
                            add(requiredArr.optString(i))
                        }
                    }
                }
                val params = linkedMapOf<String, Schema>()
                val optionalParams = mutableListOf<String>()
                if (rawProps != null) {
                    val keys = rawProps.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val propObj = rawProps.optJSONObject(key) ?: continue
                        val propDesc = propObj.optString("description").ifBlank { key }
                        val propSchema = when (propObj.optString("type", "string").lowercase()) {
                            "boolean" -> Schema.boolean(description = propDesc)
                            "integer" -> Schema.integer(description = propDesc)
                            "number" -> Schema.double(description = propDesc)
                            else -> Schema.string(description = propDesc)
                        }
                        params[key] = propSchema
                        if (key !in requiredSet) {
                            optionalParams.add(key)
                        }
                    }
                }
                FunctionDeclaration(
                    name = name,
                    description = desc,
                    parameters = params,
                    optionalParameters = optionalParams,
                )
            }
    }

    private fun jsonMapToOrgJsonObject(map: Map<String, JsonElement>): JSONObject {
        val out = JSONObject()
        map.forEach { (k, v) -> out.put(k, jsonElementToOrgValue(v)) }
        return out
    }

    private fun jsonElementToOrgValue(el: JsonElement): Any {
        return when (el) {
            is JsonNull -> JSONObject.NULL
            is JsonPrimitive -> {
                if (el.isString) {
                    el.content
                } else {
                    el.booleanOrNull
                        ?: el.contentOrNull?.toLongOrNull()
                        ?: el.contentOrNull?.toDoubleOrNull()
                        ?: el.content
                }
            }
            is JsonObject -> jsonMapToOrgJsonObject(el)
            is JsonArray -> JSONArray().apply {
                el.forEach { put(jsonElementToOrgValue(it)) }
            }
        }
    }

    private fun orgJsonObjectToKotlinx(obj: JSONObject): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = orgValueToJsonElement(obj.opt(k))
        }
        return JsonObject(map)
    }

    private fun orgValueToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null, JSONObject.NULL -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is JSONObject -> orgJsonObjectToKotlinx(value)
            is JSONArray -> JsonArray(
                (0 until value.length()).map { orgValueToJsonElement(value.opt(it)) },
            )
            else -> JsonPrimitive(value.toString())
        }
    }

    companion object {
        private val FIREBASE_MODEL_IDS = setOf(
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-4-argon",
        )

        fun isFirebaseConfigured(): Boolean {
            return runCatching { com.google.firebase.FirebaseApp.getInstance() }.isSuccess
        }

        fun isFirebaseAiLogicModel(modelId: String): Boolean {
            return modelId.trim().removePrefix("models/").lowercase() in FIREBASE_MODEL_IDS
        }

        fun buildFirebaseModelCandidates(requestedModel: String): List<String> {
            val clean = requestedModel.trim().removePrefix("models/").lowercase()
            val ordered = linkedSetOf<String>()
            if (clean in FIREBASE_MODEL_IDS) {
                ordered.add(clean)
            }
            ordered.add("gemini-3.8-flash")
            ordered.add("gemini-3.5-flash-lite")
            return ordered.toList()
        }
    }
}
