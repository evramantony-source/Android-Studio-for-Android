package com.example.ui.panels

import androidx.compose.foundation.background
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.BuildDiagnostic
import com.example.model.BuildResult
import com.example.model.BuildVariant
import com.example.model.DiagnosticSeverity
import com.example.model.ReleaseSigningConfig
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun BuildPanel(
    isBuilding: Boolean,
    selectedVariant: BuildVariant,
    releaseSigningConfig: ReleaseSigningConfig,
    logs: List<String>,
    buildResult: BuildResult?,
    onSelectVariant: (BuildVariant) -> Unit,
    onTriggerBuild: (BuildVariant) -> Unit,
    onOpenKeystoreManager: () -> Unit,
    onOpenEmulator: () -> Unit,
    onDiagnosticClick: (BuildDiagnostic) -> Unit,
    onAskGeminiToFix: () -> Unit,
    onExportArtifact: (String) -> Unit,
    onShareArtifact: (String) -> Unit,
    onOpenGitHubCloudBuild: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.weight(1f),
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Build Output")
                            }
                        },
                        modifier = Modifier.testTag("build_output_tab"),
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Package Inspector")
                            }
                        },
                        modifier = Modifier.testTag("apk_inspector_tab"),
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.testTag("close_build_panel")) {
                    Icon(Icons.Default.Close, contentDescription = "Close build panel")
                }
            }

            // Build Variant Selector + Keystore Status Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BuildVariant.entries.forEach { variant ->
                    FilterChip(
                        selected = selectedVariant == variant,
                        onClick = { onSelectVariant(variant) },
                        label = { Text(variant.shortLabel, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("build_variant_${variant.name.lowercase()}"),
                    )
                }
                AssistChip(
                    onClick = onOpenKeystoreManager,
                    label = {
                        Text(
                            text = if (releaseSigningConfig.isConfigured) {
                                "Key: ${releaseSigningConfig.alias}"
                            } else {
                                "Keystore"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = if (releaseSigningConfig.isConfigured) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    },
                    modifier = Modifier.testTag("open_keystore_manager_chip"),
                )
                AssistChip(
                    onClick = { onTriggerBuild(selectedVariant) },
                    enabled = !isBuilding,
                    label = { Text("Run ${selectedVariant.shortLabel}", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                )
                AssistChip(
                    onClick = onOpenGitHubCloudBuild,
                    label = { Text("Cloud CI", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    modifier = Modifier.testTag("build_panel_cloud_ci_chip"),
                )
            }

            if (isBuilding) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (selectedTab == 0) {
                BuildConsoleTab(
                    isBuilding = isBuilding,
                    logs = logs,
                    buildResult = buildResult,
                    onDiagnosticClick = onDiagnosticClick,
                    onAskGeminiToFix = onAskGeminiToFix,
                    onExportArtifact = onExportArtifact,
                    onShareArtifact = onShareArtifact,
                    onOpenEmulator = onOpenEmulator,
                    onOpenGitHubCloudBuild = onOpenGitHubCloudBuild,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ArtifactInspectorTab(
                    buildResult = buildResult,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BuildConsoleTab(
    isBuilding: Boolean,
    logs: List<String>,
    buildResult: BuildResult?,
    onDiagnosticClick: (BuildDiagnostic) -> Unit,
    onAskGeminiToFix: () -> Unit,
    onExportArtifact: (String) -> Unit,
    onShareArtifact: (String) -> Unit,
    onOpenEmulator: () -> Unit,
    onOpenGitHubCloudBuild: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (buildResult != null && !isBuilding) {
            Surface(
                color = if (buildResult.success) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (buildResult.success) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (buildResult.success) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                        val artifactNoun = if (buildResult.variant.isBundle) "AAB" else buildResult.variant.shortLabel
                        Text(
                            text = if (buildResult.success) {
                                "$artifactNoun Built (${formatBytes(buildResult.apkSizeBytes)}) in ${buildResult.durationMs}ms"
                            } else {
                                "Build Failed (${buildResult.diagnostics.size} issue(s))"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (buildResult.success) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (buildResult.success && buildResult.apkPath != null) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { onExportArtifact(buildResult.apkPath) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary,
                                ),
                                modifier = Modifier.testTag("export_apk_button"),
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (buildResult.variant.isBundle) "Save AAB" else "Save APK")
                            }
                            OutlinedButton(
                                onClick = { onShareArtifact(buildResult.apkPath) },
                                modifier = Modifier.testTag("share_apk_button"),
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share / Open")
                            }
                            OutlinedButton(
                                onClick = onOpenEmulator,
                                modifier = Modifier.testTag("preview_emulator_after_build"),
                            ) {
                                Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Emulator")
                            }
                            OutlinedButton(
                                onClick = onOpenGitHubCloudBuild,
                                modifier = Modifier.testTag("cloud_ci_after_build"),
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("GitHub CI")
                            }
                        }
                    } else if (!buildResult.success) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onAskGeminiToFix,
                                modifier = Modifier.testTag("gemini_fix_errors_button"),
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Ask AI to Fix Errors")
                            }
                            OutlinedButton(
                                onClick = onOpenGitHubCloudBuild,
                                modifier = Modifier.testTag("cloud_ci_on_failure"),
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Cloud CI")
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (buildResult != null && buildResult.diagnostics.isNotEmpty()) {
                items(buildResult.diagnostics) { diag ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDiagnosticClick(diag) }
                            .testTag("diagnostic_item"),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (diag.severity == DiagnosticSeverity.ERROR) {
                                    Icons.Default.Error
                                } else {
                                    Icons.Default.Warning
                                },
                                contentDescription = null,
                                tint = if (diag.severity == DiagnosticSeverity.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${diag.filePath}:${diag.line}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = diag.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (logs.isEmpty()) {
                item {
                    Text(
                        text = "Choose Debug APK, Release APK, or Release AAB above to compile Java 8-25 or Kotlin offline with streaming ZIP64 packaging, or tap Cloud CI for multi-GB external Gradle/NDK builds on GitHub Actions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(logs) { logLine ->
                    Text(
                        text = logLine,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            logLine.startsWith("✔") -> MaterialTheme.colorScheme.secondary
                            logLine.startsWith("✖") -> MaterialTheme.colorScheme.error
                            logLine.startsWith("⚠") -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactInspectorTab(
    buildResult: BuildResult?,
    modifier: Modifier = Modifier,
) {
    if (buildResult == null || !buildResult.success) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Build an APK or AAB to inspect its DEX bytecode, binary/proto resources, SHA-256 digests, and cryptographic signature entries.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (buildResult.variant.isBundle) {
                                    "Signed App Bundle (.aab) • JAR PKCS#7"
                                } else {
                                    "Signed ${buildResult.variant.label} • v1 / v2 / v3"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = buildResult.signerSubject.ifBlank { "Android Studio Signing Key" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = formatBytes(buildResult.apkSizeBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (buildResult.signerSha256.isNotBlank()) {
                        Text(
                            text = "Signer SHA-256: ${buildResult.signerSha256}",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (buildResult.artifactSha256.isNotBlank()) {
                        Text(
                            text = "Artifact SHA-256: ${buildResult.artifactSha256}",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        items(buildResult.apkEntries) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.name,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.isStored) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(
                            text = "STORED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = formatBytes(entry.uncompressedSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
