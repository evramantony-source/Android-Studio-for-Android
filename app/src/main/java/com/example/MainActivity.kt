package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.IdeViewModel
import com.example.ui.screens.IdeWorkspaceScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.theme.DroidForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            val viewModel: IdeViewModel = viewModel()
            val editorSettings by viewModel.editorSettings.collectAsStateWithLifecycle()
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

            val githubToken by viewModel.githubToken.collectAsStateWithLifecycle()
            val githubUser by viewModel.githubUser.collectAsStateWithLifecycle()
            val githubRepos by viewModel.githubRepos.collectAsStateWithLifecycle()
            val githubPendingChanges by viewModel.githubPendingChanges.collectAsStateWithLifecycle()
            val githubWorkflowRuns by viewModel.githubWorkflowRuns.collectAsStateWithLifecycle()
            val isGitHubBusy by viewModel.isGitHubBusy.collectAsStateWithLifecycle()

            DroidForgeTheme(darkTheme = editorSettings.darkTheme) {
                val currentProject = activeProject
                val loadingName = openingProjectName
                Box(modifier = Modifier.fillMaxSize()) {
                    if (currentProject == null) {
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
                            onRefreshLiveModels = viewModel::refreshLiveModels,
                            onResetAiUsage = viewModel::resetAiUsage,
                            onGenerateKeystore = viewModel::generateReleaseKeystore,
                            onImportKeystore = viewModel::importKeystoreFromUri,
                            onClearKeystore = viewModel::clearReleaseKeystore,
                            onConnectGitHub = viewModel::connectGitHubToken,
                            onDisconnectGitHub = viewModel::disconnectGitHub,
                            onRefreshGitHubRepos = viewModel::refreshGitHubRepos,
                        )
                    } else {
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
                            onRefreshLiveModels = viewModel::refreshLiveModels,
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
                            onPushGitHub = viewModel::pushProjectToGitHub,
                            onPullGitHub = viewModel::pullProjectFromGitHub,
                            onScaffoldGradleCi = viewModel::scaffoldGradleCi,
                            onTriggerCloudBuild = viewModel::triggerGitHubCloudBuild,
                            onRefreshWorkflowRuns = viewModel::refreshGitHubWorkflowRuns,
                            onUpdateSettings = viewModel::updateEditorSettings,
                            onBackToProjects = viewModel::closeProject,
                        )
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
