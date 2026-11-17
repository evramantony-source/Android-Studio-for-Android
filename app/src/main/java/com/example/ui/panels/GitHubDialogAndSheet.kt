package com.example.ui.panels

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.GitHubPendingChange
import com.example.github.GitHubRepoSummary
import com.example.github.GitHubWorkflowRun
import com.example.model.ProjectMetadata
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun GitHubSheetDialog(
    project: ProjectMetadata?,
    githubToken: String,
    githubUser: String,
    githubRepos: List<GitHubRepoSummary>,
    pendingChanges: List<GitHubPendingChange>,
    workflowRuns: List<GitHubWorkflowRun>,
    isGitHubBusy: Boolean,
    onConnectToken: (String) -> Unit,
    onDisconnectGitHub: () -> Unit,
    onRefreshRepos: () -> Unit,
    onCreateAndLinkRepo: (name: String, description: String, isPrivate: Boolean) -> Unit,
    onLinkExistingRepo: (owner: String, repo: String, branch: String) -> Unit,
    onPushProject: (commitMessage: String) -> Unit,
    onPullProject: () -> Unit,
    onScaffoldGradleCi: () -> Unit,
    onTriggerCloudBuild: () -> Unit,
    onRefreshWorkflowRuns: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var tokenInput by remember(githubToken) { mutableStateOf(githubToken) }
    var repoModeCreate by remember { mutableStateOf(false) }
    var newRepoName by remember(project?.name) {
        mutableStateOf(
            (project?.name ?: "droidforge-app").lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-'),
        )
    }
    var newRepoPrivate by remember { mutableStateOf(false) }
    var branchInput by remember(project?.githubLink?.branch) {
        mutableStateOf(project?.githubLink?.branch ?: "main")
    }
    var commitMessage by remember(project?.name) {
        mutableStateOf("Update ${project?.name ?: "project"} from DroidForge Studio")
    }

    fun openBrowserUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text("GitHub & Cloud CI", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = if (githubUser.isNotBlank()) "Signed in as @$githubUser" else "Connect Personal Access Token",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isGitHubBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // 1. Account Connection
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
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "@$githubUser",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onRefreshRepos, enabled = !isGitHubBusy) {
                                    Text("Repos", maxLines = 1, softWrap = false)
                                }
                                TextButton(onClick = onDisconnectGitHub, enabled = !isGitHubBusy) {
                                    Text("Sign Out", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = { tokenInput = it },
                                label = { Text("GitHub PAT (repo, workflow scopes)") },
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

                // 2. Project Repository Link + Push/Pull
                if (project != null && githubUser.isNotBlank()) {
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
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Project Repository", style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        text = linked?.let { "${it.fullName} (${it.branch})" } ?: "Not linked to a repo yet",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (linked != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (linked != null) {
                                    IconButton(onClick = { openBrowserUrl(linked.htmlUrl) }) {
                                        Icon(Icons.Default.OpenInNew, contentDescription = "Open repo in browser")
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !repoModeCreate,
                                    onClick = { repoModeCreate = false },
                                    label = { Text("Link Existing", maxLines = 1, softWrap = false) },
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
                                    modifier = Modifier.fillMaxWidth(),
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
                                    },
                                    enabled = !isGitHubBusy && newRepoName.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Create & Link Repository", maxLines = 1, softWrap = false)
                                }
                            } else {
                                OutlinedTextField(
                                    value = branchInput,
                                    onValueChange = { branchInput = it },
                                    label = { Text("Target Branch") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (githubRepos.isEmpty()) {
                                    OutlinedButton(
                                        onClick = onRefreshRepos,
                                        enabled = !isGitHubBusy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Load My Repositories", maxLines = 1, softWrap = false)
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        githubRepos.take(6).forEach { r ->
                                            val isCurr = linked?.fullName == r.fullName
                                            Surface(
                                                color = if (isCurr) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable(enabled = !isGitHubBusy) {
                                                        val b = branchInput.trim().ifBlank { r.defaultBranch }
                                                        onLinkExistingRepo(r.owner, r.name, b)
                                                    },
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                ) {
                                                    Text(
                                                        text = r.fullName,
                                                        fontFamily = JetBrainsMonoFontFamily,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    Text(
                                                        text = if (isCurr) "Linked" else r.defaultBranch,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (linked != null) {
                                HorizontalDivider()
                                Text(
                                    text = "Pending Changes (${pendingChanges.size})",
                                    style = MaterialTheme.typography.labelLarge,
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
                                        modifier = Modifier.weight(0.75f),
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Pull", maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }

                    // 3. GitHub Actions Cloud Build (Java 8..25, Multi-GB & NDK)
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
                                text = "Cloud Gradle CI (JDK ${project.javaVersion.majorVersion})",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = "Scaffolds app/build.gradle.kts + GitHub Actions workflow using Temurin JDK ${project.javaVersion.majorVersion} for NDK, external dependencies, or multi-GB builds.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = onScaffoldGradleCi,
                                    enabled = !isGitHubBusy,
                                ) {
                                    Text("Scaffold CI", maxLines = 1, softWrap = false)
                                }
                                Button(
                                    onClick = onTriggerCloudBuild,
                                    enabled = !isGitHubBusy && linked != null,
                                    modifier = Modifier.testTag("github_trigger_cloud_build_button"),
                                ) {
                                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("Run Cloud Build", maxLines = 1, softWrap = false)
                                }
                                if (linked != null) {
                                    IconButton(onClick = onRefreshWorkflowRuns, enabled = !isGitHubBusy) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh workflow runs")
                                    }
                                }
                            }

                            if (workflowRuns.isNotEmpty()) {
                                workflowRuns.take(4).forEach { run ->
                                    val isOk = run.conclusion == "success"
                                    val isFail = run.conclusion == "failure" || run.conclusion == "cancelled"
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (run.htmlUrl.isNotBlank()) openBrowserUrl(run.htmlUrl)
                                            },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
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
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = run.name,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = "${run.status}${if (run.conclusion.isNotBlank()) " • ${run.conclusion}" else ""}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}
