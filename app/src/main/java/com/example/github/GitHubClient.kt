package com.example.github

import com.example.data.ProjectRepository
import com.example.model.GitHubRepoLink
import com.example.model.ProjectLanguage
import com.example.model.ProjectMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class GitHubUser(
    val login: String,
    val name: String,
    val publicRepos: Int,
    val privateRepos: Int,
)

data class GitHubRepoSummary(
    val owner: String,
    val name: String,
    val fullName: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val htmlUrl: String,
)

data class GitHubTreeEntry(
    val path: String,
    val type: String, // "blob" or "tree"
    val size: Long,
    val sha: String,
)

data class GitHubWorkflowRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String,
    val htmlUrl: String,
    val createdAt: String,
)

data class GitHubPushResult(
    val commitSha: String,
    val pushedFileCount: Int,
    val branch: String,
    val htmlUrl: String,
    val uploadedHashes: Map<String, String>,
)

object GitHubClient {

    private const val API_ROOT = "https://api.github.com"
    private const val TIMEOUT_MS = 25_000

    suspend fun verifyToken(token: String): Result<GitHubUser> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = token.trim()
            require(cleanToken.isNotBlank()) { "GitHub token is empty." }
            val resp = requestJson("GET", "/user", cleanToken)
            val login = resp.optString("login")
            require(login.isNotBlank()) { "Invalid GitHub user response." }
            GitHubUser(
                login = login,
                name = resp.optString("name").ifBlank { login },
                publicRepos = resp.optInt("public_repos", 0),
                privateRepos = resp.optInt("total_private_repos", 0),
            )
        }
    }

    suspend fun listRepos(token: String): Result<List<GitHubRepoSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = token.trim()
            require(cleanToken.isNotBlank()) { "Connect a GitHub token first." }
            val arr = requestJsonArray("GET", "/user/repos?sort=updated&per_page=50", cleanToken)
            val list = mutableListOf<GitHubRepoSummary>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ownerObj = obj.optJSONObject("owner")
                val ownerLogin = ownerObj?.optString("login").orEmpty()
                val repoName = obj.optString("name")
                if (ownerLogin.isBlank() || repoName.isBlank()) continue
                list.add(
                    GitHubRepoSummary(
                        owner = ownerLogin,
                        name = repoName,
                        fullName = obj.optString("full_name", "$ownerLogin/$repoName"),
                        defaultBranch = obj.optString("default_branch", "main").ifBlank { "main" },
                        isPrivate = obj.optBoolean("private", false),
                        htmlUrl = obj.optString("html_url", "https://github.com/$ownerLogin/$repoName"),
                    ),
                )
            }
            list
        }
    }

    suspend fun createRepo(
        token: String,
        name: String,
        description: String,
        isPrivate: Boolean,
    ): Result<GitHubRepoSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = token.trim()
            val cleanName = name.trim().replace(Regex("[^a-zA-Z0-9._-]"), "-")
            require(cleanName.isNotBlank()) { "Repository name is required." }
            val payload = JSONObject()
                .put("name", cleanName)
                .put("description", description.trim())
                .put("private", isPrivate)
                .put("auto_init", true)
            val obj = requestJson("POST", "/user/repos", cleanToken, payload)
            val ownerLogin = obj.optJSONObject("owner")?.optString("login").orEmpty()
            val repoName = obj.optString("name", cleanName)
            GitHubRepoSummary(
                owner = ownerLogin,
                name = repoName,
                fullName = obj.optString("full_name", "$ownerLogin/$repoName"),
                defaultBranch = obj.optString("default_branch", "main").ifBlank { "main" },
                isPrivate = obj.optBoolean("private", isPrivate),
                htmlUrl = obj.optString("html_url", "https://github.com/$ownerLogin/$repoName"),
            )
        }
    }

    suspend fun listRepoFiles(
        token: String,
        owner: String,
        repo: String,
        branch: String,
    ): Result<List<GitHubTreeEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedBranch = URLEncoder.encode(branch.ifBlank { "main" }, "UTF-8")
            val resp = requestJson("GET", "/repos/$owner/$repo/git/trees/$encodedBranch?recursive=1", token)
            val arr = resp.optJSONArray("tree") ?: JSONArray()
            val entries = mutableListOf<GitHubTreeEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val path = obj.optString("path")
                if (path.isBlank()) continue
                entries.add(
                    GitHubTreeEntry(
                        path = path,
                        type = obj.optString("type", "blob"),
                        size = obj.optLong("size", 0L),
                        sha = obj.optString("sha"),
                    ),
                )
            }
            entries
        }
    }

    suspend fun readRepoFile(
        token: String,
        owner: String,
        repo: String,
        path: String,
        branch: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPath = path.trim().trimStart('/')
            val encodedBranch = URLEncoder.encode(branch.ifBlank { "main" }, "UTF-8")
            val resp = requestJson("GET", "/repos/$owner/$repo/contents/$cleanPath?ref=$encodedBranch", token)
            val contentB64 = resp.optString("content").replace("\n", "").replace("\r", "")
            val bytes = decodeBase64(contentB64)
            bytes.toString(Charsets.UTF_8)
        }
    }

    suspend fun pullRepository(
        token: String,
        link: GitHubRepoLink,
        targetDir: File,
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val entries = listRepoFiles(token, link.owner, link.repo, link.branch).getOrThrow()
            val pulledHashes = linkedMapOf<String, String>()
            val blobs = entries.filter { it.type == "blob" && !it.path.startsWith(".git/") }
            for (entry in blobs.take(200)) {
                if (entry.size > 4_000_000L) continue
                val encodedBranch = URLEncoder.encode(link.branch, "UTF-8")
                val resp = requestJson(
                    "GET",
                    "/repos/${link.owner}/${link.repo}/contents/${entry.path}?ref=$encodedBranch",
                    token,
                )
                val b64 = resp.optString("content").replace("\n", "").replace("\r", "")
                val rawBytes = if (b64.isNotEmpty()) decodeBase64(b64) else ByteArray(0)
                val dest = File(targetDir, entry.path)
                dest.parentFile?.mkdirs()
                dest.writeBytes(rawBytes)
                pulledHashes[entry.path] = sha256Hex(rawBytes)
            }
            pulledHashes
        }
    }

    suspend fun pushProject(
        token: String,
        link: GitHubRepoLink,
        projectDir: File,
        commitMessage: String,
    ): Result<GitHubPushResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = token.trim()
            require(cleanToken.isNotBlank()) { "GitHub token is missing." }
            val branch = link.branch.ifBlank { "main" }
            val msg = commitMessage.trim().ifBlank { "Update from DroidForge Studio" }

            val allFiles = collectPushableFiles(projectDir)
            require(allFiles.isNotEmpty()) { "No project files to push." }

            // Check branch reference; initialize empty repository if needed
            val refPath = "/repos/${link.owner}/${link.repo}/git/ref/heads/${URLEncoder.encode(branch, "UTF-8")}"
            val headCommitSha = runCatching {
                val refObj = requestJson("GET", refPath, cleanToken)
                refObj.getJSONObject("object").getString("sha")
            }.getOrElse {
                val initPayload = JSONObject()
                    .put("message", "Initialize repository")
                    .put("content", encodeBase64("# ${link.repo}\n".toByteArray(Charsets.UTF_8)))
                    .put("branch", branch)
                val initResp = requestJson("PUT", "/repos/${link.owner}/${link.repo}/contents/README.md", cleanToken, initPayload)
                initResp.getJSONObject("commit").getString("sha")
            }

            val headCommit = requestJson("GET", "/repos/${link.owner}/${link.repo}/git/commits/$headCommitSha", cleanToken)
            val baseTreeSha = headCommit.getJSONObject("tree").getString("sha")

            val treeArray = JSONArray()
            val newHashes = linkedMapOf<String, String>()
            for ((relPath, file) in allFiles) {
                val bytes = file.readBytes()
                newHashes[relPath] = sha256Hex(bytes)
                val blobReq = JSONObject()
                    .put("content", encodeBase64(bytes))
                    .put("encoding", "base64")
                val blobResp = requestJson("POST", "/repos/${link.owner}/${link.repo}/git/blobs", cleanToken, blobReq)
                val blobSha = blobResp.getString("sha")
                treeArray.put(
                    JSONObject()
                        .put("path", relPath)
                        .put("mode", "100644")
                        .put("type", "blob")
                        .put("sha", blobSha),
                )
            }

            val treeReq = JSONObject()
                .put("base_tree", baseTreeSha)
                .put("tree", treeArray)
            val treeResp = requestJson("POST", "/repos/${link.owner}/${link.repo}/git/trees", cleanToken, treeReq)
            val newTreeSha = treeResp.getString("sha")

            val commitReq = JSONObject()
                .put("message", msg)
                .put("tree", newTreeSha)
                .put("parents", JSONArray().put(headCommitSha))
            val commitResp = requestJson("POST", "/repos/${link.owner}/${link.repo}/git/commits", cleanToken, commitReq)
            val newCommitSha = commitResp.getString("sha")

            val patchReq = JSONObject()
                .put("sha", newCommitSha)
                .put("force", false)
            requestJson("PATCH", "/repos/${link.owner}/${link.repo}/git/refs/heads/${URLEncoder.encode(branch, "UTF-8")}", cleanToken, patchReq)

            GitHubPushResult(
                commitSha = newCommitSha,
                pushedFileCount = allFiles.size,
                branch = branch,
                htmlUrl = "${link.htmlUrl}/commit/$newCommitSha",
                uploadedHashes = newHashes,
            )
        }
    }

    fun scaffoldGradleAndActions(projectDir: File, meta: ProjectMetadata): List<String> {
        val written = mutableListOf<String>()

        fun writeText(relPath: String, text: String) {
            val f = File(projectDir, relPath)
            f.parentFile?.mkdirs()
            f.writeText(text)
            written.add(relPath)
        }

        val cleanName = meta.name.replace("\"", "").ifBlank { "DroidForgeApp" }
        writeText(
            "settings.gradle.kts",
            buildString {
                appendLine("pluginManagement {")
                appendLine("    repositories { google(); mavenCentral(); gradlePluginPortal() }")
                appendLine("}")
                appendLine("dependencyResolutionManagement {")
                appendLine("    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)")
                appendLine("    repositories { google(); mavenCentral() }")
                appendLine("}")
                appendLine("rootProject.name = \"$cleanName\"")
                appendLine("include(\":app\")")
            },
        )

        val kotlinPluginLine = if (meta.language == ProjectLanguage.KOTLIN) {
            "    id(\"org.jetbrains.kotlin.android\") version \"2.1.0\" apply false"
        } else {
            ""
        }
        writeText(
            "build.gradle.kts",
            buildString {
                appendLine("plugins {")
                appendLine("    id(\"com.android.application\") version \"8.7.3\" apply false")
                if (kotlinPluginLine.isNotEmpty()) appendLine(kotlinPluginLine)
                appendLine("}")
            },
        )

        writeText(
            "gradle.properties",
            buildString {
                appendLine("org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8")
                appendLine("android.useAndroidX=false")
                appendLine("android.nonTransitiveRClass=true")
            },
        )

        writeText("app/build.gradle.kts", buildAppGradleKts(meta))
        writeText(".github/workflows/droidforge-build.yml", buildWorkflowYaml(meta))
        return written
    }

    fun buildAppGradleKts(meta: ProjectMetadata): String {
        val jdkMajor = meta.javaVersion.majorVersion
        val jvmVersionToken = when (jdkMajor) {
            8 -> "JavaVersion.VERSION_1_8"
            11 -> "JavaVersion.VERSION_11"
            17 -> "JavaVersion.VERSION_17"
            21 -> "JavaVersion.VERSION_21"
            else -> "JavaVersion.toVersion($jdkMajor)"
        }
        return buildString {
            appendLine("plugins {")
            appendLine("    id(\"com.android.application\")")
            if (meta.language == ProjectLanguage.KOTLIN) {
                appendLine("    id(\"org.jetbrains.kotlin.android\")")
            }
            appendLine("}")
            appendLine("")
            appendLine("android {")
            appendLine("    namespace = \"${meta.packageName}\"")
            appendLine("    compileSdk = 35")
            appendLine("    defaultConfig {")
            appendLine("        applicationId = \"${meta.packageName}\"")
            appendLine("        minSdk = ${meta.minSdkVersion}")
            appendLine("        targetSdk = 35")
            appendLine("        versionCode = 1")
            appendLine("        versionName = \"1.0\"")
            appendLine("        multiDexEnabled = true")
            appendLine("    }")
            appendLine("    sourceSets {")
            appendLine("        getByName(\"main\") {")
            appendLine("            manifest.srcFile(\"../AndroidManifest.xml\")")
            appendLine("            java.srcDirs(\"../src\")")
            appendLine("            res.srcDirs(\"../res\")")
            appendLine("            assets.srcDirs(\"../assets\")")
            appendLine("            jniLibs.srcDirs(\"../lib\", \"../jniLibs\")")
            appendLine("        }")
            appendLine("    }")
            appendLine("    compileOptions {")
            appendLine("        sourceCompatibility = $jvmVersionToken")
            appendLine("        targetCompatibility = $jvmVersionToken")
            appendLine("    }")
            if (meta.language == ProjectLanguage.KOTLIN) {
                appendLine("    kotlinOptions {")
                appendLine("        jvmTarget = \"${if (jdkMajor > 21) 21 else jdkMajor}\"")
                appendLine("    }")
            }
            appendLine("}")
            appendLine("")
            appendLine("dependencies {")
            appendLine("    implementation(fileTree(mapOf(\"dir\" to \"../libs\", \"include\" to listOf(\"*.jar\"))))")
            appendLine("}")
        }
    }

    fun buildWorkflowYaml(meta: ProjectMetadata): String {
        val jdk = meta.javaVersion.majorVersion
        return buildString {
            appendLine("name: DroidForge Android Cloud CI")
            appendLine("")
            appendLine("on:")
            appendLine("  workflow_dispatch:")
            appendLine("  push:")
            appendLine("    branches: [\"main\", \"master\"]")
            appendLine("")
            appendLine("jobs:")
            appendLine("  android-build:")
            appendLine("    runs-on: ubuntu-latest")
            appendLine("    steps:")
            appendLine("      - uses: actions/checkout@v4")
            appendLine("      - uses: actions/setup-java@v4")
            appendLine("        with:")
            appendLine("          distribution: temurin")
            appendLine("          java-version: '$jdk'")
            appendLine("      - uses: gradle/actions/setup-gradle@v4")
            appendLine("        with:")
            appendLine("          gradle-version: '8.11.1'")
            appendLine("      - name: Compile Debug APK and Release AAB")
            appendLine("        run: gradle :app:assembleDebug :app:bundleRelease --stacktrace")
            appendLine("      - uses: actions/upload-artifact@v4")
            appendLine("        with:")
            appendLine("          name: droidforge-outputs")
            appendLine("          path: app/build/outputs/**")
        }
    }

    suspend fun triggerWorkflow(token: String, link: GitHubRepoLink): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = token.trim()
            require(cleanToken.isNotBlank()) { "GitHub token is missing." }
            val body = JSONObject().put("ref", link.branch.ifBlank { "main" })
            requestNoContent(
                "POST",
                "/repos/${link.owner}/${link.repo}/actions/workflows/droidforge-build.yml/dispatches",
                cleanToken,
                body,
            )
            "${link.htmlUrl}/actions"
        }
    }

    suspend fun listWorkflowRuns(token: String, link: GitHubRepoLink): Result<List<GitHubWorkflowRun>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = requestJson("GET", "/repos/${link.owner}/${link.repo}/actions/runs?per_page=10", token)
            val arr = resp.optJSONArray("workflow_runs") ?: JSONArray()
            val runs = mutableListOf<GitHubWorkflowRun>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                runs.add(
                    GitHubWorkflowRun(
                        id = obj.optLong("id"),
                        name = obj.optString("name", "Android CI"),
                        status = obj.optString("status", "unknown"),
                        conclusion = obj.optString("conclusion"),
                        htmlUrl = obj.optString("html_url"),
                        createdAt = obj.optString("created_at"),
                    ),
                )
            }
            runs
        }
    }

    private fun collectPushableFiles(projectDir: File): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        projectDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(projectDir).path.replace(File.separatorChar, '/')
                val skip = rel.startsWith("build/") ||
                    rel.startsWith(".droidforge/") ||
                    rel.startsWith(".git/") ||
                    rel == ProjectRepository.METADATA_FILE ||
                    file.length() > 40_000_000L
                if (!skip) {
                    result.add(rel to file)
                }
            }
        return result.sortedBy { it.first }
    }

    private fun requestJson(method: String, path: String, token: String, body: JSONObject? = null): JSONObject {
        val raw = executeHttp(method, path, token, body)
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun requestJsonArray(method: String, path: String, token: String): JSONArray {
        val raw = executeHttp(method, path, token, null)
        return if (raw.isBlank()) JSONArray() else JSONArray(raw)
    }

    private fun requestNoContent(method: String, path: String, token: String, body: JSONObject? = null) {
        executeHttp(method, path, token, body)
    }

    private fun executeHttp(method: String, path: String, token: String, body: JSONObject?): String {
        val url = URL("$API_ROOT$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "DroidForgeStudio/1.0")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val errMsg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "GitHub HTTP $code"
                throw IllegalStateException(errMsg)
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun encodeBase64(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val hasB1 = i + 1 < data.size
            val hasB2 = i + 2 < data.size
            val b1 = if (hasB1) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) data[i + 2].toInt() and 0xFF else 0

            out.append(B64_ALPHABET[b0 ushr 2])
            out.append(B64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            out.append(if (hasB1) B64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
            out.append(if (hasB2) B64_ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    private fun decodeBase64(input: String): ByteArray {
        val clean = input.filter { !it.isWhitespace() }
        if (clean.isEmpty()) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream((clean.length * 3) / 4)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val val6 = B64_ALPHABET.indexOf(ch)
            if (val6 < 0) continue
            buffer = (buffer shl 6) or val6
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
