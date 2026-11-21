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

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 65_000

    fun buildSharedToolSpecs(): List<Triple<String, String, JSONObject>> {
        fun strProp(desc: String) = JSONObject().put("type", "string").put("description", desc)
        fun boolProp(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
        fun intProp(desc: String) = JSONObject().put("type", "integer").put("description", desc)

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
            Triple(
                "github_status",
                "Checks connected GitHub account, linked repository, branch, and pending modified/added/deleted files.",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            ),
            Triple(
                "github_list_repos",
                "Lists the authenticated user's GitHub repositories.",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            ),
            Triple(
                "github_create_repo",
                "Creates a new GitHub repository for the connected account and links it to the active project.",
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("name", strProp("Repository name to create."))
                            .put("description", strProp("Optional repository description."))
                            .put("private", boolProp("Set true to make the repository private (default false).")),
                    )
                    .put("required", JSONArray().put("name")),
            ),
            Triple(
                "github_link_repo",
                "Links an existing GitHub repository to the active Android Studio: For Android project.",
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("owner", strProp("GitHub repository owner/organization."))
                            .put("repo", strProp("GitHub repository name."))
                            .put("branch", strProp("Optional branch name (defaults to main).")),
                    )
                    .put("required", JSONArray().put("owner").put("repo")),
            ),
            Triple(
                "github_list_branches",
                "Lists branches on the linked or specified GitHub repository.",
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("owner", strProp("Optional GitHub owner (defaults to linked repo owner)."))
                            .put("repo", strProp("Optional repository name (defaults to linked repo).")),
                    ),
            ),
            Triple(
                "github_list_repo_files",
                "Lists files in the linked GitHub repo or a specified owner/repo/branch.",
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("owner", strProp("Optional GitHub owner (defaults to linked repo owner)."))
                            .put("repo", strProp("Optional repository name (defaults to linked repo)."))
                            .put("branch", strProp("Optional branch name (defaults to linked branch or main).")),
                    ),
            ),
            Triple(
                "github_read_file",
                "Reads the text content of a file from the linked or specified GitHub repository.",
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("path", strProp("File path inside the GitHub repository."))
                            .put("owner", strProp("Optional GitHub owner (defaults to linked repo owner)."))
                            .put("repo", strProp("Optional repository name (defaults to linked repo)."))
                            .put("branch", strProp("Optional branch name (defaults to linked branch or main).")),
                    )
                    .put("required", JSONArray().put("path")),
            ),
            Triple(
                "github_upload_file",
                "Uploads a single file from the project (or explicit content) directly to the linked GitHub repository.",
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("remote_path", strProp("Target file path inside the GitHub repository."))
                            .put("local_path", strProp("Optional relative file path inside the project to upload."))
                            .put("content", strProp("Optional explicit text content if local_path is omitted."))
                            .put("commit_message", strProp("Optional commit message.")),
                    )
                    .put("required", JSONArray().put("remote_path")),
            ),
            Triple(
                "github_download_file",
                "Downloads a file from the linked or specified GitHub repository and saves it into the active project.",
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("remote_path", strProp("Source file path in the GitHub repository."))
                            .put("local_path", strProp("Optional destination relative path in the project (defaults to remote_path)."))
                            .put("owner", strProp("Optional GitHub owner (defaults to linked repo owner)."))
                            .put("repo", strProp("Optional repository name (defaults to linked repo)."))
                            .put("branch", strProp("Optional branch name.")),
                    )
                    .put("required", JSONArray().put("remote_path")),
            ),
            Triple(
                "github_pull_changes",
                "Pulls and syncs files from the linked GitHub repository into the active project.",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            ),
            Triple(
                "github_push_changes",
                "Commits all current project files and pushes them to the linked GitHub repository.",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("commit_message", strProp("Git commit message.")))
                    .put("required", JSONArray().put("commit_message")),
            ),
            Triple(
                "github_trigger_cloud_build",
                "Generates Gradle & GitHub Actions CI files if needed, pushes updates, and triggers a Cloud Android APK/AAB build on GitHub Actions.",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            ),
            Triple(
                "github_list_workflow_runs",
                "Lists recent GitHub Actions workflow runs, including statuses, conclusions, branches, and run IDs.",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            ),
            Triple(
                "github_get_workflow_logs",
                "Fetches jobs, step failure summaries, and log tails for a GitHub Actions workflow run ID.",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("run_id", intProp("GitHub Actions workflow run ID."))),
            ),
            Triple(
                "github_rerun_workflow",
                "Reruns a GitHub Actions workflow run (all jobs or only failed jobs).",
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("run_id", intProp("GitHub Actions workflow run ID to rerun."))
                            .put("failed_only", boolProp("Set true to rerun only failed jobs (default false).")),
                    )
                    .put("required", JSONArray().put("run_id")),
            ),
        )
    }

    suspend fun streamOpenAi(
        apiKey: String,
        modelId: String,
        messages: JSONArray,
        thinkingMode: AiThinkingMode = AiThinkingMode.FAST,
        includeGitHubTools: Boolean = true,
        includeTools: Boolean = true,
        includeTemperature: Boolean = true,
        onConnected: () -> Unit,
        onTextDelta: (String) -> Unit,
    ): ExternalTurnOutcome = streamOpenAiCompatible(
        endpointUrl = "https://api.openai.com/v1/chat/completions",
        apiKey = apiKey,
        modelId = modelId,
        messages = messages,
        thinkingMode = thinkingMode,
        isOpenRouter = false,
        extraHeaders = emptyMap(),
        includeTools = includeTools,
        includeGitHubTools = includeGitHubTools,
        allowTextToolCalls = false,
        includeStreamOptions = true,
        includeTemperature = includeTemperature,
        allowOpenRouterDataCollectionFallback = false,
        foldSystemIntoUser = false,
        stripImages = false,
        onConnected = onConnected,
        onReasoningDelta = {},
        onTextDelta = onTextDelta,
    )

    suspend fun streamOpenRouter(
        apiKey: String,
        modelId: String,
        messages: JSONArray,
        thinkingMode: AiThinkingMode = AiThinkingMode.FAST,
        includeTools: Boolean = true,
        includeGitHubTools: Boolean = false,
        allowTextToolCalls: Boolean = true,
        includeStreamOptions: Boolean = true,
        includeTemperature: Boolean = true,
        allowOpenRouterDataCollectionFallback: Boolean = false,
        foldSystemIntoUser: Boolean = false,
        stripImages: Boolean = false,
        onConnected: () -> Unit,
        onReasoningDelta: () -> Unit = {},
        onTextDelta: (String) -> Unit,
    ): ExternalTurnOutcome = streamOpenAiCompatible(
        endpointUrl = "https://openrouter.ai/api/v1/chat/completions",
        apiKey = apiKey,
        modelId = modelId,
        messages = messages,
        thinkingMode = thinkingMode,
        isOpenRouter = true,
        extraHeaders = mapOf(
            "HTTP-Referer" to "https://github.com/android-studio-for-android",
            "X-Title" to "Android Studio: For Android",
        ),
        includeTools = includeTools,
        includeGitHubTools = includeGitHubTools,
        allowTextToolCalls = allowTextToolCalls,
        includeStreamOptions = includeStreamOptions,
        includeTemperature = includeTemperature,
        allowOpenRouterDataCollectionFallback = allowOpenRouterDataCollectionFallback,
        foldSystemIntoUser = foldSystemIntoUser,
        stripImages = stripImages,
        onConnected = onConnected,
        onReasoningDelta = onReasoningDelta,
        onTextDelta = onTextDelta,
    )

    internal fun buildAdaptedOpenAiMessages(
        messages: JSONArray,
        foldSystemIntoUser: Boolean,
        convertToolsToTextHistory: Boolean,
        stripImages: Boolean,
        textToolPromptSuffix: String = "",
    ): JSONArray {
        val systemParts = mutableListOf<String>()
        val intermediate = mutableListOf<JSONObject>()

        for (i in 0 until messages.length()) {
            val orig = messages.optJSONObject(i) ?: continue
            val copy = JSONObject(orig.toString())
            val role = copy.optString("role")

            if (stripImages) {
                val contentVal = copy.opt("content")
                if (contentVal is JSONArray) {
                    val textBuilder = StringBuilder()
                    for (j in 0 until contentVal.length()) {
                        val part = contentVal.optJSONObject(j) ?: continue
                        when (part.optString("type")) {
                            "text" -> {
                                val t = part.optString("text", "")
                                if (t.isNotEmpty()) {
                                    if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                                    textBuilder.append(t)
                                }
                            }
                            "image_url" -> {
                                if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                                textBuilder.append("[Image attachment omitted for text-only model]")
                            }
                        }
                    }
                    copy.put("content", textBuilder.toString())
                }
            }

            if (convertToolsToTextHistory) {
                if (role == "tool") {
                    val toolText = copy.optString("content", "{}")
                    intermediate.add(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "[Tool Result]\n$toolText"),
                    )
                    continue
                }
                if (role == "assistant" && copy.has("tool_calls")) {
                    val tcArr = copy.optJSONArray("tool_calls")
                    val callsSummary = buildList {
                        if (tcArr != null) {
                            for (k in 0 until tcArr.length()) {
                                val fn = tcArr.optJSONObject(k)?.optJSONObject("function")
                                val n = fn?.optString("name").orEmpty()
                                val a = fn?.optString("arguments").orEmpty()
                                if (n.isNotBlank()) add("<tool_call>{\"name\":\"$n\",\"arguments\":$a}</tool_call>")
                            }
                        }
                    }.joinToString("\n")
                    val existing = copy.optString("content", "").let { if (it == "null") "" else it }
                    val merged = listOf(existing, callsSummary).filter { it.isNotBlank() }.joinToString("\n")
                    copy.remove("tool_calls")
                    copy.put("content", merged.ifBlank { "Executing requested tool." })
                }
            } else if (role == "assistant" && !copy.has("content")) {
                copy.put("content", "")
            }

            if (foldSystemIntoUser && role == "system") {
                val sysText = copy.optString("content", "")
                if (sysText.isNotBlank()) systemParts.add(sysText)
            } else {
                intermediate.add(copy)
            }
        }

        val combinedSystemPrefix = buildString {
            if (systemParts.isNotEmpty()) {
                append(systemParts.joinToString("\n\n"))
            }
            if (textToolPromptSuffix.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(textToolPromptSuffix)
            }
        }

        if (foldSystemIntoUser && combinedSystemPrefix.isNotBlank()) {
            val firstUserIdx = intermediate.indexOfFirst { it.optString("role") == "user" }
            if (firstUserIdx >= 0) {
                val firstUser = intermediate[firstUserIdx]
                val existingContent = firstUser.opt("content")
                if (existingContent is JSONArray) {
                    val updatedArr = JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", "Instructions:\n$combinedSystemPrefix\n\nUser request:"))
                        for (j in 0 until existingContent.length()) {
                            put(existingContent.get(j))
                        }
                    }
                    firstUser.put("content", updatedArr)
                } else {
                    val origText = if (existingContent == null || existingContent === JSONObject.NULL) "" else existingContent.toString()
                    firstUser.put("content", "Instructions:\n$combinedSystemPrefix\n\nUser request:\n$origText")
                }
            } else {
                intermediate.add(0, JSONObject().put("role", "user").put("content", combinedSystemPrefix))
            }
        } else if (!foldSystemIntoUser && textToolPromptSuffix.isNotBlank()) {
            val sysIdx = intermediate.indexOfFirst { it.optString("role") == "system" }
            if (sysIdx >= 0) {
                val prev = intermediate[sysIdx].optString("content", "")
                intermediate[sysIdx].put("content", "$prev\n\n$textToolPromptSuffix")
            } else {
                intermediate.add(0, JSONObject().put("role", "system").put("content", textToolPromptSuffix))
            }
        }

        return JSONArray().apply {
            intermediate.forEach { put(it) }
        }
    }

    internal fun buildTextModeToolPrompt(includeGitHubTools: Boolean): String {
        val allowedNames = buildSharedToolSpecs()
            .filter { (name, _, _) -> includeGitHubTools || !name.startsWith("github_") }
            .joinToString(", ") { (name, _, _) -> name }
        return """
            |Tool Calling Format (CRITICAL when native function calling is unavailable):
            |Available tools: $allowedNames
            |Key tool arguments:
            |- list_files: {}
            |- read_file: {"path":"..."}
            |- write_file: {"path":"...","content":"..."}
            |- edit_file: {"path":"...","old_text":"...","new_text":"..."}
            |- delete_file: {"path":"..."}
            |- search_files: {"query":"..."}
            |- build_apk: {"release":false}
            |- build_aab: {}
            |To execute a tool, emit one or more blocks using this exact XML tag:
            |<tool_call>{"name":"edit_file","arguments":{"path":"...","old_text":"...","new_text":"..."}}</tool_call>
            |Only emit <tool_call> when you actually want a tool run; otherwise give your normal Markdown response.
        """.trimMargin()
    }

    internal fun extractTextToolCalls(rawText: String): Pair<String, List<OpenAiToolCallRecord>> {
        val knownTools = buildSharedToolSpecs().map { it.first }.toSet()
        val found = mutableListOf<OpenAiToolCallRecord>()
        val tagRegex = Regex("""<tool_call>\s*(\{[\s\S]*?\})\s*</tool_call>""", RegexOption.IGNORE_CASE)
        var cleaned = rawText
        tagRegex.findAll(rawText).forEachIndexed { idx, match ->
            val jsonStr = match.groupValues[1].trim()
            val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return@forEachIndexed
            val name = obj.optString("name").trim()
            if (name in knownTools) {
                val argsAny = obj.opt("arguments") ?: obj.opt("parameters") ?: JSONObject()
                val argsJson = when (argsAny) {
                    is JSONObject -> argsAny.toString()
                    is String -> argsAny.ifBlank { "{}" }
                    else -> "{}"
                }
                found.add(
                    OpenAiToolCallRecord(
                        id = "text_call_${System.currentTimeMillis()}_$idx",
                        name = name,
                        argsJson = argsJson,
                    ),
                )
                cleaned = cleaned.replace(match.value, "")
            }
        }
        return cleaned.trim() to found
    }

    internal fun stripThinkBlocks(rawText: String): String {
        val stripped = rawText
            .replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "")
            .trim()
        return stripped.ifBlank { rawText.trim() }
    }

    internal fun sanitizeStreamingVisibleText(rawText: String, hideToolCalls: Boolean): String {
        var text = rawText
            .replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<think>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        if (hideToolCalls) {
            text = text
                .replace(Regex("""<tool_call>[\s\S]*?</tool_call>""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<tool_call>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        }
        val lastLt = text.lastIndexOf('<')
        if (lastLt >= 0) {
            val tail = text.substring(lastLt).lowercase()
            val matchesThinkStart = "<think>".startsWith(tail)
            val matchesToolStart = hideToolCalls && "<tool_call>".startsWith(tail)
            if (matchesThinkStart || matchesToolStart) {
                text = text.substring(0, lastLt)
            }
        }
        return text
    }

    internal fun buildOpenAiCompatibleRequestBody(
        modelId: String,
        adaptedMessages: JSONArray,
        tools: JSONArray,
        thinkingMode: AiThinkingMode,
        isOpenRouter: Boolean,
        includeTools: Boolean = true,
        includeStreamOptions: Boolean = true,
        includeTemperature: Boolean = true,
        allowOpenRouterDataCollectionFallback: Boolean = false,
    ): JSONObject {
        val bodyObj = JSONObject()
            .put("model", modelId)
            .put("messages", adaptedMessages)
            .put("stream", true)

        if (includeTools && tools.length() > 0) {
            bodyObj.put("tools", tools)
        }
        if (includeStreamOptions) {
            bodyObj.put("stream_options", JSONObject().put("include_usage", true))
        }

        val lowerModel = modelId.lowercase()
        val tailModel = lowerModel.substringAfter('/')
        val isFixedTempReasoning = tailModel.startsWith("o1") ||
            tailModel.startsWith("o3") ||
            tailModel.startsWith("o4") ||
            (!isOpenRouter && (tailModel.startsWith("gpt-5") || tailModel.startsWith("gpt-6")))
        if (includeTemperature && !isFixedTempReasoning) {
            bodyObj.put("temperature", 0.3)
        } else if (!isOpenRouter && isFixedTempReasoning && !tailModel.startsWith("o1")) {
            val effort = when (thinkingMode) {
                AiThinkingMode.FAST -> "low"
                AiThinkingMode.BALANCED -> "medium"
                AiThinkingMode.DEEP -> "high"
            }
            bodyObj.put("reasoning_effort", effort)
        }

        if (isOpenRouter && allowOpenRouterDataCollectionFallback) {
            bodyObj.put(
                "provider",
                JSONObject()
                    .put("data_collection", "allow")
                    .put("allow_fallbacks", true),
            )
        }
        return bodyObj
    }

    private suspend fun streamOpenAiCompatible(
        endpointUrl: String,
        apiKey: String,
        modelId: String,
        messages: JSONArray,
        thinkingMode: AiThinkingMode,
        isOpenRouter: Boolean,
        extraHeaders: Map<String, String>,
        includeTools: Boolean,
        includeGitHubTools: Boolean,
        allowTextToolCalls: Boolean,
        includeStreamOptions: Boolean,
        includeTemperature: Boolean,
        allowOpenRouterDataCollectionFallback: Boolean,
        foldSystemIntoUser: Boolean,
        stripImages: Boolean,
        onConnected: () -> Unit,
        onReasoningDelta: () -> Unit,
        onTextDelta: (String) -> Unit,
    ): ExternalTurnOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val activeSpecs = buildSharedToolSpecs().filter { (name, _, _) ->
            includeGitHubTools || !name.startsWith("github_")
        }
        val tools = JSONArray().apply {
            activeSpecs.forEach { (name, desc, schema) ->
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

        val textToolSuffix = if (!includeTools && allowTextToolCalls) {
            buildTextModeToolPrompt(includeGitHubTools)
        } else {
            ""
        }
        val adaptedMessages = buildAdaptedOpenAiMessages(
            messages = messages,
            foldSystemIntoUser = foldSystemIntoUser,
            convertToolsToTextHistory = !includeTools,
            stripImages = stripImages,
            textToolPromptSuffix = textToolSuffix,
        )

        val bodyObj = buildOpenAiCompatibleRequestBody(
            modelId = modelId,
            adaptedMessages = adaptedMessages,
            tools = tools,
            thinkingMode = thinkingMode,
            isOpenRouter = isOpenRouter,
            includeTools = includeTools,
            includeStreamOptions = includeStreamOptions,
            includeTemperature = includeTemperature,
            allowOpenRouterDataCollectionFallback = allowOpenRouterDataCollectionFallback,
        )

        val bodyBytes = bodyObj.toString().toByteArray(Charsets.UTF_8)
        val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
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
            extraHeaders.forEach { (header, value) ->
                setRequestProperty(header, value)
            }
        }

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) Thread { runCatching { conn.disconnect() } }.start()
        }

        val textSb = StringBuilder()
        val rawNonSseSb = StringBuilder()
        var sawSseData = false
        var lastStreamedVisible = ""
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
                    if (!line.startsWith("data:")) {
                        if (!sawSseData && rawNonSseSb.length < 512_000 && !line.startsWith(":")) {
                            rawNonSseSb.append(line).append('\n')
                        }
                        continue
                    }
                    sawSseData = true
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue

                    val root = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    root.optJSONObject("error")?.let { err ->
                        val errMsg = err.optString("message").ifBlank { "Streaming error from provider" }
                        val rawMeta = err.optJSONObject("metadata")?.optString("raw").orEmpty()
                        val fullMsg = if (rawMeta.isNotBlank() && !errMsg.contains(rawMeta)) "$errMsg ($rawMeta)" else errMsg
                        val parsedCode = err.optInt("code", 0)
                        val effectiveCode = when {
                            parsedCode in 400..599 -> parsedCode
                            fullMsg.contains("rate", ignoreCase = true) || fullMsg.contains("limit", ignoreCase = true) -> 429
                            fullMsg.contains("credit", ignoreCase = true) || fullMsg.contains("afford", ignoreCase = true) -> 402
                            fullMsg.contains("not found", ignoreCase = true) || fullMsg.contains("no endpoints", ignoreCase = true) -> 404
                            else -> 502
                        }
                        throw GeminiApiException(effectiveCode, fullMsg)
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
                    val reasoningDelta = delta.optString("reasoning", "").let { r ->
                        if (r.isNotEmpty() && r != "null") r else delta.optString("reasoning_content", "")
                    }
                    if (reasoningDelta.isNotEmpty() && reasoningDelta != "null") {
                        onReasoningDelta()
                    }

                    val contentDelta = delta.optString("content", "")
                    if (contentDelta.isNotEmpty() && contentDelta != "null") {
                        textSb.append(contentDelta)
                        val hideToolsDuringStream = !includeTools && allowTextToolCalls
                        val sanitizedNow = sanitizeStreamingVisibleText(
                            rawText = textSb.toString(),
                            hideToolCalls = hideToolsDuringStream,
                        ).let { if (lastStreamedVisible.isEmpty()) it.trimStart() else it }
                        if (sanitizedNow.length > lastStreamedVisible.length && sanitizedNow.startsWith(lastStreamedVisible)) {
                            val incremental = sanitizedNow.substring(lastStreamedVisible.length)
                            lastStreamedVisible = sanitizedNow
                            onTextDelta(incremental)
                        } else if (textSb.contains("<think>", ignoreCase = true) && !textSb.contains("</think>", ignoreCase = true)) {
                            onReasoningDelta()
                        }
                    }

                    val tcArr = delta.optJSONArray("tool_calls") ?: continue
                    for (i in 0 until tcArr.length()) {
                        val tc = tcArr.optJSONObject(i) ?: continue
                        val idx = if (tc.has("index")) tc.optInt("index", i) else i
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

            // Fallback when an upstream provider returns a non-SSE application/json body despite stream=true.
            if (!sawSseData && rawNonSseSb.isNotEmpty()) {
                val rawJson = rawNonSseSb.toString().trim()
                val root = runCatching { JSONObject(rawJson) }.getOrNull()
                if (root != null) {
                    root.optJSONObject("error")?.let { err ->
                        val parsedCode = err.optInt("code", 502).let { if (it in 400..599) it else 502 }
                        val parsedMsg = GeminiCodeAssistant.extractApiErrorMessage(rawJson)
                            .ifBlank { err.optString("message", "Provider error") }
                        throw GeminiApiException(parsedCode, parsedMsg)
                    }
                    root.optJSONObject("usage")?.let { u ->
                        inputTokens = u.optLong("prompt_tokens", inputTokens)
                        outputTokens = u.optLong("completion_tokens", outputTokens)
                        totalTokens = u.optLong("total_tokens", inputTokens + outputTokens)
                    }
                    val choice = root.optJSONArray("choices")?.optJSONObject(0)
                    val fr = choice?.optString("finish_reason", "").orEmpty()
                    if (fr.isNotBlank() && fr != "null") finishReason = fr
                    val msgObj = choice?.optJSONObject("message")
                    val content = msgObj?.optString("content", "").orEmpty()
                    if (content.isNotEmpty() && content != "null") {
                        textSb.append(content)
                        onTextDelta(stripThinkBlocks(content))
                    }
                    val tcArr = msgObj?.optJSONArray("tool_calls")
                    if (tcArr != null) {
                        for (i in 0 until tcArr.length()) {
                            val tc = tcArr.optJSONObject(i) ?: continue
                            val id = tc.optString("id", "call_$i")
                            val fn = tc.optJSONObject("function") ?: continue
                            val fnName = fn.optString("name", "")
                            val fnArgs = fn.optString("arguments", "{}")
                            if (fnName.isNotBlank()) {
                                toolCallIds[i] = id
                                toolCallNames[i] = fnName
                                toolCallArgs[i] = StringBuilder(fnArgs)
                            }
                        }
                    }
                }
            }

            var calls = toolCallNames.keys.sorted().mapNotNull { idx ->
                val name = toolCallNames[idx]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OpenAiToolCallRecord(
                    id = toolCallIds[idx] ?: "call_$idx",
                    name = name,
                    argsJson = toolCallArgs[idx]?.toString().orEmpty().ifBlank { "{}" },
                )
            }

            var finalVisibleText = stripThinkBlocks(textSb.toString())
            if (calls.isEmpty() && !includeTools && allowTextToolCalls && finalVisibleText.contains("<tool_call>", ignoreCase = true)) {
                val (cleaned, parsedTextCalls) = extractTextToolCalls(finalVisibleText)
                if (parsedTextCalls.isNotEmpty()) {
                    finalVisibleText = cleaned
                    calls = parsedTextCalls
                }
            }

            if (calls.isEmpty() && finalVisibleText.isBlank()) {
                throw GeminiApiException(502, "$modelId returned an empty response")
            }

            val remReq = conn.getHeaderField("x-ratelimit-remaining-requests")
            val remTok = conn.getHeaderField("x-ratelimit-remaining-tokens")
            val quotaNote = listOfNotNull(
                remReq?.takeIf { it.isNotBlank() }?.let { "Req left: $it" },
                remTok?.takeIf { it.isNotBlank() }?.let { "Tok left: $it" },
            ).joinToString(" • ")

            ExternalTurnOutcome(
                text = finalVisibleText,
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
        thinkingMode: AiThinkingMode = AiThinkingMode.FAST,
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
