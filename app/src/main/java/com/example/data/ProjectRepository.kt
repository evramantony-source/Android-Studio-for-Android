package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ai.AiModelsCatalog
import com.example.ai.AiProvider
import com.example.ai.AiUsageSummary
import com.example.ai.GEMINI_MODEL_ID
import com.example.ai.ProviderUsageStats
import com.example.ai.TokenUsageDelta
import com.example.model.BuildVariant
import com.example.model.EditorSettings
import com.example.model.FileLanguage
import com.example.model.ProjectFile
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
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

        val AI_USAGE_JSON = stringPreferencesKey("ai_usage_summary_json")
        val RELEASE_SIGNING_JSON = stringPreferencesKey("release_signing_json")
        val BUILD_VARIANT = stringPreferencesKey("preferred_build_variant")
    }

    val activeProvider: Flow<AiProvider> = context.editorDataStore.data.map { prefs ->
        runCatching { AiProvider.valueOf(prefs[PrefKeys.ACTIVE_PROVIDER].orEmpty()) }
            .getOrDefault(AiProvider.GEMINI)
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
            AiProvider.GEMINI to (prefs[PrefKeys.GEMINI_MODEL_ID_KEY]?.takeIf { it.isNotBlank() } ?: GEMINI_MODEL_ID),
            AiProvider.OPENAI to (prefs[PrefKeys.OPENAI_MODEL_ID_KEY]?.takeIf { it.isNotBlank() } ?: AiProvider.OPENAI.defaultModelId),
            AiProvider.CLAUDE to (prefs[PrefKeys.CLAUDE_MODEL_ID_KEY]?.takeIf { it.isNotBlank() } ?: AiProvider.CLAUDE.defaultModelId),
        )
    }

    val customGeminiApiKey: Flow<String> = context.editorDataStore.data.map { prefs ->
        prefs[PrefKeys.CUSTOM_GEMINI_API_KEY].orEmpty()
    }

    val geminiModelId: Flow<String> = context.editorDataStore.data.map { prefs ->
        prefs[PrefKeys.GEMINI_MODEL_ID_KEY]?.takeIf { it.isNotBlank() } ?: GEMINI_MODEL_ID
    }

    val aiUsageSummary: Flow<AiUsageSummary> = context.editorDataStore.data.map { prefs ->
        AiUsageSummary.fromJson(prefs[PrefKeys.AI_USAGE_JSON].orEmpty())
    }

    val preferredBuildVariant: Flow<BuildVariant> = context.editorDataStore.data.map { prefs ->
        runCatching { BuildVariant.valueOf(prefs[PrefKeys.BUILD_VARIANT].orEmpty()) }
            .getOrDefault(BuildVariant.DEBUG_APK)
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

    suspend fun saveProviderConfig(provider: AiProvider, apiKey: String, modelId: String) {
        context.editorDataStore.edit { prefs ->
            val cleanedKey = apiKey.trim()
            val keyPref = when (provider) {
                AiProvider.GEMINI -> PrefKeys.CUSTOM_GEMINI_API_KEY
                AiProvider.OPENAI -> PrefKeys.OPENAI_API_KEY
                AiProvider.CLAUDE -> PrefKeys.CLAUDE_API_KEY
            }
            if (cleanedKey.isEmpty()) prefs.remove(keyPref) else prefs[keyPref] = cleanedKey

            val cleanedModel = modelId.trim().removePrefix("models/")
            val modelPref = when (provider) {
                AiProvider.GEMINI -> PrefKeys.GEMINI_MODEL_ID_KEY
                AiProvider.OPENAI -> PrefKeys.OPENAI_MODEL_ID_KEY
                AiProvider.CLAUDE -> PrefKeys.CLAUDE_MODEL_ID_KEY
            }
            if (cleanedModel.isEmpty()) prefs.remove(modelPref) else prefs[modelPref] = cleanedModel
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
            val cleaned = modelId.trim().removePrefix("models/")
            if (cleaned.isEmpty()) prefs.remove(PrefKeys.GEMINI_MODEL_ID_KEY)
            else prefs[PrefKeys.GEMINI_MODEL_ID_KEY] = cleaned
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
    ): ProjectMetadata = withContext(Dispatchers.IO) {
        createProjectInternal(name, packageName, template)
    }

    private fun createProjectInternal(
        name: String,
        packageName: String,
        template: ProjectTemplate,
    ): ProjectMetadata {
        val id = UUID.randomUUID().toString().take(8)
        val projectDir = File(projectsRoot, id).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val files = TemplateProvider.generateFiles(template, name, packageName)
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
        )
        writeMetadata(projectDir, meta)
        return meta
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
                ProjectFile(
                    relativePath = rel,
                    content = file.readText(),
                    language = detectLanguage(rel),
                )
            }
            .sortedWith(
                compareBy<ProjectFile>(
                    { if (it.fileName == "MainActivity.java") 0 else 1 },
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
        path.endsWith(".xml", ignoreCase = true) -> FileLanguage.XML
        path.endsWith(".json", ignoreCase = true) -> FileLanguage.JSON
        else -> FileLanguage.TEXT
    }

    private fun writeMetadata(dir: File, meta: ProjectMetadata) {
        val obj = JSONObject().apply {
            put("id", meta.id)
            put("name", meta.name)
            put("packageName", meta.packageName)
            put("template", meta.template.name)
            put("createdAtMillis", meta.createdAtMillis)
            put("updatedAtMillis", meta.updatedAtMillis)
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
            ProjectMetadata(
                id = obj.getString("id"),
                name = obj.getString("name"),
                packageName = obj.getString("packageName"),
                template = ProjectTemplate.valueOf(
                    obj.optString("template", ProjectTemplate.BLANK_ACTIVITY.name),
                ),
                createdAtMillis = obj.optLong("createdAtMillis", dir.lastModified()),
                updatedAtMillis = obj.optLong("updatedAtMillis", dir.lastModified()),
                lastBuildSuccess = if (obj.has("lastBuildSuccess")) obj.getBoolean("lastBuildSuccess") else null,
                lastApkSizeBytes = if (obj.has("lastApkSizeBytes")) obj.getLong("lastApkSizeBytes") else null,
                lastBuildTimestamp = if (obj.has("lastBuildTimestamp")) obj.getLong("lastBuildTimestamp") else null,
                lastBuildVariant = variant,
            )
        }.getOrNull()
    }
}
