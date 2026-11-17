package com.example.ui.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.example.ui.theme.JetBrainsMonoFontFamily

internal const val GITHUB_PAT_CREATE_URL =
    "https://github.com/settings/tokens/new?description=DroidForge+Studio&scopes=repo,workflow,read:user"

@Composable
internal fun GitHubAccountAndReposTab(
    project: ProjectMetadata?,
    githubUser: String,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    githubRepos: List<GitHubRepoSummary>,
    branches: List<GitHubBranch>,
    isGitHubBusy: Boolean,
    onPasteToken: () -> Unit,
    onOpenTokenCreator: () -> Unit,
    onConnectToken: (String) -> Unit,
    onDisconnectGitHub: () -> Unit,
    onRefreshRepos: () -> Unit,
    onCreateAndLinkRepo: (String, String, Boolean) -> Unit,
    onLinkExistingRepo: (String, String, String) -> Unit,
    onCloneRepoAsProject: (GitHubRepoSummary) -> Unit,
    onRefreshBranches: () -> Unit,
    onSwitchBranch: (String) -> Unit,
    onCreateBranch: (String) -> Unit,
    onOpenBrowserUrl: (String) -> Unit,
) {
    var repoSearch by remember { mutableStateOf("") }
    var repoModeCreate by remember { mutableStateOf(false) }
    var newRepoName by remember(project?.name) {
        mutableStateOf(
            (project?.name ?: "droidforge-app").lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-'),
        )
    }
    var newRepoPrivate by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var showCreateBranchRow by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Account Connect / Profile Card
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("GitHub Account", style = MaterialTheme.typography.labelLarge)
                if (githubUser.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Connected",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "@$githubUser",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${githubRepos.size} repository(ies) loaded",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = onRefreshRepos,
                            enabled = !isGitHubBusy,
                            modifier = Modifier.testTag("github_refresh_repos_button"),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh repositories")
                        }
                        TextButton(
                            onClick = onDisconnectGitHub,
                            enabled = !isGitHubBusy,
                            modifier = Modifier.testTag("github_sign_out_button"),
                        ) {
                            Text("Sign Out", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                        }
                    }
                } else {
                    Text(
                        text = "Sign in with one tap via GitHub PAT (repo, workflow, read:user scopes) — no repository URLs needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onOpenTokenCreator,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("github_generate_token_button"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Generate Token", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = onPasteToken,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("github_paste_token_button"),
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Paste", maxLines = 1, softWrap = false)
                        }
                    }
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = onTokenInputChange,
                        label = { Text("GitHub Personal Access Token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("github_token_input"),
                    )
                    Button(
                        onClick = { onConnectToken(tokenInput) },
                        enabled = !isGitHubBusy && tokenInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("github_connect_button"),
                    ) {
                        Text("Connect GitHub Account", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        if (githubUser.isNotBlank()) {
            // Active Project Link & Branch Card
            if (project != null) {
                val linked = project.githubLink
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Linked Project Repo", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = linked?.let { "${it.fullName} @ ${it.branch}" } ?: "Select a repo below or create a new one",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (linked != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (linked != null) {
                                IconButton(onClick = { onOpenBrowserUrl(linked.htmlUrl) }) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open repository on GitHub")
                                }
                            }
                        }

                        if (linked != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Branches", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = onRefreshBranches, enabled = !isGitHubBusy) {
                                    Text("Reload", maxLines = 1, softWrap = false)
                                }
                                TextButton(
                                    onClick = { showCreateBranchRow = !showCreateBranchRow },
                                    modifier = Modifier.testTag("github_new_branch_toggle"),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Branch", maxLines = 1, softWrap = false)
                                }
                            }

                            if (branches.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    branches.forEach { b ->
                                        FilterChip(
                                            selected = b.name == linked.branch,
                                            onClick = { if (b.name != linked.branch) onSwitchBranch(b.name) },
                                            label = { Text(b.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                                        )
                                    }
                                }
                            }

                            if (showCreateBranchRow) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = newBranchName,
                                        onValueChange = { newBranchName = it },
                                        label = { Text("New branch name") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Button(
                                        onClick = {
                                            val name = newBranchName.trim()
                                            if (name.isNotEmpty()) {
                                                onCreateBranch(name)
                                                newBranchName = ""
                                                showCreateBranchRow = false
                                            }
                                        },
                                        enabled = !isGitHubBusy && newBranchName.isNotBlank(),
                                    ) {
                                        Text("Create", maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !repoModeCreate,
                                onClick = { repoModeCreate = false },
                                label = { Text("Browse & Pick", maxLines = 1, softWrap = false) },
                            )
                            FilterChip(
                                selected = repoModeCreate,
                                onClick = { repoModeCreate = true },
                                label = { Text("Create New Repo", maxLines = 1, softWrap = false) },
                            )
                        }

                        if (repoModeCreate) {
                            OutlinedTextField(
                                value = newRepoName,
                                onValueChange = { newRepoName = it },
                                label = { Text("Repository Name") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("github_new_repo_name_input"),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Private Repository", style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = newRepoPrivate, onCheckedChange = { newRepoPrivate = it })
                            }
                            Button(
                                onClick = {
                                    onCreateAndLinkRepo(newRepoName, "Built with DroidForge Studio", newRepoPrivate)
                                    repoModeCreate = false
                                },
                                enabled = !isGitHubBusy && newRepoName.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("github_create_repo_button"),
                            ) {
                                Text("Create & Link Repository", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            // Repositories Browser (Always accessible, supports Link + Clone as Project!)
            if (!repoModeCreate || project == null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Your Repositories (${githubRepos.size})", style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = onRefreshRepos, enabled = !isGitHubBusy) {
                                Text("Refresh", maxLines = 1, softWrap = false)
                            }
                        }
                        OutlinedTextField(
                            value = repoSearch,
                            onValueChange = { repoSearch = it },
                            placeholder = { Text("Search repositories...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("github_repo_search_input"),
                        )
                        val filtered = remember(githubRepos, repoSearch) {
                            val q = repoSearch.trim()
                            if (q.isEmpty()) githubRepos else githubRepos.filter { it.fullName.contains(q, ignoreCase = true) }
                        }
                        if (filtered.isEmpty()) {
                            Text(
                                text = if (githubRepos.isEmpty()) "Tap Refresh to load your GitHub repositories." else "No matching repositories.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            filtered.take(25).forEach { r ->
                                val isCurr = project?.githubLink?.fullName == r.fullName
                                Surface(
                                    color = if (isCurr) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = r.fullName,
                                                fontFamily = JetBrainsMonoFontFamily,
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${r.defaultBranch}${if (r.isPrivate) " • Private" else " • Public"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (project != null) {
                                            TextButton(
                                                onClick = { onLinkExistingRepo(r.owner, r.name, r.defaultBranch) },
                                                enabled = !isGitHubBusy && !isCurr,
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.testTag("github_link_repo_${r.name}"),
                                            ) {
                                                Text(if (isCurr) "Linked" else "Link", maxLines = 1, softWrap = false)
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { onCloneRepoAsProject(r) },
                                            enabled = !isGitHubBusy,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.testTag("github_clone_repo_${r.name}"),
                                        ) {
                                            Icon(Icons.Default.FolderCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Clone", maxLines = 1, softWrap = false)
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
}

@Composable
internal fun GitHubFilesAndSyncTab(
    project: ProjectMetadata?,
    activeFilePath: String?,
    pendingChanges: List<GitHubPendingChange>,
    remoteFiles: List<GitHubTreeEntry>,
    isGitHubBusy: Boolean,
    onPushProject: (String) -> Unit,
    onPullProject: () -> Unit,
    onUploadActiveFile: (String, String) -> Unit,
    onPickAndUploadFile: () -> Unit,
    onRefreshRemoteFiles: () -> Unit,
    onDownloadRemoteFile: (String) -> Unit,
) {
    val linked = project?.githubLink
    if (project == null || linked == null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("No Linked Repository", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (project == null) {
                        "Open a project or clone a repo from the Account & Repos tab to push, pull, upload, or download files."
                    } else {
                        "Link or create a repository in the Account & Repos tab first."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    var commitMessage by remember(project.name) {
        mutableStateOf("Update ${project.name} from DroidForge Studio")
    }
    var remoteFilter by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Pending Diff + Full Push / Pull
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Sync ${linked.fullName} (${linked.branch})",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Pending Changes (${pendingChanges.size})",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (pendingChanges.isEmpty()) {
                    Text(
                        text = "Working tree matches last push on GitHub.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        pendingChanges.take(8).forEach { ch ->
                            Text(
                                text = "${ch.status.uppercase().take(3)}  ${ch.path}",
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = when (ch.status) {
                                    "added" -> MaterialTheme.colorScheme.secondary
                                    "deleted" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (pendingChanges.size > 8) {
                            Text(
                                text = "... +${pendingChanges.size - 8} more file(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    label = { Text("Commit Message") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onPushProject(commitMessage) },
                        enabled = !isGitHubBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("github_push_button"),
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Commit & Push", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onPullProject,
                        enabled = !isGitHubBusy,
                        modifier = Modifier
                            .weight(0.75f)
                            .testTag("github_pull_button"),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pull All", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // Direct File Uploads
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Upload File to Repository", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onPickAndUploadFile,
                        enabled = !isGitHubBusy,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("github_upload_device_file_button"),
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Device File", maxLines = 1, softWrap = false)
                    }
                    if (!activeFilePath.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = { onUploadActiveFile(activeFilePath, commitMessage) },
                            enabled = !isGitHubBusy,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("github_upload_active_file_button"),
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Push ${activeFilePath.substringAfterLast('/')}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // Remote File Browser + Single-File Download
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Remote Repo Files (${remoteFiles.size})", style = MaterialTheme.typography.labelLarge)
                    IconButton(
                        onClick = onRefreshRemoteFiles,
                        enabled = !isGitHubBusy,
                        modifier = Modifier.testTag("github_refresh_remote_files_button"),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh remote file tree")
                    }
                }
                OutlinedTextField(
                    value = remoteFilter,
                    onValueChange = { remoteFilter = it },
                    placeholder = { Text("Filter remote files...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val visibleFiles = remember(remoteFiles, remoteFilter) {
                    val q = remoteFilter.trim()
                    if (q.isEmpty()) remoteFiles else remoteFiles.filter { it.path.contains(q, ignoreCase = true) }
                }
                if (visibleFiles.isEmpty()) {
                    Text(
                        text = if (remoteFiles.isEmpty()) "Tap Refresh to browse files on GitHub." else "No matching remote files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    visibleFiles.take(40).forEach { entry ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.path,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = formatBytes(entry.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { onDownloadRemoteFile(entry.path) },
                                    enabled = !isGitHubBusy,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Get", maxLines = 1, softWrap = false)
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
internal fun GitHubWorkflowsAndCiTab(
    project: ProjectMetadata?,
    workflowRuns: List<GitHubWorkflowRun>,
    inspectedRunId: Long?,
    workflowJobs: List<GitHubWorkflowJob>,
    selectedRunLogs: String,
    workflowArtifacts: List<GitHubWorkflowArtifact>,
    autoWatchCi: Boolean,
    onToggleAutoWatchCi: (Boolean) -> Unit,
    isGitHubBusy: Boolean,
    onScaffoldGradleCi: () -> Unit,
    onTriggerCloudBuild: () -> Unit,
    onRefreshWorkflowRuns: () -> Unit,
    onInspectWorkflowRun: (Long) -> Unit,
    onRerunWorkflowRun: (Long, Boolean) -> Unit,
    onDownloadArtifact: (Long) -> Unit,
    onAskAiToFixRun: (GitHubWorkflowRun) -> Unit,
    onOpenBrowserUrl: (String) -> Unit,
) {
    val linked = project?.githubLink
    if (project == null || linked == null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Workflows Require Linked Project", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Open a project and link a GitHub repository in Account & Repos to watch Actions runs, rerun jobs, download build APKs, and ask AI to fix CI failures.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // CI Controls Card
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "GitHub Actions CI (JDK ${project.javaVersion.majorVersion})",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(
                        onClick = onRefreshWorkflowRuns,
                        enabled = !isGitHubBusy,
                        modifier = Modifier.testTag("github_refresh_runs_button"),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh workflow runs")
                    }
                }
                Text(
                    text = "Scaffolds full Gradle + Kotlin + NDK cloud workflow, watches live builds, reruns jobs, downloads APK/AAB artifacts, and lets AI diagnose & fix failures.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onScaffoldGradleCi, enabled = !isGitHubBusy) {
                        Text("Scaffold CI", maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = onTriggerCloudBuild,
                        enabled = !isGitHubBusy,
                        modifier = Modifier.testTag("github_trigger_cloud_build_button"),
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Run Cloud Build", maxLines = 1, softWrap = false)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Auto-watch active CI runs (6s)", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = autoWatchCi,
                        onCheckedChange = onToggleAutoWatchCi,
                        modifier = Modifier.testTag("github_autowatch_switch"),
                    )
                }
            }
        }

        // Workflow Runs List
        if (workflowRuns.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "No workflow runs yet. Tap Run Cloud Build or push changes to trigger Actions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else {
            workflowRuns.take(10).forEach { run ->
                val isOk = run.conclusion == "success"
                val isFail = run.conclusion == "failure" || run.conclusion == "cancelled" || run.conclusion == "timed_out"
                val isSelected = inspectedRunId == run.id
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onInspectWorkflowRun(run.id) },
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (isFail) Icons.Default.Error else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = when {
                                    isOk -> MaterialTheme.colorScheme.secondary
                                    isFail -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                val numPrefix = if (run.runNumber > 0) "#${run.runNumber} " else ""
                                Text(
                                    text = "$numPrefix${run.name}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val branchPart = run.headBranch.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
                                Text(
                                    text = "${run.status}${if (run.conclusion.isNotBlank()) " • ${run.conclusion}" else ""}$branchPart",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (run.htmlUrl.isNotBlank()) {
                                IconButton(onClick = { onOpenBrowserUrl(run.htmlUrl) }) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open run on GitHub", modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onInspectWorkflowRun(run.id) },
                                enabled = !isGitHubBusy,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("github_inspect_run_${run.id}"),
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Logs", maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(
                                onClick = { onRerunWorkflowRun(run.id, false) },
                                enabled = !isGitHubBusy,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("github_rerun_${run.id}"),
                            ) {
                                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Rerun", maxLines = 1, softWrap = false)
                            }
                            if (isFail) {
                                OutlinedButton(
                                    onClick = { onRerunWorkflowRun(run.id, true) },
                                    enabled = !isGitHubBusy,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text("Rerun Failed", maxLines = 1, softWrap = false)
                                }
                                Button(
                                    onClick = { onAskAiToFixRun(run) },
                                    enabled = !isGitHubBusy,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                                    modifier = Modifier.testTag("github_ask_ai_fix_${run.id}"),
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Ask AI to Fix", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Selected Run Job Steps, Artifacts & Log Viewer
        if (inspectedRunId != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Run #$inspectedRunId Inspector", style = MaterialTheme.typography.labelLarge)

                    workflowJobs.forEach { job ->
                        Text(
                            text = "Job: ${job.name} (${job.conclusion.ifBlank { job.status }})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            job.steps.forEach { step ->
                                val stepOk = step.conclusion == "success"
                                val stepFail = step.conclusion == "failure"
                                Text(
                                    text = "${if (stepOk) "[OK]" else if (stepFail) "[FAIL]" else "[..]"} ${step.number}. ${step.name}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        stepFail -> MaterialTheme.colorScheme.error
                                        stepOk -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    if (workflowArtifacts.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Artifacts (${workflowArtifacts.size})", style = MaterialTheme.typography.labelMedium)
                        workflowArtifacts.forEach { art ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${art.name} (${formatBytes(art.sizeInBytes)})",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { onDownloadArtifact(art.id) },
                                    enabled = !isGitHubBusy && !art.expired,
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (art.expired) "Expired" else "Download", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }

                    if (selectedRunLogs.isNotBlank()) {
                        HorizontalDivider()
                        Text("Workflow Log Tail", style = MaterialTheme.typography.labelMedium)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 220.dp),
                        ) {
                            Text(
                                text = selectedRunLogs.takeLast(6_000),
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp)
                                    .testTag("github_workflow_log_viewer"),
                            )
                        }
                    }
                }
            }
        }
    }
}
