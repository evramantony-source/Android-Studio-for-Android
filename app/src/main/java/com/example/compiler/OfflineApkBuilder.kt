package com.example.compiler

import android.content.Context
import com.android.apksig.ApkSigner
import com.android.dx.command.dexer.Main as DxMain
import com.example.model.ApkEntryInfo
import com.example.model.BuildDiagnostic
import com.example.model.BuildResult
import com.example.model.BuildVariant
import com.example.model.DiagnosticSeverity
import com.example.model.JavaVersionOption
import com.example.model.ReleaseSigningConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jdt.internal.compiler.batch.Main as EcjMain
import java.io.File
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

class OfflineApkBuilder(
    private val context: Context? = null,
) {
    suspend fun buildProject(
        projectDir: File,
        packageName: String,
        variant: BuildVariant = BuildVariant.DEBUG_APK,
        releaseSigning: ReleaseSigningConfig? = null,
        targetMinSdk: Int? = null,
        targetJavaVersion: JavaVersionOption = JavaVersionOption.JAVA_17,
        onProgress: (String) -> Unit = {},
    ): BuildResult = withContext(Dispatchers.IO) {
        val ctx = requireNotNull(context) { "Android Context is required for asset extraction" }
        val toolchainDir = File(ctx.filesDir, "toolchain").apply { mkdirs() }
        val stubsJar = ensureAssetExtracted(ctx, "android-stubs.jar", File(toolchainDir, "android-stubs.jar"))
        val debugKeystoreFile = ensureAssetExtracted(ctx, "debug_signing.p12", File(toolchainDir, "debug_signing.p12"))

        val activeSigning = if (variant.isRelease && releaseSigning?.isConfigured == true) {
            releaseSigning
        } else {
            null
        }

        val keystoreFile = activeSigning?.keystorePath?.let { File(it) }?.takeIf { it.exists() }
            ?: debugKeystoreFile
        val storePassword = activeSigning?.storePassword ?: "droidforge"
        val keyPassword = activeSigning?.keyPassword?.ifBlank { storePassword } ?: "droidforge"
        val keyAlias = activeSigning?.alias.orEmpty()

        buildWithToolchain(
            projectDir = projectDir,
            packageName = packageName,
            stubsJar = stubsJar,
            keystoreFile = keystoreFile,
            variant = variant,
            storePassword = storePassword,
            keyPassword = keyPassword,
            keyAlias = keyAlias,
            usedReleaseKey = activeSigning != null && keystoreFile != debugKeystoreFile,
            targetMinSdk = targetMinSdk,
            targetJavaVersion = targetJavaVersion,
            onProgress = onProgress,
        )
    }

    fun buildWithToolchain(
        projectDir: File,
        packageName: String,
        stubsJar: File,
        keystoreFile: File,
        variant: BuildVariant = BuildVariant.DEBUG_APK,
        storePassword: String = "droidforge",
        keyPassword: String = "droidforge",
        keyAlias: String = "",
        usedReleaseKey: Boolean = false,
        targetMinSdk: Int? = null,
        targetJavaVersion: JavaVersionOption = JavaVersionOption.JAVA_17,
        onProgress: (String) -> Unit = {},
    ): BuildResult {
        val startMs = System.currentTimeMillis()
        val logs = mutableListOf<String>()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        fun log(msg: String) {
            logs.add(msg)
            onProgress(msg)
        }

        return try {
            val buildDir = File(projectDir, "build")
            if (buildDir.exists()) buildDir.deleteRecursively()
            val genDir = File(buildDir, "gen").apply { mkdirs() }
            val classesDir = File(buildDir, "classes").apply { mkdirs() }
            val dexDir = File(buildDir, "dex").apply { mkdirs() }
            val outDir = File(
                buildDir,
                if (variant.isBundle) "outputs/bundle" else "outputs/apk",
            ).apply { mkdirs() }

            log("▶ Target variant: ${variant.label} • Toolchain: ${targetJavaVersion.label}")
            if (variant.isRelease && !usedReleaseKey) {
                log("⚠ No custom Release Keystore selected; using local fallback signing key. Create a Release Keystore in Keystore Manager for Play Store uploads.")
            }

            // 1. Compile XML resources -> R.java + resources.arsc (+ layouts/ids/drawables + proto for AAB)
            log("[1/5] Compiling resources (strings, colors, layouts, drawables)...")
            val stringsFile = File(projectDir, "res/values/strings.xml")
            val colorsFile = File(projectDir, "res/values/colors.xml")
            val layoutDir = File(projectDir, "res/layout")
            val layoutFiles = if (layoutDir.exists() && layoutDir.isDirectory) {
                layoutDir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml", ignoreCase = true) }
                    ?.sortedBy { it.name }
                    ?.map { it.name to it.readText() }
                    .orEmpty()
            } else emptyList()
            val drawableDir = File(projectDir, "res/drawable")
            val drawableFiles = if (drawableDir.exists() && drawableDir.isDirectory) {
                drawableDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
            } else emptyList()

            val compiledRes = ResourceTableCompiler.compile(
                packageName = packageName,
                stringsXml = stringsFile.takeIf { it.exists() }?.readText(),
                colorsXml = colorsFile.takeIf { it.exists() }?.readText(),
                layoutFiles = layoutFiles,
                drawableNames = drawableFiles.map { it.name },
            )
            val rFileDir = File(genDir, packageName.replace('.', '/')).apply { mkdirs() }
            File(rFileDir, "R.java").writeText(compiledRes.rJavaSource)
            log("      R.java: ${compiledRes.stringCount} strings, ${compiledRes.colorCount} colors, ${compiledRes.layoutCount} layouts, ${compiledRes.idCount} ids")

            // 2. Encode AndroidManifest.xml -> binary AXML (or proto XmlNode for AAB)
            val manifestFile = File(projectDir, "AndroidManifest.xml")
            if (!manifestFile.exists()) {
                diagnostics.add(
                    BuildDiagnostic("AndroidManifest.xml", 1, 1, DiagnosticSeverity.ERROR, "Missing AndroidManifest.xml in project root."),
                )
                return BuildResult(
                    success = false,
                    variant = variant,
                    durationMs = System.currentTimeMillis() - startMs,
                    logs = logs,
                    diagnostics = diagnostics,
                )
            }
            val rawManifestXml = manifestFile.readText()
            val manifestPrep = prepareManifestXml(
                rawManifestXml = rawManifestXml,
                packageName = packageName,
                isRelease = variant.isRelease,
                resourceIds = compiledRes.resourceIds,
                fallbackMinSdk = targetMinSdk ?: 24,
            )
            manifestPrep.warnings.forEach { log("⚠ $it") }
            if (manifestPrep.errors.isNotEmpty()) {
                manifestPrep.errors.forEach { msg ->
                    log("✖ AndroidManifest.xml: $msg")
                    diagnostics.add(
                        BuildDiagnostic("AndroidManifest.xml", 1, 1, DiagnosticSeverity.ERROR, msg),
                    )
                }
                return BuildResult(
                    success = false,
                    variant = variant,
                    durationMs = System.currentTimeMillis() - startMs,
                    logs = logs,
                    diagnostics = diagnostics,
                )
            }
            val preparedManifestXml = manifestPrep.xml
            val manifestMinSdk = manifestPrep.minSdkVersion

            val manifestBytes = if (variant.isBundle) {
                log("[2/5] Encoding Proto AndroidManifest.xml & resources.pb for AAB...")
                ProtobufWriter.encodeProtoXml(
                    xmlText = preparedManifestXml,
                    androidAttrIds = BinaryXmlEncoder.ANDROID_ATTR_IDS,
                    resourceIds = compiledRes.resourceIds,
                )
            } else {
                log("[2/5] Encoding binary AndroidManifest.xml (AXML)...")
                BinaryXmlEncoder.encodeManifest(
                    xmlText = preparedManifestXml,
                    resourceIds = compiledRes.resourceIds,
                )
            }
            log("      Encoded manifest (${manifestBytes.size} bytes, minSdk=$manifestMinSdk)")

            // Encode compiled layout & XML drawable entries
            val encodedResXmlEntries = linkedMapOf<String, ByteArray>()
            layoutFiles.forEach { (name, xml) ->
                val encoded = runCatching {
                    if (variant.isBundle) {
                        ProtobufWriter.encodeProtoXml(xml, BinaryXmlEncoder.ANDROID_ATTR_IDS, compiledRes.resourceIds)
                    } else {
                        BinaryXmlEncoder.encodeManifest(xml, compiledRes.resourceIds)
                    }
                }.getOrNull()
                if (encoded != null) {
                    encodedResXmlEntries["res/layout/$name"] = encoded
                }
            }
            drawableFiles.forEach { file ->
                val rel = "res/drawable/${file.name}"
                if (file.name.endsWith(".xml", ignoreCase = true)) {
                    val encoded = runCatching {
                        val xml = file.readText()
                        if (variant.isBundle) {
                            ProtobufWriter.encodeProtoXml(xml, BinaryXmlEncoder.ANDROID_ATTR_IDS, compiledRes.resourceIds)
                        } else {
                            BinaryXmlEncoder.encodeManifest(xml, compiledRes.resourceIds)
                        }
                    }.getOrNull()
                    if (encoded != null) encodedResXmlEntries[rel] = encoded
                } else {
                    encodedResXmlEntries[rel] = file.readBytes()
                }
            }

            // 3. Transpile Kotlin (.kt) and modern Java (11/17/21/25) -> Java 8 and compile via ECJ
            val kotlinGenDir = File(buildDir, "kotlin-gen").apply { mkdirs() }
            val javaGenDir = File(buildDir, "java-gen").apply { mkdirs() }
            val ktFiles = collectFilesWithExt(File(projectDir, "src"), ".kt")
            val sourceOriginMap = mutableMapOf<String, Pair<String, Map<Int, Int>>>()
            if (ktFiles.isNotEmpty()) {
                log("[3/5] Transpiling ${ktFiles.size} Kotlin (.kt) source(s) -> Java 8...")
                val rtDir = File(kotlinGenDir, packageName.replace('.', '/')).apply { mkdirs() }
                File(rtDir, "_KtRt.java").writeText(KotlinRuntimeHelper.buildSource(packageName))
                val getterRegex = Regex("""\bval\s+([A-Za-z_]\w*)\s*:\s*[^=\n]+?\s+get\(\)\s*=""")
                val knownGetters = ktFiles.flatMap { f ->
                    getterRegex.findAll(f.readText()).map { it.groupValues[1] }.toList()
                }.toSet()
                ktFiles.forEach { ktFile ->
                    val origRel = ktFile.relativeTo(projectDir).invariantSeparatorsPath
                    val res = KotlinToJavaTranspiler.transpile(
                        kotlinSource = ktFile.readText(),
                        fallbackPackage = packageName,
                        fileName = ktFile.name,
                        runtimePackage = packageName,
                        knownPropertyGetters = knownGetters,
                    )
                    val outJavaFile = File(
                        File(kotlinGenDir, res.packageName.replace('.', '/')).apply { mkdirs() },
                        "${res.primaryClassName}.java",
                    )
                    outJavaFile.writeText(res.javaSource)
                    val genRel = outJavaFile.relativeTo(projectDir).invariantSeparatorsPath
                    sourceOriginMap[genRel] = origRel to res.lineMap
                }
            }

            val rawJavaFiles = collectFilesWithExt(File(projectDir, "src"), ".java")
            val preparedJavaFiles = if (targetJavaVersion.majorVersion > 8 && rawJavaFiles.isNotEmpty()) {
                val loweredList = mutableListOf<File>()
                val loweredFeatures = linkedSetOf<String>()
                rawJavaFiles.forEach { jFile ->
                    val origRel = jFile.relativeTo(projectDir).invariantSeparatorsPath
                    val res = ModernJavaTranspiler.transpileToJava8(jFile.readText())
                    if (res.rewrittenFeatures.isNotEmpty()) {
                        loweredFeatures.addAll(res.rewrittenFeatures)
                    }
                    val subRel = jFile.relativeTo(File(projectDir, "src")).invariantSeparatorsPath
                    val outJavaFile = File(javaGenDir, subRel).apply { parentFile?.mkdirs() }
                    outJavaFile.writeText(res.java8Source)
                    val genRel = outJavaFile.relativeTo(projectDir).invariantSeparatorsPath
                    sourceOriginMap[genRel] = origRel to res.lineMap
                    loweredList.add(outJavaFile)
                }
                if (loweredFeatures.isNotEmpty()) {
                    log("      Lowered ${targetJavaVersion.badge} features (${loweredFeatures.joinToString(", ")}) -> DEX bytecode")
                }
                loweredList
            } else {
                rawJavaFiles
            }

            val javaFiles = preparedJavaFiles +
                collectFilesWithExt(kotlinGenDir, ".java") +
                collectFilesWithExt(genDir, ".java")
            if (javaFiles.isEmpty()) {
                diagnostics.add(
                    BuildDiagnostic("src", 1, 1, DiagnosticSeverity.ERROR, "No .java or .kt source files found in src/."),
                )
                return BuildResult(
                    success = false,
                    variant = variant,
                    durationMs = System.currentTimeMillis() - startMs,
                    logs = logs,
                    diagnostics = diagnostics,
                )
            }
            val modeDesc = if (variant.isRelease) "Release" else "Debug"
            log("[3/5] Compiling ${javaFiles.size} source unit(s) (${targetJavaVersion.badge}, $modeDesc)...")

            val extraLibs = collectClasspathJars(projectDir)
            val classpathEntries = (listOf(stubsJar.absolutePath) + extraLibs.map { it.absolutePath })
                .joinToString(File.pathSeparator)

            val ecjOut = StringWriter()
            val ecjErr = StringWriter()
            val ecjArgs = buildList {
                add("-1.8")
                add("-proc:none")
                add("-encoding")
                add("UTF-8")
                if (variant.isRelease) {
                    add("-g:none")
                }
                add("-bootclasspath")
                add(stubsJar.absolutePath)
                add("-classpath")
                add(classpathEntries)
                add("-d")
                add(classesDir.absolutePath)
                javaFiles.forEach { add(it.absolutePath) }
            }.toTypedArray()

            val ecjCompiler = EcjMain(PrintWriter(ecjOut), PrintWriter(ecjErr), false, null, null)
            val ecjOk = ecjCompiler.compile(ecjArgs)
            val combinedEcjOutput = buildString {
                if (ecjOut.toString().isNotBlank()) appendLine(ecjOut.toString().trim())
                if (ecjErr.toString().isNotBlank()) appendLine(ecjErr.toString().trim())
            }.trim()

            if (combinedEcjOutput.isNotEmpty()) {
                diagnostics.addAll(parseEcjDiagnostics(combinedEcjOutput, projectDir, sourceOriginMap))
                combinedEcjOutput.lines().take(30).forEach { line ->
                    if (line.isNotBlank()) log("      $line")
                }
            }

            if (!ecjOk) {
                log("✖ Source compilation failed with ${diagnostics.count { it.severity == DiagnosticSeverity.ERROR }} error(s).")
                if (ktFiles.isNotEmpty()) {
                    log("💡 Tip: For full Gradle kotlinc compilation of advanced Kotlin features, use GitHub Hub -> Cloud Build.")
                }
                return BuildResult(
                    success = false,
                    variant = variant,
                    durationMs = System.currentTimeMillis() - startMs,
                    logs = logs,
                    diagnostics = diagnostics,
                )
            }

            val classFiles = collectFilesWithExt(classesDir, ".class")
            log("      Compiled ${classFiles.size} .class file(s)")

            // 4. Convert .class (+ optional .jar libs) -> Multi-DEX classes*.dex via dalvik-dx
            log("[4/5] Converting JVM bytecode to Dalvik Multi-DEX...")
            val dxInputs = listOf(classesDir.absolutePath) + extraLibs.map { it.absolutePath }
            val dxArgs = DxMain.Arguments().apply {
                multiDex = true
                outName = dexDir.absolutePath
                fileNames = dxInputs.toTypedArray()
                minSdkVersion = manifestMinSdk
                if (variant.isRelease) {
                    optimize = true
                }
                makeOptionsObjects()
            }
            val dxResult = DxMain.run(dxArgs)
            val dexFiles = dexDir.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
                ?.sortedWith(
                    compareBy<File>(
                        { if (it.name == "classes.dex") 0 else 1 },
                        { it.name },
                    ),
                )
                .orEmpty()
            if (dxResult != 0 || dexFiles.isEmpty()) {
                log("✖ DEX conversion failed (exit code $dxResult).")
                return BuildResult(
                    success = false,
                    variant = variant,
                    compiledClassCount = classFiles.size,
                    durationMs = System.currentTimeMillis() - startMs,
                    logs = logs,
                    diagnostics = diagnostics,
                )
            }
            val totalDexBytes = dexFiles.sumOf { it.length() }
            log("      Generated ${dexFiles.size} DEX shard(s) (${totalDexBytes} bytes total)")

            // Collect optional native .so shared libraries (lib/<abi>/*.so or jniLibs/<abi>/*.so)
            val nativeSoFiles = collectNativeLibs(projectDir)
            if (nativeSoFiles.isNotEmpty()) {
                log("      Bundling ${nativeSoFiles.size} native .so library(s)")
            }

            // Compute estimated payload footprint so we warn transparently near OS/Play 4GB cap
            val assetsDir = File(projectDir, "assets")
            val totalAssetBytes = if (assetsDir.exists()) {
                assetsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else 0L
            if (totalAssetBytes > 3_800_000_000L) {
                log("⚠ Large project payload (${totalAssetBytes / (1024 * 1024)} MB). ZIP64 streaming enabled; note Android's on-device PackageInstaller rejects single APKs >4 GB (use AAB or on-demand DLC for >4 GB assets).")
            }

            // 5. Package & Sign (APK or AAB)
            val loadedSigner = KeystoreManager.inspectKeystore(
                keystoreFile = keystoreFile,
                storePassword = storePassword,
                preferredAlias = keyAlias,
                keyPassword = keyPassword,
            )

            val finalArtifact = if (variant.isBundle) {
                log("[5/5] Packaging & signing Android App Bundle (.aab)...")
                val unsignedAab = File(buildDir, "app-unsigned.aab")
                val signedAab = File(outDir, "app-release.aab")
                val bundleConfigBytes = ProtobufWriter.encodeBundleConfig()
                val resourcesPbBytes = ProtobufWriter.encodeResourceTable(
                    packageName = packageName,
                    strings = compiledRes.strings,
                    colors = compiledRes.colors,
                )
                packageUnsignedAab(
                    outputAab = unsignedAab,
                    bundleConfigPb = bundleConfigBytes,
                    resourcesPb = resourcesPbBytes,
                    manifestPb = manifestBytes,
                    dexFiles = dexFiles,
                    resXmlEntries = encodedResXmlEntries,
                    nativeLibs = nativeSoFiles,
                    assetsDir = assetsDir,
                )
                KeystoreManager.signJarArchive(
                    unsignedZip = unsignedAab,
                    signedOutput = signedAab,
                    signer = loadedSigner,
                )
                signedAab
            } else {
                val fileName = if (variant.isRelease) "app-release.apk" else "app-debug.apk"
                log("[5/5] Packaging & signing ${variant.shortLabel} (Scheme v1/v2/v3)...")
                val unsignedApk = File(buildDir, "app-unsigned.apk")
                val signedApk = File(outDir, fileName)
                packageUnsignedApk(
                    outputApk = unsignedApk,
                    resourcesArsc = compiledRes.resourcesArsc,
                    manifestAxml = manifestBytes,
                    dexFiles = dexFiles,
                    resXmlEntries = encodedResXmlEntries,
                    nativeLibs = nativeSoFiles,
                    assetsDir = assetsDir,
                )

                val signerConfig = loadedSigner.toApkSignerConfig()
                ApkSigner.Builder(listOf(signerConfig))
                    .setInputApk(unsignedApk)
                    .setOutputApk(signedApk)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setMinSdkVersion(manifestMinSdk)
                    .build()
                    .sign()
                signedApk
            }

            val entries = inspectZipEntries(finalArtifact)
            val artifactHash = if (finalArtifact.length() < 512L * 1024L * 1024L) {
                KeystoreManager.sha256Hex(finalArtifact)
            } else {
                "large-artifact-${finalArtifact.length()}"
            }
            val elapsed = System.currentTimeMillis() - startMs
            log("✔ ${variant.shortLabel} succeeded in ${elapsed}ms -> ${finalArtifact.name} (${finalArtifact.length()} bytes)")

            BuildResult(
                success = true,
                variant = variant,
                apkPath = finalArtifact.absolutePath,
                apkSizeBytes = finalArtifact.length(),
                compiledClassCount = classFiles.size,
                durationMs = elapsed,
                logs = logs,
                diagnostics = diagnostics,
                apkEntries = entries,
                signerSubject = loadedSigner.subject,
                signerSha256 = loadedSigner.sha256Fingerprint,
                artifactSha256 = artifactHash,
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            logs.add("✖ Build error: ${t.javaClass.simpleName}: ${t.localizedMessage}")
            diagnostics.add(
                BuildDiagnostic("build", 1, 1, DiagnosticSeverity.ERROR, t.localizedMessage ?: "Unexpected build error"),
            )
            BuildResult(
                success = false,
                variant = variant,
                durationMs = System.currentTimeMillis() - startMs,
                logs = logs,
                diagnostics = diagnostics,
            )
        }
    }

    private data class ManifestPreparationResult(
        val xml: String,
        val minSdkVersion: Int,
        val warnings: List<String>,
        val errors: List<String>,
    )

    private fun prepareManifestXml(
        rawManifestXml: String,
        packageName: String,
        isRelease: Boolean,
        resourceIds: Map<String, Int>,
        fallbackMinSdk: Int = 24,
    ): ManifestPreparationResult {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val androidNs = "http://schemas.android.com/apk/res/android"
        val coercedFallback = fallbackMinSdk.coerceIn(21, 35)
        var parsedMinSdk = coercedFallback

        val serialized = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(rawManifestXml.trim())))
            val manifestEl = doc.documentElement

            if (manifestEl.getAttribute("package").isBlank()) {
                manifestEl.setAttribute("package", packageName)
            }
            if (manifestEl.getAttributeNS(androidNs, "versionCode").isBlank()) {
                manifestEl.setAttributeNS(androidNs, "android:versionCode", "1")
            }
            if (manifestEl.getAttributeNS(androidNs, "versionName").isBlank()) {
                manifestEl.setAttributeNS(androidNs, "android:versionName", "1.0")
            }

            // Ensure <uses-sdk> exists so Android 14+ never rejects targetSdk=1
            val usesSdkNodes = manifestEl.getElementsByTagName("uses-sdk")
            if (usesSdkNodes.length == 0) {
                val usesSdk = doc.createElement("uses-sdk")
                usesSdk.setAttributeNS(androidNs, "android:minSdkVersion", coercedFallback.toString())
                usesSdk.setAttributeNS(androidNs, "android:targetSdkVersion", "35")
                manifestEl.insertBefore(usesSdk, manifestEl.firstChild)
                warnings.add("Injected <uses-sdk minSdkVersion=$coercedFallback targetSdkVersion=35> for Android compatibility.")
            } else {
                val el = usesSdkNodes.item(0) as? Element
                if (el != null) {
                    val minStr = el.getAttributeNS(androidNs, "minSdkVersion")
                    parsedMinSdk = minStr.toIntOrNull()?.coerceAtLeast(21) ?: coercedFallback
                    if (minStr.isBlank()) {
                        el.setAttributeNS(androidNs, "android:minSdkVersion", coercedFallback.toString())
                    }
                    if (el.getAttributeNS(androidNs, "targetSdkVersion").isBlank()) {
                        el.setAttributeNS(androidNs, "android:targetSdkVersion", "35")
                    }
                }
            }

            val optionalRefAttrs = setOf(
                "icon",
                "roundIcon",
                "dataExtractionRules",
                "fullBackupContent",
                "networkSecurityConfig",
                "localeConfig",
            )

            fun visit(el: Element) {
                if (el.tagName == "application") {
                    if (isRelease) {
                        el.setAttributeNS(androidNs, "android:debuggable", "false")
                    }
                    val themeAttr = el.getAttributeNS(androidNs, "theme")
                    if (themeAttr.startsWith("@style/")) {
                        val key = themeAttr.removePrefix("@")
                        if (!resourceIds.containsKey(key)) {
                            el.setAttributeNS(
                                androidNs,
                                "android:theme",
                                "@android:style/Theme.Material.Light.DarkActionBar",
                            )
                            warnings.add("Replaced uncompiled manifest theme '$themeAttr' with @android:style/Theme.Material.Light.DarkActionBar.")
                        }
                    }
                }

                val attrsToRemove = mutableListOf<Pair<String?, String>>()
                val attrsToUpdate = mutableListOf<Triple<String, String, String>>()
                val named = el.attributes
                for (i in 0 until named.length) {
                    val node = named.item(i)
                    val ns = node.namespaceURI?.takeIf { it.isNotEmpty() }
                    val local = node.localName ?: node.nodeName.substringAfter(':')
                    val value = node.nodeValue.orEmpty()
                    if (ns == androidNs && value.startsWith("@") && !value.startsWith("@android:")) {
                        val key = value.removePrefix("@")
                        if (!resourceIds.containsKey(key)) {
                            if (local in optionalRefAttrs) {
                                attrsToRemove.add(ns to local)
                                warnings.add("Removed uncompiled reference android:$local=\"$value\" (not supported without res/ XML or image compilation).")
                            } else if (value.startsWith("@style/")) {
                                attrsToUpdate.add(
                                    Triple(
                                        "android:$local",
                                        "@android:style/Theme.Material.Light.DarkActionBar",
                                        value,
                                    ),
                                )
                            } else {
                                errors.add("Unresolved resource reference '$value' in android:$local.")
                            }
                        }
                    }
                }
                attrsToRemove.forEach { (ns, local) ->
                    el.removeAttributeNS(ns, local)
                }
                attrsToUpdate.forEach { (qName, replacement, original) ->
                    el.setAttributeNS(androidNs, qName, replacement)
                    warnings.add("Replaced uncompiled style $qName=\"$original\" with Material Light DarkActionBar.")
                }

                val children = el.childNodes
                for (i in 0 until children.length) {
                    val child = children.item(i)
                    if (child.nodeType == Node.ELEMENT_NODE) {
                        visit(child as Element)
                    }
                }
            }

            visit(manifestEl)

            val sw = StringWriter()
            val transformer = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }
            transformer.transform(DOMSource(doc), StreamResult(sw))
            sw.toString()
        }.getOrElse { err ->
            errors.add("Invalid XML in AndroidManifest.xml: ${err.localizedMessage ?: "syntax error"}")
            if (isRelease) stripDebuggableFlagFallback(rawManifestXml) else rawManifestXml
        }

        return ManifestPreparationResult(
            xml = serialized,
            minSdkVersion = parsedMinSdk,
            warnings = warnings,
            errors = errors,
        )
    }

    private fun stripDebuggableFlagFallback(manifestXml: String): String {
        val cleaned = manifestXml
            .replace(Regex("""\s+android:debuggable\s*=\s*["']true["']"""), "")
        return if (cleaned.contains("android:debuggable")) {
            cleaned
        } else {
            cleaned.replaceFirst(
                "<application",
                "<application android:debuggable=\"false\"",
            )
        }
    }

    private fun packageUnsignedApk(
        outputApk: File,
        resourcesArsc: ByteArray,
        manifestAxml: ByteArray,
        dexFiles: List<File>,
        resXmlEntries: Map<String, ByteArray>,
        nativeLibs: List<Pair<String, File>>,
        assetsDir: File,
    ) {
        val copyBuffer = ByteArray(64 * 1024)
        ZipOutputStream(outputApk.outputStream().buffered(64 * 1024)).use { zos ->
            val arscCrc = CRC32().apply { update(resourcesArsc) }.value
            val arscEntry = ZipEntry("resources.arsc").apply {
                method = ZipEntry.STORED
                size = resourcesArsc.size.toLong()
                compressedSize = resourcesArsc.size.toLong()
                crc = arscCrc
            }
            zos.putNextEntry(arscEntry)
            zos.write(resourcesArsc)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(manifestAxml)
            zos.closeEntry()

            resXmlEntries.forEach { (relPath, bytes) ->
                zos.putNextEntry(ZipEntry(relPath))
                zos.write(bytes)
                zos.closeEntry()
            }

            dexFiles.forEach { dex ->
                zos.putNextEntry(ZipEntry(dex.name))
                streamFileToZip(dex, zos, copyBuffer)
                zos.closeEntry()
            }

            nativeLibs.forEach { (zipRelPath, soFile) ->
                zos.putNextEntry(ZipEntry(zipRelPath))
                streamFileToZip(soFile, zos, copyBuffer)
                zos.closeEntry()
            }

            if (assetsDir.exists() && assetsDir.isDirectory) {
                assetsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(assetsDir).invariantSeparatorsPath
                    zos.putNextEntry(ZipEntry("assets/$rel"))
                    streamFileToZip(file, zos, copyBuffer)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun packageUnsignedAab(
        outputAab: File,
        bundleConfigPb: ByteArray,
        resourcesPb: ByteArray,
        manifestPb: ByteArray,
        dexFiles: List<File>,
        resXmlEntries: Map<String, ByteArray>,
        nativeLibs: List<Pair<String, File>>,
        assetsDir: File,
    ) {
        val copyBuffer = ByteArray(64 * 1024)
        ZipOutputStream(outputAab.outputStream().buffered(64 * 1024)).use { zos ->
            zos.putNextEntry(ZipEntry("BundleConfig.pb"))
            zos.write(bundleConfigPb)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("base/manifest/AndroidManifest.xml"))
            zos.write(manifestPb)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("base/resources.pb"))
            zos.write(resourcesPb)
            zos.closeEntry()

            resXmlEntries.forEach { (relPath, bytes) ->
                zos.putNextEntry(ZipEntry("base/$relPath"))
                zos.write(bytes)
                zos.closeEntry()
            }

            dexFiles.forEach { dex ->
                zos.putNextEntry(ZipEntry("base/dex/${dex.name}"))
                streamFileToZip(dex, zos, copyBuffer)
                zos.closeEntry()
            }

            nativeLibs.forEach { (zipRelPath, soFile) ->
                zos.putNextEntry(ZipEntry("base/$zipRelPath"))
                streamFileToZip(soFile, zos, copyBuffer)
                zos.closeEntry()
            }

            if (assetsDir.exists() && assetsDir.isDirectory) {
                assetsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(assetsDir).invariantSeparatorsPath
                    zos.putNextEntry(ZipEntry("base/assets/$rel"))
                    streamFileToZip(file, zos, copyBuffer)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun streamFileToZip(file: File, zos: ZipOutputStream, buffer: ByteArray) {
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                zos.write(buffer, 0, read)
            }
        }
    }

    private fun collectClasspathJars(projectDir: File): List<File> {
        val libsDir = File(projectDir, "libs")
        if (!libsDir.exists() || !libsDir.isDirectory) return emptyList()
        return libsDir.walkTopDown().filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }.toList()
    }

    private fun collectNativeLibs(projectDir: File): List<Pair<String, File>> {
        val collected = mutableListOf<Pair<String, File>>()
        listOf("lib", "jniLibs").forEach { rootName ->
            val root = File(projectDir, rootName)
            if (root.exists() && root.isDirectory) {
                root.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".so", ignoreCase = true) }
                    .forEach { so ->
                        val rel = so.relativeTo(root).invariantSeparatorsPath
                        collected.add("lib/$rel" to so)
                    }
            }
        }
        return collected
    }

    private fun inspectZipEntries(zipFile: File): List<ApkEntryInfo> {
        return ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().take(400).map { entry ->
                ApkEntryInfo(
                    name = entry.name,
                    compressedSize = entry.compressedSize,
                    uncompressedSize = entry.size,
                    isStored = entry.method == ZipEntry.STORED,
                )
            }.toList()
        }
    }

    private fun ensureAssetExtracted(ctx: Context, assetName: String, target: File): File {
        val expectedLength = runCatching { ctx.assets.openFd(assetName).use { it.length } }.getOrNull()
        val needsCopy = !target.exists() ||
            target.length() == 0L ||
            (expectedLength != null && expectedLength > 0L && target.length() != expectedLength)
        if (needsCopy) {
            ctx.assets.open(assetName).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return target
    }

    private fun collectFilesWithExt(dir: File, ext: String): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(ext) }.toList()
    }

    private fun parseEcjDiagnostics(
        output: String,
        projectDir: File,
        kotlinOriginMap: Map<String, Pair<String, Map<Int, Int>>> = emptyMap(),
    ): List<BuildDiagnostic> {
        val diagnostics = mutableListOf<BuildDiagnostic>()
        val headerRegex = Regex("""^\d+\.\s+(ERROR|WARNING)\s+in\s+(.+?)\s+\(at line (\d+)\)""")
        val lines = output.lines()
        var idx = 0
        while (idx < lines.size) {
            val match = headerRegex.find(lines[idx].trim())
            if (match != null) {
                val severity = if (match.groupValues[1] == "ERROR") {
                    DiagnosticSeverity.ERROR
                } else {
                    DiagnosticSeverity.WARNING
                }
                val rawPath = match.groupValues[2].trim()
                val rawRelPath = runCatching {
                    File(rawPath).relativeTo(projectDir).invariantSeparatorsPath
                }.getOrDefault(File(rawPath).name)
                val rawLineNum = match.groupValues[3].toIntOrNull() ?: 1

                val mappedOrigin = kotlinOriginMap[rawRelPath]
                val finalRelPath = mappedOrigin?.first ?: rawRelPath
                val finalLineNum = mappedOrigin?.second?.get(rawLineNum) ?: rawLineNum

                var message = "Compilation issue"
                var lookAhead = idx + 1
                while (lookAhead < lines.size && lookAhead <= idx + 5) {
                    val candidate = lines[lookAhead].trim()
                    if (candidate.isNotEmpty() &&
                        !candidate.startsWith("^") &&
                        !candidate.startsWith("----------") &&
                        !headerRegex.containsMatchIn(candidate)
                    ) {
                        message = candidate
                    }
                    if (candidate.startsWith("----------")) break
                    lookAhead++
                }
                diagnostics.add(
                    BuildDiagnostic(
                        filePath = finalRelPath,
                        line = finalLineNum,
                        column = 1,
                        severity = severity,
                        message = message,
                    ),
                )
            }
            idx++
        }
        return diagnostics
    }
}
