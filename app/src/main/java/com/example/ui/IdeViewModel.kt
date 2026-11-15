package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AgentToolResult
import com.example.ai.AiModelOption
import com.example.ai.AiModelsCatalog
import com.example.ai.AiProvider
import com.example.ai.AiThinkingMode
import com.example.ai.AiUiState
import com.example.ai.AiUsageSummary
import com.example.ai.ChatMessage
import com.example.ai.GEMINI_MODEL_ID
import com.example.ai.GeminiCodeAssistant
import com.example.ai.ProjectChatStore
import com.example.ai.ProjectChatThread
import com.example.ai.ProjectToolExecutor
import com.example.ai.SuggestedCodeBlock
import com.example.compiler.KeystoreManager
import com.example.compiler.OfflineApkBuilder
import com.example.data.ProjectRepository
import com.example.model.BuildDiagnostic
import com.example.model.BuildResult
import com.example.model.BuildVariant
import com.example.model.EditorSettings
import com.example.model.ProjectFile
import com.example.model.ProjectMetadata
import com.example.model.ProjectSearchHit
import com.example.model.ProjectTemplate
import com.example.model.ReleaseSigningConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class IdeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val apkBuilder = OfflineApkBuilder(application)
    private val geminiAssistant = GeminiCodeAssistant()

    private val _projects = MutableStateFlow<List<ProjectMetadata>>(emptyList())
    val projects: StateFlow<List<ProjectMetadata>> = _projects.asStateFlow()

    private val _activeProject = MutableStateFlow<ProjectMetadata?>(null)
    val activeProject: StateFlow<ProjectMetadata?> = _activeProject.asStateFlow()

    private val _projectFiles = MutableStateFlow<List<ProjectFile>>(emptyList())
    val projectFiles: StateFlow<List<ProjectFile>> = _projectFiles.asStateFlow()

    private val _openTabs = MutableStateFlow<List<String>>(emptyList())
    val openTabs: StateFlow<List<String>> = _openTabs.asStateFlow()

    private val _activeFilePath = MutableStateFlow<String?>(null)
    val activeFilePath: StateFlow<String?> = _activeFilePath.asStateFlow()

    private val _isBuilding = MutableStateFlow(false)
    val isBuilding: StateFlow<Boolean> = _isBuilding.asStateFlow()

    private val _liveLogs = MutableStateFlow<List<String>>(emptyList())
    val liveLogs: StateFlow<List<String>> = _liveLogs.asStateFlow()

    private val _lastBuildResult = MutableStateFlow<BuildResult?>(null)
    val lastBuildResult: StateFlow<BuildResult?> = _lastBuildResult.asStateFlow()

    private val _jumpToLine = MutableStateFlow<Pair<String, Int>?>(null)
    val jumpToLine: StateFlow<Pair<String, Int>?> = _jumpToLine.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _chatThreads = MutableStateFlow<List<ProjectChatThread>>(listOf(ProjectChatStore.createMainThread()))
    val chatThreads: StateFlow<List<ProjectChatThread>> = _chatThreads.asStateFlow()

    private val _activeThreadId = MutableStateFlow("main")
    val activeThreadId: StateFlow<String> = _activeThreadId.asStateFlow()

    private val _splitSideThreadId = MutableStateFlow<String?>(null)
    val splitSideThreadId: StateFlow<String?> = _splitSideThreadId.asStateFlow()

    private val _liveModels = MutableStateFlow<List<AiModelOption>>(emptyList())
    val liveModels: StateFlow<List<AiModelOption>> = _liveModels.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    val editorSettings: StateFlow<EditorSettings> = repository.editorSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EditorSettings(),
    )

    val activeProvider: StateFlow<AiProvider> = repository.activeProvider.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AiProvider.GEMINI,
    )

    val aiThinkingMode: StateFlow<AiThinkingMode> = repository.aiThinkingMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AiThinkingMode.FAST,
    )

    val providerApiKeys: StateFlow<Map<AiProvider, String>> = repository.providerApiKeys.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap(),
    )

    val providerModels: StateFlow<Map<AiProvider, String>> = repository.providerModels.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = mapOf(
            AiProvider.GEMINI to GEMINI_MODEL_ID,
            AiProvider.OPENAI to AiProvider.OPENAI.defaultModelId,
            AiProvider.CLAUDE to AiProvider.CLAUDE.defaultModelId,
        ),
    )

    val aiUsageSummary: StateFlow<AiUsageSummary> = repository.aiUsageSummary.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AiUsageSummary(),
    )

    val selectedBuildVariant: StateFlow<BuildVariant> = repository.preferredBuildVariant.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BuildVariant.DEBUG_APK,
    )

    val releaseSigningConfig: StateFlow<ReleaseSigningConfig> = repository.releaseSigningConfig.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ReleaseSigningConfig(),
    )

    val customGeminiApiKey: StateFlow<String> = repository.customGeminiApiKey.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "",
    )

    val geminiModelId: StateFlow<String> = repository.geminiModelId.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GEMINI_MODEL_ID,
    )

    val aiMessages: StateFlow<List<ChatMessage>> = geminiAssistant.messages
    val aiState: StateFlow<AiUiState> = geminiAssistant.uiState

    private var saveJob: Job? = null
    private var aiJob: Job? = null

    init {
        refreshProjects()
    }

    fun refreshProjects() {
        viewModelScope.launch {
            _projects.value = repository.listProjects()
        }
    }

    fun createProject(name: String, packageName: String, template: ProjectTemplate) {
        viewModelScope.launch {
            val cleanName = name.trim().ifBlank { template.defaultAppName }
            val cleanPkg = sanitizePackageName(packageName.trim().ifBlank { template.defaultPackage })
            val meta = repository.createProject(cleanName, cleanPkg, template)
            _projects.value = repository.listProjects()
            openProject(meta)
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            if (_activeProject.value?.id == projectId) {
                _activeProject.value = null
            }
            _projects.value = repository.listProjects()
        }
    }

    fun openProject(meta: ProjectMetadata) {
        aiJob?.cancel()
        viewModelScope.launch {
            val projectDir = repository.getProjectDir(meta.id)
            val files = repository.loadProjectFiles(meta.id)
            val threads = ProjectChatStore.loadThreads(projectDir)
            val firstThread = threads.first()

            _activeProject.value = meta
            _projectFiles.value = files
            val initialTabs = files.take(3).map { it.relativePath }
            _openTabs.value = initialTabs
            _activeFilePath.value = initialTabs.firstOrNull()
            _lastBuildResult.value = null
            _liveLogs.value = emptyList()

            _chatThreads.value = threads
            _activeThreadId.value = firstThread.id
            _splitSideThreadId.value = threads.firstOrNull { !it.isMain }?.id
            geminiAssistant.setMessages(firstThread.messages)
        }
    }

    fun closeProject() {
        saveAllImmediately()
        persistActiveThreadMessages()
        _activeProject.value = null
        _projectFiles.value = emptyList()
        _openTabs.value = emptyList()
        _activeFilePath.value = null
        _lastBuildResult.value = null
        refreshProjects()
    }

    fun openFile(relativePath: String) {
        if (relativePath !in _openTabs.value) {
            _openTabs.value = _openTabs.value + relativePath
        }
        _activeFilePath.value = relativePath
    }

    fun closeTab(relativePath: String) {
        val nextTabs = _openTabs.value.filterNot { it == relativePath }
        _openTabs.value = nextTabs
        if (_activeFilePath.value == relativePath) {
            _activeFilePath.value = nextTabs.lastOrNull()
        }
    }

    fun createNewFile(relativePath: String) {
        val project = _activeProject.value ?: return
        val clean = relativePath.trim().trimStart('/')
        if (clean.isEmpty()) return
        if (_projectFiles.value.any { it.relativePath == clean }) {
            openFile(clean)
            return
        }
        val starter = buildStarterContentForNewFile(clean, project.packageName)
        viewModelScope.launch {
            repository.saveFile(project.id, clean, starter)
            _projectFiles.value = repository.loadProjectFiles(project.id)
            openFile(clean)
        }
    }

    fun deleteFile(relativePath: String) {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            repository.deleteFile(project.id, relativePath)
            closeTab(relativePath)
            _projectFiles.value = repository.loadProjectFiles(project.id)
        }
    }

    fun updateActiveFileContent(newContent: String) {
        val project = _activeProject.value ?: return
        val path = _activeFilePath.value ?: return
        _projectFiles.value = _projectFiles.value.map { file ->
            if (file.relativePath == path) file.copy(content = newContent) else file
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(250L)
            repository.saveFile(project.id, path, newContent)
        }
    }

    private fun saveAllImmediately() {
        val project = _activeProject.value ?: return
        val files = _projectFiles.value
        viewModelScope.launch {
            files.forEach { file ->
                repository.saveFile(project.id, file.relativePath, file.content)
            }
        }
    }

    fun selectBuildVariant(variant: BuildVariant) {
        viewModelScope.launch {
            repository.savePreferredBuildVariant(variant)
        }
    }

    fun buildApk(variantOverride: BuildVariant? = null) {
        val project = _activeProject.value ?: return
        if (_isBuilding.value) return
        val variant = variantOverride ?: selectedBuildVariant.value
        val signing = releaseSigningConfig.value

        viewModelScope.launch {
            _isBuilding.value = true
            _liveLogs.value = listOf("Starting ${variant.label} build for ${project.name}...")
            _lastBuildResult.value = null

            _projectFiles.value.forEach { file ->
                repository.saveFile(project.id, file.relativePath, file.content)
            }

            val result = apkBuilder.buildProject(
                projectDir = repository.getProjectDir(project.id),
                packageName = project.packageName,
                variant = variant,
                releaseSigning = signing,
                onProgress = { line ->
                    _liveLogs.value = _liveLogs.value + line
                },
            )
            _lastBuildResult.value = result
            _isBuilding.value = false
            repository.recordBuildResult(project.id, result.success, result.apkSizeBytes, variant)
            _projects.value = repository.listProjects()
            _activeProject.value = _projects.value.find { it.id == project.id } ?: project
        }
    }

    fun generateReleaseKeystore(
        alias: String,
        storePassword: String,
        commonName: String,
        organization: String,
    ) {
        if (storePassword.length < 6) {
            _statusBanner.value = "Keystore password must be at least 6 characters."
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val outFile = File(repository.keystoresDir, "release_keystore.p12")
                    KeystoreManager.generatePkcs12Keystore(
                        outputFile = outFile,
                        alias = alias,
                        storePassword = storePassword,
                        keyPassword = storePassword,
                        commonName = commonName,
                        organization = organization,
                    )
                }
            }
            result.onSuccess { cfg ->
                repository.saveReleaseSigningConfig(cfg)
                _statusBanner.value = "Generated 2048-bit Release Keystore (${cfg.alias})."
            }.onFailure { err ->
                _statusBanner.value = "Keystore generation failed: ${err.localizedMessage}"
            }
        }
    }

    fun importKeystoreFromUri(uri: Uri, alias: String, storePassword: String) {
        if (storePassword.isBlank()) {
            _statusBanner.value = "Enter the keystore password before importing."
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(repository.keystoresDir, "imported_release.keystore")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Unable to open keystore file")

                    val signer = KeystoreManager.inspectKeystore(
                        keystoreFile = target,
                        storePassword = storePassword,
                        preferredAlias = alias,
                        keyPassword = storePassword,
                    )
                    ReleaseSigningConfig(
                        keystorePath = target.absolutePath,
                        alias = signer.alias,
                        storePassword = storePassword,
                        keyPassword = storePassword,
                        commonName = signer.subject,
                        sha1Fingerprint = signer.sha1Fingerprint,
                        sha256Fingerprint = signer.sha256Fingerprint,
                    )
                }
            }
            result.onSuccess { cfg ->
                repository.saveReleaseSigningConfig(cfg)
                _statusBanner.value = "Imported release keystore (${cfg.alias})."
            }.onFailure { err ->
                _statusBanner.value = "Keystore import failed: ${err.localizedMessage}"
            }
        }
    }

    fun clearReleaseKeystore() {
        viewModelScope.launch {
            repository.saveReleaseSigningConfig(null)
            _statusBanner.value = "Cleared custom release keystore."
        }
    }

    fun jumpToDiagnostic(diagnostic: BuildDiagnostic) {
        val exists = _projectFiles.value.any { it.relativePath == diagnostic.filePath }
        if (exists) {
            openFile(diagnostic.filePath)
            _jumpToLine.value = diagnostic.filePath to diagnostic.line
        }
    }

    fun jumpToFileAndLine(path: String, line: Int) {
        if (_projectFiles.value.any { it.relativePath == path }) {
            openFile(path)
            _jumpToLine.value = path to line
        }
    }

    fun clearJumpRequest() {
        _jumpToLine.value = null
    }

    fun exportApkToUri(destinationUri: Uri) {
        val apkPath = _lastBuildResult.value?.apkPath ?: return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val src = File(apkPath)
                    getApplication<Application>().contentResolver.openOutputStream(destinationUri)?.use { out ->
                        src.inputStream().use { input -> input.copyTo(out) }
                    }
                    true
                }.getOrDefault(false)
            }
            _statusBanner.value = if (saved) {
                "Artifact saved successfully."
            } else {
                "Failed to save artifact to selected destination."
            }
        }
    }

    fun exportProjectZipToUri(destinationUri: Uri) {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            _projectFiles.value.forEach { file ->
                repository.saveFile(project.id, file.relativePath, file.content)
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(destinationUri)?.use { out ->
                        repository.exportProjectZip(project.id, out)
                    }
                    true
                }.getOrDefault(false)
            }
            _statusBanner.value = if (ok) {
                "Project exported as ZIP."
            } else {
                "Failed to export project ZIP."
            }
        }
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun searchWorkspace(query: String): List<ProjectSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val hits = mutableListOf<ProjectSearchHit>()
        for (file in _projectFiles.value) {
            file.content.lines().forEachIndexed { idx, line ->
                if (hits.size < 80 && line.contains(needle, ignoreCase = true)) {
                    hits.add(
                        ProjectSearchHit(
                            relativePath = file.relativePath,
                            lineNumber = idx + 1,
                            previewLine = line.trim().take(140),
                        ),
                    )
                }
            }
        }
        return hits
    }

    // ---- Multi-Provider AI & Side Chats ----

    fun selectProvider(provider: AiProvider) {
        viewModelScope.launch {
            repository.saveActiveProvider(provider)
        }
    }

    fun selectThinkingMode(mode: AiThinkingMode) {
        viewModelScope.launch {
            repository.saveThinkingMode(mode)
        }
    }

    fun selectModel(provider: AiProvider, modelId: String) {
        viewModelScope.launch {
            val currentKey = providerApiKeys.value[provider].orEmpty()
            repository.saveProviderConfig(provider, currentKey, modelId)
            repository.saveActiveProvider(provider)
        }
    }

    fun saveProviderConfig(provider: AiProvider, apiKey: String, modelId: String) {
        viewModelScope.launch {
            repository.saveProviderConfig(provider, apiKey, modelId)
            repository.saveActiveProvider(provider)
            val cleanModel = modelId.trim().removePrefix("models/").ifBlank { provider.defaultModelId }
            _statusBanner.value = "Saved ${provider.shortName} settings ($cleanModel)."
        }
    }

    fun saveGeminiConfig(apiKey: String, modelId: String) {
        saveProviderConfig(AiProvider.GEMINI, apiKey, modelId)
    }

    fun refreshLiveModels(provider: AiProvider) {
        if (_isFetchingModels.value) return
        val key = when (provider) {
            AiProvider.GEMINI -> GeminiCodeAssistant.resolveApiKey(providerApiKeys.value[AiProvider.GEMINI].orEmpty())
            else -> providerApiKeys.value[provider].orEmpty()
        }
        viewModelScope.launch {
            _isFetchingModels.value = true
            val res = AiModelsCatalog.fetchLiveModels(provider, key)
            _isFetchingModels.value = false
            res.onSuccess { fetched ->
                val otherProviders = _liveModels.value.filter { it.provider != provider }
                _liveModels.value = otherProviders + fetched
                _statusBanner.value = "Loaded ${fetched.size} ${provider.shortName} model(s)."
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Failed to fetch models."
            }
        }
    }

    fun resetAiUsage() {
        viewModelScope.launch {
            repository.resetAiUsage()
            _statusBanner.value = "AI usage counters reset."
        }
    }

    fun selectChatThread(threadId: String) {
        if (_activeThreadId.value == threadId) return
        persistActiveThreadMessages()
        val target = _chatThreads.value.find { it.id == threadId } ?: return
        _activeThreadId.value = target.id
        geminiAssistant.setMessages(target.messages)
    }

    fun createSideChat(title: String = "") {
        persistActiveThreadMessages()
        val sideCount = _chatThreads.value.count { !it.isMain } + 1
        val thread = ProjectChatStore.createSideThread(sideCount, title)
        val updated = _chatThreads.value + thread
        _chatThreads.value = updated
        _activeThreadId.value = thread.id
        if (_splitSideThreadId.value == null) {
            _splitSideThreadId.value = thread.id
        }
        geminiAssistant.setMessages(emptyList())
        saveThreadsToDisk(updated)
    }

    fun renameChatThread(threadId: String, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return
        val updated = _chatThreads.value.map {
            if (it.id == threadId) it.copy(title = clean, updatedAtMillis = System.currentTimeMillis()) else it
        }
        _chatThreads.value = updated
        saveThreadsToDisk(updated)
    }

    fun deleteChatThread(threadId: String) {
        val target = _chatThreads.value.find { it.id == threadId } ?: return
        if (target.isMain) return
        val updated = _chatThreads.value.filterNot { it.id == threadId }
        _chatThreads.value = updated
        if (_activeThreadId.value == threadId) {
            val fallback = updated.first()
            _activeThreadId.value = fallback.id
            geminiAssistant.setMessages(fallback.messages)
        }
        if (_splitSideThreadId.value == threadId) {
            _splitSideThreadId.value = updated.firstOrNull { !it.isMain }?.id
        }
        saveThreadsToDisk(updated)
    }

    fun setSplitSideThread(threadId: String?) {
        _splitSideThreadId.value = threadId
    }

    fun askGemini(prompt: String) {
        val project = _activeProject.value ?: return
        val active = _projectFiles.value.find { it.relativePath == _activeFilePath.value }
        val diagnostics = _lastBuildResult.value?.diagnostics.orEmpty()
        val provider = activeProvider.value
        val thinkingMode = aiThinkingMode.value
        val apiKey = providerApiKeys.value[provider].orEmpty()
        val modelId = providerModels.value[provider].orEmpty().ifBlank { provider.defaultModelId }
        val threadIdAtStart = _activeThreadId.value
        val previousJob = aiJob

        aiJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()
            val executor = createToolExecutor(project.id, project.packageName)
            geminiAssistant.askGemini(
                userPrompt = prompt,
                apiKey = apiKey,
                modelId = modelId,
                provider = provider,
                thinkingMode = thinkingMode,
                projectName = project.name,
                packageName = project.packageName,
                activeFile = active,
                allFiles = _projectFiles.value,
                diagnostics = diagnostics,
                toolExecutor = executor,
                shouldPublishToUi = { _activeThreadId.value == threadIdAtStart },
                onUsageRecorded = { usedProvider, usedModel, delta, tools, failed ->
                    viewModelScope.launch {
                        repository.recordAiUsage(usedProvider, usedModel, delta, tools, failed)
                    }
                },
                onMessagesChanged = { latestMsgs ->
                    val updated = _chatThreads.value.map { t ->
                        if (t.id == threadIdAtStart) {
                            t.copy(messages = latestMsgs, updatedAtMillis = System.currentTimeMillis())
                        } else {
                            t
                        }
                    }
                    _chatThreads.value = updated
                    saveThreadsToDisk(updated)
                },
            )
        }
    }

    fun cancelGemini() {
        geminiAssistant.cancelByUser()
        aiJob?.cancel()
        aiJob = null
        persistActiveThreadMessages()
    }

    fun retryLastGeminiPrompt() {
        val lastPrompt = geminiAssistant.popLastPromptForRetry() ?: return
        askGemini(lastPrompt)
    }

    fun clearAiChat() {
        geminiAssistant.clearConversation()
        persistActiveThreadMessages()
    }

    private fun persistActiveThreadMessages() {
        val threadId = _activeThreadId.value
        val currentMsgs = geminiAssistant.messages.value.map { it.copy(isStreaming = false) }
        val updated = _chatThreads.value.map { t ->
            if (t.id == threadId) {
                t.copy(messages = currentMsgs, updatedAtMillis = System.currentTimeMillis())
            } else {
                t
            }
        }
        _chatThreads.value = updated
        saveThreadsToDisk(updated)
    }

    private fun saveThreadsToDisk(threads: List<ProjectChatThread>) {
        val project = _activeProject.value ?: return
        val dir = repository.getProjectDir(project.id)
        viewModelScope.launch {
            ProjectChatStore.saveThreads(dir, threads)
        }
    }

    private fun createToolExecutor(
        projectId: String,
        packageName: String,
    ): ProjectToolExecutor = object : ProjectToolExecutor {

        private suspend fun flushInMemoryFiles() {
            saveJob?.cancel()
            _projectFiles.value.forEach { file ->
                repository.saveFile(projectId, file.relativePath, file.content)
            }
        }

        override suspend fun listFiles(): AgentToolResult {
            flushInMemoryFiles()
            val paths = repository.listAllProjectPaths(projectId, includeBuild = false)
            val arr = JSONArray().apply { paths.forEach { put(it) } }
            return AgentToolResult(
                success = true,
                stepDetail = "${paths.size} file(s) in project",
                payload = JSONObject().put("files", arr).put("count", paths.size),
                detailedOutput = paths.joinToString("\n"),
            )
        }

        override suspend fun readFile(path: String): AgentToolResult {
            flushInMemoryFiles()
            val cleanPath = path.trim().replace('\\', '/').trimStart('/')
            val target = repository.resolveProjectFile(projectId, cleanPath)
                ?: return AgentToolResult(
                    success = false,
                    stepDetail = "Invalid path: $path",
                    payload = JSONObject().put("error", "Path is outside project directory or empty: $path"),
                )
            if (!target.exists() || !target.isFile) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "File not found: $cleanPath",
                    payload = JSONObject().put("error", "File does not exist: $cleanPath"),
                )
            }
            val content = withContext(Dispatchers.IO) { target.readText() }
            val lines = content.lines()
            val truncated = if (content.length > 50_000) content.take(50_000) + "\n...[truncated]" else content
            return AgentToolResult(
                success = true,
                stepDetail = "Read ${lines.size} lines (${content.length} chars)",
                payload = JSONObject()
                    .put("path", cleanPath)
                    .put("lineCount", lines.size)
                    .put("content", truncated),
                detailedOutput = lines.take(18).joinToString("\n") +
                    if (lines.size > 18) "\n... (+${lines.size - 18} more lines)" else "",
            )
        }

        override suspend fun writeFile(path: String, content: String): AgentToolResult {
            flushInMemoryFiles()
            val cleanPath = path.trim().replace('\\', '/').trimStart('/')
            val target = repository.resolveProjectFile(projectId, cleanPath)
                ?: return AgentToolResult(
                    success = false,
                    stepDetail = "Invalid path: $path",
                    payload = JSONObject().put("error", "Path is outside project directory or empty: $path"),
                )
            val existed = target.exists()
            repository.saveFile(projectId, cleanPath, content)
            _projectFiles.value = repository.loadProjectFiles(projectId)
            if (!cleanPath.startsWith("build/") && !cleanPath.startsWith(".droidforge/") && cleanPath != "project.json") {
                openFile(cleanPath)
            }
            val verb = if (existed) "Updated" else "Created"
            val lineCount = content.lines().size
            return AgentToolResult(
                success = true,
                stepDetail = "$verb $lineCount lines",
                payload = JSONObject()
                    .put("status", verb.lowercase())
                    .put("path", cleanPath)
                    .put("lineCount", lineCount),
                detailedOutput = content.lines().take(20).joinToString("\n") +
                    if (lineCount > 20) "\n... ($lineCount lines total)" else "",
            )
        }

        override suspend fun editFile(path: String, oldText: String, newText: String): AgentToolResult {
            flushInMemoryFiles()
            val cleanPath = path.trim().replace('\\', '/').trimStart('/')
            val target = repository.resolveProjectFile(projectId, cleanPath)
                ?: return AgentToolResult(
                    success = false,
                    stepDetail = "Invalid path: $path",
                    payload = JSONObject().put("error", "Path is outside project directory or empty: $path"),
                )
            if (!target.exists() || !target.isFile) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "File not found: $cleanPath",
                    payload = JSONObject().put("error", "File does not exist: $cleanPath"),
                )
            }
            val current = withContext(Dispatchers.IO) { target.readText() }
            val normalizedCurrent = current.replace("\r\n", "\n")
            val normalizedOld = oldText.replace("\r\n", "\n")
            val normalizedNew = newText.replace("\r\n", "\n")

            if (normalizedOld.isEmpty()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Empty old_text",
                    payload = JSONObject().put("error", "old_text must not be empty. Use write_file instead."),
                )
            }
            val firstIdx = normalizedCurrent.indexOf(normalizedOld)
            if (firstIdx < 0) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Snippet not found in $cleanPath",
                    payload = JSONObject().put("error", "old_text was not found in $cleanPath. Call read_file first."),
                )
            }
            val secondIdx = normalizedCurrent.indexOf(normalizedOld, startIndex = firstIdx + normalizedOld.length)
            if (secondIdx >= 0) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Multiple matches in $cleanPath",
                    payload = JSONObject().put("error", "old_text matched multiple locations in $cleanPath."),
                )
            }

            val updated = normalizedCurrent.replaceFirst(normalizedOld, normalizedNew)
            repository.saveFile(projectId, cleanPath, updated)
            _projectFiles.value = repository.loadProjectFiles(projectId)
            if (!cleanPath.startsWith("build/") && !cleanPath.startsWith(".droidforge/") && cleanPath != "project.json") {
                openFile(cleanPath)
            }
            val oldLines = normalizedOld.lines().size
            val newLines = normalizedNew.lines().size
            val diffPreview = buildString {
                normalizedOld.lines().take(6).forEach { appendLine("- $it") }
                normalizedNew.lines().take(8).forEach { appendLine("+ $it") }
            }.trim()

            return AgentToolResult(
                success = true,
                stepDetail = "Edited (-$oldLines / +$newLines lines)",
                payload = JSONObject().put("status", "edited").put("path", cleanPath),
                detailedOutput = diffPreview,
            )
        }

        override suspend fun deleteFile(path: String): AgentToolResult {
            flushInMemoryFiles()
            val cleanPath = path.trim().replace('\\', '/').trimStart('/')
            if (cleanPath == "AndroidManifest.xml" || cleanPath == "project.json") {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Protected file: $cleanPath",
                    payload = JSONObject().put("error", "$cleanPath cannot be deleted."),
                )
            }
            val target = repository.resolveProjectFile(projectId, cleanPath)
                ?: return AgentToolResult(
                    success = false,
                    stepDetail = "Invalid path: $path",
                    payload = JSONObject().put("error", "Path is outside project directory: $path"),
                )
            if (!target.exists()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "File not found: $cleanPath",
                    payload = JSONObject().put("error", "File does not exist: $cleanPath"),
                )
            }
            repository.deleteFile(projectId, cleanPath)
            closeTab(cleanPath)
            _projectFiles.value = repository.loadProjectFiles(projectId)
            return AgentToolResult(
                success = true,
                stepDetail = "Deleted $cleanPath",
                payload = JSONObject().put("status", "deleted").put("path", cleanPath),
            )
        }

        override suspend fun searchFiles(query: String): AgentToolResult {
            flushInMemoryFiles()
            val needle = query.trim()
            if (needle.isEmpty()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Empty search query",
                    payload = JSONObject().put("error", "Search query cannot be empty."),
                )
            }
            val paths = repository.listAllProjectPaths(projectId, includeBuild = false)
            val matches = JSONArray()
            val previewLines = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (relPath in paths) {
                    if (matches.length() >= 50) break
                    val file = repository.resolveProjectFile(projectId, relPath) ?: continue
                    file.readLines().forEachIndexed { idx, lineText ->
                        if (matches.length() < 50 && lineText.contains(needle, ignoreCase = true)) {
                            val trimmed = lineText.trim().take(140)
                            matches.put(
                                JSONObject()
                                    .put("path", relPath)
                                    .put("line", idx + 1)
                                    .put("text", trimmed),
                            )
                            if (previewLines.size < 12) {
                                previewLines.add("$relPath:${idx + 1}: $trimmed")
                            }
                        }
                    }
                }
            }
            return AgentToolResult(
                success = true,
                stepDetail = "${matches.length()} match(es)",
                payload = JSONObject().put("query", needle).put("matches", matches),
                detailedOutput = previewLines.joinToString("\n"),
            )
        }

        override suspend fun buildApk(isRelease: Boolean): AgentToolResult {
            val variant = if (isRelease) BuildVariant.RELEASE_APK else BuildVariant.DEBUG_APK
            return runToolBuild(variant)
        }

        override suspend fun buildAab(): AgentToolResult {
            return runToolBuild(BuildVariant.RELEASE_AAB)
        }

        private suspend fun runToolBuild(variant: BuildVariant): AgentToolResult {
            flushInMemoryFiles()
            _isBuilding.value = true
            _liveLogs.value = listOf("AI triggered ${variant.label} build...")
            _lastBuildResult.value = null

            val result = try {
                apkBuilder.buildProject(
                    projectDir = repository.getProjectDir(projectId),
                    packageName = packageName,
                    variant = variant,
                    releaseSigning = releaseSigningConfig.value,
                    onProgress = { line ->
                        _liveLogs.value = _liveLogs.value + line
                    },
                )
            } finally {
                _isBuilding.value = false
            }

            _lastBuildResult.value = result
            repository.recordBuildResult(projectId, result.success, result.apkSizeBytes, variant)
            _projects.value = repository.listProjects()
            _activeProject.value = _projects.value.find { it.id == projectId }

            val diagArray = JSONArray().apply {
                result.diagnostics.take(25).forEach { d ->
                    put(
                        JSONObject()
                            .put("severity", d.severity.name)
                            .put("filePath", d.filePath)
                            .put("line", d.line)
                            .put("message", d.message),
                    )
                }
            }
            val summary = if (result.success) {
                "${variant.shortLabel} built in ${result.durationMs}ms (${result.apkSizeBytes / 1024} KB)"
            } else {
                "Failed with ${result.diagnostics.size} diagnostic(s)"
            }
            return AgentToolResult(
                success = result.success,
                stepDetail = summary,
                payload = JSONObject()
                    .put("success", result.success)
                    .put("variant", variant.name)
                    .put("durationMs", result.durationMs)
                    .put("artifactSizeBytes", result.apkSizeBytes)
                    .put("compiledClassCount", result.compiledClassCount)
                    .put("diagnostics", diagArray)
                    .put("logs", JSONArray().apply { result.logs.takeLast(20).forEach { put(it) } }),
                detailedOutput = result.logs.joinToString("\n"),
            )
        }
    }

    fun applySuggestedCode(block: SuggestedCodeBlock) {
        val project = _activeProject.value ?: return
        val targetPath = block.suggestedPath
            ?.takeIf { it.isNotBlank() }
            ?: _activeFilePath.value
            ?: return

        viewModelScope.launch {
            repository.saveFile(project.id, targetPath, block.code)
            _projectFiles.value = repository.loadProjectFiles(project.id)
            openFile(targetPath)
            _statusBanner.value = "Applied AI code to $targetPath"
        }
    }

    fun updateEditorSettings(transform: (EditorSettings) -> EditorSettings) {
        viewModelScope.launch {
            repository.updateEditorSettings(transform)
        }
    }

    private fun buildStarterContentForNewFile(relPath: String, packageName: String): String {
        return when {
            relPath.endsWith(".java") -> {
                val className = relPath.substringAfterLast('/').removeSuffix(".java")
                val pkgSub = relPath.removePrefix("src/").substringBeforeLast('/', "")
                    .replace('/', '.')
                    .ifBlank { packageName }
                """
                package $pkgSub;

                public class $className {
                    public $className() {
                    }
                }
                """.trimIndent() + "\n"
            }
            relPath.endsWith(".xml") -> """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                </resources>
            """.trimIndent() + "\n"
            else -> ""
        }
    }

    private fun sanitizePackageName(raw: String): String {
        val cleaned = raw.lowercase()
            .replace(Regex("[^a-z0-9._]"), "")
            .split('.')
            .filter { it.isNotBlank() }
            .map { seg -> if (seg.first().isDigit()) "a$seg" else seg }
            .joinToString(".")
        return if (cleaned.contains('.')) cleaned else "com.example.${cleaned.ifBlank { "app" }}"
    }
}
