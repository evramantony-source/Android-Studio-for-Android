package com.example.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

internal data class OpenAiToolCallRecord(
    val id: String,
    val name: String,
    val argsJson: String,
)

internal data class ClaudeToolUseRecord(
    val id: String,
    val name: String,
    val input: JSONObject,
)

internal data class ExternalTurnOutcome(
    val text: String,
    val openAiToolCalls: List<OpenAiToolCallRecord> = emptyList(),
    val claudeToolUses: List<ClaudeToolUseRecord> = emptyList(),
    val claudeAssistantBlocks: JSONArray = JSONArray(),
    val usage: TokenUsageDelta = TokenUsageDelta(),
    val finishReason: String? = null,
)

internal object ProviderApiClient {

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 180_000

    fun buildSharedToolSpecs(): List<Triple<String, String, JSONObject>> {
        fun strProp(desc: String) = JSONObject().put("type", "string").put("description", desc)
        fun boolProp(desc: String) = JSONObject().put("type", "boolean").put("description", desc)

        return listOf(
            Triple(
                "list_files",
                "Lists all file paths in the active Android project.",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            ),
            Triple(
                "read_file",
                "Reads the complete text content of any file in the active project.",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("path", strProp("Relative file path inside the project.")))
                    .put("required", JSONArray().put("path")),
            ),
            Triple(
                "write_file",
                "Creates a new file or completely overwrites an existing file in the project immediately without confirmation.",
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("path", strProp("Relative file path to create or overwrite."))
                            .put("content", strProp("Complete file text content.")),
                    )
                    .put("required", JSONArray().put("path").put("content")),
            ),
            Triple(
                "edit_file",
                "Replaces an exact text snippet inside an existing project file immediately without confirmation.",
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("path", strProp("Relative file path to edit."))
                            .put("old_text", strProp("Exact existing text snippet to replace."))
                            .put("new_text", strProp("Replacement text snippet.")),
                    )
                    .put("required", JSONArray().put("path").put("old_text").put("new_text")),
            ),
            Triple(
                "delete_file",
                "Deletes a file from the active project immediately without confirmation.",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("path", strProp("Relative file path to delete.")))
                    .put("required", JSONArray().put("path")),
            ),
            Triple(
                "search_files",
                "Searches for text across all files in the active project and returns matching lines.",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("query", strProp("Case-insensitive text query.")))
                    .put("required", JSONArray().put("query")),
            ),
            Triple(
                "build_apk",
                "Compiles the active project into a signed Debug or Release APK and returns compiler diagnostics.",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("release", boolProp("Set true for a release APK, false for debug APK."))),
            ),
            Triple(
                "build_aab",
                "Compiles and signs an Android App Bundle (.aab) ready for Play Store distribution and returns diagnostics.",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            ),
        )
    }

    suspend fun streamOpenAi(
        apiKey: String,
        modelId: String,
        messages: JSONArray,
        onConnected: () -> Unit,
        onTextDelta: (String) -> Unit,
    ): ExternalTurnOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val tools = JSONArray().apply {
            buildSharedToolSpecs().forEach { (name, desc, schema) ->
                put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", name)
                                .put("description", desc)
                                .put("parameters", schema),
                        ),
                )
            }
        }

        val bodyObj = JSONObject()
            .put("model", modelId)
            .put("messages", messages)
            .put("tools", tools)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))

        val isFixedTempReasoning = modelId.startsWith("o1") ||
            modelId.startsWith("o3") ||
            modelId.startsWith("o4") ||
            modelId.startsWith("gpt-5") ||
            modelId.startsWith("gpt-6")
        if (!isFixedTempReasoning) {
            bodyObj.put("temperature", 0.3)
        }

        val bodyBytes = bodyObj.toString().toByteArray(Charsets.UTF_8)
        val conn = (URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            doOutput = true
            setFixedLengthStreamingMode(bodyBytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) Thread { runCatching { conn.disconnect() } }.start()
        }

        val textSb = StringBuilder()
        val toolCallIds = mutableMapOf<Int, String>()
        val toolCallNames = mutableMapOf<Int, String>()
        val toolCallArgs = mutableMapOf<Int, StringBuilder>()
        var inputTokens = 0L
        var outputTokens = 0L
        var totalTokens = 0L
        var finishReason: String? = null

        try {
            conn.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }

            val status = conn.responseCode
            if (status !in 200..299) {
                val rawErr = (conn.errorStream ?: conn.inputStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val msg = GeminiCodeAssistant.extractApiErrorMessage(rawErr).ifBlank { conn.responseMessage ?: "HTTP $status" }
                throw GeminiApiException(status, msg)
            }

            onConnected()

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue

                    val root = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    root.optJSONObject("error")?.let { err ->
                        throw GeminiApiException(status, err.optString("message", "OpenAI streaming error"))
                    }

                    root.optJSONObject("usage")?.let { u ->
                        inputTokens = u.optLong("prompt_tokens", inputTokens)
                        outputTokens = u.optLong("completion_tokens", outputTokens)
                        totalTokens = u.optLong("total_tokens", inputTokens + outputTokens)
                    }

                    val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val fr = choice.optString("finish_reason", "")
                    if (fr.isNotBlank() && fr != "null") finishReason = fr

                    val delta = choice.optJSONObject("delta") ?: continue
                    val contentDelta = delta.optString("content", "")
                    if (contentDelta.isNotEmpty() && contentDelta != "null") {
                        textSb.append(contentDelta)
                        onTextDelta(contentDelta)
                    }

                    val tcArr = delta.optJSONArray("tool_calls") ?: continue
                    for (i in 0 until tcArr.length()) {
                        val tc = tcArr.optJSONObject(i) ?: continue
                        val idx = tc.optInt("index", 0)
                        val id = tc.optString("id", "")
                        if (id.isNotBlank()) toolCallIds[idx] = id
                        val fn = tc.optJSONObject("function") ?: continue
                        val fnName = fn.optString("name", "")
                        if (fnName.isNotBlank()) toolCallNames[idx] = fnName
                        val argsChunk = fn.optString("arguments", "")
                        if (argsChunk.isNotEmpty()) {
                            toolCallArgs.getOrPut(idx) { StringBuilder() }.append(argsChunk)
                        }
                    }
                }
            }

            val calls = toolCallNames.keys.sorted().mapNotNull { idx ->
                val name = toolCallNames[idx]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OpenAiToolCallRecord(
                    id = toolCallIds[idx] ?: "call_$idx",
                    name = name,
                    argsJson = toolCallArgs[idx]?.toString().orEmpty().ifBlank { "{}" },
                )
            }

            val remReq = conn.getHeaderField("x-ratelimit-remaining-requests")
            val remTok = conn.getHeaderField("x-ratelimit-remaining-tokens")
            val quotaNote = listOfNotNull(
                remReq?.takeIf { it.isNotBlank() }?.let { "Req left: $it" },
                remTok?.takeIf { it.isNotBlank() }?.let { "Tok left: $it" },
            ).joinToString(" • ")

            ExternalTurnOutcome(
                text = textSb.toString(),
                openAiToolCalls = calls,
                usage = TokenUsageDelta(
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    totalTokens = if (totalTokens > 0) totalTokens else inputTokens + outputTokens,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    remainingQuotaNote = quotaNote,
                ),
                finishReason = finishReason,
            )
        } finally {
            cancelHandle?.dispose()
            conn.disconnect()
        }
    }

    suspend fun streamClaude(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        messages: JSONArray,
        onConnected: () -> Unit,
        onTextDelta: (String) -> Unit,
    ): ExternalTurnOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val tools = JSONArray().apply {
            buildSharedToolSpecs().forEach { (name, desc, schema) ->
                put(
                    JSONObject()
                        .put("name", name)
                        .put("description", desc)
                        .put("input_schema", schema),
                )
            }
        }

        val bodyBytes = JSONObject()
            .put("model", modelId)
            .put("max_tokens", 8192)
            .put("system", systemInstruction)
            .put("messages", messages)
            .put("tools", tools)
            .put("stream", true)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            doOutput = true
            setFixedLengthStreamingMode(bodyBytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) Thread { runCatching { conn.disconnect() } }.start()
        }

        val textSb = StringBuilder()
        val toolIds = mutableMapOf<Int, String>()
        val toolNames = mutableMapOf<Int, String>()
        val toolJsonBuilders = mutableMapOf<Int, StringBuilder>()
        val blockTypes = mutableMapOf<Int, String>()
        val blockTexts = mutableMapOf<Int, StringBuilder>()
        var inputTokens = 0L
        var outputTokens = 0L
        var stopReason: String? = null

        try {
            conn.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }

            val status = conn.responseCode
            if (status !in 200..299) {
                val rawErr = (conn.errorStream ?: conn.inputStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val msg = GeminiCodeAssistant.extractApiErrorMessage(rawErr).ifBlank { conn.responseMessage ?: "HTTP $status" }
                throw GeminiApiException(status, msg)
            }

            onConnected()

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue

                    val root = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val eventType = root.optString("type", "")
                    when (eventType) {
                        "error" -> {
                            val err = root.optJSONObject("error")
                            throw GeminiApiException(status, err?.optString("message") ?: "Claude streaming error")
                        }
                        "message_start" -> {
                            val usage = root.optJSONObject("message")?.optJSONObject("usage")
                            if (usage != null) {
                                inputTokens = usage.optLong("input_tokens", inputTokens)
                            }
                        }
                        "content_block_start" -> {
                            val idx = root.optInt("index", 0)
                            val block = root.optJSONObject("content_block") ?: continue
                            val type = block.optString("type", "")
                            blockTypes[idx] = type
                            if (type == "tool_use") {
                                toolIds[idx] = block.optString("id", "tool_$idx")
                                toolNames[idx] = block.optString("name", "")
                                toolJsonBuilders[idx] = StringBuilder()
                            } else if (type == "text") {
                                blockTexts[idx] = StringBuilder()
                            }
                        }
                        "content_block_delta" -> {
                            val idx = root.optInt("index", 0)
                            val delta = root.optJSONObject("delta") ?: continue
                            when (delta.optString("type")) {
                                "text_delta" -> {
                                    val txt = delta.optString("text", "")
                                    if (txt.isNotEmpty()) {
                                        textSb.append(txt)
                                        blockTexts.getOrPut(idx) { StringBuilder() }.append(txt)
                                        onTextDelta(txt)
                                    }
                                }
                                "input_json_delta" -> {
                                    val partial = delta.optString("partial_json", "")
                                    if (partial.isNotEmpty()) {
                                        toolJsonBuilders.getOrPut(idx) { StringBuilder() }.append(partial)
                                    }
                                }
                            }
                        }
                        "message_delta" -> {
                            val delta = root.optJSONObject("delta")
                            val sr = delta?.optString("stop_reason", "")
                            if (!sr.isNullOrBlank() && sr != "null") stopReason = sr
                            val usage = root.optJSONObject("usage")
                            if (usage != null) {
                                outputTokens = usage.optLong("output_tokens", outputTokens)
                            }
                        }
                    }
                }
            }

            val assistantBlocks = JSONArray()
            val toolUses = mutableListOf<ClaudeToolUseRecord>()
            blockTypes.keys.sorted().forEach { idx ->
                when (blockTypes[idx]) {
                    "text" -> {
                        val t = blockTexts[idx]?.toString().orEmpty()
                        if (t.isNotEmpty()) {
                            assistantBlocks.put(JSONObject().put("type", "text").put("text", t))
                        }
                    }
                    "tool_use" -> {
                        val id = toolIds[idx] ?: "tool_$idx"
                        val name = toolNames[idx].orEmpty()
                        val rawJson = toolJsonBuilders[idx]?.toString().orEmpty().ifBlank { "{}" }
                        val parsedInput = runCatching { JSONObject(rawJson) }.getOrElse { JSONObject() }
                        assistantBlocks.put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", id)
                                .put("name", name)
                                .put("input", parsedInput),
                        )
                        if (name.isNotBlank()) {
                            toolUses.add(ClaudeToolUseRecord(id = id, name = name, input = parsedInput))
                        }
                    }
                }
            }

            val remReq = conn.getHeaderField("anthropic-ratelimit-requests-remaining")
            val remTok = conn.getHeaderField("anthropic-ratelimit-tokens-remaining")
            val quotaNote = listOfNotNull(
                remReq?.takeIf { it.isNotBlank() }?.let { "Req left: $it" },
                remTok?.takeIf { it.isNotBlank() }?.let { "Tok left: $it" },
            ).joinToString(" • ")

            ExternalTurnOutcome(
                text = textSb.toString(),
                claudeToolUses = toolUses,
                claudeAssistantBlocks = assistantBlocks,
                usage = TokenUsageDelta(
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    totalTokens = inputTokens + outputTokens,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    remainingQuotaNote = quotaNote,
                ),
                finishReason = stopReason,
            )
        } finally {
            cancelHandle?.dispose()
            conn.disconnect()
        }
    }
}
