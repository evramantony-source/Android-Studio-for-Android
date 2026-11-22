package com.example.ui.screens

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.example.ai.AgentRole
import com.example.ai.AiModelOption
import com.example.ai.AiProvider
import com.example.ai.AiThinkingMode
import com.example.ai.AiUiState
import com.example.ai.AiUsageSummary
import com.example.ai.ChatMessage
import com.example.ai.ProjectChatThread
import com.example.ai.SuggestedCodeBlock
import com.example.data.GitHubPendingChange
import com.example.emulator.EmulatorPanel
import com.example.github.GitHubBranch
import com.example.github.GitHubRepoSummary
import com.example.github.GitHubTreeEntry
import com.example.github.GitHubWorkflowArtifact
import com.example.github.GitHubWorkflowJob
import com.example.github.GitHubWorkflowRun
import com.example.model.AiAttachment
import com.example.model.AndroidMinSdk
import com.example.model.BuildDiagnostic
import com.example.model.BuildResult
import com.example.model.BuildVariant
import com.example.model.DiagnosticSeverity
import com.example.model.EditorSettings
import com.example.model.FileLanguage
import com.example.model.JavaVersionOption
import com.example.model.ProjectFile
import com.example.model.ProjectLanguage
import com.example.model.ProjectMetadata
import com.example.model.ProjectSearchHit
import com.example.model.ReleaseSigningConfig
import com.example.ui.editor.CodeEditor
import com.example.ui.editor.CodeStructureExtractor
import com.example.ui.editor.SymbolKind
import com.example.ui.panels.AiUsageDialog
import com.example.ui.panels.BuildPanel
import com.example.ui.panels.GeminiPanel
import com.example.ui.panels.GitHubSheetDialog
import com.example.ui.panels.ModelMenuDialog
import com.example.ui.panels.ProjectSearchDialog
import com.example.ui.panels.ProviderKeysDialog
import com.example.ui.panels.ReleaseKeystoreDialog
import com.example.ui.panels.SideChatSplitPane
import com.example.ui.panels.UiCustomizationDialog
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class MaximizedPane {
    NONE,
    FILES,
    AI,
    SIDE_CHAT,
    EMULATOR,
    BUILD,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeWorkspaceScreen(
    project: ProjectMetadata,
    files: List<ProjectFile>,
    openTabs: List<String>,
    activeFilePath: String?,
    editorSettings: EditorSettings,
    isBuilding: Boolean,
    selectedBuildVariant: BuildVariant,
    releaseSigningConfig: ReleaseSigningConfig,
    buildLogs: List<String>,
    lastBuildResult: BuildResult?,
    jumpToLine: Pair<String, Int>?,
    statusBanner: String?,
    activeProvider: AiProvider,
    aiThinkingMode: AiThinkingMode = AiThinkingMode.FAST,
    providerApiKeys: Map<AiProvider, String>,
    providerModels: Map<AiProvider, String>,
    liveModels: List<AiModelOption>,
    isFetchingModels: Boolean,
    aiUsageSummary: AiUsageSummary,
    chatThreads: List<ProjectChatThread>,
    activeThreadId: String,
    splitSideThreadId: String?,
    aiMessages: List<ChatMessage>,
    pendingAiAttachments: List<AiAttachment> = emptyList(),
    aiState: AiUiState,
    githubToken: String = "",
    githubUser: String = "",
    githubRepos: List<GitHubRepoSummary> = emptyList(),
    githubPendingChanges: List<GitHubPendingChange> = emptyList(),
    githubWorkflowRuns: List<GitHubWorkflowRun> = emptyList(),
    githubBranches: List<GitHubBranch> = emptyList(),
    githubRemoteFiles: List<GitHubTreeEntry> = emptyList(),
    githubInspectedRunId: Long? = null,
    githubWorkflowJobs: List<GitHubWorkflowJob> = emptyList(),
    githubSelectedRunLogs: String = "",
    githubWorkflowArtifacts: List<GitHubWorkflowArtifact> = emptyList(),
    openAiPanelRequest: Long = 0L,
    isGitHubBusy: Boolean = false,
    onSelectFile: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onImportProjectFileUri: (Uri) -> Unit = {},
    onUpdateProjectConfig: (ProjectLanguage, JavaVersionOption, Int) -> Unit = { _, _, _ -> },
    onUpdateContent: (String) -> Unit,
    onSelectBuildVariant: (BuildVariant) -> Unit,
    onBuildApk: (BuildVariant?) -> Unit,
    onGenerateKeystore: (String, String, String, String) -> Unit,
    onImportKeystore: (Uri, String, String) -> Unit,
    onClearKeystore: () -> Unit,
    onSearchWorkspace: (String) -> List<ProjectSearchHit>,
    onJumpToFileAndLine: (String, Int) -> Unit,
    onJumpToDiagnostic: (BuildDiagnostic) -> Unit,
    onClearJump: () -> Unit,
    onExportApkUri: (Uri) -> Unit,
    onExportProjectZipUri: (Uri) -> Unit,
    onClearStatusBanner: () -> Unit,
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
    onSetSplitSideThread: (String?) -> Unit,
    onAddAiAttachmentUri: (Uri, Boolean) -> Unit = { _, _ -> },
    onRemoveAiAttachment: (String) -> Unit = {},
    onAskGemini: (String) -> Unit,
    onRetryGemini: () -> Unit,
    onStopGemini: () -> Unit,
    onApplyGeminiCode: (SuggestedCodeBlock) -> Unit,
    onClearAiChat: () -> Unit,
    onConnectGitHub: (String) -> Unit = {},
    onDisconnectGitHub: () -> Unit = {},
    onRefreshGitHubRepos: () -> Unit = {},
    onCreateAndLinkGitHubRepo: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onLinkExistingGitHubRepo: (String, String, String) -> Unit = { _, _, _ -> },
    onCloneGitHubRepo: (GitHubRepoSummary) -> Unit = {},
    onRefreshGitHubBranches: () -> Unit = {},
    onSwitchGitHubBranch: (String) -> Unit = {},
    onCreateGitHubBranch: (String) -> Unit = {},
    onRefreshGitHubRemoteFiles: () -> Unit = {},
    onDownloadGitHubFile: (String) -> Unit = {},
    onUploadActiveFileToGitHub: (String, String) -> Unit = { _, _ -> },
    onUploadDeviceFileUriToGitHub: (Uri) -> Unit = {},
    onPushGitHub: (String) -> Unit = {},
    onPullGitHub: () -> Unit = {},
    onScaffoldGradleCi: () -> Unit = {},
    onTriggerCloudBuild: () -> Unit = {},
    onRefreshWorkflowRuns: () -> Unit = {},
    onInspectWorkflowRun: (Long) -> Unit = {},
    onRerunWorkflowRun: (Long, Boolean) -> Unit = { _, _ -> },
    onDownloadWorkflowArtifact: (Long) -> Unit = {},
    onAskAiToFixWorkflowRun: (GitHubWorkflowRun) -> Unit = {},
    onUpdateSettings: ((EditorSettings) -> EditorSettings) -> Unit,
    onBackToProjects: () -> Unit,
    modifier: Modifier = Modifier,
    threadMessagesMap: Map<String, List<ChatMessage>> = emptyMap(),
    threadUiStates: Map<String, AiUiState> = emptyMap(),
    runningThreadIds: Set<String> = emptySet(),
    terminalLines: List<String> = emptyList(),
    onLaunchRoleAgent: (AgentRole, String?) -> Unit = { _, _ -> },
    onAskGeminiInThread: (String, String) -> Unit = { _, prompt -> onAskGemini(prompt) },
    onUpdateThreadProviderModel: (String, AiProvider, String) -> Unit = { _, _, _ -> },
    onUpdateThreadSystemAndTemp: (String, String, Float) -> Unit = { _, _, _ -> },
    onStopThread: (String) -> Unit = { onStopGemini() },
    onRetryThread: (String) -> Unit = { onRetryGemini() },
    onClearThreadChat: (String) -> Unit = { onClearAiChat() },
    onRunTerminalCommand: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val density = LocalDensity.current

    var showBuildPanel by remember { mutableStateOf(false) }
    var showGeminiPanel by remember { mutableStateOf(false) }
    var showEmulatorPanel by remember { mutableStateOf(false) }
    var showSplitSideChat by remember { mutableStateOf(false) }
    var maximizedPane by remember { mutableStateOf(MaximizedPane.NONE) }
    var phoneSplitMode by remember { mutableStateOf(true) }
    var showMoreMenu by remember { mutableStateOf(false) }

    fun toggleMaximize(target: MaximizedPane) {
        maximizedPane = if (maximizedPane == target) MaximizedPane.NONE else target
    }

    // Draggable Window Dimensions (Tablets + Phones)
    var explorerWidthDp by remember { mutableFloatStateOf(230f) }
    var buildPanelHeightDp by remember { mutableFloatStateOf(260f) }
    var emulatorWidthDp by remember { mutableFloatStateOf(325f) }
    var aiPanelWidthDp by remember { mutableFloatStateOf(360f) }
    var splitChatWidthDp by remember { mutableFloatStateOf(300f) }
    var phoneEditorFraction by remember { mutableFloatStateOf(0.42f) }

    var showNewFileDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showKeystoreDialog by remember { mutableStateOf(false) }
    var showModelMenuDialog by remember { mutableStateOf(false) }
    var showKeysDialog by remember { mutableStateOf(false) }
    var showUsageDialog by remember { mutableStateOf(false) }
    var showProjectConfigDialog by remember { mutableStateOf(false) }
    var showGitHubDialog by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val exportApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
    ) { uri ->
        if (uri != null) onExportApkUri(uri)
    }

    val exportAabLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) onExportApkUri(uri)
    }

    val exportZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) onExportProjectZipUri(uri)
    }

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onAddAiAttachmentUri(uri, true)
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val captured = pendingCameraUri
        pendingCameraUri = null
        if (success && captured != null) {
            onAddAiAttachmentUri(captured, true)
        }
    }

    val pickAiFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAddAiAttachmentUri(uri, false)
    }

    val importProjectFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onImportProjectFileUri(uri)
    }

    fun launchCameraCapture() {
        runCatching {
            val file = File(context.cacheDir, "ai_camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        }
    }

    LaunchedEffect(isBuilding, lastBuildResult) {
        if (isBuilding || lastBuildResult != null) {
            showBuildPanel = true
        }
    }

    LaunchedEffect(statusBanner) {
        if (!statusBanner.isNullOrBlank()) {
            snackbarHostState.showSnackbar(statusBanner)
            onClearStatusBanner()
        }
    }

    LaunchedEffect(openAiPanelRequest) {
        if (openAiPanelRequest > 0L) {
            showGitHubDialog = false
            showGeminiPanel = true
            showEmulatorPanel = false
            if (maximizedPane != MaximizedPane.NONE && maximizedPane != MaximizedPane.AI) {
                maximizedPane = MaximizedPane.NONE
            }
        }
    }

    BackHandler {
        when {
            maximizedPane != MaximizedPane.NONE -> maximizedPane = MaximizedPane.NONE
            drawerState.isOpen -> scope.launch { drawerState.close() }
            showEmulatorPanel -> showEmulatorPanel = false
            showGeminiPanel -> showGeminiPanel = false
            showBuildPanel -> showBuildPanel = false
            else -> onBackToProjects()
        }
    }

    fun shareBuiltArtifact(artifactPath: String) {
        runCatching {
            val file = File(artifactPath)
            if (!file.exists() || file.length() == 0L) return
            val isBundle = artifactPath.endsWith(".aab", ignoreCase = true)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val mimeType = if (isBundle) "application/octet-stream" else "application/vnd.android.package-archive"
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(sendIntent, if (isBundle) "Share App Bundle (.aab)" else "Share or Open APK"),
            )
        }
    }

    fun launchExportArtifact() {
        val safeName = project.name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "app" }
        val builtPath = lastBuildResult?.apkPath
        val variant = lastBuildResult?.variant ?: selectedBuildVariant
        when {
            builtPath?.endsWith(".aab", ignoreCase = true) == true || variant == BuildVariant.RELEASE_AAB ->
                exportAabLauncher.launch("$safeName-release.aab")
            variant == BuildVariant.RELEASE_APK ->
                exportApkLauncher.launch("$safeName-release.apk")
            else ->
                exportApkLauncher.launch("$safeName-debug.apk")
        }
    }

    val activeFile = files.find { it.relativePath == activeFilePath }
    val splitThread = remember(chatThreads, splitSideThreadId, activeThreadId) {
        chatThreads.find { it.id == splitSideThreadId && it.id != activeThreadId }
            ?: chatThreads.firstOrNull { it.id != activeThreadId }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 840.dp

        val mainScaffold: @Composable () -> Unit = {
            Scaffold(
                contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    if (maximizedPane == MaximizedPane.NONE) {
                        TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = onBackToProjects,
                                modifier = Modifier.testTag("back_to_projects_button"),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to projects")
                            }
                        },
                        title = {
                            Column(
                                modifier = Modifier
                                    .clickable { showProjectConfigDialog = true }
                                    .testTag("open_project_config_title"),
                            ) {
                                Text(
                                    text = project.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val langLabel = if (project.language == ProjectLanguage.JAVA) {
                                    project.javaVersion.badge
                                } else {
                                    project.language.displayName
                                }
                                Text(
                                    text = "$langLabel • API ${project.minSdkVersion} • ${selectedBuildVariant.shortLabel}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { showGitHubDialog = true },
                                modifier = Modifier.testTag("topbar_github_push_button"),
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (githubPendingChanges.isNotEmpty()) {
                                            Badge {
                                                Text(
                                                    text = if (githubPendingChanges.size > 99) "99+" else githubPendingChanges.size.toString(),
                                                )
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudUpload,
                                        contentDescription = "Push to GitHub",
                                        tint = if (project.githubLink != null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                            Button(
                                onClick = { onBuildApk(null) },
                                enabled = !isBuilding,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary,
                                ),
                                modifier = Modifier
                                    .padding(end = 2.dp)
                                    .testTag("build_apk_button"),
                            ) {
                                if (isBuilding) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSecondary,
                                    )
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = when {
                                        isBuilding -> "..."
                                        selectedBuildVariant == BuildVariant.RELEASE_AAB -> "AAB"
                                        selectedBuildVariant == BuildVariant.RELEASE_APK -> "Rel"
                                        else -> "Build"
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            Box {
                                IconButton(
                                    onClick = { showMoreMenu = true },
                                    modifier = Modifier.testTag("workspace_more_menu_button"),
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More tools")
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("App Language & Min SDK") },
                                        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            showProjectConfigDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Import File into Project") },
                                        leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            importProjectFileLauncher.launch(arrayOf("*/*"))
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Choose AI Model") },
                                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            showModelMenuDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("AI Provider Keys") },
                                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            showKeysDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("AI Usage & Tokens") },
                                        leadingIcon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            showUsageDialog = true
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("GitHub Push & Cloud CI") },
                                        leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            showGitHubDialog = true
                                        },
                                        modifier = Modifier.testTag("workspace_github_menu_item"),
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Release Keystore Manager") },
                                        leadingIcon = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            showKeystoreDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Export Project ZIP") },
                                        leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            val safeName = project.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
                                            exportZipLauncher.launch("$safeName-source.zip")
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Customize UI & AI Studio") },
                                        leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            showSettingsDialog = true
                                        },
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    }
                },
            ) { innerPadding ->
                val isImeVisible = WindowInsets.ime.getBottom(density) > 0
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)
                        .imePadding(),
                ) {
                    if (maximizedPane != MaximizedPane.NONE) {
                        when (maximizedPane) {
                            MaximizedPane.FILES -> ProjectExplorerPane(
                                files = files,
                                activeFile = activeFile,
                                activeFilePath = activeFilePath,
                                onSelectFile = { path ->
                                    onSelectFile(path)
                                    maximizedPane = MaximizedPane.NONE
                                },
                                onJumpToSymbolLine = { line ->
                                    if (activeFile != null) {
                                        onJumpToFileAndLine(activeFile.relativePath, line)
                                        maximizedPane = MaximizedPane.NONE
                                    }
                                },
                                onCreateFile = { showNewFileDialog = true },
                                onImportFile = { importProjectFileLauncher.launch(arrayOf("*/*")) },
                                onDeleteFile = onDeleteFile,
                                isMaximized = true,
                                onToggleMaximize = { maximizedPane = MaximizedPane.NONE },
                                modifier = Modifier.fillMaxSize(),
                            )

                            MaximizedPane.AI -> GeminiPanel(
                                activeFileName = activeFile?.relativePath,
                                activeProvider = activeProvider,
                                thinkingMode = aiThinkingMode,
                                providerApiKeys = providerApiKeys,
                                providerModels = providerModels,
                                liveModels = liveModels,
                                isFetchingModels = isFetchingModels,
                                aiUsageSummary = aiUsageSummary,
                                chatThreads = chatThreads,
                                activeThreadId = activeThreadId,
                                diagnostics = lastBuildResult?.diagnostics.orEmpty(),
                                messages = aiMessages,
                                pendingAttachments = pendingAiAttachments,
                                aiState = aiState,
                                editorSettings = editorSettings,
                                threadMessagesMap = threadMessagesMap,
                                threadUiStates = threadUiStates,
                                runningThreadIds = runningThreadIds,
                                showSplitSideChat = showSplitSideChat,
                                onToggleSplitSideChat = {
                                    val next = !showSplitSideChat
                                    showSplitSideChat = next
                                    if (next && splitThread != null) {
                                        maximizedPane = MaximizedPane.SIDE_CHAT
                                    }
                                },
                                onSelectProvider = onSelectProvider,
                                onSelectThinkingMode = onSelectThinkingMode,
                                onSelectModel = onSelectModel,
                                onSaveProviderConfig = onSaveProviderConfig,
                                onRefreshLiveModels = onRefreshLiveModels,
                                onResetAiUsage = onResetAiUsage,
                                onSelectChatThread = onSelectChatThread,
                                onCreateSideChat = onCreateSideChat,
                                onRenameChatThread = onRenameChatThread,
                                onDeleteChatThread = onDeleteChatThread,
                                onLaunchRoleAgent = onLaunchRoleAgent,
                                onUpdateThreadProviderModel = onUpdateThreadProviderModel,
                                onUpdateThreadSystemAndTemp = onUpdateThreadSystemAndTemp,
                                onSendPromptToThread = onAskGeminiInThread,
                                onStopThread = onStopThread,
                                onRetryThread = onRetryThread,
                                onClearThreadChat = onClearThreadChat,
                                onOpenCustomization = { showSettingsDialog = true },
                                onPickPhoto = {
                                    pickPhotoLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                onCaptureCamera = { launchCameraCapture() },
                                onPickFile = { pickAiFileLauncher.launch(arrayOf("*/*")) },
                                onRemoveAttachment = onRemoveAiAttachment,
                                onSendPrompt = onAskGemini,
                                onRetryGemini = onRetryGemini,
                                onStopGemini = onStopGemini,
                                onApplyCode = onApplyGeminiCode,
                                onClearChat = onClearAiChat,
                                onClose = {
                                    maximizedPane = MaximizedPane.NONE
                                    showGeminiPanel = false
                                },
                                isMaximized = true,
                                onToggleMaximize = { maximizedPane = MaximizedPane.NONE },
                                modifier = Modifier.fillMaxSize(),
                            )

                            MaximizedPane.SIDE_CHAT -> SideChatSplitPane(
                                threads = chatThreads,
                                activeThreadId = activeThreadId,
                                splitThread = splitThread,
                                activeFileName = activeFile?.relativePath,
                                onSelectSplitThread = onSetSplitSideThread,
                                onCreateSideChat = {
                                    onCreateSideChat("")
                                    showSplitSideChat = true
                                },
                                onSwitchToMainInput = { targetId ->
                                    onSelectChatThread(targetId)
                                    showSplitSideChat = false
                                    maximizedPane = MaximizedPane.AI
                                },
                                onApplyCode = onApplyGeminiCode,
                                onClose = {
                                    showSplitSideChat = false
                                    maximizedPane = MaximizedPane.NONE
                                },
                                threadMessagesMap = threadMessagesMap,
                                threadUiStates = threadUiStates,
                                runningThreadIds = runningThreadIds,
                                activeProvider = activeProvider,
                                providerModels = providerModels,
                                liveModels = liveModels,
                                isFetchingModels = isFetchingModels,
                                onSelectSplitProviderModel = onUpdateThreadProviderModel,
                                onRefreshLiveModels = onRefreshLiveModels,
                                onSendPromptToThread = onAskGeminiInThread,
                                onStopThread = onStopThread,
                                onRetryThread = onRetryThread,
                                isMaximized = true,
                                onToggleMaximize = { maximizedPane = MaximizedPane.NONE },
                                modifier = Modifier.fillMaxSize(),
                            )

                            MaximizedPane.EMULATOR -> EmulatorPanel(
                                appName = project.name,
                                packageName = project.packageName,
                                files = files,
                                builtApkPath = lastBuildResult?.apkPath,
                                onInstallApk = { apkPath -> shareBuiltArtifact(apkPath) },
                                onClose = {
                                    maximizedPane = MaximizedPane.NONE
                                    showEmulatorPanel = false
                                },
                                isMaximized = true,
                                onToggleMaximize = { maximizedPane = MaximizedPane.NONE },
                                modifier = Modifier.fillMaxSize(),
                            )

                            MaximizedPane.BUILD -> BuildPanel(
                                isBuilding = isBuilding,
                                selectedVariant = selectedBuildVariant,
                                releaseSigningConfig = releaseSigningConfig,
                                logs = buildLogs,
                                buildResult = lastBuildResult,
                                onSelectVariant = onSelectBuildVariant,
                                onTriggerBuild = { variant -> onBuildApk(variant) },
                                onOpenKeystoreManager = { showKeystoreDialog = true },
                                onOpenEmulator = {
                                    maximizedPane = MaximizedPane.NONE
                                    showEmulatorPanel = true
                                    if (!isExpanded) showGeminiPanel = false
                                },
                                onDiagnosticClick = { diag ->
                                    maximizedPane = MaximizedPane.NONE
                                    onJumpToDiagnostic(diag)
                                },
                                onAskGeminiToFix = {
                                    maximizedPane = MaximizedPane.NONE
                                    showGeminiPanel = true
                                    if (!isExpanded) showEmulatorPanel = false
                                    onLaunchRoleAgent(
                                        AgentRole.ERROR_FIXER,
                                        "Inspect the build errors, edit the affected files directly, and re-run build_apk until the build succeeds.",
                                    )
                                },
                                onExportArtifact = { launchExportArtifact() },
                                onShareArtifact = { path -> shareBuiltArtifact(path) },
                                onOpenGitHubCloudBuild = { showGitHubDialog = true },
                                terminalLines = terminalLines,
                                onRunTerminalCommand = onRunTerminalCommand,
                                onClose = {
                                    maximizedPane = MaximizedPane.NONE
                                    showBuildPanel = false
                                },
                                isMaximized = true,
                                onToggleMaximize = { maximizedPane = MaximizedPane.NONE },
                                modifier = Modifier.fillMaxSize(),
                            )

                            MaximizedPane.NONE -> Unit
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxSize()) {
                    // Persistent Explorer Sidebar on Expanded Screens (Resizable)
                    if (isExpanded) {
                        ProjectExplorerPane(
                            files = files,
                            activeFile = activeFile,
                            activeFilePath = activeFilePath,
                            onSelectFile = onSelectFile,
                            onJumpToSymbolLine = { line ->
                                if (activeFile != null) {
                                    onJumpToFileAndLine(activeFile.relativePath, line)
                                }
                            },
                            onCreateFile = { showNewFileDialog = true },
                            onImportFile = { importProjectFileLauncher.launch(arrayOf("*/*")) },
                            onDeleteFile = onDeleteFile,
                            isMaximized = false,
                            onToggleMaximize = { toggleMaximize(MaximizedPane.FILES) },
                            modifier = Modifier
                                .width(explorerWidthDp.dp)
                                .fillMaxHeight(),
                        )
                        HorizontalResizableDivider(
                            onDragDp = { delta -> explorerWidthDp = (explorerWidthDp + delta).coerceIn(175f, 360f) },
                        )
                    }

                    // Center Editor + Bottom Console (+ Phone Split Dock)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        // Quick Workspace Tool Strip (keeps TopAppBar uncrowded on phones)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (!isExpanded) {
                                    WorkspaceStripChip(
                                        icon = Icons.Default.FolderOpen,
                                        label = "Files",
                                        selected = drawerState.isOpen,
                                        onClick = { scope.launch { drawerState.open() } },
                                        tag = "open_files_drawer_button",
                                    )
                                }
                                WorkspaceStripChip(
                                    icon = Icons.Default.AutoAwesome,
                                    label = if (runningThreadIds.isNotEmpty()) {
                                        "AI (${runningThreadIds.size})"
                                    } else {
                                        "AI Agent"
                                    },
                                    selected = showGeminiPanel,
                                    onClick = {
                                        val next = !showGeminiPanel
                                        showGeminiPanel = next
                                        if (!isExpanded && next) showEmulatorPanel = false
                                    },
                                    tag = "toggle_gemini_button",
                                )
                                WorkspaceStripChip(
                                    icon = Icons.Default.Build,
                                    label = "Features",
                                    selected = showGeminiPanel && chatThreads.find { it.agentRole == AgentRole.FEATURE_BUILDER }?.id == activeThreadId,
                                    onClick = {
                                        showGeminiPanel = true
                                        if (!isExpanded) showEmulatorPanel = false
                                        onLaunchRoleAgent(AgentRole.FEATURE_BUILDER, null)
                                    },
                                    tag = "workspace_role_feature_chip",
                                )
                                WorkspaceStripChip(
                                    icon = Icons.Default.BugReport,
                                    label = "Fix Errors",
                                    selected = showGeminiPanel && chatThreads.find { it.agentRole == AgentRole.ERROR_FIXER }?.id == activeThreadId,
                                    onClick = {
                                        showGeminiPanel = true
                                        if (!isExpanded) showEmulatorPanel = false
                                        onLaunchRoleAgent(AgentRole.ERROR_FIXER, null)
                                    },
                                    tag = "workspace_role_fixer_chip",
                                )
                                WorkspaceStripChip(
                                    icon = Icons.Default.PhoneAndroid,
                                    label = "Emulator",
                                    selected = showEmulatorPanel,
                                    onClick = {
                                        val next = !showEmulatorPanel
                                        showEmulatorPanel = next
                                        if (!isExpanded && next) showGeminiPanel = false
                                    },
                                    tag = "toggle_emulator_button",
                                )
                                if (!isExpanded) {
                                    WorkspaceStripChip(
                                        icon = Icons.Default.Splitscreen,
                                        label = if (phoneSplitMode) "Split" else "Sheet",
                                        selected = phoneSplitMode,
                                        onClick = { phoneSplitMode = !phoneSplitMode },
                                        tag = "toggle_phone_split_mode_button",
                                    )
                                }
                                val pushLabel = when {
                                    project.githubLink != null && githubPendingChanges.isNotEmpty() ->
                                        "Push (${githubPendingChanges.size})"
                                    project.githubLink != null -> "GitHub"
                                    else -> "Push"
                                }
                                WorkspaceStripChip(
                                    icon = Icons.Default.CloudUpload,
                                    label = pushLabel,
                                    selected = showGitHubDialog || (project.githubLink != null && githubPendingChanges.isNotEmpty()),
                                    onClick = { showGitHubDialog = true },
                                    tag = "workspace_github_push_chip",
                                )
                                WorkspaceStripChip(
                                    icon = Icons.Default.Terminal,
                                    label = "Console",
                                    selected = showBuildPanel,
                                    onClick = { showBuildPanel = !showBuildPanel },
                                    tag = "toggle_console_button",
                                )
                                WorkspaceStripChip(
                                    icon = Icons.Default.Search,
                                    label = "Search",
                                    selected = showSearchDialog,
                                    onClick = { showSearchDialog = true },
                                    tag = "workspace_search_button",
                                )
                                WorkspaceStripChip(
                                    icon = Icons.Default.Palette,
                                    label = "Theme",
                                    selected = showSettingsDialog,
                                    onClick = { showSettingsDialog = true },
                                    tag = "workspace_theme_chip",
                                )
                                WorkspaceStripChip(
                                    icon = Icons.Default.UploadFile,
                                    label = "Import",
                                    selected = false,
                                    onClick = { importProjectFileLauncher.launch(arrayOf("*/*")) },
                                    tag = "import_project_file_button",
                                )
                                WorkspaceStripChip(
                                    icon = Icons.Default.Settings,
                                    label = if (project.language == ProjectLanguage.JAVA) {
                                        "${project.javaVersion.badge} • API ${project.minSdkVersion}"
                                    } else {
                                        "${project.language.displayName} • API ${project.minSdkVersion}"
                                    },
                                    selected = showProjectConfigDialog,
                                    onClick = { showProjectConfigDialog = true },
                                    tag = "project_config_chip",
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // File Tabs Strip
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LazyRow(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    items(openTabs, key = { it }) { tabPath ->
                                        val isSelected = tabPath == activeFilePath
                                        val fileName = tabPath.substringAfterLast('/')
                                        Surface(
                                            onClick = { onSelectFile(tabPath) },
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    text = fileName,
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) {
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                                )
                                                IconButton(
                                                    onClick = { onCloseTab(tabPath) },
                                                    modifier = Modifier.size(20.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "Close $fileName",
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = { showNewFileDialog = true },
                                    modifier = Modifier.testTag("new_file_button"),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Create new file")
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Breadcrumbs Bar (Android Studio New UI) — hidden on phone when IME is open to save editor space
                        if (activeFile != null && editorSettings.showBreadcrumbs && !(isImeVisible && !isExpanded)) {
                            val symbols by produceState(
                                initialValue = emptyList(),
                                activeFile.relativePath,
                                activeFile.content,
                            ) {
                                value = withContext(Dispatchers.Default) {
                                    if (activeFile.isBinary) emptyList()
                                    else CodeStructureExtractor.extract(activeFile.content, activeFile.language)
                                }
                            }
                            EditorBreadcrumbBar(
                                projectName = project.name,
                                relativePath = activeFile.relativePath,
                                firstSymbol = symbols.firstOrNull()?.name,
                                onOpenSearch = { showSearchDialog = true },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }

                        val phoneBottomActive = !isExpanded && phoneSplitMode && (showEmulatorPanel || showGeminiPanel)
                        val editorWeight = if (phoneBottomActive) phoneEditorFraction else 1f

                        // Code Editor Area
                        Box(
                            modifier = Modifier
                                .weight(editorWeight)
                                .fillMaxWidth(),
                        ) {
                            if (activeFile != null && activeFile.isBinary) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.secondary,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(activeFile.fileName, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = activeFile.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else if (activeFile != null) {
                                CodeEditor(
                                    filePath = activeFile.relativePath,
                                    content = activeFile.content,
                                    language = activeFile.language,
                                    settings = editorSettings,
                                    diagnostics = lastBuildResult?.diagnostics.orEmpty(),
                                    jumpToLineRequest = jumpToLine,
                                    onJumpHandled = onClearJump,
                                    onContentChange = onUpdateContent,
                                    onRequestFixWithAi = {
                                        showGeminiPanel = true
                                        if (!isExpanded) showEmulatorPanel = false
                                        onLaunchRoleAgent(
                                            AgentRole.ERROR_FIXER,
                                            "Fix the compiler errors in ${activeFile.relativePath} and verify via build_apk.",
                                        )
                                    },
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        Icons.Default.Code,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text("No file open", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Open a Kotlin, Java, or XML file from the Project Explorer.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // Phone Resizable Split Bottom Dock (Emulator OR Concurrent AI Agent while editing code above!)
                        if (phoneBottomActive) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f - phoneEditorFraction)
                                    .fillMaxWidth(),
                            ) {
                                val totalHalfHeightPx = with(density) { (maxHeight / (1f - phoneEditorFraction)).toPx() }
                                    .coerceAtLeast(300f)
                                Column(modifier = Modifier.fillMaxSize()) {
                                    VerticalResizableDivider(
                                        onDragDp = { deltaDp ->
                                            val deltaPx = with(density) { deltaDp.dp.toPx() }
                                            phoneEditorFraction = (phoneEditorFraction + deltaPx / totalHalfHeightPx)
                                                .coerceIn(0.24f, 0.74f)
                                        },
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                    ) {
                                        if (showEmulatorPanel) {
                                            EmulatorPanel(
                                                appName = project.name,
                                                packageName = project.packageName,
                                                files = files,
                                                builtApkPath = lastBuildResult?.apkPath,
                                                onInstallApk = { apkPath -> shareBuiltArtifact(apkPath) },
                                                onClose = { showEmulatorPanel = false },
                                                isMaximized = false,
                                                onToggleMaximize = { toggleMaximize(MaximizedPane.EMULATOR) },
                                            )
                                        } else if (showGeminiPanel) {
                                            if (showSplitSideChat && splitThread != null) {
                                                SideChatSplitPane(
                                                    threads = chatThreads,
                                                    activeThreadId = activeThreadId,
                                                    splitThread = splitThread,
                                                    activeFileName = activeFile?.relativePath,
                                                    onSelectSplitThread = onSetSplitSideThread,
                                                    onCreateSideChat = {
                                                        onCreateSideChat("")
                                                        showSplitSideChat = true
                                                    },
                                                    onSwitchToMainInput = { targetId ->
                                                        onSelectChatThread(targetId)
                                                        showSplitSideChat = false
                                                    },
                                                    onApplyCode = onApplyGeminiCode,
                                                    onClose = { showSplitSideChat = false },
                                                    threadMessagesMap = threadMessagesMap,
                                                    threadUiStates = threadUiStates,
                                                    runningThreadIds = runningThreadIds,
                                                    activeProvider = activeProvider,
                                                    providerModels = providerModels,
                                                    liveModels = liveModels,
                                                    isFetchingModels = isFetchingModels,
                                                    onSelectSplitProviderModel = onUpdateThreadProviderModel,
                                                    onRefreshLiveModels = onRefreshLiveModels,
                                                    onSendPromptToThread = onAskGeminiInThread,
                                                    onStopThread = onStopThread,
                                                    onRetryThread = onRetryThread,
                                                    isMaximized = false,
                                                    onToggleMaximize = { toggleMaximize(MaximizedPane.SIDE_CHAT) },
                                                )
                                            } else {
                                                GeminiPanel(
                                                    activeFileName = activeFile?.relativePath,
                                                    activeProvider = activeProvider,
                                                    thinkingMode = aiThinkingMode,
                                                    providerApiKeys = providerApiKeys,
                                                    providerModels = providerModels,
                                                    liveModels = liveModels,
                                                    isFetchingModels = isFetchingModels,
                                                    aiUsageSummary = aiUsageSummary,
                                                    chatThreads = chatThreads,
                                                    activeThreadId = activeThreadId,
                                                    diagnostics = lastBuildResult?.diagnostics.orEmpty(),
                                                    messages = aiMessages,
                                                    pendingAttachments = pendingAiAttachments,
                                                    aiState = aiState,
                                                    editorSettings = editorSettings,
                                                    threadMessagesMap = threadMessagesMap,
                                                    threadUiStates = threadUiStates,
                                                    runningThreadIds = runningThreadIds,
                                                    showSplitSideChat = showSplitSideChat,
                                                    onToggleSplitSideChat = { showSplitSideChat = !showSplitSideChat },
                                                    onSelectProvider = onSelectProvider,
                                                    onSelectThinkingMode = onSelectThinkingMode,
                                                    onSelectModel = onSelectModel,
                                                    onSaveProviderConfig = onSaveProviderConfig,
                                                    onRefreshLiveModels = onRefreshLiveModels,
                                                    onResetAiUsage = onResetAiUsage,
                                                    onSelectChatThread = onSelectChatThread,
                                                    onCreateSideChat = onCreateSideChat,
                                                    onRenameChatThread = onRenameChatThread,
                                                    onDeleteChatThread = onDeleteChatThread,
                                                    onLaunchRoleAgent = onLaunchRoleAgent,
                                                    onUpdateThreadProviderModel = onUpdateThreadProviderModel,
                                                    onUpdateThreadSystemAndTemp = onUpdateThreadSystemAndTemp,
                                                    onSendPromptToThread = onAskGeminiInThread,
                                                    onStopThread = onStopThread,
                                                    onRetryThread = onRetryThread,
                                                    onClearThreadChat = onClearThreadChat,
                                                    onOpenCustomization = { showSettingsDialog = true },
                                                    onPickPhoto = {
                                                        pickPhotoLauncher.launch(
                                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                                        )
                                                    },
                                                    onCaptureCamera = { launchCameraCapture() },
                                                    onPickFile = { pickAiFileLauncher.launch(arrayOf("*/*")) },
                                                    onRemoveAttachment = onRemoveAiAttachment,
                                                    onSendPrompt = onAskGemini,
                                                    onRetryGemini = onRetryGemini,
                                                    onStopGemini = onStopGemini,
                                                    onApplyCode = onApplyGeminiCode,
                                                    onClearChat = onClearAiChat,
                                                    onClose = { showGeminiPanel = false },
                                                    isMaximized = false,
                                                    onToggleMaximize = { toggleMaximize(MaximizedPane.AI) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Collapsible Resizable Bottom Build Console, Package Inspector & Interactive Terminal
                        AnimatedVisibility(visible = showBuildPanel) {
                            Column {
                                VerticalResizableDivider(
                                    onDragDp = { delta ->
                                        buildPanelHeightDp = (buildPanelHeightDp - delta).coerceIn(150f, 460f)
                                    },
                                )
                                BuildPanel(
                                    isBuilding = isBuilding,
                                    selectedVariant = selectedBuildVariant,
                                    releaseSigningConfig = releaseSigningConfig,
                                    logs = buildLogs,
                                    buildResult = lastBuildResult,
                                    onSelectVariant = onSelectBuildVariant,
                                    onTriggerBuild = { variant -> onBuildApk(variant) },
                                    onOpenKeystoreManager = { showKeystoreDialog = true },
                                    onOpenEmulator = {
                                        showEmulatorPanel = true
                                        if (!isExpanded) showGeminiPanel = false
                                    },
                                    onDiagnosticClick = onJumpToDiagnostic,
                                    onAskGeminiToFix = {
                                        showGeminiPanel = true
                                        if (!isExpanded) showEmulatorPanel = false
                                        onLaunchRoleAgent(
                                            AgentRole.ERROR_FIXER,
                                            "Inspect the build errors, edit the affected files directly, and re-run build_apk until the build succeeds.",
                                        )
                                    },
                                    onExportArtifact = { launchExportArtifact() },
                                    onShareArtifact = { path -> shareBuiltArtifact(path) },
                                    onOpenGitHubCloudBuild = { showGitHubDialog = true },
                                    terminalLines = terminalLines,
                                    onRunTerminalCommand = onRunTerminalCommand,
                                    onClose = { showBuildPanel = false },
                                    isMaximized = false,
                                    onToggleMaximize = { toggleMaximize(MaximizedPane.BUILD) },
                                    modifier = Modifier.height(buildPanelHeightDp.dp),
                                )
                            }
                        }
                    }

                    // Persistent Live Android Emulator Pane on Expanded Screens (Resizable)
                    if (isExpanded && showEmulatorPanel) {
                        HorizontalResizableDivider(
                            onDragDp = { delta -> emulatorWidthDp = (emulatorWidthDp - delta).coerceIn(240f, 460f) },
                        )
                        EmulatorPanel(
                            appName = project.name,
                            packageName = project.packageName,
                            files = files,
                            builtApkPath = lastBuildResult?.apkPath,
                            onInstallApk = { apkPath -> shareBuiltArtifact(apkPath) },
                            onClose = { showEmulatorPanel = false },
                            isMaximized = false,
                            onToggleMaximize = { toggleMaximize(MaximizedPane.EMULATOR) },
                            modifier = Modifier
                                .width(emulatorWidthDp.dp)
                                .fillMaxHeight(),
                        )
                    }

                    // Persistent Multi-Provider AI Pane on Expanded Screens (Resizable)
                    if (isExpanded && showGeminiPanel) {
                        HorizontalResizableDivider(
                            onDragDp = { delta -> aiPanelWidthDp = (aiPanelWidthDp - delta).coerceIn(280f, 520f) },
                        )
                        GeminiPanel(
                            activeFileName = activeFile?.relativePath,
                            activeProvider = activeProvider,
                            thinkingMode = aiThinkingMode,
                            providerApiKeys = providerApiKeys,
                            providerModels = providerModels,
                            liveModels = liveModels,
                            isFetchingModels = isFetchingModels,
                            aiUsageSummary = aiUsageSummary,
                            chatThreads = chatThreads,
                            activeThreadId = activeThreadId,
                            diagnostics = lastBuildResult?.diagnostics.orEmpty(),
                            messages = aiMessages,
                            pendingAttachments = pendingAiAttachments,
                            aiState = aiState,
                            editorSettings = editorSettings,
                            threadMessagesMap = threadMessagesMap,
                            threadUiStates = threadUiStates,
                            runningThreadIds = runningThreadIds,
                            showSplitSideChat = showSplitSideChat,
                            onToggleSplitSideChat = { showSplitSideChat = !showSplitSideChat },
                            onSelectProvider = onSelectProvider,
                            onSelectThinkingMode = onSelectThinkingMode,
                            onSelectModel = onSelectModel,
                            onSaveProviderConfig = onSaveProviderConfig,
                            onRefreshLiveModels = onRefreshLiveModels,
                            onResetAiUsage = onResetAiUsage,
                            onSelectChatThread = onSelectChatThread,
                            onCreateSideChat = onCreateSideChat,
                            onRenameChatThread = onRenameChatThread,
                            onDeleteChatThread = onDeleteChatThread,
                            onLaunchRoleAgent = onLaunchRoleAgent,
                            onUpdateThreadProviderModel = onUpdateThreadProviderModel,
                            onUpdateThreadSystemAndTemp = onUpdateThreadSystemAndTemp,
                            onSendPromptToThread = onAskGeminiInThread,
                            onStopThread = onStopThread,
                            onRetryThread = onRetryThread,
                            onClearThreadChat = onClearThreadChat,
                            onOpenCustomization = { showSettingsDialog = true },
                            onPickPhoto = {
                                pickPhotoLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onCaptureCamera = { launchCameraCapture() },
                            onPickFile = { pickAiFileLauncher.launch(arrayOf("*/*")) },
                            onRemoveAttachment = onRemoveAiAttachment,
                            onSendPrompt = onAskGemini,
                            onRetryGemini = onRetryGemini,
                            onStopGemini = onStopGemini,
                            onApplyCode = onApplyGeminiCode,
                            onClearChat = onClearAiChat,
                            onClose = { showGeminiPanel = false },
                            isMaximized = false,
                            onToggleMaximize = { toggleMaximize(MaximizedPane.AI) },
                            modifier = Modifier
                                .width(aiPanelWidthDp.dp)
                                .fillMaxHeight(),
                        )
                    }

                    // Optional Side-by-Side Split Side Chat on Expanded Screens (Resizable & Independent!)
                    if (isExpanded && showGeminiPanel && showSplitSideChat) {
                        HorizontalResizableDivider(
                            onDragDp = { delta -> splitChatWidthDp = (splitChatWidthDp - delta).coerceIn(240f, 440f) },
                        )
                        SideChatSplitPane(
                            threads = chatThreads,
                            activeThreadId = activeThreadId,
                            splitThread = splitThread,
                            activeFileName = activeFile?.relativePath,
                            onSelectSplitThread = onSetSplitSideThread,
                            onCreateSideChat = {
                                onCreateSideChat("")
                                showSplitSideChat = true
                            },
                            onSwitchToMainInput = { targetId ->
                                onSelectChatThread(targetId)
                            },
                            onApplyCode = onApplyGeminiCode,
                            onClose = { showSplitSideChat = false },
                            threadMessagesMap = threadMessagesMap,
                            threadUiStates = threadUiStates,
                            runningThreadIds = runningThreadIds,
                            activeProvider = activeProvider,
                            providerModels = providerModels,
                            liveModels = liveModels,
                            isFetchingModels = isFetchingModels,
                            onSelectSplitProviderModel = onUpdateThreadProviderModel,
                            onRefreshLiveModels = onRefreshLiveModels,
                            onSendPromptToThread = onAskGeminiInThread,
                            onStopThread = onStopThread,
                            onRetryThread = onRetryThread,
                            isMaximized = false,
                            onToggleMaximize = { toggleMaximize(MaximizedPane.SIDE_CHAT) },
                            modifier = Modifier
                                .width(splitChatWidthDp.dp)
                                .fillMaxHeight(),
                        )
                    }
                        }
                    }
                }
            }
        }

        if (isExpanded) {
            mainScaffold()
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                        ProjectExplorerPane(
                            files = files,
                            activeFile = activeFile,
                            activeFilePath = activeFilePath,
                            onSelectFile = { path ->
                                onSelectFile(path)
                                scope.launch { drawerState.close() }
                            },
                            onJumpToSymbolLine = { line ->
                                if (activeFile != null) {
                                    onJumpToFileAndLine(activeFile.relativePath, line)
                                    scope.launch { drawerState.close() }
                                }
                            },
                            onCreateFile = {
                                scope.launch { drawerState.close() }
                                showNewFileDialog = true
                            },
                            onImportFile = {
                                scope.launch { drawerState.close() }
                                importProjectFileLauncher.launch(arrayOf("*/*"))
                            },
                            onDeleteFile = onDeleteFile,
                            isMaximized = false,
                            onToggleMaximize = {
                                scope.launch { drawerState.close() }
                                maximizedPane = MaximizedPane.FILES
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
                content = mainScaffold,
            )

            if (!phoneSplitMode && showEmulatorPanel && maximizedPane == MaximizedPane.NONE) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showEmulatorPanel = false },
                    sheetState = sheetState,
                    contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.86f)
                            .imePadding()
                            .navigationBarsPadding(),
                    ) {
                        EmulatorPanel(
                            appName = project.name,
                            packageName = project.packageName,
                            files = files,
                            builtApkPath = lastBuildResult?.apkPath,
                            onInstallApk = { apkPath -> shareBuiltArtifact(apkPath) },
                            onClose = { showEmulatorPanel = false },
                            isMaximized = false,
                            onToggleMaximize = {
                                showEmulatorPanel = true
                                maximizedPane = MaximizedPane.EMULATOR
                            },
                        )
                    }
                }
            } else if (!phoneSplitMode && showGeminiPanel && maximizedPane == MaximizedPane.NONE) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showGeminiPanel = false },
                    sheetState = sheetState,
                    contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.88f)
                            .imePadding()
                            .navigationBarsPadding(),
                    ) {
                        GeminiPanel(
                            activeFileName = activeFile?.relativePath,
                            activeProvider = activeProvider,
                            thinkingMode = aiThinkingMode,
                            providerApiKeys = providerApiKeys,
                            providerModels = providerModels,
                            liveModels = liveModels,
                            isFetchingModels = isFetchingModels,
                            aiUsageSummary = aiUsageSummary,
                            chatThreads = chatThreads,
                            activeThreadId = activeThreadId,
                            diagnostics = lastBuildResult?.diagnostics.orEmpty(),
                            messages = aiMessages,
                            pendingAttachments = pendingAiAttachments,
                            aiState = aiState,
                            editorSettings = editorSettings,
                            threadMessagesMap = threadMessagesMap,
                            threadUiStates = threadUiStates,
                            runningThreadIds = runningThreadIds,
                            showSplitSideChat = showSplitSideChat,
                            onToggleSplitSideChat = { showSplitSideChat = !showSplitSideChat },
                            onSelectProvider = onSelectProvider,
                            onSelectThinkingMode = onSelectThinkingMode,
                            onSelectModel = onSelectModel,
                            onSaveProviderConfig = onSaveProviderConfig,
                            onRefreshLiveModels = onRefreshLiveModels,
                            onResetAiUsage = onResetAiUsage,
                            onSelectChatThread = onSelectChatThread,
                            onCreateSideChat = onCreateSideChat,
                            onRenameChatThread = onRenameChatThread,
                            onDeleteChatThread = onDeleteChatThread,
                            onLaunchRoleAgent = onLaunchRoleAgent,
                            onUpdateThreadProviderModel = onUpdateThreadProviderModel,
                            onUpdateThreadSystemAndTemp = onUpdateThreadSystemAndTemp,
                            onSendPromptToThread = onAskGeminiInThread,
                            onStopThread = onStopThread,
                            onRetryThread = onRetryThread,
                            onClearThreadChat = onClearThreadChat,
                            onOpenCustomization = { showSettingsDialog = true },
                            onPickPhoto = {
                                pickPhotoLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onCaptureCamera = { launchCameraCapture() },
                            onPickFile = { pickAiFileLauncher.launch(arrayOf("*/*")) },
                            onRemoveAttachment = onRemoveAiAttachment,
                            onSendPrompt = onAskGemini,
                            onRetryGemini = onRetryGemini,
                            onStopGemini = onStopGemini,
                            onApplyCode = onApplyGeminiCode,
                            onClearChat = onClearAiChat,
                            onClose = { showGeminiPanel = false },
                            isMaximized = false,
                            onToggleMaximize = {
                                showGeminiPanel = true
                                maximizedPane = MaximizedPane.AI
                            },
                        )
                    }
                }
            }
        }
    }

    if (showNewFileDialog) {
        NewFileDialog(
            packageName = project.packageName,
            projectLanguage = project.language,
            onDismiss = { showNewFileDialog = false },
            onCreate = { path ->
                showNewFileDialog = false
                onCreateFile(path)
            },
        )
    }

    if (showProjectConfigDialog) {
        ProjectConfigDialog(
            currentLanguage = project.language,
            currentJavaVersion = project.javaVersion,
            currentMinSdk = project.minSdkVersion,
            onDismiss = { showProjectConfigDialog = false },
            onSave = { lang, javaVer, minSdk ->
                showProjectConfigDialog = false
                onUpdateProjectConfig(lang, javaVer, minSdk)
            },
        )
    }

    if (showSettingsDialog) {
        UiCustomizationDialog(
            settings = editorSettings,
            onUpdateSettings = onUpdateSettings,
            onDismiss = { showSettingsDialog = false },
        )
    }

    if (showSearchDialog) {
        ProjectSearchDialog(
            onSearch = onSearchWorkspace,
            onJumpToHit = { hit -> onJumpToFileAndLine(hit.relativePath, hit.lineNumber) },
            onDismiss = { showSearchDialog = false },
        )
    }

    if (showKeystoreDialog) {
        ReleaseKeystoreDialog(
            signingConfig = releaseSigningConfig,
            onGenerateKeystore = onGenerateKeystore,
            onImportKeystore = onImportKeystore,
            onClearKeystore = onClearKeystore,
            onDismiss = { showKeystoreDialog = false },
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

    if (showKeysDialog) {
        ProviderKeysDialog(
            initialProvider = activeProvider,
            providerApiKeys = providerApiKeys,
            providerModels = providerModels,
            onSaveConfig = onSaveProviderConfig,
            onDismiss = { showKeysDialog = false },
        )
    }

    if (showUsageDialog) {
        AiUsageDialog(
            usageSummary = aiUsageSummary,
            onResetUsage = onResetAiUsage,
            onDismiss = { showUsageDialog = false },
        )
    }

    if (showGitHubDialog) {
        GitHubSheetDialog(
            project = project,
            activeFilePath = activeFilePath,
            githubToken = githubToken,
            githubUser = githubUser,
            githubRepos = githubRepos,
            pendingChanges = githubPendingChanges,
            workflowRuns = githubWorkflowRuns,
            branches = githubBranches,
            remoteFiles = githubRemoteFiles,
            inspectedRunId = githubInspectedRunId,
            workflowJobs = githubWorkflowJobs,
            selectedRunLogs = githubSelectedRunLogs,
            workflowArtifacts = githubWorkflowArtifacts,
            isGitHubBusy = isGitHubBusy,
            onConnectToken = onConnectGitHub,
            onDisconnectGitHub = onDisconnectGitHub,
            onRefreshRepos = onRefreshGitHubRepos,
            onCreateAndLinkRepo = onCreateAndLinkGitHubRepo,
            onLinkExistingRepo = onLinkExistingGitHubRepo,
            onCloneRepoAsProject = onCloneGitHubRepo,
            onRefreshBranches = onRefreshGitHubBranches,
            onSwitchBranch = onSwitchGitHubBranch,
            onCreateBranch = onCreateGitHubBranch,
            onRefreshRemoteFiles = onRefreshGitHubRemoteFiles,
            onDownloadRemoteFile = onDownloadGitHubFile,
            onUploadActiveFile = onUploadActiveFileToGitHub,
            onUploadDeviceFileUri = onUploadDeviceFileUriToGitHub,
            onPushProject = onPushGitHub,
            onPullProject = onPullGitHub,
            onScaffoldGradleCi = onScaffoldGradleCi,
            onTriggerCloudBuild = onTriggerCloudBuild,
            onRefreshWorkflowRuns = onRefreshWorkflowRuns,
            onInspectWorkflowRun = onInspectWorkflowRun,
            onRerunWorkflowRun = onRerunWorkflowRun,
            onDownloadArtifact = onDownloadWorkflowArtifact,
            onAskAiToFixRun = onAskAiToFixWorkflowRun,
            onDismiss = { showGitHubDialog = false },
        )
    }
}

@Composable
private fun WorkspaceStripChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    Surface(
        onClick = onClick,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun HorizontalResizableDivider(
    onDragDp: (Float) -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .width(10.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val deltaDp = with(density) { dragAmount.toDp().value }
                    onDragDp(deltaDp)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

@Composable
private fun VerticalResizableDivider(
    onDragDp: (Float) -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val deltaDp = with(density) { dragAmount.toDp().value }
                    onDragDp(deltaDp)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

@Composable
private fun EditorBreadcrumbBar(
    projectName: String,
    relativePath: String,
    firstSymbol: String?,
    onOpenSearch: () -> Unit,
) {
    val segments = remember(relativePath) { relativePath.split('/').filter { it.isNotBlank() } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("editor_breadcrumb_bar"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .clickable(onClick = onOpenSearch)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = projectName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            segments.forEachIndexed { idx, seg ->
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = seg,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (idx == segments.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (idx == segments.lastIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (!firstSymbol.isNullOrBlank()) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = firstSymbol,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun ProjectExplorerPane(
    files: List<ProjectFile>,
    activeFile: ProjectFile?,
    activeFilePath: String?,
    onSelectFile: (String) -> Unit,
    onJumpToSymbolLine: (Int) -> Unit,
    onCreateFile: () -> Unit,
    onImportFile: () -> Unit,
    onDeleteFile: (String) -> Unit,
    isMaximized: Boolean = false,
    onToggleMaximize: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val symbols by produceState(
        initialValue = emptyList(),
        activeFile?.relativePath,
        activeFile?.content,
    ) {
        value = withContext(Dispatchers.Default) {
            if (activeFile != null && !activeFile.isBinary) {
                CodeStructureExtractor.extract(activeFile.content, activeFile.language)
            } else {
                emptyList()
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Project",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onImportFile,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("explorer_import_file_button"),
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = "Upload or import file", modifier = Modifier.size(19.dp))
                }
                IconButton(
                    onClick = onCreateFile,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("explorer_new_file_button"),
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New file", modifier = Modifier.size(19.dp))
                }
                if (onToggleMaximize != null) {
                    IconButton(
                        onClick = onToggleMaximize,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("explorer_maximize_button"),
                    ) {
                        Icon(
                            imageVector = if (isMaximized) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                            contentDescription = if (isMaximized) "Restore project explorer" else "Maximize project explorer",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Files (${files.size})", style = MaterialTheme.typography.labelSmall) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Structure (${symbols.size})", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("explorer_structure_tab"),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (selectedTab == 0) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(files, key = { it.relativePath }) { file ->
                        val isSelected = file.relativePath == activeFilePath
                        val isProtected = file.relativePath == "AndroidManifest.xml" || file.relativePath == "app/src/main/AndroidManifest.xml"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable { onSelectFile(file.relativePath) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = when (file.language) {
                                    FileLanguage.JAVA, FileLanguage.KOTLIN -> Icons.Default.Code
                                    else -> Icons.Default.Description
                                },
                                contentDescription = null,
                                tint = when (file.language) {
                                    FileLanguage.JAVA, FileLanguage.KOTLIN -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.secondary
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.fileName,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = file.relativePath,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!isProtected) {
                                IconButton(
                                    onClick = { onDeleteFile(file.relativePath) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Delete ${file.fileName}",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                if (symbols.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Default.AccountTree,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Open a Kotlin, Java, XML, or JSON file to browse its symbols.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(symbols, key = { "${it.line}_${it.name}" }) { sym ->
                            Surface(
                                onClick = { onJumpToSymbolLine(sym.line) },
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Surface(
                                        color = when (sym.kind) {
                                            SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM -> MaterialTheme.colorScheme.primaryContainer
                                            SymbolKind.METHOD -> MaterialTheme.colorScheme.secondaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(
                                            text = sym.kind.badge,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = sym.name,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${sym.detail} • Ln ${sym.line}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

@Composable
private fun NewFileDialog(
    packageName: String,
    projectLanguage: ProjectLanguage,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val pkgPath = packageName.replace('.', '/')
    val defaultExt = if (projectLanguage == ProjectLanguage.KOTLIN) "kt" else "java"
    var pathInput by remember { mutableStateOf("src/$pkgPath/Helper.$defaultExt") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create File") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enter a relative project path (.kt or .java under src/, or .xml under res/values/).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(pathInput) }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ProjectConfigDialog(
    currentLanguage: ProjectLanguage,
    currentJavaVersion: JavaVersionOption,
    currentMinSdk: Int,
    onDismiss: () -> Unit,
    onSave: (ProjectLanguage, JavaVersionOption, Int) -> Unit,
) {
    var selectedLang by remember { mutableStateOf(currentLanguage) }
    var selectedJava by remember { mutableStateOf(currentJavaVersion) }
    var selectedSdk by remember { mutableStateOf(AndroidMinSdk.fromApiLevel(currentMinSdk)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App Language, Java & Min SDK") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Primary Language", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProjectLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = selectedLang == lang,
                            onClick = { selectedLang = lang },
                            label = { Text(lang.badge) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text("Java Version (8 – 25)", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    JavaVersionOption.entries.forEach { jv ->
                        FilterChip(
                            selected = selectedJava == jv,
                            onClick = { selectedJava = jv },
                            label = { Text(jv.badge, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("config_java_${jv.majorVersion}"),
                        )
                    }
                }
                Text(
                    text = selectedJava.highlights,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Minimum Android Version (syncs to AndroidManifest.xml)", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AndroidMinSdk.entries.forEach { sdk ->
                        val selected = sdk == selectedSdk
                        Surface(
                            onClick = { selectedSdk = sdk },
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = sdk.shortLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(selectedLang, selectedJava, selectedSdk.apiLevel) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
