package com.example.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SuggestedCodeBlock(
    val language: String,
    val suggestedPath: String?,
    val code: String,
)

enum class AgentStepStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}

data class AgentStep(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: AgentStepStatus = AgentStepStatus.RUNNING,
    val expandedOutput: String = "",
    val durationMs: Long = 0L,
    val toolName: String = "",
)

data class ChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isStreaming: Boolean = false,
    val steps: List<AgentStep> = emptyList(),
    val codeBlocks: List<SuggestedCodeBlock> = emptyList(),
    val attachmentSummaries: List<String> = emptyList(),
    val providerName: String = "",
    val modelId: String = "",
    val totalTokens: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
)

enum class AgentRole(
    val label: String,
    val badge: String,
    val shortHint: String,
    val defaultSystemFocus: String,
) {
    GENERAL(
        label = "Main Agent",
        badge = "Main",
        shortHint = "Full-stack coding, building & debugging",
        defaultSystemFocus = "",
    ),
    FEATURE_BUILDER(
        label = "Feature Architect",
        badge = "Features",
        shortHint = "Adds new screens, gameplay, animations & UI polish",
        defaultSystemFocus = "ROLE: Feature Architect Agent. Focus on designing and implementing complete new user-facing features, rich UI/Canvas experiences, clean modular code, and running build_apk to verify every addition.",
    ),
    ERROR_FIXER(
        label = "Error Fixer",
        badge = "Fixer",
        shortHint = "Diagnoses compiler diagnostics, crashes & layout bugs",
        defaultSystemFocus = "ROLE: Error Fixer Agent. Focus ruthlessly on compiler diagnostics, runtime crashes, XML references, imports, Java/Kotlin compatibility, and minimal surgical fixes verified by build_apk.",
    ),
    GITHUB_WATCHER(
        label = "GitHub Monitor",
        badge = "GitHub",
        shortHint = "Monitors push status, branches, Actions CI runs & logs",
        defaultSystemFocus = "ROLE: GitHub & CI Monitor Agent. Focus on inspecting repository diff status, branches, GitHub Actions workflow runs, build failure logs, pushing commits safely, and re-running CI workflows.",
    ),
}

data class ProjectChatThread(
    val id: String,
    val title: String,
    val isMain: Boolean = false,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val agentRole: AgentRole = AgentRole.GENERAL,
    val providerName: String = "",
    val modelId: String = "",
    val customSystemInstruction: String = "",
    val temperature: Float? = null,
    val isPinnedAgent: Boolean = false,
)

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Streaming : AiUiState
    data class Error(
        val message: String,
        val isRetryable: Boolean = true,
        val isModelOverloaded: Boolean = false,
    ) : AiUiState
}

data class AgentToolResult(
    val success: Boolean,
    val stepDetail: String,
    val payload: JSONObject,
    val detailedOutput: String = "",
)

interface ProjectToolExecutor {
    suspend fun listFiles(): AgentToolResult
    suspend fun readFile(path: String): AgentToolResult
    suspend fun writeFile(path: String, content: String): AgentToolResult
    suspend fun editFile(path: String, oldText: String, newText: String): AgentToolResult
    suspend fun deleteFile(path: String): AgentToolResult
    suspend fun searchFiles(query: String): AgentToolResult
    suspend fun buildApk(isRelease: Boolean = false): AgentToolResult
    suspend fun buildAab(): AgentToolResult
    suspend fun githubStatus(): AgentToolResult = AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubListRepos(): AgentToolResult = AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubCreateRepo(name: String, description: String = "", isPrivate: Boolean = false): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubLinkRepo(owner: String, repo: String, branch: String = "main"): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubListBranches(owner: String, repo: String): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubListRepoFiles(owner: String, repo: String, branch: String): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubReadFile(path: String, owner: String, repo: String, branch: String): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubUploadFile(remotePath: String, localPath: String, content: String, commitMessage: String): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubDownloadFile(remotePath: String, localPath: String, owner: String, repo: String, branch: String): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubPullChanges(): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubPushChanges(commitMessage: String): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubTriggerCloudBuild(): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubListWorkflowRuns(): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubGetWorkflowLogs(runId: Long): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
    suspend fun githubRerunWorkflow(runId: Long, failedOnly: Boolean): AgentToolResult =
        AgentToolResult(false, "GitHub unavailable", JSONObject().put("error", "Not implemented"))
}

object ProjectChatStore {

    private const val STORE_DIR = ".droidforge"
    private const val CHATS_FILE = "chats.json"

    fun createMainThread(): ProjectChatThread = ProjectChatThread(
        id = "main",
        title = "Main Agent",
        isMain = true,
        updatedAtMillis = System.currentTimeMillis(),
        messages = emptyList(),
        agentRole = AgentRole.GENERAL,
        isPinnedAgent = true,
    )

    fun createRoleAgentThread(
        role: AgentRole,
        providerName: String = "",
        modelId: String = "",
    ): ProjectChatThread = when (role) {
        AgentRole.GENERAL -> createMainThread().copy(providerName = providerName, modelId = modelId)
        AgentRole.FEATURE_BUILDER -> ProjectChatThread(
            id = "agent_feature",
            title = role.label,
            isMain = false,
            updatedAtMillis = System.currentTimeMillis(),
            messages = emptyList(),
            agentRole = role,
            providerName = providerName,
            modelId = modelId,
            isPinnedAgent = true,
        )
        AgentRole.ERROR_FIXER -> ProjectChatThread(
            id = "agent_fixer",
            title = role.label,
            isMain = false,
            updatedAtMillis = System.currentTimeMillis(),
            messages = emptyList(),
            agentRole = role,
            providerName = providerName,
            modelId = modelId,
            isPinnedAgent = true,
        )
        AgentRole.GITHUB_WATCHER -> ProjectChatThread(
            id = "agent_github",
            title = role.label,
            isMain = false,
            updatedAtMillis = System.currentTimeMillis(),
            messages = emptyList(),
            agentRole = role,
            providerName = providerName,
            modelId = modelId,
            isPinnedAgent = true,
        )
    }

    fun createSideThread(
        index: Int,
        customTitle: String = "",
        providerName: String = "",
        modelId: String = "",
    ): ProjectChatThread = ProjectChatThread(
        id = "side_${UUID.randomUUID().toString().take(8)}",
        title = customTitle.trim().ifBlank { "Side Chat $index" },
        isMain = false,
        updatedAtMillis = System.currentTimeMillis(),
        messages = emptyList(),
        agentRole = AgentRole.GENERAL,
        providerName = providerName,
        modelId = modelId,
        isPinnedAgent = false,
    )

    suspend fun loadThreads(projectDir: File): List<ProjectChatThread> = withContext(Dispatchers.IO) {
        val file = File(File(projectDir, STORE_DIR), CHATS_FILE)
        if (!file.exists()) {
            return@withContext listOf(createMainThread())
        }
        val parsed = runCatching {
            deserializeThreads(file.readText())
        }.getOrDefault(emptyList())
        if (parsed.isEmpty()) listOf(createMainThread()) else parsed
    }

    suspend fun saveThreads(projectDir: File, threads: List<ProjectChatThread>) = withContext(Dispatchers.IO) {
        val dir = File(projectDir, STORE_DIR).apply { mkdirs() }
        val file = File(dir, CHATS_FILE)
        file.writeText(serializeThreads(threads))
    }

    internal fun serializeThreads(threads: List<ProjectChatThread>): String {
        val root = JSONObject()
        val arr = JSONArray()
        threads.forEach { thread ->
            val msgArr = JSONArray()
            thread.messages.forEach { msg ->
                val stepsArr = JSONArray()
                msg.steps.forEach { s ->
                    stepsArr.put(
                        JSONObject()
                            .put("id", s.id)
                            .put("title", s.title)
                            .put("detail", s.detail)
                            .put("status", if (s.status == AgentStepStatus.RUNNING) AgentStepStatus.COMPLETED.name else s.status.name)
                            .put("expandedOutput", s.expandedOutput)
                            .put("durationMs", s.durationMs)
                            .put("toolName", s.toolName),
                    )
                }
                val blocksArr = JSONArray()
                msg.codeBlocks.forEach { b ->
                    blocksArr.put(
                        JSONObject()
                            .put("language", b.language)
                            .put("suggestedPath", b.suggestedPath ?: "")
                            .put("code", b.code),
                    )
                }
                val attArr = JSONArray().apply { msg.attachmentSummaries.forEach { put(it) } }
                msgArr.put(
                    JSONObject()
                        .put("id", msg.id)
                        .put("isUser", msg.isUser)
                        .put("text", msg.text)
                        .put("steps", stepsArr)
                        .put("codeBlocks", blocksArr)
                        .put("attachmentSummaries", attArr)
                        .put("providerName", msg.providerName)
                        .put("modelId", msg.modelId)
                        .put("totalTokens", msg.totalTokens)
                        .put("timestampMillis", msg.timestampMillis),
                )
            }
            val tObj = JSONObject()
                .put("id", thread.id)
                .put("title", thread.title)
                .put("isMain", thread.isMain)
                .put("updatedAtMillis", thread.updatedAtMillis)
                .put("messages", msgArr)
                .put("agentRole", thread.agentRole.name)
                .put("providerName", thread.providerName)
                .put("modelId", thread.modelId)
                .put("customSystemInstruction", thread.customSystemInstruction)
                .put("isPinnedAgent", thread.isPinnedAgent)
            if (thread.temperature != null) {
                tObj.put("temperature", thread.temperature.toDouble())
            }
            arr.put(tObj)
        }
        root.put("threads", arr)
        return root.toString()
    }

    internal fun deserializeThreads(raw: String): List<ProjectChatThread> {
        if (raw.isBlank()) return emptyList()
        val root = JSONObject(raw)
        val arr = root.optJSONArray("threads") ?: return emptyList()
        val list = mutableListOf<ProjectChatThread>()
        for (i in 0 until arr.length()) {
            val tObj = arr.optJSONObject(i) ?: continue
            val msgArr = tObj.optJSONArray("messages") ?: JSONArray()
            val msgs = mutableListOf<ChatMessage>()
            for (j in 0 until msgArr.length()) {
                val mObj = msgArr.optJSONObject(j) ?: continue
                val stepsArr = mObj.optJSONArray("steps") ?: JSONArray()
                val steps = mutableListOf<AgentStep>()
                for (k in 0 until stepsArr.length()) {
                    val sObj = stepsArr.optJSONObject(k) ?: continue
                    val status = runCatching {
                        AgentStepStatus.valueOf(sObj.optString("status", AgentStepStatus.COMPLETED.name))
                    }.getOrDefault(AgentStepStatus.COMPLETED)
                    steps.add(
                        AgentStep(
                            id = sObj.optString("id"),
                            title = sObj.optString("title"),
                            detail = sObj.optString("detail"),
                            status = status,
                            expandedOutput = sObj.optString("expandedOutput"),
                            durationMs = sObj.optLong("durationMs", 0L),
                            toolName = sObj.optString("toolName"),
                        ),
                    )
                }
                val blocksArr = mObj.optJSONArray("codeBlocks") ?: JSONArray()
                val blocks = mutableListOf<SuggestedCodeBlock>()
                for (k in 0 until blocksArr.length()) {
                    val bObj = blocksArr.optJSONObject(k) ?: continue
                    val path = bObj.optString("suggestedPath").takeIf { it.isNotBlank() }
                    blocks.add(
                        SuggestedCodeBlock(
                            language = bObj.optString("language", "java"),
                            suggestedPath = path,
                            code = bObj.optString("code"),
                        ),
                    )
                }
                val attArr = mObj.optJSONArray("attachmentSummaries") ?: JSONArray()
                val attList = (0 until attArr.length()).mapNotNull { idx ->
                    attArr.optString(idx).takeIf { it.isNotBlank() }
                }
                msgs.add(
                    ChatMessage(
                        id = mObj.optString("id"),
                        isUser = mObj.optBoolean("isUser", false),
                        text = mObj.optString("text"),
                        isStreaming = false,
                        steps = steps,
                        codeBlocks = blocks,
                        attachmentSummaries = attList,
                        providerName = mObj.optString("providerName"),
                        modelId = mObj.optString("modelId"),
                        totalTokens = mObj.optLong("totalTokens", 0L),
                        timestampMillis = mObj.optLong("timestampMillis", System.currentTimeMillis()),
                    ),
                )
            }
            val parsedRole = runCatching {
                AgentRole.valueOf(tObj.optString("agentRole", AgentRole.GENERAL.name))
            }.getOrDefault(AgentRole.GENERAL)
            val tempVal = if (tObj.has("temperature")) tObj.optDouble("temperature").toFloat() else null
            list.add(
                ProjectChatThread(
                    id = tObj.optString("id"),
                    title = tObj.optString("title", "Chat"),
                    isMain = tObj.optBoolean("isMain", false),
                    updatedAtMillis = tObj.optLong("updatedAtMillis", System.currentTimeMillis()),
                    messages = msgs,
                    agentRole = parsedRole,
                    providerName = tObj.optString("providerName", ""),
                    modelId = tObj.optString("modelId", ""),
                    customSystemInstruction = tObj.optString("customSystemInstruction", ""),
                    temperature = tempVal,
                    isPinnedAgent = tObj.optBoolean("isPinnedAgent", tObj.optBoolean("isMain", false)),
                ),
            )
        }
        return list
    }
}
