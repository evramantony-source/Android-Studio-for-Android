package com.example.ui.panels

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.example.model.ProjectSearchHit
import com.example.model.ReleaseSigningConfig
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun ReleaseKeystoreDialog(
    signingConfig: ReleaseSigningConfig,
    onGenerateKeystore: (alias: String, password: String, commonName: String, organization: String) -> Unit,
    onImportKeystore: (uri: Uri, alias: String, password: String) -> Unit,
    onClearKeystore: () -> Unit,
    onDismiss: () -> Unit,
) {
    var aliasInput by remember(signingConfig.alias) {
        mutableStateOf(signingConfig.alias.ifBlank { "release" })
    }
    var passwordInput by remember { mutableStateOf("") }
    var commonNameInput by remember(signingConfig.commonName) {
        mutableStateOf(signingConfig.commonName.ifBlank { "DroidForge Publisher" })
    }
    var orgInput by remember(signingConfig.organization) {
        mutableStateOf(signingConfig.organization.ifBlank { "DroidForge Studio" })
    }
    var importAliasInput by remember { mutableStateOf("release") }
    var importPasswordInput by remember { mutableStateOf("") }

    val importKeystoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImportKeystore(uri, importAliasInput, importPasswordInput)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Text("Release Keystore Manager")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Current Signing Status
                Surface(
                    color = if (signingConfig.isConfigured) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (signingConfig.isConfigured) {
                                "Active Release Key: ${signingConfig.alias}"
                            } else {
                                "No Custom Release Keystore Configured"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (signingConfig.isConfigured) {
                            if (signingConfig.sha256Fingerprint.isNotBlank()) {
                                Text(
                                    text = "SHA-256: ${signingConfig.sha256Fingerprint}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (signingConfig.sha1Fingerprint.isNotBlank()) {
                                Text(
                                    text = "SHA-1: ${signingConfig.sha1Fingerprint}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = onClearKeystore,
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Remove Keystore", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Text(
                                text = "Release APKs and App Bundles (.aab) will use the local fallback key until you generate or import your own 2048-bit RSA release keystore.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "Generate New 2048-Bit RSA Keystore (.p12)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = aliasInput,
                    onValueChange = { aliasInput = it },
                    label = { Text("Key Alias") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Store & Key Password (min 6 chars)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("keystore_generate_password_input"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = commonNameInput,
                        onValueChange = { commonNameInput = it },
                        label = { Text("Developer CN") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = orgInput,
                        onValueChange = { orgInput = it },
                        label = { Text("Organization") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = {
                        onGenerateKeystore(aliasInput, passwordInput, commonNameInput, orgInput)
                    },
                    enabled = passwordInput.length >= 6 && aliasInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("generate_release_keystore_button"),
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Generate Release Keystore")
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "Or Import Existing Keystore (.p12 / .jks)",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = importAliasInput,
                        onValueChange = { importAliasInput = it },
                        label = { Text("Alias (optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = importPasswordInput,
                        onValueChange = { importPasswordInput = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedButton(
                    onClick = { importKeystoreLauncher.launch(arrayOf("*/*")) },
                    enabled = importPasswordInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Choose Keystore File")
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

@Composable
fun ProjectSearchDialog(
    onSearch: (String) -> List<ProjectSearchHit>,
    onJumpToHit: (ProjectSearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val hits = remember(query) { onSearch(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Search Project Files")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search classes, methods, IDs, XML...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("project_search_input"),
                )

                Text(
                    text = if (query.isBlank()) {
                        "Type to search across all Java & XML files in this project."
                    } else {
                        "${hits.size} match(es)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(hits) { hit ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onJumpToHit(hit)
                                    onDismiss()
                                }
                                .testTag("search_hit_item"),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = "${hit.relativePath}:${hit.lineNumber}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = hit.previewLine,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
