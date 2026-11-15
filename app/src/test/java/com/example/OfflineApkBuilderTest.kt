package com.example

import com.android.apksig.ApkVerifier
import com.example.compiler.OfflineApkBuilder
import com.example.data.TemplateProvider
import com.example.model.DiagnosticSeverity
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
                        language = if (path.endsWith(".java")) com.example.model.FileLanguage.JAVA else com.example.model.FileLanguage.XML,
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
}
