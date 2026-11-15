package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
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
            val providerApiKeys by viewModel.providerApiKeys.collectAsStateWithLifecycle()
            val providerModels by viewModel.providerModels.collectAsStateWithLifecycle()
            val liveModels by viewModel.liveModels.collectAsStateWithLifecycle()
            val isFetchingModels by viewModel.isFetchingModels.collectAsStateWithLifecycle()
            val aiUsageSummary by viewModel.aiUsageSummary.collectAsStateWithLifecycle()
            val chatThreads by viewModel.chatThreads.collectAsStateWithLifecycle()
            val activeThreadId by viewModel.activeThreadId.collectAsStateWithLifecycle()
            val splitSideThreadId by viewModel.splitSideThreadId.collectAsStateWithLifecycle()
            val aiMessages by viewModel.aiMessages.collectAsStateWithLifecycle()
            val aiState by viewModel.aiState.collectAsStateWithLifecycle()

            DroidForgeTheme(darkTheme = editorSettings.darkTheme) {
                val currentProject = activeProject
                if (currentProject == null) {
                    ProjectsScreen(
                        projects = projects,
                        editorSettings = editorSettings,
                        activeProvider = activeProvider,
                        providerApiKeys = providerApiKeys,
                        providerModels = providerModels,
                        liveModels = liveModels,
                        isFetchingModels = isFetchingModels,
                        aiUsageSummary = aiUsageSummary,
                        releaseSigningConfig = releaseSigningConfig,
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
                        providerApiKeys = providerApiKeys,
                        providerModels = providerModels,
                        liveModels = liveModels,
                        isFetchingModels = isFetchingModels,
                        aiUsageSummary = aiUsageSummary,
                        chatThreads = chatThreads,
                        activeThreadId = activeThreadId,
                        splitSideThreadId = splitSideThreadId,
                        aiMessages = aiMessages,
                        aiState = aiState,
                        onSelectFile = viewModel::openFile,
                        onCloseTab = viewModel::closeTab,
                        onCreateFile = viewModel::createNewFile,
                        onDeleteFile = viewModel::deleteFile,
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
                        onSelectModel = viewModel::selectModel,
                        onSaveProviderConfig = viewModel::saveProviderConfig,
                        onRefreshLiveModels = viewModel::refreshLiveModels,
                        onResetAiUsage = viewModel::resetAiUsage,
                        onSelectChatThread = viewModel::selectChatThread,
                        onCreateSideChat = viewModel::createSideChat,
                        onRenameChatThread = viewModel::renameChatThread,
                        onDeleteChatThread = viewModel::deleteChatThread,
                        onSetSplitSideThread = viewModel::setSplitSideThread,
                        onAskGemini = viewModel::askGemini,
                        onRetryGemini = viewModel::retryLastGeminiPrompt,
                        onStopGemini = viewModel::cancelGemini,
                        onApplyGeminiCode = viewModel::applySuggestedCode,
                        onClearAiChat = viewModel::clearAiChat,
                        onUpdateSettings = viewModel::updateEditorSettings,
                        onBackToProjects = viewModel::closeProject,
                    )
                }
            }
        }
    }
}
