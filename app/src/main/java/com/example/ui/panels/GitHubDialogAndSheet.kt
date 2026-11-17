package com.example.ui.panels

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.GitHubPendingChange
import com.example.github.GitHubBranch
import com.example.github.GitHubRepoSummary
import com.example.github.GitHubTreeEntry
import com.example.github.GitHubWorkflowArtifact
import com.example.github.GitHubWorkflowJob
import com.example.github.GitHubWorkflowRun
import com.example.model.ProjectMetadata
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubSheetDialog(
    project: ProjectMetadata?,
    activeFilePath: String? = null,
    githubToken: String,
    githubUser: String,
    githubRepos: List<GitHubRepoSummary>,
    pendingChanges: List<GitHubPendingChange>,
    workflowRuns: List<GitHubWorkflowRun>,
    branches: List<GitHubBranch> = emptyList(),
    remoteFiles: List<GitHubTreeEntry> = emptyList(),
    inspectedRunId: Long? = null,
    workflowJobs: List<GitHubWorkflowJob> = emptyList(),
    selectedRunLogs: String = "",
    workflowArtifacts: List<GitHubWorkflowArtifact> = emptyList(),
    isGitHubBusy: Boolean,
    onConnectToken: (String) -> Unit,
    onDisconnectGitHub: () -> Unit,
    onRefreshRepos: () -> Unit,
    onCreateAndLinkRepo: (name: String, description: String, isPrivate: Boolean) -> Unit,
    onLinkExistingRepo: (owner: String, repo: String, branch: String) -> Unit,
    onCloneRepoAsProject: (GitHubRepoSummary) -> Unit = {},
    onRefreshBranches: () -> Unit = {},
    onSwitchBranch: (String) -> Unit = {},
    onCreateBranch: (String) -> Unit = {},
    onRefreshRemoteFiles: () -> Unit = {},
    onDownloadRemoteFile: (String) -> Unit = {},
    onUploadActiveFile: (String, String) -> Unit = { _, _ -> },
    onUploadDeviceFileUri: (Uri) -> Unit = {},
    onPushProject: (commitMessage: String) -> Unit,
    onPullProject: () -> Unit,
    onScaffoldGradleCi: () -> Unit,
    onTriggerCloudBuild: () -> Unit,
    onRefreshWorkflowRuns: () -> Unit,
    onInspectWorkflowRun: (Long) -> Unit = {},
    onRerunWorkflowRun: (Long, Boolean) -> Unit = { _, _ -> },
    onDownloadArtifact: (Long) -> Unit = {},
    onAskAiToFixRun: (GitHubWorkflowRun) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var tokenInput by remember(githubToken) { mutableStateOf(githubToken) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var autoWatchCi by remember { mutableStateOf(true) }

    val pickUploadFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onUploadDeviceFileUri(uri)
    }

    fun openBrowserUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    fun pasteClipboardToken() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        if (!clip.isNullOrBlank()) {
            tokenInput = clip
        }
    }

    // Auto-load repos/branches/files/runs when opening dialog or switching tabs
    LaunchedEffect(githubUser, project?.githubLink?.fullName, selectedTab) {
        if (githubUser.isNotBlank()) {
            if (githubRepos.isEmpty()) onRefreshRepos()
            if (project?.githubLink != null) {
                when (selectedTab) {
                    0 -> if (branches.isEmpty()) onRefreshBranches()
                    1 -> if (remoteFiles.isEmpty()) onRefreshRemoteFiles()
                    2 -> if (workflowRuns.isEmpty()) onRefreshWorkflowRuns()
                }
            }
        }
    }

    // Auto-watch active CI runs while Workflows tab is visible
    val hasActiveRun = workflowRuns.any { it.status == "queued" || it.status == "in_progress" }
    LaunchedEffect(selectedTab, autoWatchCi, hasActiveRun, project?.githubLink?.fullName) {
        if (selectedTab == 2 && autoWatchCi && hasActiveRun && project?.githubLink != null) {
            while (true) {
                delay(6_000L)
                onRefreshWorkflowRuns()
            }
        }
    }

    val tabTitles = listOf("Account & Repos", "Files & Sync", "Workflows")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text("GitHub Hub & Actions CI", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = if (githubUser.isNotBlank()) "Signed in as @$githubUser" else "One-tap PAT sign-in & full repo control",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tabTitles.forEachIndexed { idx, label ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.testTag("github_hub_tab_$idx"),
                        )
                    }
                }

                if (isGitHubBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (selectedTab) {
                        0 -> GitHubAccountAndReposTab(
                            project = project,
                            githubUser = githubUser,
                            tokenInput = tokenInput,
                            onTokenInputChange = { tokenInput = it },
                            githubRepos = githubRepos,
                            branches = branches,
                            isGitHubBusy = isGitHubBusy,
                            onPasteToken = { pasteClipboardToken() },
                            onOpenTokenCreator = { openBrowserUrl(GITHUB_PAT_CREATE_URL) },
                            onConnectToken = onConnectToken,
                            onDisconnectGitHub = onDisconnectGitHub,
                            onRefreshRepos = onRefreshRepos,
                            onCreateAndLinkRepo = onCreateAndLinkRepo,
                            onLinkExistingRepo = onLinkExistingRepo,
                            onCloneRepoAsProject = { repo ->
                                onCloneRepoAsProject(repo)
                                onDismiss()
                            },
                            onRefreshBranches = onRefreshBranches,
                            onSwitchBranch = onSwitchBranch,
                            onCreateBranch = onCreateBranch,
                            onOpenBrowserUrl = ::openBrowserUrl,
                        )
                        1 -> GitHubFilesAndSyncTab(
                            project = project,
                            activeFilePath = activeFilePath,
                            pendingChanges = pendingChanges,
                            remoteFiles = remoteFiles,
                            isGitHubBusy = isGitHubBusy,
                            onPushProject = onPushProject,
                            onPullProject = onPullProject,
                            onUploadActiveFile = onUploadActiveFile,
                            onPickAndUploadFile = { pickUploadFileLauncher.launch(arrayOf("*/*")) },
                            onRefreshRemoteFiles = onRefreshRemoteFiles,
                            onDownloadRemoteFile = onDownloadRemoteFile,
                        )
                        else -> GitHubWorkflowsAndCiTab(
                            project = project,
                            workflowRuns = workflowRuns,
                            inspectedRunId = inspectedRunId,
                            workflowJobs = workflowJobs,
                            selectedRunLogs = selectedRunLogs,
                            workflowArtifacts = workflowArtifacts,
                            autoWatchCi = autoWatchCi,
                            onToggleAutoWatchCi = { autoWatchCi = it },
                            isGitHubBusy = isGitHubBusy,
                            onScaffoldGradleCi = onScaffoldGradleCi,
                            onTriggerCloudBuild = onTriggerCloudBuild,
                            onRefreshWorkflowRuns = onRefreshWorkflowRuns,
                            onInspectWorkflowRun = onInspectWorkflowRun,
                            onRerunWorkflowRun = onRerunWorkflowRun,
                            onDownloadArtifact = onDownloadArtifact,
                            onAskAiToFixRun = { run ->
                                onAskAiToFixRun(run)
                                onDismiss()
                            },
                            onOpenBrowserUrl = ::openBrowserUrl,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("github_hub_done_button"),
            ) {
                Text("Done")
            }
        },
    )
}
