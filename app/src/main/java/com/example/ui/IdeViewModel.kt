package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AgentRole
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
import com.example.data.GitHubPendingChange
import com.example.data.ProjectRepository
import com.example.github.GitHubBranch
import com.example.github.GitHubClient
import com.example.github.GitHubRepoSummary
import com.example.github.GitHubTreeEntry
import com.example.github.GitHubWorkflowArtifact
import com.example.github.GitHubWorkflowJob
import com.example.github.GitHubWorkflowRun
import com.example.model.AiAttachment
import com.example.model.BuildDiagnostic
import com.example.model.BuildResult
import com.example.model.BuildVariant
import com.example.model.EditorSettings
import com.example.model.GitHubRepoLink
import com.example.model.JavaVersionOption
import com.example.model.ProjectFile
import com.example.model.ProjectLanguage
import com.example.model.ProjectMetadata
import com.example.model.ProjectSearchHit
import com.example.model.ProjectTemplate
import com.example.model.ReleaseSigningConfig
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class IdeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val apkBuilder = OfflineApkBuilder(application)
    private val assistants = ConcurrentHashMap<String, GeminiCodeAssistant>()
    private val aiJobs = ConcurrentHashMap<String, Job>()
    private val assistantCollectors = ConcurrentHashMap<String, Pair<Job, Job>>()
    private val fileWriteMutex = Mutex()

    private val _projects = MutableStateFlow<List<ProjectMetadata>>(emptyList())
    val projects: StateFlow<List<ProjectMetadata>> = _projects.asStateFlow()

    private val _isBootLoading = MutableStateFlow(true)
    val isBootLoading: StateFlow<Boolean> = _isBootLoading.asStateFlow()

    private val _isLoadingProjects = MutableStateFlow(true)
    val isLoadingProjects: StateFlow<Boolean> = _isLoadingProjects.asStateFlow()

    private val _openingProjectName = MutableStateFlow<String?>(null)
    val openingProjectName: StateFlow<String?> = _openingProjectName.asStateFlow()

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

    private val _pendingAiAttachments = MutableStateFlow<List<AiAttachment>>(emptyList())
    val pendingAiAttachments: StateFlow<List<AiAttachment>> = _pendingAiAttachments.asStateFlow()

    val githubToken: StateFlow<String> = repository.githubToken.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "",
    )

    val githubUser: StateFlow<String> = repository.githubUser.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "",
    )

    private val _githubRepos = MutableStateFlow<List<GitHubRepoSummary>>(emptyList())
    val githubRepos: StateFlow<List<GitHubRepoSummary>> = _githubRepos.asStateFlow()

    private val _githubPendingChanges = MutableStateFlow<List<GitHubPendingChange>>(emptyList())
    val githubPendingChanges: StateFlow<List<GitHubPendingChange>> = _githubPendingChanges.asStateFlow()

    private val _githubWorkflowRuns = MutableStateFlow<List<GitHubWorkflowRun>>(emptyList())
    val githubWorkflowRuns: StateFlow<List<GitHubWorkflowRun>> = _githubWorkflowRuns.asStateFlow()

    private val _githubBranches = MutableStateFlow<List<GitHubBranch>>(emptyList())
    val githubBranches: StateFlow<List<GitHubBranch>> = _githubBranches.asStateFlow()

    private val _githubRemoteFiles = MutableStateFlow<List<GitHubTreeEntry>>(emptyList())
    val githubRemoteFiles: StateFlow<List<GitHubTreeEntry>> = _githubRemoteFiles.asStateFlow()

    private val _githubWorkflowJobs = MutableStateFlow<List<GitHubWorkflowJob>>(emptyList())
    val githubWorkflowJobs: StateFlow<List<GitHubWorkflowJob>> = _githubWorkflowJobs.asStateFlow()

    private val _githubSelectedRunLogs = MutableStateFlow("")
    val githubSelectedRunLogs: StateFlow<String> = _githubSelectedRunLogs.asStateFlow()

    private val _githubWorkflowArtifacts = MutableStateFlow<List<GitHubWorkflowArtifact>>(emptyList())
    val githubWorkflowArtifacts: StateFlow<List<GitHubWorkflowArtifact>> = _githubWorkflowArtifacts.asStateFlow()

    private val _githubInspectedRunId = MutableStateFlow<Long?>(null)
    val githubInspectedRunId: StateFlow<Long?> = _githubInspectedRunId.asStateFlow()

    private val _openAiPanelRequest = MutableStateFlow(0L)
    val openAiPanelRequest: StateFlow<Long> = _openAiPanelRequest.asStateFlow()

    private val _isGitHubBusy = MutableStateFlow(false)
    val isGitHubBusy: StateFlow<Boolean> = _isGitHubBusy.asStateFlow()

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
            AiProvider.OPENROUTER to AiProvider.OPENROUTER.defaultModelId,
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

    private val _aiMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val aiMessages: StateFlow<List<ChatMessage>> = _aiMessages.asStateFlow()

    private val _aiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val aiState: StateFlow<AiUiState> = _aiState.asStateFlow()

    private val _threadMessagesMap = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val threadMessagesMap: StateFlow<Map<String, List<ChatMessage>>> = _threadMessagesMap.asStateFlow()

    private val _threadUiStates = MutableStateFlow<Map<String, AiUiState>>(emptyMap())
    val threadUiStates: StateFlow<Map<String, AiUiState>> = _threadUiStates.asStateFlow()

    private val _runningThreadIds = MutableStateFlow<Set<String>>(emptySet())
    val runningThreadIds: StateFlow<Set<String>> = _runningThreadIds.asStateFlow()

    private val _terminalLines = MutableStateFlow(
        listOf(
            "Android Studio Workspace Console — type `help` for commands (build, ls, grep, git status, cat).",
        ),
    )
    val terminalLines: StateFlow<List<String>> = _terminalLines.asStateFlow()

    private var saveJob: Job? = null

    private fun assistantFor(threadId: String): GeminiCodeAssistant {
        return assistants.getOrPut(threadId) {
            GeminiCodeAssistant().also { inst ->
                val initialMsgs = _chatThreads.value.find { it.id == threadId }?.messages.orEmpty()
                if (initialMsgs.isNotEmpty()) inst.setMessages(initialMsgs)
                val msgJob = viewModelScope.launch {
                    inst.messages.collect { msgs ->
                        _threadMessagesMap.value = _threadMessagesMap.value + (threadId to msgs)
                        if (_activeThreadId.value == threadId) {
                            _aiMessages.value = msgs
                        }
                    }
                }
                val stateJob = viewModelScope.launch {
                    inst.uiState.collect { st ->
                        _threadUiStates.value = _threadUiStates.value + (threadId to st)
                        if (st is AiUiState.Streaming) {
                            _runningThreadIds.value = _runningThreadIds.value + threadId
                        } else {
                            _runningThreadIds.value = _runningThreadIds.value - threadId
                        }
                        if (_activeThreadId.value == threadId) {
                            _aiState.value = st
                        }
                    }
                }
                assistantCollectors[threadId] = msgJob to stateJob
            }
        }
    }

    init {
        assistantFor("main")
        viewModelScope.launch {
            try {
                _projects.value = repository.listProjects()
            } finally {
                _isLoadingProjects.value = false
                _isBootLoading.value = false
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val savedKeys = repository.providerApiKeys.first()
                val savedGeminiKey = savedKeys[AiProvider.GEMINI].orEmpty()
                val resolvedKey = GeminiCodeAssistant.resolveApiKey(savedGeminiKey)
                val savedModel = repository.providerModels.first()[AiProvider.GEMINI].orEmpty()

                // Upgrade a saved model that Google has since retired. Without this a stale
                // preference (e.g. gemini-2.5-flash) makes every Gemini turn fail with 404.
                val deRetired = AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, savedModel)
                if (savedModel.isNotBlank() && !deRetired.equals(savedModel, ignoreCase = true)) {
                    repository.saveProviderConfig(AiProvider.GEMINI, savedGeminiKey, deRetired)
                }

                if (resolvedKey.isNotBlank()) {
                    val fetched = AiModelsCatalog.fetchLiveModels(AiProvider.GEMINI, resolvedKey).getOrNull().orEmpty()
                    if (fetched.isNotEmpty()) {
                        val otherProviders = _liveModels.value.filter { it.provider != AiProvider.GEMINI }
                        _liveModels.value = otherProviders + fetched
                        autoHealUnavailableGeminiModel(fetched, explicitApiKey = savedGeminiKey)
                    }
                }

                val savedOrKey = savedKeys[AiProvider.OPENROUTER].orEmpty()
                val resolvedOrKey = GeminiCodeAssistant.resolveOpenRouterApiKey(savedOrKey)
                val activeProv = runCatching { repository.activeProvider.first() }.getOrDefault(AiProvider.GEMINI)
                if (resolvedOrKey.isNotBlank() || activeProv == AiProvider.OPENROUTER) {
                    val fetchedOr = AiModelsCatalog.fetchOpenRouterPublicModels(resolvedOrKey).getOrNull().orEmpty()
                    if (fetchedOr.isNotEmpty()) {
                        val otherProviders = _liveModels.value.filter { it.provider != AiProvider.OPENROUTER }
                        _liveModels.value = otherProviders + fetchedOr
                        autoHealUnavailableOpenRouterModel(fetchedOr, explicitApiKey = savedOrKey)
                    }
                }

                val savedOpenAiKey = savedKeys[AiProvider.OPENAI].orEmpty()
                if (savedOpenAiKey.isNotBlank()) {
                    val fetchedOpenAi = AiModelsCatalog.fetchLiveModels(AiProvider.OPENAI, savedOpenAiKey).getOrNull().orEmpty()
                    if (fetchedOpenAi.isNotEmpty()) {
                        val otherProviders = _liveModels.value.filter { it.provider != AiProvider.OPENAI }
                        _liveModels.value = otherProviders + fetchedOpenAi
                    }
                }

                val savedClaudeKey = savedKeys[AiProvider.CLAUDE].orEmpty()
                if (savedClaudeKey.isNotBlank()) {
                    val fetchedClaude = AiModelsCatalog.fetchLiveModels(AiProvider.CLAUDE, savedClaudeKey).getOrNull().orEmpty()
                    if (fetchedClaude.isNotEmpty()) {
                        val otherProviders = _liveModels.value.filter { it.provider != AiProvider.CLAUDE }
                        _liveModels.value = otherProviders + fetchedClaude
                    }
                }
            }
        }
    }

    private suspend fun autoHealUnavailableGeminiModel(
        fetched: List<AiModelOption>,
        explicitApiKey: String? = null,
    ) {
        if (fetched.isEmpty()) return
        val savedModel = repository.providerModels.first()[AiProvider.GEMINI].orEmpty()
        val fetchedIds = fetched.map { it.id.lowercase() }.toSet()
        if (savedModel.lowercase() !in fetchedIds) {
            // Prefer a model this key has actually been verified to serve, walking our
            // known-good pool in order before falling back to anything else live.
            val replacement = AiModelsCatalog.geminiFailoverPool
                .firstOrNull { pooled -> fetched.any { it.id.equals(pooled, ignoreCase = true) } }
                ?: fetched.firstOrNull { AiModelsCatalog.isCodingCapableGeminiModel(it.id) }?.id
                ?: GEMINI_MODEL_ID
            val keyToSave = explicitApiKey ?: repository.providerApiKeys.first()[AiProvider.GEMINI].orEmpty()
            repository.saveProviderConfig(AiProvider.GEMINI, keyToSave, replacement)
        }
    }

    private suspend fun autoHealUnavailableOpenRouterModel(
        fetched: List<AiModelOption>,
        explicitApiKey: String? = null,
    ) {
        if (fetched.size < 4) return
        val savedModel = AiModelsCatalog.normalizeModelId(
            AiProvider.OPENROUTER,
            repository.providerModels.first()[AiProvider.OPENROUTER],
        )
        if (AiModelsCatalog.isOpenRouterMetaRouter(savedModel)) return
        val resolved = AiModelsCatalog.resolveOpenRouterModelId(savedModel, fetched)
        if (!resolved.equals(savedModel, ignoreCase = true)) {
            val keyToSave = explicitApiKey ?: repository.providerApiKeys.first()[AiProvider.OPENROUTER].orEmpty()
            repository.saveProviderConfig(AiProvider.OPENROUTER, keyToSave, resolved)
        }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            if (_projects.value.isEmpty()) {
                _isLoadingProjects.value = true
            }
            try {
                _projects.value = repository.listProjects()
            } finally {
                _isLoadingProjects.value = false
            }
        }
    }

    fun createProject(
        name: String,
        packageName: String,
        template: ProjectTemplate,
        language: ProjectLanguage = ProjectLanguage.JAVA,
        javaVersion: JavaVersionOption = JavaVersionOption.JAVA_17,
        minSdkVersion: Int = 24,
    ) {
        viewModelScope.launch {
            val cleanName = name.trim().ifBlank { template.defaultAppName }
            _openingProjectName.value = cleanName
            try {
                val cleanPkg = sanitizePackageName(packageName.trim().ifBlank { template.defaultPackage })
                val meta = repository.createProject(cleanName, cleanPkg, template, language, javaVersion, minSdkVersion)
                _projects.value = repository.listProjects()
                openProjectInternal(meta)
            } finally {
                _openingProjectName.value = null
            }
        }
    }

    fun updateProjectConfig(
        language: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
    ) {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            val updated = repository.updateProjectConfig(project.id, language, javaVersion, minSdkVersion) ?: return@launch
            _activeProject.value = updated
            _projects.value = repository.listProjects()
            _projectFiles.value = repository.loadProjectFiles(project.id)
            refreshGitHubPendingChangesInternal(updated)
            val langLabel = if (updated.language == ProjectLanguage.JAVA) updated.javaVersion.badge else updated.language.displayName
            _statusBanner.value = "Updated project: $langLabel, Min SDK API ${updated.minSdkVersion}"
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

    private fun clearAllThreadRuntimeState() {
        aiJobs.values.forEach { it.cancel() }
        aiJobs.clear()
        assistantCollectors.values.forEach { (mJob, sJob) ->
            mJob.cancel()
            sJob.cancel()
        }
        assistantCollectors.clear()
        assistants.clear()
        _runningThreadIds.value = emptySet()
        _threadMessagesMap.value = emptyMap()
        _threadUiStates.value = emptyMap()
    }

    fun openProject(meta: ProjectMetadata) {
        clearAllThreadRuntimeState()
        viewModelScope.launch {
            _openingProjectName.value = meta.name
            try {
                openProjectInternal(meta)
            } finally {
                _openingProjectName.value = null
            }
        }
    }

    private suspend fun openProjectInternal(meta: ProjectMetadata) {
        val (files, threads) = withContext(Dispatchers.IO) {
            val projectDir = repository.getProjectDir(meta.id)
            val loadedFiles = repository.loadProjectFiles(meta.id)
            val loadedThreads = ProjectChatStore.loadThreads(projectDir)
            loadedFiles to loadedThreads
        }
        val firstThread = threads.first()

        _projectFiles.value = files
        val initialTabs = files.take(3).map { it.relativePath }
        _openTabs.value = initialTabs
        _activeFilePath.value = initialTabs.firstOrNull()
        _lastBuildResult.value = null
        _liveLogs.value = emptyList()
        _terminalLines.value = listOf(
            "Android Studio Workspace [${meta.name}] — ${files.size} file(s) loaded. Type `help` for commands.",
        )
        clearGitHubWorkspaceCaches()

        _chatThreads.value = threads
        _activeThreadId.value = firstThread.id
        _splitSideThreadId.value = threads.firstOrNull { !it.isMain }?.id
        threads.forEach { t ->
            assistantFor(t.id).setMessages(t.messages)
            _threadMessagesMap.value = _threadMessagesMap.value + (t.id to t.messages)
        }
        _aiMessages.value = firstThread.messages
        _aiState.value = AiUiState.Idle
        _activeProject.value = meta

        // Defer SHA-256 diffing so the IDE workspace opens immediately
        viewModelScope.launch {
            refreshGitHubPendingChangesInternal(meta)
        }
    }

    private fun clearGitHubWorkspaceCaches() {
        _githubWorkflowRuns.value = emptyList()
        _githubBranches.value = emptyList()
        _githubRemoteFiles.value = emptyList()
        _githubWorkflowJobs.value = emptyList()
        _githubSelectedRunLogs.value = ""
        _githubWorkflowArtifacts.value = emptyList()
        _githubInspectedRunId.value = null
    }

    fun closeProject() {
        saveAllImmediately()
        persistActiveThreadMessages()
        _activeProject.value = null
        _projectFiles.value = emptyList()
        _openTabs.value = emptyList()
        _activeFilePath.value = null
        _lastBuildResult.value = null
        _githubPendingChanges.value = emptyList()
        clearGitHubWorkspaceCaches()
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
            refreshGitHubPendingChangesInternal(project)
        }
    }

    fun deleteFile(relativePath: String) {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            repository.deleteFile(project.id, relativePath)
            closeTab(relativePath)
            _projectFiles.value = repository.loadProjectFiles(project.id)
            refreshGitHubPendingChangesInternal(project)
        }
    }

    fun updateActiveFileContent(newContent: String) {
        val project = _activeProject.value ?: return
        val path = _activeFilePath.value ?: return
        val active = _projectFiles.value.find { it.relativePath == path }
        if (active?.isBinary == true) return
        _projectFiles.value = _projectFiles.value.map { file ->
            if (file.relativePath == path) file.copy(content = newContent) else file
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(250L)
            repository.saveFile(project.id, path, newContent)
            refreshGitHubPendingChangesInternal(project)
        }
    }

    private fun saveAllImmediately() {
        val project = _activeProject.value ?: return
        val files = _projectFiles.value.filterNot { it.isBinary }
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

            _projectFiles.value.filterNot { it.isBinary }.forEach { file ->
                repository.saveFile(project.id, file.relativePath, file.content)
            }

            val result = apkBuilder.buildProject(
                projectDir = repository.getProjectDir(project.id),
                packageName = project.packageName,
                variant = variant,
                releaseSigning = signing,
                targetMinSdk = project.minSdkVersion,
                targetJavaVersion = project.javaVersion,
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
                    if (!src.exists() || src.length() == 0L) return@runCatching false
                    val resolver = getApplication<Application>().contentResolver
                    val out = runCatching { resolver.openOutputStream(destinationUri, "wt") }.getOrNull()
                        ?: resolver.openOutputStream(destinationUri)
                        ?: return@runCatching false
                    val copiedBytes = out.use { stream ->
                        src.inputStream().use { input ->
                            val count = input.copyTo(stream)
                            stream.flush()
                            count
                        }
                    }
                    copiedBytes == src.length()
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
            _projectFiles.value.filterNot { it.isBinary }.forEach { file ->
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
            if (provider == AiProvider.OPENROUTER && _liveModels.value.none { it.provider == AiProvider.OPENROUTER }) {
                refreshLiveModels(AiProvider.OPENROUTER, silentOnEmptyKey = true)
            }
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
            val cleanedKey = apiKey.trim()
            val adjustedModel = if (provider == AiProvider.GEMINI && modelId.isNotBlank()) {
                AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, modelId)
            } else {
                modelId
            }
            repository.saveProviderConfig(provider, cleanedKey, adjustedModel)
            repository.saveActiveProvider(provider)
            val cleanModel = adjustedModel.trim().removePrefix("models/").ifBlank { provider.defaultModelId }
            _statusBanner.value = "Saved ${provider.shortName} settings ($cleanModel)."
            if (cleanedKey.isNotBlank() || provider == AiProvider.OPENROUTER) {
                refreshLiveModels(provider, explicitApiKey = cleanedKey.ifBlank { null }, silentOnEmptyKey = cleanedKey.isBlank())
            }
        }
    }

    fun saveGeminiConfig(apiKey: String, modelId: String) {
        saveProviderConfig(AiProvider.GEMINI, apiKey, modelId)
    }

    fun refreshLiveModels(
        provider: AiProvider,
        explicitApiKey: String? = null,
        silentOnEmptyKey: Boolean = false,
    ) {
        if (_isFetchingModels.value) return
        val key = explicitApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: when (provider) {
            AiProvider.GEMINI -> GeminiCodeAssistant.resolveApiKey(providerApiKeys.value[AiProvider.GEMINI].orEmpty())
            AiProvider.OPENROUTER -> GeminiCodeAssistant.resolveOpenRouterApiKey(providerApiKeys.value[AiProvider.OPENROUTER].orEmpty())
            else -> providerApiKeys.value[provider].orEmpty()
        }
        viewModelScope.launch {
            _isFetchingModels.value = true
            val res = if (provider == AiProvider.OPENROUTER) {
                AiModelsCatalog.fetchOpenRouterPublicModels(key)
            } else {
                AiModelsCatalog.fetchLiveModels(provider, key)
            }
            _isFetchingModels.value = false
            res.onSuccess { fetched ->
                val otherProviders = _liveModels.value.filter { it.provider != provider }
                _liveModels.value = otherProviders + fetched
                if (provider == AiProvider.GEMINI && fetched.isNotEmpty()) {
                    autoHealUnavailableGeminiModel(fetched, explicitApiKey = explicitApiKey)
                } else if (provider == AiProvider.OPENROUTER && fetched.isNotEmpty()) {
                    autoHealUnavailableOpenRouterModel(fetched, explicitApiKey = explicitApiKey)
                }
                if (!silentOnEmptyKey) {
                    _statusBanner.value = "Loaded ${fetched.size} ${provider.shortName} model(s)."
                }
            }.onFailure { err ->
                if (!silentOnEmptyKey) {
                    _statusBanner.value = err.localizedMessage ?: "Failed to fetch models."
                }
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
        persistThreadMessages(_activeThreadId.value)
        val target = _chatThreads.value.find { it.id == threadId } ?: return
        _activeThreadId.value = target.id
        val inst = assistantFor(target.id)
        if (target.id !in _runningThreadIds.value && inst.messages.value.isEmpty() && target.messages.isNotEmpty()) {
            inst.setMessages(target.messages)
        }
        _aiMessages.value = inst.messages.value
        _aiState.value = inst.uiState.value
    }

    fun createSideChat(
        title: String = "",
        provider: AiProvider = activeProvider.value,
        modelId: String = providerModels.value[provider] ?: provider.defaultModelId,
        switchToThread: Boolean = true,
    ): String {
        persistThreadMessages(_activeThreadId.value)
        val sideCount = _chatThreads.value.count { !it.isMain } + 1
        val thread = ProjectChatStore.createSideThread(
            index = sideCount,
            customTitle = title,
            providerName = provider.name,
            modelId = modelId,
        )
        val updated = _chatThreads.value + thread
        _chatThreads.value = updated
        assistantFor(thread.id)
        if (switchToThread) {
            _activeThreadId.value = thread.id
            _aiMessages.value = emptyList()
            _aiState.value = AiUiState.Idle
        }
        if (_splitSideThreadId.value == null) {
            _splitSideThreadId.value = thread.id
        }
        saveThreadsToDisk(updated)
        return thread.id
    }

    fun ensureRoleAgentThread(
        role: AgentRole,
        provider: AiProvider? = null,
        modelId: String? = null,
    ): String {
        val existing = _chatThreads.value.find { it.agentRole == role }
        if (existing != null) return existing.id
        val resolvedProv = provider ?: activeProvider.value
        val resolvedModel = modelId ?: (providerModels.value[resolvedProv] ?: resolvedProv.defaultModelId)
        val created = ProjectChatStore.createRoleAgentThread(
            role = role,
            providerName = resolvedProv.name,
            modelId = resolvedModel,
        )
        val updated = _chatThreads.value + created
        _chatThreads.value = updated
        assistantFor(created.id)
        saveThreadsToDisk(updated)
        return created.id
    }

    fun updateThreadProviderAndModel(
        threadId: String,
        provider: AiProvider,
        modelId: String,
    ) {
        val cleanModel = AiModelsCatalog.normalizeModelId(provider, modelId)
        val updated = _chatThreads.value.map { t ->
            if (t.id == threadId) {
                t.copy(
                    providerName = provider.name,
                    modelId = cleanModel,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            } else {
                t
            }
        }
        _chatThreads.value = updated
        saveThreadsToDisk(updated)
    }

    fun updateThreadSystemAndTemperature(
        threadId: String,
        systemInstruction: String,
        temperature: Float,
    ) {
        val updated = _chatThreads.value.map { t ->
            if (t.id == threadId) {
                t.copy(
                    customSystemInstruction = systemInstruction.trim(),
                    temperature = temperature.coerceIn(0f, 1.5f),
                    updatedAtMillis = System.currentTimeMillis(),
                )
            } else {
                t
            }
        }
        _chatThreads.value = updated
        saveThreadsToDisk(updated)
    }

    fun resolveProviderAndModelForThread(thread: ProjectChatThread?): Pair<AiProvider, String> {
        val prov = thread?.providerName
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
            ?: activeProvider.value
        val mdl = thread?.modelId
            ?.takeIf { it.isNotBlank() }
            ?.let { AiModelsCatalog.normalizeModelId(prov, it) }
            ?: AiModelsCatalog.normalizeModelId(prov, providerModels.value[prov])
        return prov to mdl
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
        cancelThread(threadId)
        assistantCollectors.remove(threadId)?.let { (m, s) -> m.cancel(); s.cancel() }
        assistants.remove(threadId)
        val updated = _chatThreads.value.filterNot { it.id == threadId }
        _chatThreads.value = updated
        if (_activeThreadId.value == threadId) {
            val fallback = updated.first()
            _activeThreadId.value = fallback.id
            val inst = assistantFor(fallback.id)
            _aiMessages.value = inst.messages.value
            _aiState.value = inst.uiState.value
        }
        if (_splitSideThreadId.value == threadId) {
            _splitSideThreadId.value = updated.firstOrNull { !it.isMain }?.id
        }
        saveThreadsToDisk(updated)
    }

    fun setSplitSideThread(threadId: String?) {
        _splitSideThreadId.value = threadId
    }

    fun addAiAttachmentFromUri(uri: Uri, forceImage: Boolean = false) {
        viewModelScope.launch {
            val attachment = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val displayName = resolveDisplayName(uri) ?: if (forceImage) "photo_${System.currentTimeMillis()}.jpg" else "attachment"
                    val rawMime = resolver.getType(uri)?.lowercase().orEmpty()
                    val ext = displayName.substringAfterLast('.', "").lowercase()
                    val isImage = forceImage || rawMime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

                    if (isImage) {
                        val rawBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("Could not read image")
                        val compressed = compressImageForAi(rawBytes)
                        val base64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
                        AiAttachment(
                            id = UUID.randomUUID().toString().take(8),
                            displayName = displayName,
                            mimeType = "image/jpeg",
                            isImage = true,
                            sizeBytes = compressed.size.toLong(),
                            base64Data = base64,
                            previewUriString = uri.toString(),
                        )
                    } else {
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("Could not read file")
                        val text = bytes.take(48_000).toByteArray().toString(Charsets.UTF_8)
                        AiAttachment(
                            id = UUID.randomUUID().toString().take(8),
                            displayName = displayName,
                            mimeType = rawMime.ifBlank { "text/plain" },
                            isImage = false,
                            sizeBytes = bytes.size.toLong(),
                            textContent = text,
                            previewUriString = uri.toString(),
                        )
                    }
                }.getOrNull()
            }
            if (attachment != null) {
                _pendingAiAttachments.value = (_pendingAiAttachments.value + attachment).takeLast(5)
            } else {
                _statusBanner.value = "Failed to attach selected file or image."
            }
        }
    }

    fun removePendingAttachment(id: String) {
        _pendingAiAttachments.value = _pendingAiAttachments.value.filterNot { it.id == id }
    }

    fun importProjectFileFromUri(uri: Uri) {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val rawName = (resolveDisplayName(uri) ?: "imported_${System.currentTimeMillis()}")
                        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Unable to read selected file")
                    val pkgPath = project.packageName.replace('.', '/')
                    val lower = rawName.lowercase()
                    val targetRelPath = when {
                        lower.endsWith(".java") || lower.endsWith(".kt") -> "src/$pkgPath/$rawName"
                        lower.endsWith(".xml") -> "res/values/$rawName"
                        else -> "assets/$rawName"
                    }
                    if (repository.isBinaryPath(targetRelPath)) {
                        repository.saveBinaryFile(project.id, targetRelPath, bytes)
                    } else {
                        repository.saveFile(project.id, targetRelPath, bytes.toString(Charsets.UTF_8))
                    }
                    targetRelPath
                }
            }
            outcome.onSuccess { importedPath ->
                _projectFiles.value = repository.loadProjectFiles(project.id)
                openFile(importedPath)
                refreshGitHubPendingChangesInternal(project)
                _statusBanner.value = "Imported $importedPath into project."
            }.onFailure { err ->
                _statusBanner.value = "File import failed: ${err.localizedMessage}"
            }
        }
    }

    // ---- GitHub Account, Push, Pull & Cloud Actions CI ----

    private suspend fun refreshGitHubPendingChangesInternal(project: ProjectMetadata?) {
        val link = project?.githubLink
        if (project == null || link == null) {
            _githubPendingChanges.value = emptyList()
            return
        }
        _githubPendingChanges.value = repository.computeGitHubPendingChanges(project.id, link)
    }

    fun connectGitHubToken(token: String) {
        val cleaned = token.trim()
        if (cleaned.isBlank()) {
            _statusBanner.value = "Paste a GitHub Personal Access Token first."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            val res = GitHubClient.verifyToken(cleaned)
            res.onSuccess { user ->
                repository.saveGitHubAuth(cleaned, user.login)
                val reposRes = GitHubClient.listRepos(cleaned)
                _githubRepos.value = reposRes.getOrDefault(emptyList())
                _statusBanner.value = "Connected GitHub as @${user.login}."
            }.onFailure { err ->
                _statusBanner.value = "GitHub auth failed: ${err.localizedMessage}"
            }
            _isGitHubBusy.value = false
        }
    }

    fun disconnectGitHub() {
        viewModelScope.launch {
            repository.saveGitHubAuth("", "")
            _githubRepos.value = emptyList()
            clearGitHubWorkspaceCaches()
            _statusBanner.value = "Disconnected GitHub account."
        }
    }

    fun refreshGitHubRepos() {
        val token = githubToken.value
        if (token.isBlank()) {
            _statusBanner.value = "Connect a GitHub token first."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            val res = GitHubClient.listRepos(token)
            res.onSuccess { repos ->
                _githubRepos.value = repos
            }.onFailure { err ->
                _statusBanner.value = "Failed to load repositories: ${err.localizedMessage}"
            }
            _isGitHubBusy.value = false
        }
    }

    fun createAndLinkGitHubRepo(name: String, description: String, isPrivate: Boolean) {
        val project = _activeProject.value ?: return
        val token = githubToken.value
        if (token.isBlank()) {
            _statusBanner.value = "Connect a GitHub token first."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            val res = GitHubClient.createRepo(token, name, description, isPrivate)
            res.onSuccess { summary ->
                val link = GitHubRepoLink(
                    owner = summary.owner,
                    repo = summary.name,
                    branch = summary.defaultBranch,
                )
                val updated = repository.saveGitHubRepoLink(project.id, link)
                if (updated != null) {
                    _activeProject.value = updated
                    _projects.value = repository.listProjects()
                    refreshGitHubPendingChangesInternal(updated)
                }
                _githubRepos.value = listOf(summary) + _githubRepos.value.filterNot { it.fullName == summary.fullName }
                _statusBanner.value = "Created & linked ${summary.fullName}."
            }.onFailure { err ->
                _statusBanner.value = "Create repo failed: ${err.localizedMessage}"
            }
            _isGitHubBusy.value = false
        }
    }

    fun linkExistingGitHubRepo(owner: String, repo: String, branch: String) {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            val link = GitHubRepoLink(
                owner = owner.trim(),
                repo = repo.trim(),
                branch = branch.trim().ifBlank { "main" },
            )
            val updated = repository.saveGitHubRepoLink(project.id, link)
            if (updated != null) {
                _activeProject.value = updated
                _projects.value = repository.listProjects()
                refreshGitHubPendingChangesInternal(updated)
                _statusBanner.value = "Linked ${link.fullName} (${link.branch})."
                if (githubToken.value.isNotBlank()) {
                    refreshGitHubWorkflowRuns()
                }
            }
        }
    }

    fun pushProjectToGitHub(commitMessage: String) {
        val project = _activeProject.value ?: return
        val link = project.githubLink ?: run {
            _statusBanner.value = "Link a GitHub repository first."
            return
        }
        val token = githubToken.value
        if (token.isBlank()) {
            _statusBanner.value = "Connect a GitHub token first."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            _projectFiles.value.filterNot { it.isBinary }.forEach { file ->
                repository.saveFile(project.id, file.relativePath, file.content)
            }
            val res = GitHubClient.pushProject(
                token = token,
                link = link,
                projectDir = repository.getProjectDir(project.id),
                commitMessage = commitMessage,
            )
            res.onSuccess { pushRes ->
                val newLink = link.copy(
                    branch = pushRes.branch,
                    lastCommitSha = pushRes.commitSha,
                    lastPushedAtMillis = System.currentTimeMillis(),
                    lastPushedHashes = pushRes.uploadedHashes,
                )
                val updated = repository.saveGitHubRepoLink(project.id, newLink)
                if (updated != null) {
                    _activeProject.value = updated
                    _projects.value = repository.listProjects()
                    refreshGitHubPendingChangesInternal(updated)
                }
                _statusBanner.value = "Pushed ${pushRes.pushedFileCount} file(s) to ${link.fullName} (${pushRes.commitSha.take(7)})."
                refreshGitHubWorkflowRuns()
            }.onFailure { err ->
                _statusBanner.value = "Push failed: ${err.localizedMessage}"
            }
            _isGitHubBusy.value = false
        }
    }

    fun pullProjectFromGitHub() {
        val project = _activeProject.value ?: return
        val link = project.githubLink ?: run {
            _statusBanner.value = "Link a GitHub repository first."
            return
        }
        val token = githubToken.value
        if (token.isBlank()) {
            _statusBanner.value = "Connect a GitHub token first."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            val res = GitHubClient.pullRepository(
                token = token,
                link = link,
                targetDir = repository.getProjectDir(project.id),
            )
            res.onSuccess { pulledHashes ->
                _projectFiles.value = repository.loadProjectFiles(project.id)
                val hashes = repository.collectProjectFileHashes(project.id)
                val newLink = link.copy(
                    lastPushedAtMillis = System.currentTimeMillis(),
                    lastPushedHashes = hashes,
                )
                val updated = repository.saveGitHubRepoLink(project.id, newLink)
                if (updated != null) {
                    _activeProject.value = updated
                    _projects.value = repository.listProjects()
                    refreshGitHubPendingChangesInternal(updated)
                }
                _statusBanner.value = "Pulled ${pulledHashes.size} file(s) from ${link.fullName}."
                refreshGitHubRemoteFiles()
            }.onFailure { err ->
                _statusBanner.value = "Pull failed: ${err.localizedMessage}"
            }
            _isGitHubBusy.value = false
        }
    }

    fun scaffoldGradleCi() {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) {
                GitHubClient.scaffoldGradleAndActions(repository.getProjectDir(project.id), project)
            }
            _projectFiles.value = repository.loadProjectFiles(project.id)
            refreshGitHubPendingChangesInternal(project)
            _statusBanner.value = "Added ${written.size} Gradle + Cloud CI files (JDK ${project.javaVersion.majorVersion})."
        }
    }

    fun triggerGitHubCloudBuild() {
        val project = _activeProject.value ?: return
        val link = project.githubLink ?: run {
            _statusBanner.value = "Link a GitHub repository first."
            return
        }
        val token = githubToken.value
        if (token.isBlank()) {
            _statusBanner.value = "Connect a GitHub token first."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            val workflowFile = File(repository.getProjectDir(project.id), ".github/workflows/droidforge-build.yml")
            if (!workflowFile.exists()) {
                withContext(Dispatchers.IO) {
                    GitHubClient.scaffoldGradleAndActions(repository.getProjectDir(project.id), project)
                }
                _projectFiles.value = repository.loadProjectFiles(project.id)
                GitHubClient.pushProject(
                    token = token,
                    link = link,
                    projectDir = repository.getProjectDir(project.id),
                    commitMessage = "Add DroidForge Cloud CI workflow (JDK ${project.javaVersion.majorVersion})",
                ).onSuccess { pushRes ->
                    val newLink = link.copy(
                        lastCommitSha = pushRes.commitSha,
                        lastPushedAtMillis = System.currentTimeMillis(),
                        lastPushedHashes = pushRes.uploadedHashes,
                    )
                    repository.saveGitHubRepoLink(project.id, newLink)?.let { _activeProject.value = it }
                }
            }
            val currentLink = _activeProject.value?.githubLink ?: link
            val res = GitHubClient.triggerWorkflow(token, currentLink)
            res.onSuccess {
                _statusBanner.value = "Triggered GitHub Actions cloud build (JDK ${project.javaVersion.majorVersion})."
                refreshGitHubWorkflowRuns()
            }.onFailure { err ->
                _statusBanner.value = "Cloud build trigger failed: ${err.localizedMessage}"
            }
            _isGitHubBusy.value = false
        }
    }

    fun refreshGitHubWorkflowRuns() {
        val link = _activeProject.value?.githubLink ?: return
        val token = githubToken.value
        if (token.isBlank()) return
        viewModelScope.launch {
            GitHubClient.listWorkflowRuns(token, link).onSuccess { runs ->
                _githubWorkflowRuns.value = runs
            }
        }
    }

    fun refreshGitHubBranches(owner: String? = null, repo: String? = null) {
        val link = _activeProject.value?.githubLink
        val targetOwner = owner?.trim()?.takeIf { it.isNotBlank() } ?: link?.owner.orEmpty()
        val targetRepo = repo?.trim()?.takeIf { it.isNotBlank() } ?: link?.repo.orEmpty()
        val token = githubToken.value
        if (token.isBlank() || targetOwner.isBlank() || targetRepo.isBlank()) return
        viewModelScope.launch {
            GitHubClient.listBranches(token, targetOwner, targetRepo).onSuccess { branches ->
                _githubBranches.value = branches
            }
        }
    }

    fun switchGitHubBranch(branch: String) {
        val project = _activeProject.value ?: return
        val link = project.githubLink ?: return
        val cleanBranch = branch.trim().ifBlank { return }
        viewModelScope.launch {
            val updated = repository.saveGitHubRepoLink(project.id, link.copy(branch = cleanBranch))
            if (updated != null) {
                _activeProject.value = updated
                _projects.value = repository.listProjects()
                refreshGitHubPendingChangesInternal(updated)
                _statusBanner.value = "Switched GitHub branch to $cleanBranch."
                refreshGitHubRemoteFiles()
                refreshGitHubWorkflowRuns()
            }
        }
    }

    fun createGitHubBranch(newBranch: String) {
        val project = _activeProject.value ?: return
        val link = project.githubLink ?: return
        val token = githubToken.value
        if (token.isBlank()) {
            _statusBanner.value = "Connect a GitHub token first."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            GitHubClient.createBranch(token, link.owner, link.repo, newBranch, link.branch)
                .onSuccess { created ->
                    val updated = repository.saveGitHubRepoLink(project.id, link.copy(branch = created.name))
                    if (updated != null) {
                        _activeProject.value = updated
                        _projects.value = repository.listProjects()
                    }
                    refreshGitHubBranches()
                    refreshGitHubRemoteFiles()
                    _statusBanner.value = "Created & switched to branch ${created.name}."
                }.onFailure { err ->
                    _statusBanner.value = "Create branch failed: ${err.localizedMessage}"
                }
            _isGitHubBusy.value = false
        }
    }

    fun refreshGitHubRemoteFiles() {
        val link = _activeProject.value?.githubLink ?: return
        val token = githubToken.value
        if (token.isBlank()) return
        viewModelScope.launch {
            GitHubClient.listRepoFiles(token, link.owner, link.repo, link.branch).onSuccess { entries ->
                _githubRemoteFiles.value = entries.filter { it.type == "blob" && !it.path.startsWith(".git/") }
            }
        }
    }

    fun downloadRemoteFileToProject(remotePath: String, localPath: String = remotePath) {
        val project = _activeProject.value ?: return
        val link = project.githubLink ?: return
        val token = githubToken.value
        if (token.isBlank()) {
            _statusBanner.value = "Connect a GitHub token first."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            GitHubClient.downloadSingleFileBytes(token, link.owner, link.repo, remotePath, link.branch)
                .onSuccess { bytes ->
                    val cleanLocal = localPath.trim().trimStart('/').ifBlank { remotePath.trimStart('/') }
                    if (repository.isBinaryPath(cleanLocal)) {
                        repository.saveBinaryFile(project.id, cleanLocal, bytes)
                    } else {
                        repository.saveFile(project.id, cleanLocal, bytes.toString(Charsets.UTF_8))
                    }
                    _projectFiles.value = repository.loadProjectFiles(project.id)
                    if (!repository.isBinaryPath(cleanLocal)) openFile(cleanLocal)
                    refreshGitHubPendingChangesInternal(project)
                    _statusBanner.value = "Downloaded $cleanLocal from ${link.fullName}."
                }.onFailure { err ->
                    _statusBanner.value = "Download failed: ${err.localizedMessage}"
                }
            _isGitHubBusy.value = false
        }
    }

    fun uploadProjectFileToGitHub(
        localPath: String,
        remotePath: String = localPath,
        commitMessage: String = "",
    ) {
        val project = _activeProject.value ?: return
        val link = project.githubLink ?: run {
            _statusBanner.value = "Link a GitHub repository first."
            return
        }
        val token = githubToken.value
        if (token.isBlank()) {
            _statusBanner.value = "Connect a GitHub token first."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            _projectFiles.value.find { it.relativePath == localPath && !it.isBinary }?.let { inMem ->
                repository.saveFile(project.id, inMem.relativePath, inMem.content)
            }
            val file = repository.resolveProjectFile(project.id, localPath)
            if (file == null || !file.exists()) {
                _statusBanner.value = "Local file not found: $localPath"
                _isGitHubBusy.value = false
                return@launch
            }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val msg = commitMessage.trim().ifBlank { "Upload $remotePath from DroidForge Studio" }
            GitHubClient.uploadSingleFile(token, link, remotePath, bytes, msg)
                .onSuccess { sha ->
                    _statusBanner.value = "Uploaded $remotePath ($sha)."
                    refreshGitHubRemoteFiles()
                }.onFailure { err ->
                    _statusBanner.value = "Upload failed: ${err.localizedMessage}"
                }
            _isGitHubBusy.value = false
        }
    }

    fun uploadDeviceFileUriToGitHub(uri: Uri, remoteDir: String = "") {
        val project = _activeProject.value
        val link = project?.githubLink ?: run {
            _statusBanner.value = "Link a GitHub repository first."
            return
        }
        val token = githubToken.value
        if (token.isBlank()) {
            _statusBanner.value = "Connect a GitHub token first."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            val payload = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val name = (resolveDisplayName(uri) ?: "upload_${System.currentTimeMillis()}")
                        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read selected file")
                    name to bytes
                }
            }
            payload.onSuccess { (name, bytes) ->
                val prefix = remoteDir.trim().trim('/').takeIf { it.isNotEmpty() }
                val remotePath = if (prefix != null) "$prefix/$name" else name
                GitHubClient.uploadSingleFile(token, link, remotePath, bytes, "Upload $remotePath via DroidForge Studio")
                    .onSuccess { sha ->
                        _statusBanner.value = "Uploaded $remotePath to ${link.fullName} ($sha)."
                        refreshGitHubRemoteFiles()
                    }.onFailure { err ->
                        _statusBanner.value = "GitHub upload failed: ${err.localizedMessage}"
                    }
            }.onFailure { err ->
                _statusBanner.value = "Could not read file: ${err.localizedMessage}"
            }
            _isGitHubBusy.value = false
        }
    }

    fun cloneGitHubRepoAsProject(repo: GitHubRepoSummary) {
        val token = githubToken.value
        if (token.isBlank()) {
            _statusBanner.value = "Connect a GitHub token first."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            _openingProjectName.value = repo.name
            try {
                val link = GitHubRepoLink(
                    owner = repo.owner,
                    repo = repo.name,
                    branch = repo.defaultBranch.ifBlank { "main" },
                )
                val (stub, projectDir) = repository.createClonedProjectStub(repo.name, link)
                val pullRes = GitHubClient.pullRepository(token, link, projectDir)
                pullRes.fold(
                    onSuccess = { pulledHashes ->
                        val finalized = repository.finalizeClonedProject(stub.id, link, pulledHashes) ?: stub
                        _projects.value = repository.listProjects()
                        openProjectInternal(finalized)
                        _statusBanner.value = "Cloned ${repo.fullName} (${pulledHashes.size} files)."
                    },
                    onFailure = { err ->
                        repository.deleteProject(stub.id)
                        _statusBanner.value = "Clone failed: ${err.localizedMessage}"
                    },
                )
            } finally {
                _openingProjectName.value = null
                _isGitHubBusy.value = false
            }
        }
    }

    fun inspectWorkflowRun(runId: Long) {
        val link = _activeProject.value?.githubLink ?: return
        val token = githubToken.value
        if (token.isBlank()) return
        viewModelScope.launch {
            _isGitHubBusy.value = true
            _githubInspectedRunId.value = runId
            _githubWorkflowJobs.value = emptyList()
            _githubSelectedRunLogs.value = ""
            _githubWorkflowArtifacts.value = emptyList()

            val jobs = GitHubClient.listWorkflowJobs(token, link, runId).getOrDefault(emptyList())
            _githubWorkflowJobs.value = jobs
            val artifacts = GitHubClient.listWorkflowArtifacts(token, link, runId).getOrDefault(emptyList())
            _githubWorkflowArtifacts.value = artifacts

            val focusJob = jobs.firstOrNull { it.conclusion == "failure" || it.conclusion == "cancelled" }
                ?: jobs.firstOrNull()
            if (focusJob != null) {
                GitHubClient.fetchJobLogs(token, link, focusJob.id).onSuccess { logText ->
                    _githubSelectedRunLogs.value = logText
                }.onFailure { err ->
                    _githubSelectedRunLogs.value = "Could not load logs: ${err.localizedMessage}"
                }
            } else {
                _githubSelectedRunLogs.value = "No job logs reported yet for run #$runId."
            }
            _isGitHubBusy.value = false
        }
    }

    fun rerunWorkflowRun(runId: Long, failedOnly: Boolean = false) {
        val link = _activeProject.value?.githubLink ?: return
        val token = githubToken.value
        if (token.isBlank()) return
        viewModelScope.launch {
            _isGitHubBusy.value = true
            GitHubClient.rerunWorkflow(token, link, runId, failedOnly).onSuccess {
                _statusBanner.value = if (failedOnly) "Re-running failed jobs (#$runId)." else "Re-running workflow (#$runId)."
                refreshGitHubWorkflowRuns()
            }.onFailure { err ->
                _statusBanner.value = "Rerun failed: ${err.localizedMessage}"
            }
            _isGitHubBusy.value = false
        }
    }

    fun downloadWorkflowArtifact(artifactId: Long) {
        val project = _activeProject.value ?: return
        val link = project.githubLink ?: return
        val token = githubToken.value
        if (token.isBlank()) return
        viewModelScope.launch {
            _isGitHubBusy.value = true
            val destDir = File(repository.getProjectDir(project.id), "build/cloud-outputs")
            GitHubClient.downloadArtifactZipAndExtract(token, link, artifactId, destDir).onSuccess { files ->
                val summary = files.take(3).joinToString(", ") { it.name }
                _statusBanner.value = "Downloaded ${files.size} artifact file(s) to build/cloud-outputs/: $summary"
            }.onFailure { err ->
                _statusBanner.value = "Artifact download failed: ${err.localizedMessage}"
            }
            _isGitHubBusy.value = false
        }
    }

    fun askAiToFixWorkflowRun(run: GitHubWorkflowRun) {
        val project = _activeProject.value ?: return
        val link = project.githubLink ?: return
        val token = githubToken.value
        if (token.isBlank()) {
            _statusBanner.value = "Connect GitHub first so AI can inspect workflow logs."
            return
        }
        viewModelScope.launch {
            _isGitHubBusy.value = true
            val jobs = if (_githubInspectedRunId.value == run.id && _githubWorkflowJobs.value.isNotEmpty()) {
                _githubWorkflowJobs.value
            } else {
                GitHubClient.listWorkflowJobs(token, link, run.id).getOrDefault(emptyList())
            }
            val failedJob = jobs.firstOrNull { it.conclusion == "failure" || it.conclusion == "cancelled" }
                ?: jobs.firstOrNull()
            val logsText = if (_githubInspectedRunId.value == run.id && _githubSelectedRunLogs.value.isNotBlank()) {
                _githubSelectedRunLogs.value
            } else if (failedJob != null) {
                GitHubClient.fetchJobLogs(token, link, failedJob.id).getOrDefault("")
            } else {
                ""
            }
            _githubInspectedRunId.value = run.id
            _githubWorkflowJobs.value = jobs
            if (logsText.isNotBlank()) _githubSelectedRunLogs.value = logsText
            _isGitHubBusy.value = false

            val failedSteps = failedJob?.steps
                ?.filter { it.conclusion == "failure" }
                ?.joinToString(", ") { "#${it.number} ${it.name}" }
                .orEmpty()
            val logTail = logsText.takeLast(8_000).ifBlank { "(Logs unavailable; inspect files and run github_get_workflow_logs tool for run ${run.id}.)" }
            val prompt = buildString {
                appendLine("Fix the failed GitHub Actions workflow run #${run.runNumber.takeIf { it > 0 } ?: run.id} (${run.name}) on repo ${link.fullName} (branch ${link.branch}).")
                appendLine("Status: ${run.status}, Conclusion: ${run.conclusion}")
                if (failedSteps.isNotBlank()) appendLine("Failed steps: $failedSteps")
                appendLine("")
                appendLine("=== CI Log Tail ===")
                appendLine(logTail)
                appendLine("")
                appendLine("Inspect the project files, fix the root cause in our workspace, run github_push_changes to push the fix, and trigger or rerun the workflow if appropriate.")
            }
            _openAiPanelRequest.value = System.currentTimeMillis()
            askGemini(prompt)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val v = cursor.getString(idx)
                    if (!v.isNullOrBlank()) return v
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun compressImageForAi(rawBytes: ByteArray): ByteArray {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
        var sampleSize = 1
        val maxDim = 1024
        while (boundsOpts.outWidth / sampleSize > maxDim * 2 || boundsOpts.outHeight / sampleSize > maxDim * 2) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return rawBytes
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1))
        val finalBmp = if (scale < 0.99f) {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bmp
        }
        val out = ByteArrayOutputStream()
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (finalBmp !== bmp) finalBmp.recycle()
        bmp.recycle()
        return out.toByteArray()
    }

    fun askGemini(prompt: String) {
        askGeminiInThread(_activeThreadId.value, prompt)
    }

    fun askGeminiInThread(
        threadId: String,
        prompt: String,
        attachmentsOverride: List<AiAttachment>? = null,
    ) {
        val project = _activeProject.value ?: return
        val active = _projectFiles.value.find { it.relativePath == _activeFilePath.value }
        val diagnostics = _lastBuildResult.value?.diagnostics.orEmpty()
        val threadMeta = _chatThreads.value.find { it.id == threadId }
        val (provider, modelId) = resolveProviderAndModelForThread(threadMeta)
        val thinkingMode = aiThinkingMode.value
        val apiKey = providerApiKeys.value[provider].orEmpty()
        val attachmentsSnapshot = attachmentsOverride ?: if (threadId == _activeThreadId.value) {
            val snap = _pendingAiAttachments.value
            _pendingAiAttachments.value = emptyList()
            snap
        } else {
            emptyList()
        }
        val githubConnected = githubToken.value.isNotBlank() || project.githubLink != null
        val verifiedIds = _liveModels.value
            .filter { it.provider == provider }
            .map { it.id }
            .toSet()
        val assistant = assistantFor(threadId)
        val settingsSnap = editorSettings.value
        val systemInstructionToUse = threadMeta?.customSystemInstruction?.takeIf { it.isNotBlank() }
            ?: settingsSnap.customSystemInstruction
        val tempToUse = threadMeta?.temperature ?: settingsSnap.aiTemperature
        val agentRole = threadMeta?.agentRole ?: AgentRole.GENERAL

        // Cancel only any prior run on THIS specific thread; other side/role agents continue running concurrently!
        val previousJob = aiJobs[threadId]
        val newJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()
            val freshKeyMap = runCatching { repository.providerApiKeys.first() }.getOrDefault(emptyMap())
            val freshKey = freshKeyMap[provider].orEmpty().ifBlank { apiKey }
            val freshOrKey = freshKeyMap[AiProvider.OPENROUTER].orEmpty()
                .ifBlank { providerApiKeys.value[AiProvider.OPENROUTER].orEmpty() }
            val freshModel = threadMeta?.modelId?.takeIf { it.isNotBlank() }?.let {
                AiModelsCatalog.normalizeModelId(provider, it)
            } ?: AiModelsCatalog.normalizeModelId(
                provider,
                runCatching { repository.providerModels.first()[provider] }.getOrNull() ?: modelId,
            )
            val executor = createToolExecutor(project.id, project.packageName)
            val allLive = _liveModels.value
            val providerLiveModels = allLive.filter { it.provider == provider }
            assistant.askGemini(
                userPrompt = prompt,
                apiKey = freshKey,
                modelId = freshModel,
                provider = provider,
                thinkingMode = thinkingMode,
                agentRole = agentRole,
                customSystemInstruction = systemInstructionToUse,
                temperature = tempToUse,
                projectName = project.name,
                packageName = project.packageName,
                projectLanguage = project.language,
                javaVersion = project.javaVersion,
                minSdkVersion = project.minSdkVersion,
                attachments = attachmentsSnapshot,
                activeFile = active,
                allFiles = _projectFiles.value,
                diagnostics = diagnostics,
                toolExecutor = executor,
                githubConnected = githubConnected,
                verifiedLiveModelIds = verifiedIds,
                liveModelOptions = providerLiveModels,
                openRouterApiKey = freshOrKey,
                allLiveModels = allLive,
                shouldPublishToUi = { true },
                onModelAutoCorrected = { failedModel, workingModel ->
                    viewModelScope.launch {
                        if (threadMeta?.modelId?.isNotBlank() == true) {
                            updateThreadProviderAndModel(threadId, provider, workingModel)
                        } else {
                            repository.saveProviderConfig(provider, freshKey, workingModel)
                        }
                        _statusBanner.value = "Switched ${threadMeta?.title ?: "Agent"} from unavailable '$failedModel' to '$workingModel'."
                    }
                },
                onUsageRecorded = { usedProvider, usedModel, delta, tools, failed ->
                    viewModelScope.launch {
                        repository.recordAiUsage(usedProvider, usedModel, delta, tools, failed)
                    }
                },
                onMessagesChanged = { latestMsgs ->
                    val updated = _chatThreads.value.map { t ->
                        if (t.id == threadId) {
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
        aiJobs[threadId] = newJob
        newJob.invokeOnCompletion {
            if (aiJobs[threadId] === newJob) {
                aiJobs.remove(threadId)
            }
        }
    }

    fun launchRoleAgent(
        role: AgentRole,
        customPrompt: String? = null,
        selectAsActive: Boolean = false,
    ) {
        val project = _activeProject.value ?: return
        val threadId = ensureRoleAgentThread(role)
        if (selectAsActive) {
            selectChatThread(threadId)
            _openAiPanelRequest.value = System.currentTimeMillis()
        } else if (_splitSideThreadId.value == null) {
            _splitSideThreadId.value = threadId
        }
        val diagnostics = _lastBuildResult.value?.diagnostics.orEmpty()
        val prompt = customPrompt?.trim()?.takeIf { it.isNotEmpty() } ?: when (role) {
            AgentRole.FEATURE_BUILDER -> buildString {
                appendLine("Add an impressive interactive improvement or next feature to ${project.name}.")
                _activeFilePath.value?.let { appendLine("Currently focused file: $it") }
                appendLine("Implement cleanly and call build_apk to verify.")
            }
            AgentRole.ERROR_FIXER -> buildString {
                if (diagnostics.isNotEmpty()) {
                    appendLine("Fix ALL ${diagnostics.size} build diagnostic(s) immediately:")
                    diagnostics.take(15).forEach { d ->
                        appendLine("- ${d.severity.name} ${d.filePath}:${d.line}: ${d.message}")
                    }
                } else {
                    appendLine("Inspect ${project.name}, run build_apk, verify there are zero errors or broken references, and fix anything found.")
                }
            }
            AgentRole.GITHUB_WATCHER -> buildString {
                val link = project.githubLink
                if (link != null) {
                    appendLine("Check GitHub repository ${link.fullName} (branch ${link.branch}):")
                    appendLine("1. Call github_status to check local/remote sync and pending changes.")
                    appendLine("2. Call github_list_workflow_runs to check latest CI push/build progress.")
                    appendLine("3. If the latest run failed, call github_get_workflow_logs, summarize the root cause, and prepare/fix it.")
                } else {
                    appendLine("Inspect local workspace status via github_status and advise/assist linking or creating a GitHub repository.")
                }
            }
            AgentRole.GENERAL -> "Inspect the workspace and summarize what we should work on next."
        }
        askGeminiInThread(threadId, prompt)
    }

    fun cancelGemini() {
        cancelThread(_activeThreadId.value)
    }

    fun cancelThread(threadId: String) {
        assistants[threadId]?.cancelByUser()
        aiJobs.remove(threadId)?.cancel()
        persistThreadMessages(threadId)
    }

    fun retryLastGeminiPrompt() {
        retryThreadPrompt(_activeThreadId.value)
    }

    fun retryThreadPrompt(threadId: String) {
        val lastPrompt = assistants[threadId]?.popLastPromptForRetry() ?: return
        askGeminiInThread(threadId, lastPrompt)
    }

    fun clearAiChat() {
        clearThreadChat(_activeThreadId.value)
    }

    fun clearThreadChat(threadId: String) {
        assistants[threadId]?.clearConversation()
        persistThreadMessages(threadId)
    }

    private fun persistActiveThreadMessages() {
        persistThreadMessages(_activeThreadId.value)
    }

    private fun persistThreadMessages(threadId: String) {
        val inst = assistants[threadId] ?: return
        val currentMsgs = inst.messages.value.map { it.copy(isStreaming = false) }
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

    fun runTerminalCommand(rawCommand: String) {
        val project = _activeProject.value
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) return
        val appendLine: (String) -> Unit = { line ->
            _terminalLines.value = (_terminalLines.value + line).takeLast(300)
        }
        appendLine("$ $cmd")
        if (cmd == "clear") {
            _terminalLines.value = listOf("Terminal cleared.")
            return
        }
        if (cmd == "help") {
            appendLine(
                """
                Available commands:
                  ls [prefix]          - List project files
                  cat <path>           - Print file contents (first 60 lines)
                  grep <text>          - Search across project files
                  touch <path>         - Create an empty/starter file
                  rm <path>            - Delete a file
                  build [debug|release|aab] - Run offline APK/AAB compiler
                  git status           - Show linked GitHub repo & pending changes
                  git pull             - Pull latest files from GitHub
                  git push [msg]       - Commit & push project to GitHub
                  ci                   - List recent GitHub Actions runs
                  clear                - Clear console
                """.trimIndent(),
            )
            return
        }
        if (project == null) {
            appendLine("error: no active project open")
            return
        }

        viewModelScope.launch {
            val parts = cmd.split(Regex("""\s+"""), limit = 2)
            val root = parts[0].lowercase()
            val arg = parts.getOrNull(1)?.trim().orEmpty()
            when (root) {
                "ls" -> {
                    val paths = repository.listAllProjectPaths(project.id, includeBuild = true)
                        .filter { arg.isEmpty() || it.contains(arg, ignoreCase = true) }
                    if (paths.isEmpty()) appendLine("(no matching files)")
                    else paths.take(80).forEach { appendLine("  $it") }
                    if (paths.size > 80) appendLine("... (${paths.size - 80} more)")
                }
                "cat" -> {
                    val file = _projectFiles.value.find { it.relativePath == arg }
                    if (file == null) appendLine("cat: $arg: No such file")
                    else file.content.lines().take(60).forEachIndexed { i, l ->
                        appendLine("${(i + 1).toString().padStart(3)} | $l")
                    }
                }
                "grep" -> {
                    if (arg.isEmpty()) appendLine("usage: grep <pattern>")
                    else {
                        val hits = searchWorkspace(arg)
                        if (hits.isEmpty()) appendLine("no matches for '$arg'")
                        else hits.take(30).forEach { h -> appendLine("${h.relativePath}:${h.lineNumber}: ${h.previewLine}") }
                    }
                }
                "touch" -> {
                    if (arg.isEmpty()) appendLine("usage: touch <relativePath>")
                    else {
                        createNewFile(arg)
                        appendLine("created $arg")
                    }
                }
                "rm" -> {
                    if (arg.isEmpty()) appendLine("usage: rm <relativePath>")
                    else {
                        deleteFile(arg)
                        appendLine("removed $arg")
                    }
                }
                "build" -> {
                    val variant = when (arg.lowercase()) {
                        "release" -> BuildVariant.RELEASE_APK
                        "aab", "bundle" -> BuildVariant.RELEASE_AAB
                        else -> BuildVariant.DEBUG_APK
                    }
                    appendLine("Triggering ${variant.label}...")
                    buildApk(variant)
                }
                "git" -> {
                    val subParts = arg.split(Regex("""\s+"""), limit = 2)
                    when (subParts.getOrNull(0)?.lowercase().orEmpty()) {
                        "status" -> {
                            val link = project.githubLink
                            if (link == null) appendLine("Not linked to a GitHub repository.")
                            else {
                                appendLine("Repo: ${link.fullName} (branch ${link.branch})")
                                val changes = repository.computeGitHubPendingChanges(project.id, link)
                                if (changes.isEmpty()) appendLine("Working tree clean.")
                                else changes.forEach { appendLine("  [${it.status}] ${it.path}") }
                            }
                        }
                        "pull" -> {
                            pullProjectFromGitHub()
                            appendLine("Started GitHub pull...")
                        }
                        "push" -> {
                            val msg = subParts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Update via Android Studio Console"
                            pushProjectToGitHub(msg)
                            appendLine("Started GitHub push: \"$msg\"")
                        }
                        else -> appendLine("usage: git [status|pull|push <msg>]")
                    }
                }
                "ci" -> {
                    refreshGitHubWorkflowRuns()
                    val runs = _githubWorkflowRuns.value
                    if (runs.isEmpty()) appendLine("No workflow runs cached (refreshing...)")
                    else runs.take(8).forEach { r ->
                        appendLine("#${r.runNumber} ${r.name} — ${r.status} / ${r.conclusion} (${r.headBranch})")
                    }
                }
                else -> appendLine("unknown command '$root' — type `help` for available workspace commands")
            }
        }
    }

    private fun createToolExecutor(
        projectId: String,
        packageName: String,
    ): ProjectToolExecutor = object : ProjectToolExecutor {

        private suspend fun flushInMemoryFiles() {
            saveJob?.cancel()
            val activeSnap = _projectFiles.value.filterNot { it.isBinary }
            fileWriteMutex.withLock {
                activeSnap.forEach { file ->
                    repository.saveFile(projectId, file.relativePath, file.content)
                }
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
            val existed = fileWriteMutex.withLock {
                val ex = target.exists()
                repository.saveFile(projectId, cleanPath, content)
                _projectFiles.value = repository.loadProjectFiles(projectId)
                ex
            }
            refreshGitHubPendingChangesInternal(_activeProject.value)
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
            return fileWriteMutex.withLock {
                if (!target.exists() || !target.isFile) {
                    return@withLock AgentToolResult(
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
                    return@withLock AgentToolResult(
                        success = false,
                        stepDetail = "Empty old_text",
                        payload = JSONObject().put("error", "old_text must not be empty. Use write_file instead."),
                    )
                }
                val firstIdx = normalizedCurrent.indexOf(normalizedOld)
                if (firstIdx < 0) {
                    return@withLock AgentToolResult(
                        success = false,
                        stepDetail = "Snippet not found in $cleanPath",
                        payload = JSONObject().put("error", "old_text was not found in $cleanPath. Call read_file first."),
                    )
                }
                val secondIdx = normalizedCurrent.indexOf(normalizedOld, startIndex = firstIdx + normalizedOld.length)
                if (secondIdx >= 0) {
                    return@withLock AgentToolResult(
                        success = false,
                        stepDetail = "Multiple matches in $cleanPath",
                        payload = JSONObject().put("error", "old_text matched multiple locations in $cleanPath."),
                    )
                }

                val updated = normalizedCurrent.replaceFirst(normalizedOld, normalizedNew)
                repository.saveFile(projectId, cleanPath, updated)
                _projectFiles.value = repository.loadProjectFiles(projectId)
                refreshGitHubPendingChangesInternal(_activeProject.value)
                val oldLines = normalizedOld.lines().size
                val newLines = normalizedNew.lines().size
                val diffPreview = buildString {
                    normalizedOld.lines().take(6).forEach { appendLine("- $it") }
                    normalizedNew.lines().take(8).forEach { appendLine("+ $it") }
                }.trim()

                AgentToolResult(
                    success = true,
                    stepDetail = "Edited (-$oldLines / +$newLines lines)",
                    payload = JSONObject().put("status", "edited").put("path", cleanPath),
                    detailedOutput = diffPreview,
                )
            }
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
            refreshGitHubPendingChangesInternal(_activeProject.value)
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
                    targetMinSdk = _activeProject.value?.minSdkVersion,
                    targetJavaVersion = _activeProject.value?.javaVersion ?: JavaVersionOption.JAVA_17,
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

        override suspend fun githubStatus(): AgentToolResult {
            flushInMemoryFiles()
            val meta = _activeProject.value
            val token = githubToken.value
            val user = githubUser.value
            val link = meta?.githubLink
            val changes = if (meta != null && link != null) {
                repository.computeGitHubPendingChanges(projectId, link)
            } else {
                emptyList()
            }
            _githubPendingChanges.value = changes
            val payload = JSONObject()
                .put("connected", token.isNotBlank())
                .put("username", user)
                .put("linkedRepo", link?.fullName.orEmpty())
                .put("branch", link?.branch.orEmpty())
                .put("lastCommitSha", link?.lastCommitSha.orEmpty())
                .put("pendingCount", changes.size)
                .put(
                    "pendingFiles",
                    JSONArray().apply {
                        changes.take(30).forEach { c ->
                            put(JSONObject().put("path", c.path).put("status", c.status))
                        }
                    },
                )
            return AgentToolResult(
                success = true,
                stepDetail = if (link != null) "${link.fullName} (${changes.size} pending)" else if (token.isNotBlank()) "Connected as @$user" else "GitHub disconnected",
                payload = payload,
                detailedOutput = changes.joinToString("\n") { "${it.status.uppercase()}: ${it.path}" },
            )
        }

        override suspend fun githubListRepos(): AgentToolResult {
            val token = githubToken.value
            if (token.isBlank()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "GitHub not connected",
                    payload = JSONObject().put("error", "User has not connected a GitHub Personal Access Token yet."),
                )
            }
            val res = GitHubClient.listRepos(token)
            return res.fold(
                onSuccess = { repos ->
                    _githubRepos.value = repos
                    val arr = JSONArray().apply {
                        repos.take(30).forEach { r ->
                            put(
                                JSONObject()
                                    .put("owner", r.owner)
                                    .put("name", r.name)
                                    .put("fullName", r.fullName)
                                    .put("defaultBranch", r.defaultBranch)
                                    .put("private", r.isPrivate),
                            )
                        }
                    }
                    AgentToolResult(
                        success = true,
                        stepDetail = "${repos.size} repo(s)",
                        payload = JSONObject().put("repos", arr),
                        detailedOutput = repos.take(15).joinToString("\n") { "${it.fullName} (${it.defaultBranch})" },
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "Failed to list repos",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Unknown error"),
                    )
                },
            )
        }

        override suspend fun githubListRepoFiles(owner: String, repo: String, branch: String): AgentToolResult {
            val token = githubToken.value
            val meta = _activeProject.value
            val resolvedOwner = owner.trim().ifBlank { meta?.githubLink?.owner.orEmpty() }
            val resolvedRepo = repo.trim().ifBlank { meta?.githubLink?.repo.orEmpty() }
            val resolvedBranch = branch.trim().ifBlank { meta?.githubLink?.branch ?: "main" }
            if (resolvedOwner.isBlank() || resolvedRepo.isBlank()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "No repository specified",
                    payload = JSONObject().put("error", "Specify owner & repo or link a repository first."),
                )
            }
            val res = GitHubClient.listRepoFiles(token, resolvedOwner, resolvedRepo, resolvedBranch)
            return res.fold(
                onSuccess = { entries ->
                    val arr = JSONArray().apply {
                        entries.take(100).forEach { e ->
                            put(JSONObject().put("path", e.path).put("size", e.size))
                        }
                    }
                    AgentToolResult(
                        success = true,
                        stepDetail = "${entries.size} remote file(s)",
                        payload = JSONObject().put("files", arr),
                        detailedOutput = entries.take(30).joinToString("\n") { it.path },
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "List remote files failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Unknown error"),
                    )
                },
            )
        }

        override suspend fun githubReadFile(path: String, owner: String, repo: String, branch: String): AgentToolResult {
            val token = githubToken.value
            val meta = _activeProject.value
            val resolvedOwner = owner.trim().ifBlank { meta?.githubLink?.owner.orEmpty() }
            val resolvedRepo = repo.trim().ifBlank { meta?.githubLink?.repo.orEmpty() }
            val resolvedBranch = branch.trim().ifBlank { meta?.githubLink?.branch ?: "main" }
            if (resolvedOwner.isBlank() || resolvedRepo.isBlank()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "No repository specified",
                    payload = JSONObject().put("error", "Specify owner & repo or link a repository first."),
                )
            }
            val res = GitHubClient.readRepoFile(token, resolvedOwner, resolvedRepo, path, resolvedBranch)
            return res.fold(
                onSuccess = { content ->
                    val preview = if (content.length > 40_000) content.take(40_000) + "\n...[truncated]" else content
                    AgentToolResult(
                        success = true,
                        stepDetail = "Read ${content.lines().size} lines from $resolvedOwner/$resolvedRepo",
                        payload = JSONObject().put("path", path).put("content", preview),
                        detailedOutput = content.lines().take(15).joinToString("\n"),
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "Read remote file failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Unknown error"),
                    )
                },
            )
        }

        override suspend fun githubPushChanges(commitMessage: String): AgentToolResult {
            flushInMemoryFiles()
            val token = githubToken.value
            val meta = _activeProject.value
            val link = meta?.githubLink
            if (token.isBlank() || link == null) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "GitHub push not configured",
                    payload = JSONObject().put("error", "Connect GitHub PAT and link a repository first."),
                )
            }
            val res = GitHubClient.pushProject(
                token = token,
                link = link,
                projectDir = repository.getProjectDir(projectId),
                commitMessage = commitMessage,
            )
            return res.fold(
                onSuccess = { pushRes ->
                    val newLink = link.copy(
                        branch = pushRes.branch,
                        lastCommitSha = pushRes.commitSha,
                        lastPushedAtMillis = System.currentTimeMillis(),
                        lastPushedHashes = pushRes.uploadedHashes,
                    )
                    repository.saveGitHubRepoLink(projectId, newLink)?.let {
                        _activeProject.value = it
                        _projects.value = repository.listProjects()
                        refreshGitHubPendingChangesInternal(it)
                    }
                    AgentToolResult(
                        success = true,
                        stepDetail = "Pushed ${pushRes.pushedFileCount} file(s) (${pushRes.commitSha.take(7)})",
                        payload = JSONObject()
                            .put("commitSha", pushRes.commitSha)
                            .put("branch", pushRes.branch)
                            .put("pushedFileCount", pushRes.pushedFileCount)
                            .put("htmlUrl", pushRes.htmlUrl),
                        detailedOutput = "Commit ${pushRes.commitSha.take(7)} on ${pushRes.branch}: ${pushRes.htmlUrl}",
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "Push failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Push failed"),
                    )
                },
            )
        }

        override suspend fun githubTriggerCloudBuild(): AgentToolResult {
            flushInMemoryFiles()
            val token = githubToken.value
            val meta = _activeProject.value
            val link = meta?.githubLink
            if (token.isBlank() || link == null || meta == null) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "GitHub cloud build not configured",
                    payload = JSONObject().put("error", "Connect GitHub PAT and link a repository first."),
                )
            }
            val workflowFile = File(repository.getProjectDir(projectId), ".github/workflows/droidforge-build.yml")
            if (!workflowFile.exists()) {
                withContext(Dispatchers.IO) {
                    GitHubClient.scaffoldGradleAndActions(repository.getProjectDir(projectId), meta)
                }
                _projectFiles.value = repository.loadProjectFiles(projectId)
                GitHubClient.pushProject(
                    token = token,
                    link = link,
                    projectDir = repository.getProjectDir(projectId),
                    commitMessage = "Add DroidForge Cloud CI workflow (JDK ${meta.javaVersion.majorVersion})",
                ).onSuccess { pushRes ->
                    val newLink = link.copy(
                        lastCommitSha = pushRes.commitSha,
                        lastPushedAtMillis = System.currentTimeMillis(),
                        lastPushedHashes = pushRes.uploadedHashes,
                    )
                    repository.saveGitHubRepoLink(projectId, newLink)?.let { _activeProject.value = it }
                }
            }
            val currentLink = _activeProject.value?.githubLink ?: link
            val res = GitHubClient.triggerWorkflow(token, currentLink)
            return res.fold(
                onSuccess = { url ->
                    AgentToolResult(
                        success = true,
                        stepDetail = "Cloud CI triggered (JDK ${meta.javaVersion.majorVersion})",
                        payload = JSONObject().put("actionsUrl", url),
                        detailedOutput = "GitHub Actions triggered at $url",
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "Cloud build failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Cloud build trigger failed"),
                    )
                },
            )
        }

        override suspend fun githubCreateRepo(
            name: String,
            description: String,
            isPrivate: Boolean,
        ): AgentToolResult {
            val token = githubToken.value
            if (token.isBlank()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "GitHub not connected",
                    payload = JSONObject().put("error", "User has not connected a GitHub Personal Access Token yet."),
                )
            }
            val cleanName = name.trim()
            if (cleanName.isEmpty()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Missing repository name",
                    payload = JSONObject().put("error", "Repository name cannot be empty."),
                )
            }
            val res = GitHubClient.createRepo(token, cleanName, description.trim(), isPrivate)
            return res.fold(
                onSuccess = { summary ->
                    val link = GitHubRepoLink(
                        owner = summary.owner,
                        repo = summary.name,
                        branch = summary.defaultBranch,
                    )
                    val updated = repository.saveGitHubRepoLink(projectId, link)
                    if (updated != null) {
                        _activeProject.value = updated
                        _projects.value = repository.listProjects()
                        refreshGitHubPendingChangesInternal(updated)
                    }
                    _githubRepos.value = listOf(summary) + _githubRepos.value.filterNot { it.fullName == summary.fullName }
                    AgentToolResult(
                        success = true,
                        stepDetail = "Created & linked ${summary.fullName}",
                        payload = JSONObject()
                            .put("owner", summary.owner)
                            .put("repo", summary.name)
                            .put("fullName", summary.fullName)
                            .put("defaultBranch", summary.defaultBranch)
                            .put("private", summary.isPrivate)
                            .put("htmlUrl", summary.htmlUrl),
                        detailedOutput = "Created ${summary.fullName} (branch ${summary.defaultBranch}) at ${summary.htmlUrl}",
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "Create repo failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Create repo failed"),
                    )
                },
            )
        }

        override suspend fun githubLinkRepo(
            owner: String,
            repo: String,
            branch: String,
        ): AgentToolResult {
            val cleanOwner = owner.trim()
            val cleanRepo = repo.trim()
            val cleanBranch = branch.trim().ifBlank { "main" }
            if (cleanOwner.isEmpty() || cleanRepo.isEmpty()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Invalid owner/repo",
                    payload = JSONObject().put("error", "Both owner and repo are required."),
                )
            }
            val link = GitHubRepoLink(
                owner = cleanOwner,
                repo = cleanRepo,
                branch = cleanBranch,
            )
            val updated = repository.saveGitHubRepoLink(projectId, link)
                ?: return AgentToolResult(
                    success = false,
                    stepDetail = "Project not found",
                    payload = JSONObject().put("error", "Could not save GitHub link for active project."),
                )
            _activeProject.value = updated
            _projects.value = repository.listProjects()
            refreshGitHubPendingChangesInternal(updated)
            return AgentToolResult(
                success = true,
                stepDetail = "Linked ${link.fullName} ($cleanBranch)",
                payload = JSONObject()
                    .put("fullName", link.fullName)
                    .put("owner", cleanOwner)
                    .put("repo", cleanRepo)
                    .put("branch", cleanBranch),
                detailedOutput = "Linked project to ${link.fullName} on branch $cleanBranch",
            )
        }

        override suspend fun githubPullChanges(): AgentToolResult {
            flushInMemoryFiles()
            val token = githubToken.value
            val meta = _activeProject.value
            val link = meta?.githubLink
            if (token.isBlank() || link == null) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "GitHub pull not configured",
                    payload = JSONObject().put("error", "Connect GitHub PAT and link a repository first."),
                )
            }
            val res = GitHubClient.pullRepository(
                token = token,
                link = link,
                targetDir = repository.getProjectDir(projectId),
            )
            return res.fold(
                onSuccess = { pulledHashes ->
                    val count = pulledHashes.size
                    val loaded = repository.loadProjectFiles(projectId)
                    _projectFiles.value = loaded
                    val currentActive = _activeFilePath.value
                    if (currentActive != null && loaded.none { it.relativePath == currentActive }) {
                        loaded.firstOrNull { !it.isBinary }?.let { openFile(it.relativePath) }
                    }
                    val hashes = repository.collectProjectFileHashes(projectId)
                    val newLink = link.copy(
                        lastPushedAtMillis = System.currentTimeMillis(),
                        lastPushedHashes = hashes,
                    )
                    repository.saveGitHubRepoLink(projectId, newLink)?.let { updated ->
                        _activeProject.value = updated
                        _projects.value = repository.listProjects()
                        refreshGitHubPendingChangesInternal(updated)
                    }
                    AgentToolResult(
                        success = true,
                        stepDetail = "Pulled $count file(s) from ${link.fullName}",
                        payload = JSONObject()
                            .put("pulledFileCount", count)
                            .put("repo", link.fullName)
                            .put("branch", link.branch),
                        detailedOutput = "Synchronized $count file(s) from ${link.fullName} (${link.branch})",
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "Pull failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Pull failed"),
                    )
                },
            )
        }

        override suspend fun githubListBranches(owner: String, repo: String): AgentToolResult {
            val token = githubToken.value
            val meta = _activeProject.value
            val resolvedOwner = owner.trim().ifBlank { meta?.githubLink?.owner.orEmpty() }
            val resolvedRepo = repo.trim().ifBlank { meta?.githubLink?.repo.orEmpty() }
            if (resolvedOwner.isBlank() || resolvedRepo.isBlank()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "No repository specified",
                    payload = JSONObject().put("error", "Specify owner & repo or link a repository first."),
                )
            }
            return GitHubClient.listBranches(token, resolvedOwner, resolvedRepo).fold(
                onSuccess = { branches ->
                    _githubBranches.value = branches
                    val arr = JSONArray().apply { branches.forEach { put(it.name) } }
                    AgentToolResult(
                        success = true,
                        stepDetail = "${branches.size} branch(es)",
                        payload = JSONObject().put("branches", arr),
                        detailedOutput = branches.joinToString("\n") { "${it.name} (${it.sha.take(7)})" },
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "List branches failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Unknown error"),
                    )
                },
            )
        }

        override suspend fun githubUploadFile(
            remotePath: String,
            localPath: String,
            content: String,
            commitMessage: String,
        ): AgentToolResult {
            flushInMemoryFiles()
            val token = githubToken.value
            val link = _activeProject.value?.githubLink
            if (token.isBlank() || link == null) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "GitHub upload not configured",
                    payload = JSONObject().put("error", "Connect GitHub PAT and link a repository first."),
                )
            }
            val cleanRemote = remotePath.trim().trimStart('/').ifBlank { localPath.trim().trimStart('/') }
            val bytes = when {
                localPath.isNotBlank() -> {
                    val f = repository.resolveProjectFile(projectId, localPath)
                    if (f == null || !f.exists()) {
                        return AgentToolResult(
                            success = false,
                            stepDetail = "Local file missing",
                            payload = JSONObject().put("error", "Local file not found: $localPath"),
                        )
                    }
                    withContext(Dispatchers.IO) { f.readBytes() }
                }
                else -> content.toByteArray(Charsets.UTF_8)
            }
            return GitHubClient.uploadSingleFile(token, link, cleanRemote, bytes, commitMessage).fold(
                onSuccess = { sha ->
                    AgentToolResult(
                        success = true,
                        stepDetail = "Uploaded $cleanRemote ($sha)",
                        payload = JSONObject().put("remotePath", cleanRemote).put("commitSha", sha),
                        detailedOutput = "Uploaded $cleanRemote to ${link.fullName}@${link.branch}",
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "Upload failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Unknown error"),
                    )
                },
            )
        }

        override suspend fun githubDownloadFile(
            remotePath: String,
            localPath: String,
            owner: String,
            repo: String,
            branch: String,
        ): AgentToolResult {
            val token = githubToken.value
            val link = _activeProject.value?.githubLink
            val resolvedOwner = owner.trim().ifBlank { link?.owner.orEmpty() }
            val resolvedRepo = repo.trim().ifBlank { link?.repo.orEmpty() }
            val resolvedBranch = branch.trim().ifBlank { link?.branch ?: "main" }
            if (resolvedOwner.isBlank() || resolvedRepo.isBlank()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "No repository specified",
                    payload = JSONObject().put("error", "Specify owner & repo or link a repository first."),
                )
            }
            val cleanLocal = localPath.trim().trimStart('/').ifBlank { remotePath.trim().trimStart('/') }
            return GitHubClient.downloadSingleFileBytes(token, resolvedOwner, resolvedRepo, remotePath, resolvedBranch).fold(
                onSuccess = { bytes ->
                    if (repository.isBinaryPath(cleanLocal)) {
                        repository.saveBinaryFile(projectId, cleanLocal, bytes)
                    } else {
                        repository.saveFile(projectId, cleanLocal, bytes.toString(Charsets.UTF_8))
                    }
                    _projectFiles.value = repository.loadProjectFiles(projectId)
                    refreshGitHubPendingChangesInternal(_activeProject.value)
                    AgentToolResult(
                        success = true,
                        stepDetail = "Downloaded $cleanLocal (${bytes.size} B)",
                        payload = JSONObject().put("localPath", cleanLocal).put("bytes", bytes.size),
                        detailedOutput = "Saved $remotePath -> $cleanLocal",
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "Download failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Unknown error"),
                    )
                },
            )
        }

        override suspend fun githubListWorkflowRuns(): AgentToolResult {
            val token = githubToken.value
            val link = _activeProject.value?.githubLink
            if (token.isBlank() || link == null) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "GitHub CI not configured",
                    payload = JSONObject().put("error", "Connect GitHub PAT and link a repository first."),
                )
            }
            return GitHubClient.listWorkflowRuns(token, link).fold(
                onSuccess = { runs ->
                    _githubWorkflowRuns.value = runs
                    val arr = JSONArray().apply {
                        runs.take(10).forEach { r ->
                            put(
                                JSONObject()
                                    .put("id", r.id)
                                    .put("runNumber", r.runNumber)
                                    .put("name", r.name)
                                    .put("status", r.status)
                                    .put("conclusion", r.conclusion)
                                    .put("branch", r.headBranch),
                            )
                        }
                    }
                    AgentToolResult(
                        success = true,
                        stepDetail = "${runs.size} workflow run(s)",
                        payload = JSONObject().put("runs", arr),
                        detailedOutput = runs.take(8).joinToString("\n") { "#${it.runNumber} ${it.name}: ${it.status}/${it.conclusion}" },
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "List workflow runs failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Unknown error"),
                    )
                },
            )
        }

        override suspend fun githubGetWorkflowLogs(runId: Long): AgentToolResult {
            val token = githubToken.value
            val link = _activeProject.value?.githubLink
            if (token.isBlank() || link == null) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "GitHub CI not configured",
                    payload = JSONObject().put("error", "Connect GitHub PAT and link a repository first."),
                )
            }
            val jobsRes = GitHubClient.listWorkflowJobs(token, link, runId)
            return jobsRes.fold(
                onSuccess = { jobs ->
                    val focusJob = jobs.firstOrNull { it.conclusion == "failure" || it.conclusion == "cancelled" }
                        ?: jobs.firstOrNull()
                    val logText = if (focusJob != null) {
                        GitHubClient.fetchJobLogs(token, link, focusJob.id).getOrDefault("(No raw logs available yet)")
                    } else {
                        "(No jobs found for workflow run $runId)"
                    }
                    val trimmed = logText.takeLast(14_000)
                    AgentToolResult(
                        success = true,
                        stepDetail = "Loaded logs for run #$runId",
                        payload = JSONObject()
                            .put("runId", runId)
                            .put("jobName", focusJob?.name.orEmpty())
                            .put("conclusion", focusJob?.conclusion.orEmpty())
                            .put("logs", trimmed),
                        detailedOutput = trimmed.lines().takeLast(25).joinToString("\n"),
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "Get workflow logs failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Unknown error"),
                    )
                },
            )
        }

        override suspend fun githubRerunWorkflow(runId: Long, failedOnly: Boolean): AgentToolResult {
            val token = githubToken.value
            val link = _activeProject.value?.githubLink
            if (token.isBlank() || link == null) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "GitHub CI not configured",
                    payload = JSONObject().put("error", "Connect GitHub PAT and link a repository first."),
                )
            }
            return GitHubClient.rerunWorkflow(token, link, runId, failedOnly).fold(
                onSuccess = {
                    AgentToolResult(
                        success = true,
                        stepDetail = "Reran workflow #$runId",
                        payload = JSONObject().put("runId", runId).put("failedOnly", failedOnly),
                        detailedOutput = "Triggered rerun for workflow run $runId",
                    )
                },
                onFailure = { err ->
                    AgentToolResult(
                        success = false,
                        stepDetail = "Rerun failed",
                        payload = JSONObject().put("error", err.localizedMessage ?: "Unknown error"),
                    )
                },
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
            refreshGitHubPendingChangesInternal(project)
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
            relPath.endsWith(".kt") -> {
                val className = relPath.substringAfterLast('/').removeSuffix(".kt")
                val pkgSub = relPath.removePrefix("src/").substringBeforeLast('/', "")
                    .replace('/', '.')
                    .ifBlank { packageName }
                """
                package $pkgSub

                class $className {
                    fun info(): String {
                        return "$className ready"
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
            .mapNotNull { seg ->
                val trimmed = seg.trimStart('_')
                if (trimmed.isEmpty()) null
                else if (!trimmed.first().isLetter()) "a$trimmed"
                else trimmed
            }
            .joinToString(".")
        return if (cleaned.contains('.')) cleaned else "com.example.${cleaned.ifBlank { "app" }}"
    }
}
