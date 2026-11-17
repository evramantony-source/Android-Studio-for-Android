package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ai.AiModelsCatalog
import com.example.ai.AiProvider
import com.example.ai.AiThinkingMode
import com.example.ai.AiUsageSummary
import com.example.ai.GEMINI_MODEL_ID
import com.example.ai.ProviderUsageStats
import com.example.ai.TokenUsageDelta
import com.example.model.BuildVariant
import com.example.model.EditorSettings
import com.example.model.FileLanguage
import com.example.model.GitHubRepoLink
import com.example.model.JavaVersionOption
import com.example.model.ProjectFile
import com.example.model.ProjectLanguage
import com.example.model.ProjectMetadata
import com.example.model.ProjectTemplate
import com.example.model.ReleaseSigningConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class GitHubPendingChange(
    val path: String,
    val status: String, // "added", "modified", "deleted"
)

private val Context.editorDataStore by preferencesDataStore(name = "droidforge_editor_prefs")

class ProjectRepository(
    private val context: Context,
) {
    private val projectsRoot: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    val keystoresDir: File
        get() = File(context.filesDir, "keystores").apply { mkdirs() }

    private object PrefKeys {
        val FONT_SIZE = intPreferencesKey("editor_font_size")
        val WORD_WRAP = booleanPreferencesKey("editor_word_wrap")
        val AUTO_INDENT = booleanPreferencesKey("editor_auto_indent")
        val DARK_THEME = booleanPreferencesKey("editor_dark_theme")

        val ACTIVE_PROVIDER = stringPreferencesKey("ai_active_provider")
        val CUSTOM_GEMINI_API_KEY = stringPreferencesKey("custom_gemini_api_key")
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")

        val GEMINI_MODEL_ID_KEY = stringPreferencesKey("gemini_model_id")
        val OPENAI_MODEL_ID_KEY = stringPreferencesKey("openai_model_id")
        val CLAUDE_MODEL_ID_KEY = stringPreferencesKey("claude_model_id")

        val AI_THINKING_MODE = stringPreferencesKey("ai_thinking_mode")
        val AI_USAGE_JSON = stringPreferencesKey("ai_usage_summary_json")
        val RELEASE_SIGNING_JSON = stringPreferencesKey("release_signing_json")
        val BUILD_VARIANT = stringPreferencesKey("preferred_build_variant")
        val GITHUB_TOKEN = stringPreferencesKey("github_pat_token")
        val GITHUB_USER = stringPreferencesKey("github_user_login")
    }

    val activeProvider: Flow<AiProvider> = context.editorDataStore.data.map { prefs ->
        runCatching { AiProvider.valueOf(prefs[PrefKeys.ACTIVE_PROVIDER].orEmpty()) }
            .getOrDefault(AiProvider.GEMINI)
    }

    val aiThinkingMode: Flow<AiThinkingMode> = context.editorDataStore.data.map { prefs ->
        runCatching { AiThinkingMode.valueOf(prefs[PrefKeys.AI_THINKING_MODE].orEmpty()) }
            .getOrDefault(AiThinkingMode.FAST)
    }

    val providerApiKeys: Flow<Map<AiProvider, String>> = context.editorDataStore.data.map { prefs ->
        mapOf(
            AiProvider.GEMINI to prefs[PrefKeys.CUSTOM_GEMINI_API_KEY].orEmpty(),
            AiProvider.OPENAI to prefs[PrefKeys.OPENAI_API_KEY].orEmpty(),
            AiProvider.CLAUDE to prefs[PrefKeys.CLAUDE_API_KEY].orEmpty(),
        )
    }

    val providerModels: Flow<Map<AiProvider, String>> = context.editorDataStore.data.map { prefs ->
        mapOf(
            AiProvider.GEMINI to AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, prefs[PrefKeys.GEMINI_MODEL_ID_KEY]),
            AiProvider.OPENAI to AiModelsCatalog.normalizeModelId(AiProvider.OPENAI, prefs[PrefKeys.OPENAI_MODEL_ID_KEY]),
            AiProvider.CLAUDE to AiModelsCatalog.normalizeModelId(AiProvider.CLAUDE, prefs[PrefKeys.CLAUDE_MODEL_ID_KEY]),
        )
    }

    val customGeminiApiKey: Flow<String> = context.editorDataStore.data.map { prefs ->
        prefs[PrefKeys.CUSTOM_GEMINI_API_KEY].orEmpty()
    }

    val geminiModelId: Flow<String> = context.editorDataStore.data.map { prefs ->
        AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, prefs[PrefKeys.GEMINI_MODEL_ID_KEY])
    }

    val aiUsageSummary: Flow<AiUsageSummary> = context.editorDataStore.data.map { prefs ->
        AiUsageSummary.fromJson(prefs[PrefKeys.AI_USAGE_JSON].orEmpty())
    }

    val preferredBuildVariant: Flow<BuildVariant> = context.editorDataStore.data.map { prefs ->
        runCatching { BuildVariant.valueOf(prefs[PrefKeys.BUILD_VARIANT].orEmpty()) }
            .getOrDefault(BuildVariant.DEBUG_APK)
    }

    val githubToken: Flow<String> = context.editorDataStore.data.map { prefs ->
        prefs[PrefKeys.GITHUB_TOKEN].orEmpty()
    }

    val githubUser: Flow<String> = context.editorDataStore.data.map { prefs ->
        prefs[PrefKeys.GITHUB_USER].orEmpty()
    }

    suspend fun saveGitHubAuth(token: String, username: String) {
        context.editorDataStore.edit { prefs ->
            val cleanedToken = token.trim()
            val cleanedUser = username.trim()
            if (cleanedToken.isEmpty()) prefs.remove(PrefKeys.GITHUB_TOKEN) else prefs[PrefKeys.GITHUB_TOKEN] = cleanedToken
            if (cleanedUser.isEmpty()) prefs.remove(PrefKeys.GITHUB_USER) else prefs[PrefKeys.GITHUB_USER] = cleanedUser
        }
    }

    val releaseSigningConfig: Flow<ReleaseSigningConfig> = context.editorDataStore.data.map { prefs ->
        val raw = prefs[PrefKeys.RELEASE_SIGNING_JSON].orEmpty()
        if (raw.isBlank()) ReleaseSigningConfig()
        else runCatching {
            val o = JSONObject(raw)
            ReleaseSigningConfig(
                keystorePath = o.optString("keystorePath"),
                alias = o.optString("alias", "release"),
                storePassword = o.optString("storePassword"),
                keyPassword = o.optString("keyPassword"),
                commonName = o.optString("commonName"),
                organization = o.optString("organization"),
                sha1Fingerprint = o.optString("sha1Fingerprint"),
                sha256Fingerprint = o.optString("sha256Fingerprint"),
            )
        }.getOrDefault(ReleaseSigningConfig())
    }

    suspend fun saveActiveProvider(provider: AiProvider) {
        context.editorDataStore.edit { prefs ->
            prefs[PrefKeys.ACTIVE_PROVIDER] = provider.name
        }
    }

    suspend fun saveThinkingMode(mode: AiThinkingMode) {
        context.editorDataStore.edit { prefs ->
            prefs[PrefKeys.AI_THINKING_MODE] = mode.name
        }
    }

    suspend fun saveProviderConfig(provider: AiProvider, apiKey: String, modelId: String) {
        context.editorDataStore.edit { prefs ->
            val cleanedKey = apiKey.trim()
            val keyPref = when (provider) {
                AiProvider.GEMINI -> PrefKeys.CUSTOM_GEMINI_API_KEY
                AiProvider.OPENAI -> PrefKeys.OPENAI_API_KEY
                AiProvider.CLAUDE -> PrefKeys.CLAUDE_API_KEY
            }
            if (cleanedKey.isEmpty()) prefs.remove(keyPref) else prefs[keyPref] = cleanedKey

            val rawCleanedModel = modelId.trim().removePrefix("models/")
            val normalizedModel = if (rawCleanedModel.isEmpty()) "" else AiModelsCatalog.normalizeModelId(provider, rawCleanedModel)
            val modelPref = when (provider) {
                AiProvider.GEMINI -> PrefKeys.GEMINI_MODEL_ID_KEY
                AiProvider.OPENAI -> PrefKeys.OPENAI_MODEL_ID_KEY
                AiProvider.CLAUDE -> PrefKeys.CLAUDE_MODEL_ID_KEY
            }
            if (normalizedModel.isEmpty()) prefs.remove(modelPref) else prefs[modelPref] = normalizedModel
        }
    }

    suspend fun saveCustomGeminiApiKey(apiKey: String) {
        context.editorDataStore.edit { prefs ->
            val cleaned = apiKey.trim()
            if (cleaned.isEmpty()) prefs.remove(PrefKeys.CUSTOM_GEMINI_API_KEY)
            else prefs[PrefKeys.CUSTOM_GEMINI_API_KEY] = cleaned
        }
    }

    suspend fun saveGeminiModelId(modelId: String) {
        context.editorDataStore.edit { prefs ->
            val rawCleaned = modelId.trim().removePrefix("models/")
            if (rawCleaned.isEmpty()) {
                prefs.remove(PrefKeys.GEMINI_MODEL_ID_KEY)
            } else {
                prefs[PrefKeys.GEMINI_MODEL_ID_KEY] = AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, rawCleaned)
            }
        }
    }

    suspend fun recordAiUsage(
        provider: AiProvider,
        modelId: String,
        delta: TokenUsageDelta,
        toolCalls: Int,
        failed: Boolean,
    ) {
        context.editorDataStore.edit { prefs ->
            val current = AiUsageSummary.fromJson(prefs[PrefKeys.AI_USAGE_JSON].orEmpty())
            val prevProvider = current.byProvider[provider] ?: ProviderUsageStats(provider)
            val turnCost = AiModelsCatalog.estimateCostUsd(
                modelId = modelId,
                inputTokens = delta.inputTokens,
                outputTokens = delta.outputTokens,
                thinkingTokens = delta.thinkingTokens,
            )
            val updatedProvider = prevProvider.copy(
                requests = prevProvider.requests + 1,
                failedRequests = prevProvider.failedRequests + if (failed) 1 else 0,
                toolCalls = prevProvider.toolCalls + toolCalls,
                inputTokens = prevProvider.inputTokens + delta.inputTokens,
                outputTokens = prevProvider.outputTokens + delta.outputTokens,
                thinkingTokens = prevProvider.thinkingTokens + delta.thinkingTokens,
                totalTokens = prevProvider.totalTokens + delta.totalTokens,
                estimatedCostUsd = prevProvider.estimatedCostUsd + turnCost,
                lastLatencyMs = if (delta.latencyMs > 0) delta.latencyMs else prevProvider.lastLatencyMs,
                lastModelId = modelId.ifBlank { prevProvider.lastModelId },
                remainingQuotaNote = delta.remainingQuotaNote.ifBlank { prevProvider.remainingQuotaNote },
            )
            val nextProviders = current.byProvider.toMutableMap().apply {
                put(provider, updatedProvider)
            }
            val nextModels = current.byModel.toMutableMap().apply {
                val prev = this[modelId] ?: 0L
                this[modelId] = prev + delta.totalTokens
            }
            val nextSummary = AiUsageSummary(
                byProvider = nextProviders,
                byModel = nextModels,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
            prefs[PrefKeys.AI_USAGE_JSON] = nextSummary.toJson()
        }
    }

    suspend fun resetAiUsage() {
        context.editorDataStore.edit { prefs ->
            prefs.remove(PrefKeys.AI_USAGE_JSON)
        }
    }

    suspend fun savePreferredBuildVariant(variant: BuildVariant) {
        context.editorDataStore.edit { prefs ->
            prefs[PrefKeys.BUILD_VARIANT] = variant.name
        }
    }

    suspend fun saveReleaseSigningConfig(config: ReleaseSigningConfig?) {
        context.editorDataStore.edit { prefs ->
            if (config == null || !config.isConfigured) {
                prefs.remove(PrefKeys.RELEASE_SIGNING_JSON)
            } else {
                val obj = JSONObject()
                    .put("keystorePath", config.keystorePath)
                    .put("alias", config.alias)
                    .put("storePassword", config.storePassword)
                    .put("keyPassword", config.keyPassword)
                    .put("commonName", config.commonName)
                    .put("organization", config.organization)
                    .put("sha1Fingerprint", config.sha1Fingerprint)
                    .put("sha256Fingerprint", config.sha256Fingerprint)
                prefs[PrefKeys.RELEASE_SIGNING_JSON] = obj.toString()
            }
        }
    }

    val editorSettings: Flow<EditorSettings> = context.editorDataStore.data.map { prefs ->
        EditorSettings(
            fontSizeSp = prefs[PrefKeys.FONT_SIZE] ?: 13,
            wordWrap = prefs[PrefKeys.WORD_WRAP] ?: false,
            autoIndent = prefs[PrefKeys.AUTO_INDENT] ?: true,
            darkTheme = prefs[PrefKeys.DARK_THEME] ?: true,
        )
    }

    suspend fun updateEditorSettings(transform: (EditorSettings) -> EditorSettings) {
        context.editorDataStore.edit { prefs ->
            val current = EditorSettings(
                fontSizeSp = prefs[PrefKeys.FONT_SIZE] ?: 13,
                wordWrap = prefs[PrefKeys.WORD_WRAP] ?: false,
                autoIndent = prefs[PrefKeys.AUTO_INDENT] ?: true,
                darkTheme = prefs[PrefKeys.DARK_THEME] ?: true,
            )
            val updated = transform(current)
            prefs[PrefKeys.FONT_SIZE] = updated.fontSizeSp.coerceIn(10, 22)
            prefs[PrefKeys.WORD_WRAP] = updated.wordWrap
            prefs[PrefKeys.AUTO_INDENT] = updated.autoIndent
            prefs[PrefKeys.DARK_THEME] = updated.darkTheme
        }
    }

    suspend fun listProjects(): List<ProjectMetadata> = withContext(Dispatchers.IO) {
        val dirs = projectsRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (dirs.isEmpty()) {
            val starter = createProjectInternal(
                name = ProjectTemplate.COUNTER_APP.defaultAppName,
                packageName = ProjectTemplate.COUNTER_APP.defaultPackage,
                template = ProjectTemplate.COUNTER_APP,
            )
            return@withContext listOf(starter)
        }
        dirs.mapNotNull { readMetadata(it) }
            .sortedByDescending { it.updatedAtMillis }
    }

    suspend fun createProject(
        name: String,
        packageName: String,
        template: ProjectTemplate,
        language: ProjectLanguage = ProjectLanguage.JAVA,
        javaVersion: JavaVersionOption = JavaVersionOption.JAVA_17,
        minSdkVersion: Int = 24,
    ): ProjectMetadata = withContext(Dispatchers.IO) {
        createProjectInternal(name, packageName, template, language, javaVersion, minSdkVersion)
    }

    private fun createProjectInternal(
        name: String,
        packageName: String,
        template: ProjectTemplate,
        language: ProjectLanguage = ProjectLanguage.JAVA,
        javaVersion: JavaVersionOption = JavaVersionOption.JAVA_17,
        minSdkVersion: Int = 24,
    ): ProjectMetadata {
        val safeMinSdk = minSdkVersion.coerceIn(21, 35)
        val id = UUID.randomUUID().toString().take(8)
        val projectDir = File(projectsRoot, id).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val files = TemplateProvider.generateFiles(
            template = template,
            appName = name,
            packageName = packageName,
            language = language,
            minSdkVersion = safeMinSdk,
        )
        files.forEach { (relPath, content) ->
            val file = File(projectDir, relPath)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        val meta = ProjectMetadata(
            id = id,
            name = name,
            packageName = packageName,
            template = template,
            createdAtMillis = now,
            updatedAtMillis = now,
            language = language,
            javaVersion = javaVersion,
            minSdkVersion = safeMinSdk,
        )
        writeMetadata(projectDir, meta)
        return meta
    }

    suspend fun updateProjectConfig(
        projectId: String,
        language: ProjectLanguage,
        javaVersion: JavaVersionOption,
        minSdkVersion: Int,
    ): ProjectMetadata? = withContext(Dispatchers.IO) {
        val dir = getProjectDir(projectId)
        val current = readMetadata(dir) ?: return@withContext null
        val safeMinSdk = minSdkVersion.coerceIn(21, 35)
        val manifestFile = File(dir, "AndroidManifest.xml")
        if (manifestFile.exists()) {
            val xml = manifestFile.readText()
            val updatedXml = updateManifestMinSdk(xml, safeMinSdk)
            manifestFile.writeText(updatedXml)
        }
        val updatedMeta = current.copy(
            language = language,
            javaVersion = javaVersion,
            minSdkVersion = safeMinSdk,
            updatedAtMillis = System.currentTimeMillis(),
        )
        writeMetadata(dir, updatedMeta)
        updatedMeta
    }

    suspend fun saveGitHubRepoLink(
        projectId: String,
        link: GitHubRepoLink?,
    ): ProjectMetadata? = withContext(Dispatchers.IO) {
        val dir = getProjectDir(projectId)
        val current = readMetadata(dir) ?: return@withContext null
        val updated = current.copy(
            githubLink = link,
            updatedAtMillis = System.currentTimeMillis(),
        )
        writeMetadata(dir, updated)
        updated
    }

    suspend fun collectProjectFileHashes(projectId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val dir = getProjectDir(projectId)
        if (!dir.exists()) return@withContext emptyMap()
        val digest = MessageDigest.getInstance("SHA-256")
        val map = linkedMapOf<String, String>()
        dir.walkTopDown()
            .filter { file ->
                if (!file.isFile) return@filter false
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                rel != "project.json" && !rel.startsWith("build/") && !rel.startsWith(".droidforge/")
            }
            .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
            .forEach { file ->
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                digest.reset()
                val buf = ByteArray(16 * 1024)
                file.inputStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                    }
                }
                map[rel] = digest.digest().joinToString("") { "%02x".format(it) }
            }
        map
    }

    suspend fun computeGitHubPendingChanges(
        projectId: String,
        link: GitHubRepoLink?,
    ): List<GitHubPendingChange> {
        val current = collectProjectFileHashes(projectId)
        val previous = link?.lastPushedHashes.orEmpty()
        if (link == null || previous.isEmpty()) {
            return current.keys.map { GitHubPendingChange(it, "added") }
        }
        val changes = mutableListOf<GitHubPendingChange>()
        for ((path, hash) in current) {
            val prevHash = previous[path]
            if (prevHash == null) changes.add(GitHubPendingChange(path, "added"))
            else if (prevHash != hash) changes.add(GitHubPendingChange(path, "modified"))
        }
        for (oldPath in previous.keys) {
            if (!current.containsKey(oldPath)) {
                changes.add(GitHubPendingChange(oldPath, "deleted"))
            }
        }
        return changes.sortedBy { it.path }
    }

    private fun updateManifestMinSdk(xml: String, minSdkVersion: Int): String {
        val usesSdkRegex = Regex("""<uses-sdk\b[^>]*?>""")
        val minAttrRegex = Regex("""android:minSdkVersion\s*=\s*"\d+"""")
        return when {
            usesSdkRegex.containsMatchIn(xml) -> {
                usesSdkRegex.replace(xml) { match ->
                    val tag = match.value
                    if (minAttrRegex.containsMatchIn(tag)) {
                        minAttrRegex.replace(tag, """android:minSdkVersion="$minSdkVersion"""")
                    } else if (tag.endsWith("/>")) {
                        tag.dropLast(2).trimEnd() + """ android:minSdkVersion="$minSdkVersion" />"""
                    } else {
                        tag.dropLast(1).trimEnd() + """ android:minSdkVersion="$minSdkVersion">"""
                    }
                }
            }
            else -> {
                val manifestClose = Regex("""(<manifest\b[^>]*>)""")
                if (manifestClose.containsMatchIn(xml)) {
                    manifestClose.replaceFirst(
                        xml,
                        "$1\n\n    <uses-sdk\n        android:minSdkVersion=\"$minSdkVersion\"\n        android:targetSdkVersion=\"35\" />",
                    )
                } else {
                    xml
                }
            }
        }
    }

    suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        val dir = File(projectsRoot, projectId)
        if (dir.exists()) dir.deleteRecursively()
    }

    fun getProjectDir(projectId: String): File = File(projectsRoot, projectId)

    suspend fun loadProjectFiles(projectId: String): List<ProjectFile> = withContext(Dispatchers.IO) {
        val dir = getProjectDir(projectId)
        if (!dir.exists()) return@withContext emptyList()
        dir.walkTopDown()
            .filter { file ->
                if (!file.isFile) return@filter false
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                rel != "project.json" && !rel.startsWith("build/") && !rel.startsWith(".droidforge/")
            }
            .map { file ->
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                val isBin = isBinaryPath(rel)
                ProjectFile(
                    relativePath = rel,
                    content = if (isBin) {
                        "Binary asset (${file.length()} bytes)\nPath: $rel\nBundled automatically inside APK/AAB assets."
                    } else {
                        runCatching { file.readText() }.getOrDefault("")
                    },
                    language = detectLanguage(rel),
                    isBinary = isBin,
                )
            }
            .sortedWith(
                compareBy<ProjectFile>(
                    { if (it.fileName == "MainActivity.java" || it.fileName == "MainActivity.kt") 0 else 1 },
                    { if (it.relativePath.startsWith("src/")) 0 else 1 },
                    { it.relativePath },
                ),
            )
            .toList()
    }

    fun resolveProjectFile(projectId: String, relativePath: String): File? {
        val dir = getProjectDir(projectId).canonicalFile
        val cleanRel = relativePath.trim().replace('\\', '/').trimStart('/')
        if (cleanRel.isEmpty()) return null
        val candidate = File(dir, cleanRel).canonicalFile
        val dirPrefix = dir.path + File.separator
        if (candidate.path != dir.path && !candidate.path.startsWith(dirPrefix)) {
            return null
        }
        return candidate
    }

    suspend fun listAllProjectPaths(
        projectId: String,
        includeBuild: Boolean = false,
    ): List<String> = withContext(Dispatchers.IO) {
        val dir = getProjectDir(projectId)
        if (!dir.exists()) return@withContext emptyList()
        dir.walkTopDown()
            .filter { file ->
                if (!file.isFile) return@filter false
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                rel != "project.json" && !rel.startsWith(".droidforge/") && (includeBuild || !rel.startsWith("build/"))
            }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    suspend fun saveFile(projectId: String, relativePath: String, content: String) = withContext(Dispatchers.IO) {
        val target = resolveProjectFile(projectId, relativePath) ?: return@withContext
        target.parentFile?.mkdirs()
        target.writeText(content)
        val dir = getProjectDir(projectId)
        readMetadata(dir)?.let { meta ->
            writeMetadata(dir, meta.copy(updatedAtMillis = System.currentTimeMillis()))
        }
    }

    suspend fun saveBinaryFile(projectId: String, relativePath: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = resolveProjectFile(projectId, relativePath) ?: return@withContext
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        val dir = getProjectDir(projectId)
        readMetadata(dir)?.let { meta ->
            writeMetadata(dir, meta.copy(updatedAtMillis = System.currentTimeMillis()))
        }
    }

    suspend fun deleteFile(projectId: String, relativePath: String) = withContext(Dispatchers.IO) {
        val target = resolveProjectFile(projectId, relativePath) ?: return@withContext
        if (target.exists() && target.isFile) target.delete()
    }

    suspend fun exportProjectZip(projectId: String, outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val dir = getProjectDir(projectId)
        ZipOutputStream(outputStream.buffered()).use { zos ->
            dir.walkTopDown()
                .filter { file ->
                    if (!file.isFile) return@filter false
                    val rel = file.relativeTo(dir).invariantSeparatorsPath
                    !rel.startsWith("build/") && !rel.startsWith(".droidforge/")
                }
                .forEach { file ->
                    val rel = file.relativeTo(dir).invariantSeparatorsPath
                    zos.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }

    suspend fun recordBuildResult(
        projectId: String,
        success: Boolean,
        apkSizeBytes: Long,
        variant: BuildVariant = BuildVariant.DEBUG_APK,
    ) = withContext(Dispatchers.IO) {
        val dir = getProjectDir(projectId)
        readMetadata(dir)?.let { meta ->
            writeMetadata(
                dir,
                meta.copy(
                    updatedAtMillis = System.currentTimeMillis(),
                    lastBuildSuccess = success,
                    lastApkSizeBytes = if (success) apkSizeBytes else meta.lastApkSizeBytes,
                    lastBuildTimestamp = System.currentTimeMillis(),
                    lastBuildVariant = if (success) variant else meta.lastBuildVariant,
                ),
            )
        }
    }

    fun detectLanguage(path: String): FileLanguage = when {
        path.endsWith(".java", ignoreCase = true) -> FileLanguage.JAVA
        path.endsWith(".kt", ignoreCase = true) -> FileLanguage.KOTLIN
        path.endsWith(".xml", ignoreCase = true) -> FileLanguage.XML
        path.endsWith(".json", ignoreCase = true) -> FileLanguage.JSON
        else -> FileLanguage.TEXT
    }

    fun isBinaryPath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in BINARY_EXTENSIONS
    }

    private fun writeMetadata(dir: File, meta: ProjectMetadata) {
        val obj = JSONObject().apply {
            put("id", meta.id)
            put("name", meta.name)
            put("packageName", meta.packageName)
            put("template", meta.template.name)
            put("createdAtMillis", meta.createdAtMillis)
            put("updatedAtMillis", meta.updatedAtMillis)
            put("language", meta.language.name)
            put("javaVersion", meta.javaVersion.majorVersion)
            put("minSdkVersion", meta.minSdkVersion)
            meta.githubLink?.let { link ->
                put(
                    "githubLink",
                    JSONObject().apply {
                        put("owner", link.owner)
                        put("repo", link.repo)
                        put("branch", link.branch)
                        put("lastCommitSha", link.lastCommitSha)
                        put("lastPushedAtMillis", link.lastPushedAtMillis)
                        val hashesObj = JSONObject()
                        link.lastPushedHashes.forEach { (k, v) -> hashesObj.put(k, v) }
                        put("lastPushedHashes", hashesObj)
                    },
                )
            }
            meta.lastBuildSuccess?.let { put("lastBuildSuccess", it) }
            meta.lastApkSizeBytes?.let { put("lastApkSizeBytes", it) }
            meta.lastBuildTimestamp?.let { put("lastBuildTimestamp", it) }
            meta.lastBuildVariant?.let { put("lastBuildVariant", it.name) }
        }
        File(dir, "project.json").writeText(obj.toString(2))
    }

    private fun readMetadata(dir: File): ProjectMetadata? {
        val metaFile = File(dir, "project.json")
        if (!metaFile.exists()) return null
        return runCatching {
            val obj = JSONObject(metaFile.readText())
            val variant = obj.optString("lastBuildVariant").takeIf { it.isNotBlank() }?.let {
                runCatching { BuildVariant.valueOf(it) }.getOrNull()
            }
            val language = runCatching {
                ProjectLanguage.valueOf(obj.optString("language", ProjectLanguage.JAVA.name))
            }.getOrDefault(ProjectLanguage.JAVA)
            val javaVersion = JavaVersionOption.fromMajor(obj.optInt("javaVersion", 17))
            val minSdk = obj.optInt("minSdkVersion", 24).coerceIn(21, 35)
            val ghObj = obj.optJSONObject("githubLink")
            val githubLink = ghObj?.let { g ->
                val hashes = linkedMapOf<String, String>()
                val hObj = g.optJSONObject("lastPushedHashes")
                hObj?.keys()?.forEach { k -> hashes[k] = hObj.optString(k) }
                val owner = g.optString("owner")
                val repo = g.optString("repo")
                if (owner.isNotBlank() && repo.isNotBlank()) {
                    GitHubRepoLink(
                        owner = owner,
                        repo = repo,
                        branch = g.optString("branch", "main").ifBlank { "main" },
                        lastCommitSha = g.optString("lastCommitSha", ""),
                        lastPushedAtMillis = g.optLong("lastPushedAtMillis", 0L),
                        lastPushedHashes = hashes,
                    )
                } else null
            }
            ProjectMetadata(
                id = obj.getString("id"),
                name = obj.getString("name"),
                packageName = obj.getString("packageName"),
                template = ProjectTemplate.valueOf(
                    obj.optString("template", ProjectTemplate.BLANK_ACTIVITY.name),
                ),
                createdAtMillis = obj.optLong("createdAtMillis", dir.lastModified()),
                updatedAtMillis = obj.optLong("updatedAtMillis", dir.lastModified()),
                language = language,
                javaVersion = javaVersion,
                minSdkVersion = minSdk,
                githubLink = githubLink,
                lastBuildSuccess = if (obj.has("lastBuildSuccess")) obj.getBoolean("lastBuildSuccess") else null,
                lastApkSizeBytes = if (obj.has("lastApkSizeBytes")) obj.getLong("lastApkSizeBytes") else null,
                lastBuildTimestamp = if (obj.has("lastBuildTimestamp")) obj.getLong("lastBuildTimestamp") else null,
                lastBuildVariant = variant,
            )
        }.getOrNull()
    }

    companion object {
        const val METADATA_FILE = "project.json"
        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico",
            "ttf", "otf", "woff", "woff2",
            "mp3", "wav", "ogg", "mp4", "webm",
            "bin", "so", "jar", "dex", "apk", "aab", "zip",
        )
    }
}
