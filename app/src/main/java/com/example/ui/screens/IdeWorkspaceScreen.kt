package com.example.ui.screens

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import com.example.ai.AiModelOption
import com.example.ai.AiProvider
import com.example.ai.AiThinkingMode
import com.example.ai.AiUiState
import com.example.ai.AiUsageSummary
import com.example.ai.ChatMessage
import com.example.ai.ProjectChatThread
import com.example.ai.SuggestedCodeBlock
import com.example.emulator.EmulatorPanel
import com.example.model.BuildDiagnostic
import com.example.model.BuildResult
import com.example.model.BuildVariant
import com.example.model.EditorSettings
import com.example.model.FileLanguage
import com.example.model.ProjectFile
import com.example.model.ProjectMetadata
import com.example.model.ProjectSearchHit
import com.example.model.ReleaseSigningConfig
import com.example.ui.editor.CodeEditor
import com.example.ui.panels.AiUsageDialog
import com.example.ui.panels.BuildPanel
import com.example.ui.panels.GeminiPanel
import com.example.ui.panels.ModelMenuDialog
import com.example.ui.panels.ProjectSearchDialog
import com.example.ui.panels.ProviderKeysDialog
import com.example.ui.panels.ReleaseKeystoreDialog
import com.example.ui.panels.SideChatSplitPane
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.launch
import java.io.File

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
    aiState: AiUiState,
    onSelectFile: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
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
    onAskGemini: (String) -> Unit,
    onRetryGemini: () -> Unit,
    onStopGemini: () -> Unit,
    onApplyGeminiCode: (SuggestedCodeBlock) -> Unit,
    onClearAiChat: () -> Unit,
    onUpdateSettings: ((EditorSettings) -> EditorSettings) -> Unit,
    onBackToProjects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    var showBuildPanel by remember { mutableStateOf(false) }
    var showGeminiPanel by remember { mutableStateOf(false) }
    var showEmulatorPanel by remember { mutableStateOf(false) }
    var showSplitSideChat by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    var showNewFileDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showKeystoreDialog by remember { mutableStateOf(false) }
    var showModelMenuDialog by remember { mutableStateOf(false) }
    var showKeysDialog by remember { mutableStateOf(false) }
    var showUsageDialog by remember { mutableStateOf(false) }

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

    BackHandler {
        when {
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
            val isBundle = artifactPath.endsWith(".aab", ignoreCase = true)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = if (isBundle) "application/octet-stream" else "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(sendIntent, if (isBundle) "Share App Bundle (.aab)" else "Share or Open APK"),
            )
        }
    }

    fun launchExportArtifact() {
        val safeName = project.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val variant = lastBuildResult?.variant ?: selectedBuildVariant
        when (variant) {
            BuildVariant.DEBUG_APK -> exportApkLauncher.launch("$safeName-debug.apk")
            BuildVariant.RELEASE_APK -> exportApkLauncher.launch("$safeName-release.apk")
            BuildVariant.RELEASE_AAB -> exportAabLauncher.launch("$safeName-release.aab")
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
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
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
                            Column {
                                Text(
                                    text = project.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${project.packageName} • ${selectedBuildVariant.shortLabel}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            if (!isExpanded) {
                                IconButton(
                                    onClick = { scope.launch { drawerState.open() } },
                                    modifier = Modifier.testTag("open_files_drawer_button"),
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Project files")
                                }
                            }
                            IconButton(
                                onClick = { showSearchDialog = true },
                                modifier = Modifier.testTag("workspace_search_button"),
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "Search project files")
                            }
                            IconButton(
                                onClick = {
                                    val next = !showEmulatorPanel
                                    showEmulatorPanel = next
                                    if (!isExpanded && next) showGeminiPanel = false
                                },
                                modifier = Modifier.testTag("toggle_emulator_button"),
                            ) {
                                Icon(
                                    Icons.Default.PhoneAndroid,
                                    contentDescription = "Toggle live emulator",
                                    tint = if (showEmulatorPanel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(
                                onClick = { showBuildPanel = !showBuildPanel },
                                modifier = Modifier.testTag("toggle_console_button"),
                            ) {
                                Icon(
                                    Icons.Default.Terminal,
                                    contentDescription = "Toggle build console",
                                    tint = if (showBuildPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(
                                onClick = {
                                    val next = !showGeminiPanel
                                    showGeminiPanel = next
                                    if (!isExpanded && next) showEmulatorPanel = false
                                },
                                modifier = Modifier.testTag("toggle_gemini_button"),
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = "Toggle AI agent",
                                    tint = if (showGeminiPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
                                        text = { Text("Editor Settings") },
                                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            showSettingsDialog = true
                                        },
                                    )
                                }
                            }
                            Button(
                                onClick = { onBuildApk(null) },
                                enabled = !isBuilding,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary,
                                ),
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .testTag("build_apk_button"),
                            ) {
                                if (isBuilding) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSecondary,
                                    )
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    when {
                                        isBuilding -> "Building"
                                        selectedBuildVariant == BuildVariant.RELEASE_AAB -> "Build AAB"
                                        selectedBuildVariant == BuildVariant.RELEASE_APK -> "Release"
                                        else -> "Build APK"
                                    },
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                },
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding(),
                ) {
                    // Persistent Explorer Sidebar on Expanded Screens
                    if (isExpanded) {
                        ProjectExplorerPane(
                            files = files,
                            activeFilePath = activeFilePath,
                            onSelectFile = onSelectFile,
                            onCreateFile = { showNewFileDialog = true },
                            onDeleteFile = onDeleteFile,
                            modifier = Modifier
                                .width(225.dp)
                                .fillMaxHeight(),
                        )
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    // Center Editor + Bottom Console Column
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
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

                        // Code Editor Area
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            if (activeFile != null) {
                                CodeEditor(
                                    filePath = activeFile.relativePath,
                                    content = activeFile.content,
                                    language = activeFile.language,
                                    settings = editorSettings,
                                    diagnostics = lastBuildResult?.diagnostics.orEmpty(),
                                    jumpToLineRequest = jumpToLine,
                                    onJumpHandled = onClearJump,
                                    onContentChange = onUpdateContent,
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
                                        "Open a Java or XML file from the Project Explorer.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // Collapsible Bottom Build Console & Package Inspector
                        AnimatedVisibility(visible = showBuildPanel) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                                        onAskGemini("Inspect the build errors, edit the affected files directly, and re-run build_apk until the build succeeds.")
                                    },
                                    onExportArtifact = { launchExportArtifact() },
                                    onShareArtifact = { path -> shareBuiltArtifact(path) },
                                    onClose = { showBuildPanel = false },
                                    modifier = Modifier.height(280.dp),
                                )
                            }
                        }
                    }

                    // Persistent Live Android Emulator Pane on Expanded Screens
                    if (isExpanded && showEmulatorPanel) {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        EmulatorPanel(
                            appName = project.name,
                            packageName = project.packageName,
                            files = files,
                            builtApkPath = lastBuildResult?.apkPath,
                            onInstallApk = { apkPath -> shareBuiltArtifact(apkPath) },
                            onClose = { showEmulatorPanel = false },
                            modifier = Modifier
                                .width(320.dp)
                                .fillMaxHeight(),
                        )
                    }

                    // Persistent Multi-Provider AI Pane on Expanded Screens
                    if (isExpanded && showGeminiPanel) {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                            aiState = aiState,
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
                            onSendPrompt = onAskGemini,
                            onRetryGemini = onRetryGemini,
                            onStopGemini = onStopGemini,
                            onApplyCode = onApplyGeminiCode,
                            onClearChat = onClearAiChat,
                            onClose = { showGeminiPanel = false },
                            modifier = Modifier
                                .width(350.dp)
                                .fillMaxHeight(),
                        )
                    }

                    // Optional Side-by-Side Split Side Chat on Expanded Screens
                    if (isExpanded && showGeminiPanel && showSplitSideChat) {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                            modifier = Modifier
                                .width(300.dp)
                                .fillMaxHeight(),
                        )
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
                            activeFilePath = activeFilePath,
                            onSelectFile = { path ->
                                onSelectFile(path)
                                scope.launch { drawerState.close() }
                            },
                            onCreateFile = {
                                scope.launch { drawerState.close() }
                                showNewFileDialog = true
                            },
                            onDeleteFile = onDeleteFile,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
                content = mainScaffold,
            )

            if (showEmulatorPanel) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showEmulatorPanel = false },
                    sheetState = sheetState,
                ) {
                    Box(modifier = Modifier.height(600.dp)) {
                        EmulatorPanel(
                            appName = project.name,
                            packageName = project.packageName,
                            files = files,
                            builtApkPath = lastBuildResult?.apkPath,
                            onInstallApk = { apkPath -> shareBuiltArtifact(apkPath) },
                            onClose = { showEmulatorPanel = false },
                        )
                    }
                }
            } else if (showGeminiPanel) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showGeminiPanel = false },
                    sheetState = sheetState,
                ) {
                    Box(modifier = Modifier.height(580.dp)) {
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
                            aiState = aiState,
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
                            onSendPrompt = onAskGemini,
                            onRetryGemini = onRetryGemini,
                            onStopGemini = onStopGemini,
                            onApplyCode = onApplyGeminiCode,
                            onClearChat = onClearAiChat,
                            onClose = { showGeminiPanel = false },
                        )
                    }
                }
            }
        }
    }

    if (showNewFileDialog) {
        NewFileDialog(
            packageName = project.packageName,
            onDismiss = { showNewFileDialog = false },
            onCreate = { path ->
                showNewFileDialog = false
                onCreateFile(path)
            },
        )
    }

    if (showSettingsDialog) {
        EditorSettingsDialog(
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
}

@Composable
private fun ProjectExplorerPane(
    files: List<ProjectFile>,
    activeFilePath: String?,
    onSelectFile: (String) -> Unit,
    onCreateFile: () -> Unit,
    onDeleteFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Project Files", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onCreateFile) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New file")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(files, key = { it.relativePath }) { file ->
                    val isSelected = file.relativePath == activeFilePath
                    val isProtected = file.relativePath == "AndroidManifest.xml"
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
                                FileLanguage.JAVA -> Icons.Default.Code
                                else -> Icons.Default.Description
                            },
                            contentDescription = null,
                            tint = if (file.language == FileLanguage.JAVA) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
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
        }
    }
}

@Composable
private fun NewFileDialog(
    packageName: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val pkgPath = packageName.replace('.', '/')
    var pathInput by remember { mutableStateOf("src/$pkgPath/Helper.java") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create File") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enter a relative project path (Java under src/ or XML under res/values/).",
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
