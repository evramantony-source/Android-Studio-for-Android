package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import com.example.ai.AiModelOption
import com.example.ai.AiProvider
import com.example.ai.AiUsageSummary
import com.example.github.GitHubRepoSummary
import com.example.model.AndroidMinSdk
import com.example.model.EditorSettings
import com.example.model.JavaVersionOption
import com.example.model.ProjectLanguage
import com.example.model.ProjectMetadata
import com.example.model.ProjectTemplate
import com.example.model.ReleaseSigningConfig
import com.example.ui.panels.AiUsageDialog
import com.example.ui.panels.GitHubSheetDialog
import com.example.ui.panels.ModelMenuDialog
import com.example.ui.panels.ProviderKeysDialog
import com.example.ui.panels.ReleaseKeystoreDialog
import com.example.ui.panels.formatBytes
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    projects: List<ProjectMetadata>,
    isLoadingProjects: Boolean = false,
    editorSettings: EditorSettings,
    activeProvider: AiProvider,
    providerApiKeys: Map<AiProvider, String>,
    providerModels: Map<AiProvider, String>,
    liveModels: List<AiModelOption>,
    isFetchingModels: Boolean,
    aiUsageSummary: AiUsageSummary,
    releaseSigningConfig: ReleaseSigningConfig,
    githubToken: String = "",
    githubUser: String = "",
    githubRepos: List<GitHubRepoSummary> = emptyList(),
    isGitHubBusy: Boolean = false,
    statusBanner: String?,
    onClearStatusBanner: () -> Unit,
    onOpenProject: (ProjectMetadata) -> Unit,
    onCreateProject: (String, String, ProjectTemplate, ProjectLanguage, JavaVersionOption, Int) -> Unit,
    onDeleteProject: (String) -> Unit,
    onUpdateSettings: ((EditorSettings) -> EditorSettings) -> Unit,
    onSelectModel: (AiProvider, String) -> Unit,
    onSaveProviderConfig: (AiProvider, String, String) -> Unit,
    onRefreshLiveModels: (AiProvider) -> Unit,
    onResetAiUsage: () -> Unit,
    onGenerateKeystore: (String, String, String, String) -> Unit,
    onImportKeystore: (Uri, String, String) -> Unit,
    onClearKeystore: () -> Unit,
    onConnectGitHub: (String) -> Unit = {},
    onDisconnectGitHub: () -> Unit = {},
    onRefreshGitHubRepos: () -> Unit = {},
    onCloneGitHubRepo: (GitHubRepoSummary) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var createDialogTemplate by remember { mutableStateOf<ProjectTemplate?>(null) }
    var deleteCandidate by remember { mutableStateOf<ProjectMetadata?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showUsageDialog by remember { mutableStateOf(false) }
    var showModelMenuDialog by remember { mutableStateOf(false) }
    var showKeysDialog by remember { mutableStateOf(false) }
    var showKeystoreDialog by remember { mutableStateOf(false) }
    var showGitHubDialog by remember { mutableStateOf(false) }
    var showHomeMenu by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val nf = remember { NumberFormat.getIntegerInstance() }

    LaunchedEffect(statusBanner) {
        if (!statusBanner.isNullOrBlank()) {
            snackbarHostState.showSnackbar(statusBanner)
            onClearStatusBanner()
        }
    }

    val activeModelId = com.example.ai.AiModelsCatalog.normalizeModelId(activeProvider, providerModels[activeProvider])
    val builtProjectsCount = remember(projects) { projects.count { it.lastBuildSuccess == true } }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(
                                Icons.Default.Android,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(6.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = "Android Studio: For Android",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Offline APK & AAB IDE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showGitHubDialog = true },
                        modifier = Modifier.testTag("home_github_hub_button"),
                    ) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = "Open GitHub Hub",
                            tint = if (githubUser.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(
                        onClick = { showKeysDialog = true },
                        modifier = Modifier.testTag("home_ai_keys_button"),
                    ) {
                        Icon(Icons.Default.Key, contentDescription = "AI provider keys")
                    }
                    Box {
                        IconButton(
                            onClick = { showHomeMenu = true },
                            modifier = Modifier.testTag("home_more_menu_button"),
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More IDE options")
                        }
                        DropdownMenu(
                            expanded = showHomeMenu,
                            onDismissRequest = { showHomeMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("AI Usage & Tokens") },
                                leadingIcon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                                onClick = {
                                    showHomeMenu = false
                                    showUsageDialog = true
                                },
                                modifier = Modifier.testTag("home_ai_usage_button"),
                            )
                            DropdownMenuItem(
                                text = { Text("Choose AI Model") },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    showHomeMenu = false
                                    showModelMenuDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Release Keystore Manager") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        tint = if (releaseSigningConfig.isConfigured) {
                                            MaterialTheme.colorScheme.secondary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                onClick = {
                                    showHomeMenu = false
                                    showKeystoreDialog = true
                                },
                                modifier = Modifier.testTag("home_keystore_button"),
                            )
                            DropdownMenuItem(
                                text = { Text("GitHub Account") },
                                leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                                onClick = {
                                    showHomeMenu = false
                                    showGitHubDialog = true
                                },
                                modifier = Modifier.testTag("home_github_account_button"),
                            )
                            DropdownMenuItem(
                                text = { Text("IDE Settings") },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    showHomeMenu = false
                                    showSettingsDialog = true
                                },
                                modifier = Modifier.testTag("open_settings_button"),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { createDialogTemplate = ProjectTemplate.COUNTER_APP },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("new_project_fab"),
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Android project")
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 920.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Upgraded Hero Banner + Telemetry Strip
                item {
                    val heroBrush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f),
                        ),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(heroBrush)
                            .padding(20.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item {
                                    HeroBadgeChip(
                                        icon = Icons.Default.OfflineBolt,
                                        label = "OFFLINE APK & AAB",
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                        contentColor = MaterialTheme.colorScheme.onSecondary,
                                    )
                                }
                                item {
                                    HeroBadgeChip(
                                        icon = Icons.Default.PhoneAndroid,
                                        label = "LIVE EMULATOR",
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                                item {
                                    HeroBadgeChip(
                                        icon = Icons.Default.AutoAwesome,
                                        label = "GEMINI • CHATGPT • CLAUDE",
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                            Text(
                                text = "Build, emulate, and sign release Android APKs & AABs on-device.",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Autonomous multi-model coding agents with persistent per-project chats, side threads, interactive device preview, and 2048-bit release keystore signing.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            // Live Studio Telemetry Strip
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StudioMetricCard(
                                    label = "Projects",
                                    value = "${projects.size} ($builtProjectsCount built)",
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                )
                                StudioMetricCard(
                                    label = "${activeProvider.shortName} Model",
                                    value = activeModelId,
                                    onClick = { showModelMenuDialog = true },
                                    modifier = Modifier.weight(1.3f),
                                )
                                StudioMetricCard(
                                    label = "AI Usage",
                                    value = "${nf.format(aiUsageSummary.totalTokens)} tok • ${com.example.ui.panels.formatUsd(aiUsageSummary.totalEstimatedCostUsd)}",
                                    onClick = { showUsageDialog = true },
                                    modifier = Modifier.weight(1.1f),
                                )
                            }
                        }
                    }
                }

                // Starter Templates
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Start From Template (${ProjectTemplate.entries.size})",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(ProjectTemplate.entries) { template ->
                                Card(
                                    onClick = { createDialogTemplate = template },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    ),
                                    modifier = Modifier
                                        .width(220.dp)
                                        .testTag("template_card_${template.name.lowercase()}"),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.size(36.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = when (template) {
                                                        ProjectTemplate.ARCADE_SHOOTER -> Icons.Default.SportsEsports
                                                        ProjectTemplate.VOXEL_LAUNCHER_STUDIO -> Icons.Default.ViewInAr
                                                        ProjectTemplate.CANVAS_DRAW -> Icons.Default.Palette
                                                        ProjectTemplate.CALCULATOR -> Icons.Default.Build
                                                        ProjectTemplate.NOTES_APP -> Icons.Default.EditNote
                                                        ProjectTemplate.STOPWATCH_APP -> Icons.Default.Timer
                                                        else -> Icons.Default.Code
                                                    },
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            text = template.displayName,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = template.subtitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Recent Projects
                item {
                    Text(
                        text = if (isLoadingProjects && projects.isEmpty()) "Your Projects" else "Your Projects (${projects.size})",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                if (isLoadingProjects && projects.isEmpty()) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("projects_loading_card"),
                        ) {
                            Row(
                                modifier = Modifier.padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(26.dp),
                                    strokeWidth = 3.dp,
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "Loading projects & templates...",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = "Preparing Android Studio: For Android workspace",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                items(projects, key = { it.id }) { project ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenProject(project) }
                            .testTag("project_card_${project.id}"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(46.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        when (project.template) {
                                            ProjectTemplate.ARCADE_SHOOTER -> Icons.Default.SportsEsports
                                            ProjectTemplate.VOXEL_LAUNCHER_STUDIO -> Icons.Default.ViewInAr
                                            ProjectTemplate.CANVAS_DRAW -> Icons.Default.Palette
                                            ProjectTemplate.CALCULATOR -> Icons.Default.Build
                                            ProjectTemplate.NOTES_APP -> Icons.Default.EditNote
                                            ProjectTemplate.STOPWATCH_APP -> Icons.Default.Timer
                                            else -> Icons.Default.Code
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
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
                                val ghSuffix = project.githubLink?.fullName?.let { " • $it" }.orEmpty()
                                Text(
                                    text = "${project.packageName} • $langLabel • API ${project.minSdkVersion}$ghSuffix",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (project.lastBuildSuccess == true && project.lastApkSizeBytes != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        val variantLabel = project.lastBuildVariant?.shortLabel ?: "APK"
                                        Text(
                                            text = "$variantLabel ready (${formatBytes(project.lastApkSizeBytes)})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = { deleteCandidate = project },
                                modifier = Modifier.testTag("delete_project_${project.id}"),
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete ${project.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }

    createDialogTemplate?.let { template ->
        CreateProjectDialog(
            initialTemplate = template,
            onDismiss = { createDialogTemplate = null },
            onConfirm = { name, pkg, selectedTemplate, language, javaVersion, minSdk ->
                createDialogTemplate = null
                onCreateProject(name, pkg, selectedTemplate, language, javaVersion, minSdk)
            },
        )
    }

    deleteCandidate?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete Project?") },
            text = { Text("Delete \"${target.name}\" and all its source files, persistent AI chats, and compiled APKs/AABs?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        deleteCandidate = null
                        onDeleteProject(id)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel")
                }
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

    if (showUsageDialog) {
        AiUsageDialog(
            usageSummary = aiUsageSummary,
            onResetUsage = onResetAiUsage,
            onDismiss = { showUsageDialog = false },
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

    if (showKeystoreDialog) {
        ReleaseKeystoreDialog(
            signingConfig = releaseSigningConfig,
            onGenerateKeystore = onGenerateKeystore,
            onImportKeystore = onImportKeystore,
            onClearKeystore = onClearKeystore,
            onDismiss = { showKeystoreDialog = false },
        )
    }

    if (showGitHubDialog) {
        GitHubSheetDialog(
            project = null,
            githubToken = githubToken,
            githubUser = githubUser,
            githubRepos = githubRepos,
            pendingChanges = emptyList(),
            workflowRuns = emptyList(),
            isGitHubBusy = isGitHubBusy,
            onConnectToken = onConnectGitHub,
            onDisconnectGitHub = onDisconnectGitHub,
            onRefreshRepos = onRefreshGitHubRepos,
            onCreateAndLinkRepo = { _, _, _ -> },
            onLinkExistingRepo = { _, _, _ -> },
            onCloneRepoAsProject = onCloneGitHubRepo,
            onPushProject = {},
            onPullProject = {},
            onScaffoldGradleCi = {},
            onTriggerCloudBuild = {},
            onRefreshWorkflowRuns = {},
            onDismiss = { showGitHubDialog = false },
        )
    }
}

@Composable
private fun HeroBadgeChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun StudioMetricCard(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                fontFamily = JetBrainsMonoFontFamily,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CreateProjectDialog(
    initialTemplate: ProjectTemplate,
    onDismiss: () -> Unit,
    onConfirm: (String, String, ProjectTemplate, ProjectLanguage, JavaVersionOption, Int) -> Unit,
) {
    var selectedTemplate by remember { mutableStateOf(initialTemplate) }
    var selectedLanguage by remember { mutableStateOf(ProjectLanguage.KOTLIN) }
    var selectedJavaVersion by remember { mutableStateOf(JavaVersionOption.JAVA_25) }
    var selectedMinSdk by remember { mutableStateOf(AndroidMinSdk.API_24) }
    var appName by remember { mutableStateOf(initialTemplate.defaultAppName) }
    var packageName by remember { mutableStateOf(initialTemplate.defaultPackage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Android Project") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text("App Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_project_name_input"),
                )
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Package Name") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_project_pkg_input"),
                )

                Text("Language", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProjectLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = selectedLanguage == lang,
                            onClick = { selectedLanguage = lang },
                            label = { Text(lang.badge) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("create_lang_${lang.name.lowercase()}"),
                        )
                    }
                }

                Text("Java Toolchain Version (8 – 25)", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    JavaVersionOption.entries.forEach { jv ->
                        FilterChip(
                            selected = selectedJavaVersion == jv,
                            onClick = { selectedJavaVersion = jv },
                            label = { Text(jv.badge, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("create_java_${jv.majorVersion}"),
                        )
                    }
                }
                Text(
                    text = selectedJavaVersion.highlights,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Minimum Android Version", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AndroidMinSdk.entries.forEach { sdk ->
                        FilterChip(
                            selected = selectedMinSdk == sdk,
                            onClick = { selectedMinSdk = sdk },
                            label = { Text(sdk.shortLabel, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("create_minsdk_${sdk.apiLevel}"),
                        )
                    }
                }

                Text("Template", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProjectTemplate.entries.forEach { t ->
                        val selected = t == selectedTemplate
                        Surface(
                            onClick = {
                                selectedTemplate = t
                                appName = t.defaultAppName
                                packageName = t.defaultPackage
                            },
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = t.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        appName,
                        packageName,
                        selectedTemplate,
                        selectedLanguage,
                        selectedJavaVersion,
                        selectedMinSdk.apiLevel,
                    )
                },
                modifier = Modifier.testTag("confirm_create_project_button"),
            ) {
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
fun EditorSettingsDialog(
    settings: EditorSettings,
    onUpdateSettings: ((EditorSettings) -> EditorSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("IDE & Editor Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text(
                        text = "Editor Font Size: ${settings.fontSizeSp} sp",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = settings.fontSizeSp.toFloat(),
                        onValueChange = { v ->
                            onUpdateSettings { it.copy(fontSizeSp = v.toInt()) }
                        },
                        valueRange = 10f..20f,
                        steps = 9,
                    )
                }
                SettingsToggleRow(
                    label = "Dark IDE Theme",
                    checked = settings.darkTheme,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(darkTheme = checked) }
                    },
                )
                SettingsToggleRow(
                    label = "Word Wrap",
                    checked = settings.wordWrap,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(wordWrap = checked) }
                    },
                )
                SettingsToggleRow(
                    label = "Auto-Indent on Newline",
                    checked = settings.autoIndent,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(autoIndent = checked) }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
