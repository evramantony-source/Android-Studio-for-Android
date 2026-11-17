package com.example

import com.android.apksig.ApkVerifier
import com.example.compiler.OfflineApkBuilder
import com.example.data.TemplateProvider
import com.example.model.DiagnosticSeverity
import com.example.model.FileLanguage
import com.example.model.ProjectLanguage
import com.example.model.ProjectTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

class OfflineApkBuilderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun resolveAsset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate asset $name from ${File(".").absolutePath}")
    }

    @Test
    fun buildsAndSignsValidApkFromTemplate() {
        val projectDir = tempFolder.newFolder("counter_project")
        val template = ProjectTemplate.COUNTER_APP
        val files = TemplateProvider.generateFiles(
            template = template,
            appName = template.defaultAppName,
            packageName = template.defaultPackage,
        )
        files.forEach { (relPath, content) ->
            val target = File(projectDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        val builder = OfflineApkBuilder()
        val result = builder.buildWithToolchain(
            projectDir = projectDir,
            packageName = template.defaultPackage,
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
        )

        assertTrue("Expected APK build to succeed, logs=${result.logs}", result.success)
        assertTrue(result.apkSizeBytes > 0)

        val apkFile = File(result.apkPath!!)
        assertTrue(apkFile.exists())

        // Verify APK v1/v2/v3 cryptographic signature with apksig ApkVerifier
        val verification = ApkVerifier.Builder(apkFile)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue("APK signature verification failed: ${verification.errors}", verification.isVerified)
        assertTrue(verification.isVerifiedUsingV2Scheme)

        // Verify internal binary entries (AXML, ARSC, DEX)
        ZipFile(apkFile).use { zip ->
            val arscEntry = zip.getEntry("resources.arsc")!!
            val arscBytes = zip.getInputStream(arscEntry).readBytes()
            val arscType = ByteBuffer.wrap(arscBytes).order(ByteOrder.LITTLE_ENDIAN).short
            assertEquals(0x0002.toShort(), arscType)

            val manifestEntry = zip.getEntry("AndroidManifest.xml")!!
            val manifestBytes = zip.getInputStream(manifestEntry).readBytes()
            val manifestType = ByteBuffer.wrap(manifestBytes).order(ByteOrder.LITTLE_ENDIAN).short
            assertEquals(0x0003.toShort(), manifestType)

            val dexEntry = zip.getEntry("classes.dex")!!
            val dexBytes = zip.getInputStream(dexEntry).readBytes()
            assertEquals('d'.code.toByte(), dexBytes[0])
            assertEquals('e'.code.toByte(), dexBytes[1])
            assertEquals('x'.code.toByte(), dexBytes[2])
        }
    }

    @Test
    fun verifiesBuiltApksWithAndroidSdkBuildTools() {
        val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: return
        val buildToolsDir = File(sdkRoot, "build-tools").listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name } ?: return
        val aapt2 = File(buildToolsDir, "aapt2").takeIf { it.exists() } ?: return
        val zipalign = File(buildToolsDir, "zipalign").takeIf { it.exists() } ?: return
        val apksigner = File(buildToolsDir, "apksigner").takeIf { it.exists() } ?: return

        val debugProjectDir = tempFolder.newFolder("sdk_verify_debug")
        val releaseProjectDir = tempFolder.newFolder("sdk_verify_release")
        val template = ProjectTemplate.COUNTER_APP
        val templateFiles = TemplateProvider.generateFiles(template, template.defaultAppName, template.defaultPackage)
        listOf(debugProjectDir, releaseProjectDir).forEach { dir ->
            templateFiles.forEach { (relPath, content) ->
                val target = File(dir, relPath)
                target.parentFile?.mkdirs()
                target.writeText(content)
            }
        }

        val builder = OfflineApkBuilder()
        val debugResult = builder.buildWithToolchain(
            projectDir = debugProjectDir,
            packageName = template.defaultPackage,
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
            variant = com.example.model.BuildVariant.DEBUG_APK,
        )
        assertTrue(debugResult.success)
        val debugApk = File(debugResult.apkPath!!)

        fun runTool(vararg cmd: String): Pair<Int, String> {
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            return code to out
        }

        val (badgingCode, badgingOut) = runTool(aapt2.absolutePath, "dump", "badging", debugApk.absolutePath)
        val (xmlCode, xmlOut) = runTool(aapt2.absolutePath, "dump", "xmltree", debugApk.absolutePath, "--file", "AndroidManifest.xml")
        val (resCode, resOut) = runTool(aapt2.absolutePath, "dump", "resources", debugApk.absolutePath)
        val (alignCode, alignOut) = runTool(zipalign.absolutePath, "-c", "-v", "4", debugApk.absolutePath)
        val (signCode, signOut) = runTool(apksigner.absolutePath, "verify", "--verbose", "--print-certs", debugApk.absolutePath)

        val releaseResult = builder.buildWithToolchain(
            projectDir = releaseProjectDir,
            packageName = template.defaultPackage,
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
            variant = com.example.model.BuildVariant.RELEASE_APK,
        )
        assertTrue(releaseResult.success)
        val releaseApk = File(releaseResult.apkPath!!)
        val (relBadgingCode, relBadgingOut) = runTool(aapt2.absolutePath, "dump", "badging", releaseApk.absolutePath)
        val (relXmlCode, relXmlOut) = runTool(aapt2.absolutePath, "dump", "xmltree", releaseApk.absolutePath, "--file", "AndroidManifest.xml")
        val (relAlignCode, relAlignOut) = runTool(zipalign.absolutePath, "-c", "-v", "4", releaseApk.absolutePath)
        val (relSignCode, relSignOut) = runTool(apksigner.absolutePath, "verify", "--verbose", "--min-sdk-version", "24", releaseApk.absolutePath)

        val report = buildString {
            appendLine("=== DEBUG BADGING ($badgingCode) ===\n$badgingOut")
            appendLine("=== DEBUG XMLTREE ($xmlCode) ===\n$xmlOut")
            appendLine("=== DEBUG RESOURCES ($resCode) ===\n$resOut")
            appendLine("=== DEBUG ZIPALIGN ($alignCode) ===\n$alignOut")
            appendLine("=== DEBUG APKSIGNER ($signCode) ===\n$signOut")
            appendLine("=== RELEASE BADGING ($relBadgingCode) ===\n$relBadgingOut")
            appendLine("=== RELEASE XMLTREE ($relXmlCode) ===\n$relXmlOut")
            appendLine("=== RELEASE ZIPALIGN ($relAlignCode) ===\n$relAlignOut")
            appendLine("=== RELEASE APKSIGNER ($relSignCode) ===\n$relSignOut")
        }
        assertTrue(
            report,
            badgingCode == 0 && xmlCode == 0 && resCode == 0 && alignCode == 0 && signCode == 0 &&
                relBadgingCode == 0 && relXmlCode == 0 && relAlignCode == 0 && relSignCode == 0,
        )
        assertTrue("Expected Theme.Material.Light.DarkActionBar (0x01030238) in xmltree:\n$xmlOut", xmlOut.contains("0x01030238"))
        assertTrue("Expected debuggable(0x0101000f)=false in release xmltree:\n$relXmlOut", relXmlOut.contains("debuggable(0x0101000f)=false"))
    }

    @Test
    fun normalizesComplexManifestAttributesAndEnumsForSdkParsing() {
        val projectDir = tempFolder.newFolder("complex_manifest_project")
        val files = TemplateProvider.generateFiles(
            template = ProjectTemplate.BLANK_ACTIVITY,
            appName = "2048",
            packageName = "com.example.game2048",
        ).toMutableMap()
        files["AndroidManifest.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.game2048"
                android:versionCode="2"
                android:versionName="2"
                android:installLocation="auto">
                <uses-permission android:name="android.permission.INTERNET" />
                <application
                    android:allowBackup="true"
                    android:icon="@mipmap/ic_launcher"
                    android:roundIcon="@mipmap/ic_launcher_round"
                    android:label="2048"
                    android:largeHeap="true"
                    android:usesCleartextTraffic="true"
                    android:theme="@style/AppTheme">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true"
                        android:launchMode="singleTop"
                        android:screenOrientation="portrait"
                        android:windowSoftInputMode="adjustResize"
                        android:configChanges="orientation|screenSize|keyboardHidden">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()

        files.forEach { (relPath, content) ->
            val target = File(projectDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        val result = OfflineApkBuilder().buildWithToolchain(
            projectDir = projectDir,
            packageName = "com.example.game2048",
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
            variant = com.example.model.BuildVariant.RELEASE_APK,
        )
        assertTrue("Expected build to succeed cleanly, logs=${result.logs}", result.success)
        val apkFile = File(result.apkPath!!)

        val verification = ApkVerifier.Builder(apkFile)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue("Signature failed: ${verification.errors}", verification.isVerified)

        val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: return
        val buildToolsDir = File(sdkRoot, "build-tools").listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name } ?: return
        val aapt2 = File(buildToolsDir, "aapt2").takeIf { it.exists() } ?: return

        val badgingProc = ProcessBuilder(aapt2.absolutePath, "dump", "badging", apkFile.absolutePath)
            .redirectErrorStream(true).start()
        val badgingOut = badgingProc.inputStream.bufferedReader().readText()
        assertEquals("aapt2 badging failed:\n$badgingOut", 0, badgingProc.waitFor())
        assertTrue("Expected minSdkVersion 24 in:\n$badgingOut", badgingOut.contains("minSdkVersion:'24'"))
        assertTrue("Expected targetSdkVersion 35 in:\n$badgingOut", badgingOut.contains("targetSdkVersion:'35'"))
        assertTrue("Expected numeric app label '2048' preserved in:\n$badgingOut", badgingOut.contains("application-label:'2048'"))

        val xmlProc = ProcessBuilder(aapt2.absolutePath, "dump", "xmltree", apkFile.absolutePath, "--file", "AndroidManifest.xml")
            .redirectErrorStream(true).start()
        val xmlOut = xmlProc.inputStream.bufferedReader().readText()
        assertEquals("aapt2 xmltree failed:\n$xmlOut", 0, xmlProc.waitFor())
        assertTrue("Expected screenOrientation(0x0101001e)=1 in:\n$xmlOut", xmlOut.contains("screenOrientation(0x0101001e)=1"))
        assertTrue("Expected launchMode(0x0101001d)=1 in:\n$xmlOut", xmlOut.contains("launchMode(0x0101001d)=1"))
        assertTrue("Expected configChanges(0x0101001f)=0x000004a0 in:\n$xmlOut", xmlOut.contains("configChanges(0x0101001f)=0x000004a0"))
        assertTrue("Expected windowSoftInputMode(0x0101022b)=0x00000010 in:\n$xmlOut", xmlOut.contains("windowSoftInputMode(0x0101022b)=0x00000010"))
    }

    @Test
    fun reportsLineDiagnosticOnJavaSyntaxError() {
        val projectDir = tempFolder.newFolder("broken_project")
        val files = TemplateProvider.generateFiles(
            template = ProjectTemplate.BLANK_ACTIVITY,
            appName = "Broken",
            packageName = "com.example.broken",
        ).toMutableMap()
        files["src/com/example/broken/MainActivity.java"] = """
            package com.example.broken;
            import android.app.Activity;
            import android.os.Bundle;
            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    missingMethodCall();
                }
            }
        """.trimIndent()

        files.forEach { (relPath, content) ->
            val target = File(projectDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        val result = OfflineApkBuilder().buildWithToolchain(
            projectDir = projectDir,
            packageName = "com.example.broken",
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
        )

        assertFalse(result.success)
        assertTrue(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR && it.line == 8 })
    }

    @Test
    fun generatesCustomReleaseKeystoreAndBuildsSignedReleaseApkAndAab() {
        val keystoreFile = File(tempFolder.newFolder("keys"), "release.p12")
        val signingCfg = com.example.compiler.KeystoreManager.generatePkcs12Keystore(
            outputFile = keystoreFile,
            alias = "droidrelease",
            storePassword = "secret123",
            keyPassword = "secret123",
            commonName = "DroidForge Dev",
            organization = "DroidForge",
        )
        assertTrue(keystoreFile.exists())
        assertTrue(signingCfg.sha256Fingerprint.isNotBlank())
        assertTrue(signingCfg.sha1Fingerprint.isNotBlank())

        val inspected = com.example.compiler.KeystoreManager.inspectKeystore(
            keystoreFile = keystoreFile,
            storePassword = "secret123",
            preferredAlias = "droidrelease",
            keyPassword = "secret123",
        )
        assertEquals("droidrelease", inspected.alias)

        val projectDir = tempFolder.newFolder("release_notes")
        val template = ProjectTemplate.NOTES_APP
        TemplateProvider.generateFiles(template, template.defaultAppName, template.defaultPackage)
            .forEach { (relPath, content) ->
                val target = File(projectDir, relPath)
                target.parentFile?.mkdirs()
                target.writeText(content)
            }

        val builder = OfflineApkBuilder()

        // 1. Build Release APK
        val releaseApkResult = builder.buildWithToolchain(
            projectDir = projectDir,
            packageName = template.defaultPackage,
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = keystoreFile,
            variant = com.example.model.BuildVariant.RELEASE_APK,
            storePassword = "secret123",
            keyPassword = "secret123",
            keyAlias = "droidrelease",
            usedReleaseKey = true,
        )
        assertTrue("Release APK build failed: ${releaseApkResult.logs}", releaseApkResult.success)
        assertTrue(releaseApkResult.signerSha256.isNotBlank())
        assertTrue(releaseApkResult.artifactSha256.isNotBlank())
        val releaseApkFile = File(releaseApkResult.apkPath!!)
        val apkVerification = ApkVerifier.Builder(releaseApkFile)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue("Release APK signature invalid: ${apkVerification.errors}", apkVerification.isVerified)

        // 2. Build Signed Release AAB
        val aabResult = builder.buildWithToolchain(
            projectDir = projectDir,
            packageName = template.defaultPackage,
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = keystoreFile,
            variant = com.example.model.BuildVariant.RELEASE_AAB,
            storePassword = "secret123",
            keyPassword = "secret123",
            keyAlias = "droidrelease",
            usedReleaseKey = true,
        )
        assertTrue("Release AAB build failed: ${aabResult.logs}", aabResult.success)
        val aabFile = File(aabResult.apkPath!!)
        assertTrue(aabFile.name.endsWith(".aab"))

        ZipFile(aabFile).use { zip ->
            assertTrue(zip.getEntry("BundleConfig.pb") != null)
            assertTrue(zip.getEntry("base/resources.pb") != null)
            assertTrue(zip.getEntry("base/manifest/AndroidManifest.xml") != null)
            assertTrue(zip.getEntry("base/dex/classes.dex") != null)
            assertTrue(zip.getEntry("META-INF/MANIFEST.MF") != null)
        }

        // Verify JAR PKCS#7 signature entries on .aab
        java.util.jar.JarFile(aabFile, true).use { jar ->
            val entries = jar.entries().toList().filter { !it.isDirectory && !it.name.startsWith("META-INF/") }
            assertTrue(entries.isNotEmpty())
            entries.forEach { entry ->
                jar.getInputStream(entry).use { it.readBytes() }
                assertTrue("Expected code signer on AAB entry ${entry.name}", !entry.certificates.isNullOrEmpty())
            }
        }
    }

    @Test
    fun emulatorEngineParsesAllStarterTemplates() {
        ProjectTemplate.entries.forEach { template ->
            val files = TemplateProvider.generateFiles(template, template.defaultAppName, template.defaultPackage)
                .map { (path, content) ->
                    com.example.model.ProjectFile(
                        relativePath = path,
                        content = content,
                        language = when {
                            path.endsWith(".kt") -> FileLanguage.KOTLIN
                            path.endsWith(".java") -> FileLanguage.JAVA
                            else -> FileLanguage.XML
                        },
                    )
                }
            val parsed = com.example.emulator.EmulatorEngine.parseProject(
                appName = template.defaultAppName,
                packageName = template.defaultPackage,
                files = files,
            )
            assertTrue("Expected widgets parsed for ${template.name}", parsed.detectedWidgetsCount > 0)
        }
    }

    @Test
    fun buildsAndSignsAllKotlinStarterTemplates() {
        val builder = OfflineApkBuilder()
        ProjectTemplate.entries.forEach { template ->
            val projectDir = tempFolder.newFolder("kt_${template.name.lowercase()}")
            val files = TemplateProvider.generateFiles(
                template = template,
                appName = template.defaultAppName,
                packageName = template.defaultPackage,
                language = ProjectLanguage.KOTLIN,
                minSdkVersion = 24,
            )
            files.forEach { (relPath, content) ->
                val target = File(projectDir, relPath)
                target.parentFile?.mkdirs()
                target.writeText(content)
            }

            val result = builder.buildWithToolchain(
                projectDir = projectDir,
                packageName = template.defaultPackage,
                stubsJar = resolveAsset("android-stubs.jar"),
                keystoreFile = resolveAsset("debug_signing.p12"),
                targetMinSdk = 24,
            )
            assertTrue(
                "Expected Kotlin build to succeed for ${template.name}, logs=${result.logs}",
                result.success,
            )

            val apkFile = File(result.apkPath!!)
            val verification = ApkVerifier.Builder(apkFile)
                .setMinCheckedPlatformVersion(24)
                .build()
                .verify()
            assertTrue(
                "APK signature failed for Kotlin ${template.name}: ${verification.errors}",
                verification.isVerified,
            )
        }
    }

    @Test
    fun reportsKotlinErrorLineBackToKtSource() {
        val projectDir = tempFolder.newFolder("broken_kotlin_project")
        val files = TemplateProvider.generateFiles(
            template = ProjectTemplate.BLANK_ACTIVITY,
            appName = "BrokenKt",
            packageName = "com.example.brokenkt",
            language = ProjectLanguage.KOTLIN,
            minSdkVersion = 24,
        ).toMutableMap()
        files["src/com/example/brokenkt/MainActivity.kt"] = """
            package com.example.brokenkt

            import android.app.Activity
            import android.os.Bundle

            class MainActivity : Activity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    missingKotlinCall()
                }
            }
        """.trimIndent()

        files.forEach { (relPath, content) ->
            val target = File(projectDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        val result = OfflineApkBuilder().buildWithToolchain(
            projectDir = projectDir,
            packageName = "com.example.brokenkt",
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
            targetMinSdk = 24,
        )

        assertFalse("Expected broken Kotlin project to fail compilation", result.success)
        assertTrue(
            "Expected error diagnostic mapped back to MainActivity.kt line 9, got=${result.diagnostics}",
            result.diagnostics.any {
                it.severity == DiagnosticSeverity.ERROR &&
                    it.filePath.endsWith("MainActivity.kt") &&
                    it.line == 9
            },
        )
    }

    @Test
    fun propagatesCustomMinSdkVersionToBuiltApk() {
        val projectDir = tempFolder.newFolder("custom_min_sdk_project")
        val files = TemplateProvider.generateFiles(
            template = ProjectTemplate.COUNTER_APP,
            appName = "Api28App",
            packageName = "com.example.api28",
            language = ProjectLanguage.KOTLIN,
            minSdkVersion = 28,
        )
        files.forEach { (relPath, content) ->
            val target = File(projectDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        val result = OfflineApkBuilder().buildWithToolchain(
            projectDir = projectDir,
            packageName = "com.example.api28",
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
            targetMinSdk = 28,
        )
        assertTrue("Expected custom minSdk build to succeed, logs=${result.logs}", result.success)

        val apkFile = File(result.apkPath!!)
        val verification = ApkVerifier.Builder(apkFile)
            .setMinCheckedPlatformVersion(28)
            .build()
            .verify()
        assertTrue("Signature verification failed: ${verification.errors}", verification.isVerified)

        val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: return
        val buildToolsDir = File(sdkRoot, "build-tools").listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name } ?: return
        val aapt2 = File(buildToolsDir, "aapt2").takeIf { it.exists() } ?: return

        val badgingProc = ProcessBuilder(aapt2.absolutePath, "dump", "badging", apkFile.absolutePath)
            .redirectErrorStream(true).start()
        val badgingOut = badgingProc.inputStream.bufferedReader().readText()
        assertEquals("aapt2 badging failed:\n$badgingOut", 0, badgingProc.waitFor())
        assertTrue("Expected minSdkVersion:'28' in:\n$badgingOut", badgingOut.contains("minSdkVersion:'28'"))
    }

    @Test
    fun emulatorEngineParsesAllKotlinStarterTemplates() {
        ProjectTemplate.entries.forEach { template ->
            val files = TemplateProvider.generateFiles(
                template = template,
                appName = template.defaultAppName,
                packageName = template.defaultPackage,
                language = ProjectLanguage.KOTLIN,
                minSdkVersion = 24,
            ).map { (path, content) ->
                com.example.model.ProjectFile(
                    relativePath = path,
                    content = content,
                    language = when {
                        path.endsWith(".kt") -> FileLanguage.KOTLIN
                        path.endsWith(".java") -> FileLanguage.JAVA
                        else -> FileLanguage.XML
                    },
                )
            }
            val parsed = com.example.emulator.EmulatorEngine.parseProject(
                appName = template.defaultAppName,
                packageName = template.defaultPackage,
                files = files,
            )
            assertTrue("Expected widgets parsed for Kotlin ${template.name}", parsed.detectedWidgetsCount > 0)
        }
    }

    @Test
    fun buildsApkWithJava25FeaturesRecordsSwitchTextBlocksAndVar() {
        val projectDir = tempFolder.newFolder("java25_project")
        val files = TemplateProvider.generateFiles(
            template = ProjectTemplate.BLANK_ACTIVITY,
            appName = "Java25 App",
            packageName = "com.example.java25",
            language = ProjectLanguage.JAVA,
            minSdkVersion = 24,
        ).toMutableMap()
        files["src/com/example/java25/MainActivity.java"] = """
            package com.example.java25;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;
            import java.util.List;

            public class MainActivity extends Activity {
                public record PlayerStats(String name, int score) {}

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    var items = List.of("Alpha", "Beta", "Gamma");
                    var stats = new PlayerStats("Pilot", 420);
                    String tier = switch (items.size()) {
                        case 1 -> "Solo";
                        case 2, 3 -> "Squad";
                        default -> "Fleet";
                    };
                    var banner = ${"\"\"\""}
                        Hero: Pilot
                        Status: Online
                        ${"\"\"\""};
                    IO.println("Loaded " + stats.name() + " tier=" + tier);
                    TextView tv = new TextView(this);
                    tv.setText(stats.name() + " " + tier + " " + banner.trim());
                    setContentView(tv);
                }
            }
        """.trimIndent()
        files.forEach { (relPath, content) ->
            val target = File(projectDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        val result = OfflineApkBuilder().buildWithToolchain(
            projectDir = projectDir,
            packageName = "com.example.java25",
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
            targetJavaVersion = com.example.model.JavaVersionOption.JAVA_25,
        )
        assertTrue("Expected Java 25 build to succeed, logs=${result.logs}", result.success)
        val apkFile = File(result.apkPath!!)
        val verification = ApkVerifier.Builder(apkFile)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue("Java 25 APK signature failed: ${verification.errors}", verification.isVerified)
    }

    @Test
    fun packagesXmlLayoutsDrawablesAndNativeLibsIntoApk() {
        val projectDir = tempFolder.newFolder("layout_native_project")
        val files = TemplateProvider.generateFiles(
            template = ProjectTemplate.BLANK_ACTIVITY,
            appName = "LayoutNative",
            packageName = "com.example.layoutnative",
            language = ProjectLanguage.JAVA,
            minSdkVersion = 24,
        ).toMutableMap()
        files["res/layout/activity_main.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:orientation="vertical"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <TextView
                    android:id="@+id/title_label"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/app_name" />
                <Button
                    android:id="@+id/action_btn"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="Launch" />
            </LinearLayout>
        """.trimIndent()
        files["src/com/example/layoutnative/MainActivity.java"] = """
            package com.example.layoutnative;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.Button;
            import android.widget.TextView;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(R.layout.activity_main);
                    TextView label = (TextView) findViewById(R.id.title_label);
                    Button btn = (Button) findViewById(R.id.action_btn);
                    btn.setText("Ready: " + label.getText());
                }
            }
        """.trimIndent()
        files.forEach { (relPath, content) ->
            val target = File(projectDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
        val nativeSo = File(projectDir, "jniLibs/arm64-v8a/libengine.so")
        nativeSo.parentFile?.mkdirs()
        nativeSo.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0))

        val result = OfflineApkBuilder().buildWithToolchain(
            projectDir = projectDir,
            packageName = "com.example.layoutnative",
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
        )
        assertTrue("Expected layout+native build to succeed, logs=${result.logs}", result.success)

        val apkFile = File(result.apkPath!!)
        ZipFile(apkFile).use { zip ->
            assertTrue("Expected res/layout/activity_main.xml in APK", zip.getEntry("res/layout/activity_main.xml") != null)
            assertTrue("Expected lib/arm64-v8a/libengine.so stored uncompressed in APK", zip.getEntry("lib/arm64-v8a/libengine.so") != null)
        }
        val verification = ApkVerifier.Builder(apkFile)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue("Layout+native APK signature invalid: ${verification.errors}", verification.isVerified)
    }

    @Test
    fun liveVmExecutesArcadeShooterAndVoxelTemplatesWithCanvasOps() {
        listOf(
            ProjectTemplate.ARCADE_SHOOTER to ProjectLanguage.JAVA,
            ProjectTemplate.ARCADE_SHOOTER to ProjectLanguage.KOTLIN,
            ProjectTemplate.VOXEL_LAUNCHER_STUDIO to ProjectLanguage.JAVA,
            ProjectTemplate.VOXEL_LAUNCHER_STUDIO to ProjectLanguage.KOTLIN,
        ).forEach { (template, lang) ->
            val projectFiles = TemplateProvider.generateFiles(
                template = template,
                appName = template.defaultAppName,
                packageName = template.defaultPackage,
                language = lang,
                minSdkVersion = 24,
            ).map { (path, content) ->
                com.example.model.ProjectFile(
                    relativePath = path,
                    content = content,
                    language = when {
                        path.endsWith(".kt") -> FileLanguage.KOTLIN
                        path.endsWith(".java") -> FileLanguage.JAVA
                        else -> FileLanguage.XML
                    },
                )
            }

            val session = com.example.emulator.EmulatorEngine.createLiveSession(
                appName = template.defaultAppName,
                packageName = template.defaultPackage,
                files = projectFiles,
            )
            assertTrue("Expected LiveVmSession created for ${template.name} ($lang)", session != null)
            assertTrue("Expected onCreate() start() success for ${template.name} ($lang)", session!!.start())
            session.updateViewportSize(720, 1280)

            fun findCanvas(node: com.example.emulator.VmViewNode?): com.example.emulator.VmViewNode.CustomCanvasView? {
                return when (node) {
                    is com.example.emulator.VmViewNode.CustomCanvasView -> node
                    is com.example.emulator.VmViewNode.Container -> node.children.firstNotNullOfOrNull { findCanvas(it) }
                    else -> null
                }
            }

            val beforeSnapshot = session.buildSnapshot()
            val canvasBefore = findCanvas(beforeSnapshot)
            assertTrue("Expected CustomCanvasView rendered for ${template.name} ($lang)", canvasBefore != null)
            assertTrue(
                "Expected non-zero Canvas draw ops for ${template.name} ($lang), got=${canvasBefore!!.ops.size}",
                canvasBefore.ops.size >= 3,
            )

            // Simulate touch drag + timer tick
            session.dispatchTouch(canvasBefore.id, 0, 500f, 900f)
            session.dispatchTouch(canvasBefore.id, 2, 540f, 880f)
            session.tickTimers(System.currentTimeMillis() + 50L)

            val afterSnapshot = session.buildSnapshot()
            val canvasAfter = findCanvas(afterSnapshot)
            assertTrue("Expected CustomCanvasView after tick for ${template.name} ($lang)", canvasAfter != null)
            assertTrue("Expected >0 ops after tick for ${template.name} ($lang)", canvasAfter!!.ops.isNotEmpty())
        }
    }

    @Test
    fun githubClientGeneratesGradleAndActionsWithJava25() {
        val meta = com.example.model.ProjectMetadata(
            id = "test123",
            name = "DreamVoxel",
            packageName = "com.example.dreamvoxel",
            template = ProjectTemplate.VOXEL_LAUNCHER_STUDIO,
            createdAtMillis = 1000L,
            updatedAtMillis = 1000L,
            language = ProjectLanguage.JAVA,
            javaVersion = com.example.model.JavaVersionOption.JAVA_25,
            minSdkVersion = 26,
        )
        val workflowYaml = com.example.github.GitHubClient.buildWorkflowYaml(meta)
        val appGradle = com.example.github.GitHubClient.buildAppGradleKts(meta)
        assertTrue(workflowYaml.contains("java-version: '25'"))
        assertTrue(workflowYaml.contains("assembleDebug"))
        assertTrue(appGradle.contains("minSdk = 26"))
        assertTrue(appGradle.contains("JavaVersion.toVersion(25)"))
        assertTrue(appGradle.contains("multiDexEnabled = true"))
    }

    @Test
    fun supportsAllJavaVersions8Through25AndTranspilesModernFeatures() {
        assertEquals((8..25).toList(), com.example.model.JavaVersionOption.entries.map { it.majorVersion })

        val compactMain = com.example.compiler.ModernJavaTranspiler.transpileToJava8(
            """
            void main() {
                IO.println("Compact Java 25");
            }
            """.trimIndent(),
        )
        assertTrue("Expected compact main wrapped in class Main", compactMain.java8Source.contains("public class Main"))
        assertTrue("Expected IO.println lowered", compactMain.java8Source.contains("System.out.println"))

        val projectDir = tempFolder.newFolder("java8_to_25_project")
        val files = TemplateProvider.generateFiles(
            template = ProjectTemplate.BLANK_ACTIVITY,
            appName = "Java25 Modern",
            packageName = "com.example.java25modern",
            language = ProjectLanguage.JAVA,
            minSdkVersion = 24,
        ).toMutableMap()
        files["src/com/example/java25modern/MainActivity.java"] = """
            package com.example.java25modern;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;
            import java.util.Map;

            public class MainActivity extends Activity {
                sealed interface Item permits BlockItem {}
                static final class BlockItem implements Item {
                    String title() { return "Oak"; }
                }

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    Map<String, Integer> bag = Map.of("oak", 4, "stone", 16);
                    String repeated = "#".repeat(3);
                    long lineCount = "a\nb\nc".lines().count();
                    String kind = switch (bag.size()) {
                        case 1 -> {
                            yield "single";
                        }
                        default -> {
                            yield "multi";
                        }
                    };
                    Item item = new BlockItem();
                    TextView tv = new TextView(this);
                    tv.setText(repeated + ":" + lineCount + ":" + kind + ":" + ((BlockItem) item).title());
                    setContentView(tv);
                }
            }
        """.trimIndent()
        files.forEach { (relPath, content) ->
            val target = File(projectDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        val result = OfflineApkBuilder().buildWithToolchain(
            projectDir = projectDir,
            packageName = "com.example.java25modern",
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
            targetJavaVersion = com.example.model.JavaVersionOption.JAVA_25,
        )
        assertTrue("Expected modern Java 25 build to succeed, logs=${result.logs}", result.success)
        val apkFile = File(result.apkPath!!)
        val verification = ApkVerifier.Builder(apkFile)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue("Modern Java 25 APK signature failed: ${verification.errors}", verification.isVerified)
    }

    @Test
    fun streamingSignJarArchiveVerifiesStoredAndDeflatedEntries() {
        val keystoreFile = File(tempFolder.newFolder("stream_keys"), "stream.p12")
        com.example.compiler.KeystoreManager.generatePkcs12Keystore(
            outputFile = keystoreFile,
            alias = "streamkey",
            storePassword = "password123",
            keyPassword = "password123",
            commonName = "DroidForge Stream",
            organization = "DroidForge",
        )
        val signer = com.example.compiler.KeystoreManager.inspectKeystore(
            keystoreFile = keystoreFile,
            storePassword = "password123",
            preferredAlias = "streamkey",
            keyPassword = "password123",
        )

        val work = tempFolder.newFolder("stream_zip")
        val unsignedZip = File(work, "unsigned.aab")
        val signedZip = File(work, "signed.aab")
        val storedPayload = ByteArray(128 * 1024) { idx -> (idx % 251).toByte() }
        val deflatedPayload = "droidforge-stream-test-entry\n".repeat(400).toByteArray()

        java.util.zip.ZipOutputStream(unsignedZip.outputStream()).use { zos ->
            val crc = java.util.zip.CRC32().apply { update(storedPayload) }.value
            val storedEntry = java.util.zip.ZipEntry("base/assets/large.bin").apply {
                method = java.util.zip.ZipEntry.STORED
                size = storedPayload.size.toLong()
                compressedSize = storedPayload.size.toLong()
                this.crc = crc
            }
            zos.putNextEntry(storedEntry)
            zos.write(storedPayload)
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("base/dex/classes.dex"))
            zos.write(deflatedPayload)
            zos.closeEntry()
        }

        com.example.compiler.KeystoreManager.signJarArchive(
            unsignedZip = unsignedZip,
            signedOutput = signedZip,
            signer = signer,
        )

        ZipFile(signedZip).use { zip ->
            val storedOut = zip.getEntry("base/assets/large.bin")!!
            assertEquals(java.util.zip.ZipEntry.STORED, storedOut.method)
            assertEquals(storedPayload.size.toLong(), storedOut.size)
        }

        java.util.jar.JarFile(signedZip, true).use { jar ->
            val entries = jar.entries().toList().filter { !it.isDirectory && !it.name.startsWith("META-INF/") }
            assertEquals(2, entries.size)
            entries.forEach { entry ->
                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                if (entry.name.endsWith("large.bin")) {
                    assertTrue(bytes.contentEquals(storedPayload))
                } else {
                    assertTrue(bytes.contentEquals(deflatedPayload))
                }
                assertTrue("Expected signer certs for ${entry.name}", !entry.certificates.isNullOrEmpty())
            }
        }
    }

    @Test
    fun liveVmInflatesXmlCustomCanvasAndSurfaceViewWithPointerAndTimerSupport() {
        val projectFiles = listOf(
            com.example.model.ProjectFile(
                relativePath = "res/layout/activity_game.xml",
                content = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:orientation="vertical"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent">
                        <TextView
                            android:id="@+id/hud_text"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="HUD Ready" />
                        <com.example.xmlgame.XmlGameView
                            android:id="@+id/game_canvas"
                            android:layout_width="match_parent"
                            android:layout_height="0dp"
                            android:layout_weight="1" />
                        <SurfaceView
                            android:id="@+id/hud_surface"
                            android:layout_width="match_parent"
                            android:layout_height="120dp" />
                    </LinearLayout>
                """.trimIndent(),
                language = FileLanguage.XML,
            ),
            com.example.model.ProjectFile(
                relativePath = "src/com/example/xmlgame/MainActivity.java",
                content = """
                    package com.example.xmlgame;

                    import android.app.Activity;
                    import android.content.Context;
                    import android.graphics.Canvas;
                    import android.graphics.Color;
                    import android.graphics.Paint;
                    import android.os.Bundle;
                    import android.os.Handler;
                    import android.view.MotionEvent;
                    import android.view.SurfaceHolder;
                    import android.view.SurfaceView;
                    import android.view.View;
                    import android.widget.TextView;

                    public class MainActivity extends Activity {
                        private int ticks = 0;

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);
                            setContentView(R.layout.activity_game);
                            SurfaceView sv = (SurfaceView) findViewById(R.id.hud_surface);
                            SurfaceHolder holder = sv.getHolder();
                            Canvas sc = holder.lockCanvas();
                            if (sc != null) {
                                Paint p = new Paint();
                                p.setColor(Color.GREEN);
                                sc.drawRect(0, 0, 100, 40, p);
                                holder.unlockCanvasAndPost(sc);
                            }
                            final TextView hud = (TextView) findViewById(R.id.hud_text);
                            new Handler().postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    ticks = ticks + 1;
                                    hud.setText("Tick " + ticks);
                                }
                            }, 16);
                        }
                    }

                    class XmlGameView extends View {
                        private float playerX = 120f;
                        public XmlGameView(Context context) {
                            super(context);
                        }
                        @Override
                        protected void onDraw(Canvas canvas) {
                            Paint p = new Paint();
                            p.setColor(Color.CYAN);
                            canvas.drawCircle(playerX, 200f, 30f, p);
                        }
                        @Override
                        public boolean onTouchEvent(MotionEvent event) {
                            if (event.getPointerCount() >= 1) {
                                playerX = event.getX();
                            }
                            return true;
                        }
                    }
                """.trimIndent(),
                language = FileLanguage.JAVA,
            ),
        )

        val session = com.example.emulator.EmulatorEngine.createLiveSession(
            appName = "XmlGame",
            packageName = "com.example.xmlgame",
            files = projectFiles,
        )!!
        assertTrue(session.start())
        session.updateViewportSize(720, 1280)
        session.tickTimers(System.currentTimeMillis() + 50L)

        val root = session.buildSnapshot() as com.example.emulator.VmViewNode.Container
        val canvasNodes = root.children.filterIsInstance<com.example.emulator.VmViewNode.CustomCanvasView>()
        assertEquals("Expected both XML custom View and SurfaceView canvas nodes", 2, canvasNodes.size)
        val xmlGameNode = canvasNodes.first()
        assertEquals(1f, xmlGameNode.layoutWeight)
        assertTrue("Expected drawCircle op from XML custom view", xmlGameNode.ops.isNotEmpty())
        val surfaceNode = canvasNodes.last()
        assertTrue("Expected drawRect op from SurfaceHolder", surfaceNode.ops.isNotEmpty())
    }
}
