package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.IdeViewModel
import com.example.ui.screens.IdeWorkspaceScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.theme.AndroidStudioTheme
import com.example.ui.theme.JetBrainsMonoFontFamily

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            val viewModel: IdeViewModel = viewModel()
            val editorSettings by viewModel.editorSettings.collectAsStateWithLifecycle()
            val isBootLoading by viewModel.isBootLoading.collectAsStateWithLifecycle()
            val projects by viewModel.projects.collectAsStateWithLifecycle()
            val isLoadingProjects by viewModel.isLoadingProjects.collectAsStateWithLifecycle()
            val openingProjectName by viewModel.openingProjectName.collectAsStateWithLifecycle()
            val activeProject by viewModel.activeProject.collectAsStateWithLifecycle()
            val projectFiles by viewModel.projectFiles.collectAsStateWithLifecycle()
            val openTabs by viewModel.openTabs.collectAsStateWithLifecycle()
            val activeFilePath by viewModel.activeFilePath.collectAsStateWithLifecycle()
            val isBuilding by viewModel.isBuilding.collectAsStateWithLifecycle()
            val selectedBuildVariant by viewModel.selectedBuildVariant.collectAsStateWithLifecycle()
            val releaseSigningConfig by viewModel.releaseSigningConfig.collectAsStateWithLifecycle()
            val buildLogs by viewModel.liveLogs.collectAsStateWithLifecycle()
            val lastBuildResult by viewModel.lastBuildResult.collectAsStateWithLifecycle()
            val jumpToLine by viewModel.jumpToLine.collectAsStateWithLifecycle()
            val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

            val activeProvider by viewModel.activeProvider.collectAsStateWithLifecycle()
            val aiThinkingMode by viewModel.aiThinkingMode.collectAsStateWithLifecycle()
            val providerApiKeys by viewModel.providerApiKeys.collectAsStateWithLifecycle()
            val providerModels by viewModel.providerModels.collectAsStateWithLifecycle()
            val liveModels by viewModel.liveModels.collectAsStateWithLifecycle()
            val isFetchingModels by viewModel.isFetchingModels.collectAsStateWithLifecycle()
            val aiUsageSummary by viewModel.aiUsageSummary.collectAsStateWithLifecycle()
            val chatThreads by viewModel.chatThreads.collectAsStateWithLifecycle()
            val activeThreadId by viewModel.activeThreadId.collectAsStateWithLifecycle()
            val splitSideThreadId by viewModel.splitSideThreadId.collectAsStateWithLifecycle()
            val aiMessages by viewModel.aiMessages.collectAsStateWithLifecycle()
            val pendingAiAttachments by viewModel.pendingAiAttachments.collectAsStateWithLifecycle()
            val aiState by viewModel.aiState.collectAsStateWithLifecycle()
            val threadMessagesMap by viewModel.threadMessagesMap.collectAsStateWithLifecycle()
            val threadUiStates by viewModel.threadUiStates.collectAsStateWithLifecycle()
            val runningThreadIds by viewModel.runningThreadIds.collectAsStateWithLifecycle()
            val terminalLines by viewModel.terminalLines.collectAsStateWithLifecycle()

            val githubToken by viewModel.githubToken.collectAsStateWithLifecycle()
            val githubUser by viewModel.githubUser.collectAsStateWithLifecycle()
            val githubRepos by viewModel.githubRepos.collectAsStateWithLifecycle()
            val githubPendingChanges by viewModel.githubPendingChanges.collectAsStateWithLifecycle()
            val githubWorkflowRuns by viewModel.githubWorkflowRuns.collectAsStateWithLifecycle()
            val githubBranches by viewModel.githubBranches.collectAsStateWithLifecycle()
            val githubRemoteFiles by viewModel.githubRemoteFiles.collectAsStateWithLifecycle()
            val githubInspectedRunId by viewModel.githubInspectedRunId.collectAsStateWithLifecycle()
            val githubWorkflowJobs by viewModel.githubWorkflowJobs.collectAsStateWithLifecycle()
            val githubSelectedRunLogs by viewModel.githubSelectedRunLogs.collectAsStateWithLifecycle()
            val githubWorkflowArtifacts by viewModel.githubWorkflowArtifacts.collectAsStateWithLifecycle()
            val openAiPanelRequest by viewModel.openAiPanelRequest.collectAsStateWithLifecycle()
            val isGitHubBusy by viewModel.isGitHubBusy.collectAsStateWithLifecycle()

            AndroidStudioTheme(
                themePreset = editorSettings.themePreset,
                accentPreset = editorSettings.accentPreset,
                uiDensity = editorSettings.uiDensity,
            ) {
                val currentProject = activeProject
                val loadingName = openingProjectName
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        isBootLoading -> {
                            StudioLaunchLoadingScreen()
                        }
                        currentProject == null -> {
                            ProjectsScreen(
                                projects = projects,
                                isLoadingProjects = isLoadingProjects,
                                editorSettings = editorSettings,
                                activeProvider = activeProvider,
                                providerApiKeys = providerApiKeys,
                                providerModels = providerModels,
                                liveModels = liveModels,
                                isFetchingModels = isFetchingModels,
                                aiUsageSummary = aiUsageSummary,
                                releaseSigningConfig = releaseSigningConfig,
                                githubToken = githubToken,
                                githubUser = githubUser,
                                githubRepos = githubRepos,
                                isGitHubBusy = isGitHubBusy,
                                statusBanner = statusBanner,
                                onClearStatusBanner = viewModel::clearStatusBanner,
                                onOpenProject = viewModel::openProject,
                                onCreateProject = viewModel::createProject,
                                onDeleteProject = viewModel::deleteProject,
                                onUpdateSettings = viewModel::updateEditorSettings,
                                onSelectModel = viewModel::selectModel,
                                onSaveProviderConfig = viewModel::saveProviderConfig,
                                onRefreshLiveModels = { viewModel.refreshLiveModels(it) },
                                onResetAiUsage = viewModel::resetAiUsage,
                                onGenerateKeystore = viewModel::generateReleaseKeystore,
                                onImportKeystore = viewModel::importKeystoreFromUri,
                                onClearKeystore = viewModel::clearReleaseKeystore,
                                onConnectGitHub = viewModel::connectGitHubToken,
                                onDisconnectGitHub = viewModel::disconnectGitHub,
                                onRefreshGitHubRepos = viewModel::refreshGitHubRepos,
                                onCloneGitHubRepo = viewModel::cloneGitHubRepoAsProject,
                            )
                        }
                        else -> {
                            IdeWorkspaceScreen(
                                project = currentProject,
                                files = projectFiles,
                                openTabs = openTabs,
                                activeFilePath = activeFilePath,
                                editorSettings = editorSettings,
                                isBuilding = isBuilding,
                                selectedBuildVariant = selectedBuildVariant,
                                releaseSigningConfig = releaseSigningConfig,
                                buildLogs = buildLogs,
                                lastBuildResult = lastBuildResult,
                                jumpToLine = jumpToLine,
                                statusBanner = statusBanner,
                                activeProvider = activeProvider,
                                aiThinkingMode = aiThinkingMode,
                                providerApiKeys = providerApiKeys,
                                providerModels = providerModels,
                                liveModels = liveModels,
                                isFetchingModels = isFetchingModels,
                                aiUsageSummary = aiUsageSummary,
                                chatThreads = chatThreads,
                                activeThreadId = activeThreadId,
                                splitSideThreadId = splitSideThreadId,
                                aiMessages = aiMessages,
                                pendingAiAttachments = pendingAiAttachments,
                                aiState = aiState,
                                githubToken = githubToken,
                                githubUser = githubUser,
                                githubRepos = githubRepos,
                                githubPendingChanges = githubPendingChanges,
                                githubWorkflowRuns = githubWorkflowRuns,
                                githubBranches = githubBranches,
                                githubRemoteFiles = githubRemoteFiles,
                                githubInspectedRunId = githubInspectedRunId,
                                githubWorkflowJobs = githubWorkflowJobs,
                                githubSelectedRunLogs = githubSelectedRunLogs,
                                githubWorkflowArtifacts = githubWorkflowArtifacts,
                                openAiPanelRequest = openAiPanelRequest,
                                isGitHubBusy = isGitHubBusy,
                                onSelectFile = viewModel::openFile,
                                onCloseTab = viewModel::closeTab,
                                onCreateFile = viewModel::createNewFile,
                                onDeleteFile = viewModel::deleteFile,
                                onImportProjectFileUri = viewModel::importProjectFileFromUri,
                                onUpdateProjectConfig = viewModel::updateProjectConfig,
                                onUpdateContent = viewModel::updateActiveFileContent,
                                onSelectBuildVariant = viewModel::selectBuildVariant,
                                onBuildApk = viewModel::buildApk,
                                onGenerateKeystore = viewModel::generateReleaseKeystore,
                                onImportKeystore = viewModel::importKeystoreFromUri,
                                onClearKeystore = viewModel::clearReleaseKeystore,
                                onSearchWorkspace = viewModel::searchWorkspace,
                                onJumpToFileAndLine = viewModel::jumpToFileAndLine,
                                onJumpToDiagnostic = viewModel::jumpToDiagnostic,
                                onClearJump = viewModel::clearJumpRequest,
                                onExportApkUri = viewModel::exportApkToUri,
                                onExportProjectZipUri = viewModel::exportProjectZipToUri,
                                onClearStatusBanner = viewModel::clearStatusBanner,
                                onSelectProvider = viewModel::selectProvider,
                                onSelectThinkingMode = viewModel::selectThinkingMode,
                                onSelectModel = viewModel::selectModel,
                                onSaveProviderConfig = viewModel::saveProviderConfig,
                                onRefreshLiveModels = { viewModel.refreshLiveModels(it) },
                                onResetAiUsage = viewModel::resetAiUsage,
                                onSelectChatThread = viewModel::selectChatThread,
                                onCreateSideChat = viewModel::createSideChat,
                                onRenameChatThread = viewModel::renameChatThread,
                                onDeleteChatThread = viewModel::deleteChatThread,
                                onSetSplitSideThread = viewModel::setSplitSideThread,
                                onAddAiAttachmentUri = viewModel::addAiAttachmentFromUri,
                                onRemoveAiAttachment = viewModel::removePendingAttachment,
                                onAskGemini = viewModel::askGemini,
                                onRetryGemini = viewModel::retryLastGeminiPrompt,
                                onStopGemini = viewModel::cancelGemini,
                                onApplyGeminiCode = viewModel::applySuggestedCode,
                                onClearAiChat = viewModel::clearAiChat,
                                onConnectGitHub = viewModel::connectGitHubToken,
                                onDisconnectGitHub = viewModel::disconnectGitHub,
                                onRefreshGitHubRepos = viewModel::refreshGitHubRepos,
                                onCreateAndLinkGitHubRepo = viewModel::createAndLinkGitHubRepo,
                                onLinkExistingGitHubRepo = viewModel::linkExistingGitHubRepo,
                                onCloneGitHubRepo = viewModel::cloneGitHubRepoAsProject,
                                onRefreshGitHubBranches = { viewModel.refreshGitHubBranches() },
                                onSwitchGitHubBranch = viewModel::switchGitHubBranch,
                                onCreateGitHubBranch = viewModel::createGitHubBranch,
                                onRefreshGitHubRemoteFiles = viewModel::refreshGitHubRemoteFiles,
                                onDownloadGitHubFile = { viewModel.downloadRemoteFileToProject(it) },
                                onUploadActiveFileToGitHub = { path, msg -> viewModel.uploadProjectFileToGitHub(path, path, msg) },
                                onUploadDeviceFileUriToGitHub = { viewModel.uploadDeviceFileUriToGitHub(it) },
                                onPushGitHub = viewModel::pushProjectToGitHub,
                                onPullGitHub = viewModel::pullProjectFromGitHub,
                                onScaffoldGradleCi = viewModel::scaffoldGradleCi,
                                onTriggerCloudBuild = viewModel::triggerGitHubCloudBuild,
                                onRefreshWorkflowRuns = viewModel::refreshGitHubWorkflowRuns,
                                onInspectWorkflowRun = viewModel::inspectWorkflowRun,
                                onRerunWorkflowRun = viewModel::rerunWorkflowRun,
                                onDownloadWorkflowArtifact = viewModel::downloadWorkflowArtifact,
                                onAskAiToFixWorkflowRun = viewModel::askAiToFixWorkflowRun,
                                onUpdateSettings = viewModel::updateEditorSettings,
                                onBackToProjects = viewModel::closeProject,
                                threadMessagesMap = threadMessagesMap,
                                threadUiStates = threadUiStates,
                                runningThreadIds = runningThreadIds,
                                terminalLines = terminalLines,
                                onLaunchRoleAgent = { role, prompt ->
                                    viewModel.launchRoleAgent(role, prompt, selectAsActive = true)
                                },
                                onAskGeminiInThread = { threadId, prompt ->
                                    viewModel.askGeminiInThread(threadId, prompt)
                                },
                                onUpdateThreadProviderModel = viewModel::updateThreadProviderAndModel,
                                onUpdateThreadSystemAndTemp = viewModel::updateThreadSystemAndTemperature,
                                onStopThread = viewModel::cancelThread,
                                onRetryThread = viewModel::retryThreadPrompt,
                                onClearThreadChat = viewModel::clearThreadChat,
                                onRunTerminalCommand = viewModel::runTerminalCommand,
                            )
                        }
                    }

                    if (loadingName != null) {
                        WorkspaceLoadingOverlay(projectName = loadingName)
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioLaunchLoadingScreen() {
    val bgBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
            MaterialTheme.colorScheme.background,
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .testTag("studio_launch_loading_screen"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
                modifier = Modifier.size(82.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = "Android Studio icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            Text(
                text = "Android Studio",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Offline Kotlin & Java 8–25 APK/AAB IDE",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .height(6.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            Text(
                text = "Initializing Multi-DEX toolchain, GitHub Hub & AI agents...",
                fontFamily = JetBrainsMonoFontFamily,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WorkspaceLoadingOverlay(projectName: String) {
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
        modifier = Modifier
            .fillMaxSize()
            .testTag("workspace_loading_overlay"),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                modifier = Modifier.padding(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(38.dp),
                        strokeWidth = 3.5.dp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Loading $projectName...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Preparing editor tabs, offline compiler & AI threads",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
