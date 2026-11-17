package com.example.model

enum class JavaVersionOption(
    val majorVersion: Int,
    val label: String,
    val badge: String,
    val highlights: String,
) {
    JAVA_8(8, "Java 8 LTS", "Java 8", "Classic Android DEX baseline"),
    JAVA_9(9, "Java 9", "Java 9", "List.of / Set.of / Map.of factory collections & private interface methods"),
    JAVA_10(10, "Java 10", "Java 10", "Local variable type inference with var"),
    JAVA_11(11, "Java 11 LTS", "Java 11", "String.isBlank(), repeat(), lines(), var lambda params"),
    JAVA_12(12, "Java 12", "Java 12", "Switch expressions preview & compact formatting"),
    JAVA_13(13, "Java 13", "Java 13", "Text blocks \"\"\" & switch yield"),
    JAVA_14(14, "Java 14", "Java 14", "Standard switch expressions & multi-case arrows"),
    JAVA_15(15, "Java 15", "Java 15", "Standard text blocks \"\"\" & sealed classes preview"),
    JAVA_16(16, "Java 16", "Java 16", "Records & pattern matching for instanceof"),
    JAVA_17(17, "Java 17 LTS", "Java 17", "Text blocks, records, sealed classes, switch expressions"),
    JAVA_18(18, "Java 18", "Java 18", "UTF-8 default charset & modern JDK idioms"),
    JAVA_19(19, "Java 19", "Java 19", "Record patterns & virtual thread API previews"),
    JAVA_20(20, "Java 20", "Java 20", "Scoped values & pattern matching refinements"),
    JAVA_21(21, "Java 21 LTS", "Java 21", "Unnamed _ variables, sequenced collections, modern switch"),
    JAVA_22(22, "Java 22", "Java 22", "Unnamed _ patterns/variables finalized & Cloud JDK 22"),
    JAVA_23(23, "Java 23", "Java 23", "Compact source files & primitive pattern previews"),
    JAVA_24(24, "Java 24", "Java 24", "Stream gatherers & modern Java 24 toolchain"),
    JAVA_25(25, "Java 25 LTS", "Java 25", "Latest Java 25 LTS: IO.println, compact main, Cloud JDK 25");

    companion object {
        fun fromMajor(major: Int): JavaVersionOption {
            return entries.firstOrNull { it.majorVersion == major }
                ?: entries.minByOrNull { kotlin.math.abs(it.majorVersion - major) }
                ?: JAVA_17
        }
    }
}

enum class ProjectLanguage(
    val displayName: String,
    val extension: String,
    val badge: String,
) {
    JAVA(
        displayName = "Java",
        extension = "java",
        badge = "Java",
    ),
    KOTLIN(
        displayName = "Kotlin",
        extension = "kt",
        badge = "Kotlin",
    ),
}

enum class AndroidMinSdk(
    val apiLevel: Int,
    val androidVersion: String,
    val codename: String,
) {
    API_21(21, "Android 5.0", "Lollipop"),
    API_23(23, "Android 6.0", "Marshmallow"),
    API_24(24, "Android 7.0", "Nougat"),
    API_26(26, "Android 8.0", "Oreo"),
    API_28(28, "Android 9", "Pie"),
    API_29(29, "Android 10", "Q"),
    API_30(30, "Android 11", "R"),
    API_31(31, "Android 12", "S"),
    API_33(33, "Android 13", "Tiramisu"),
    API_34(34, "Android 14", "Upside Down Cake"),
    API_35(35, "Android 15", "Vanilla Ice Cream");

    val shortLabel: String
        get() = "API $apiLevel ($androidVersion)"

    val compactLabel: String
        get() = "API $apiLevel"

    companion object {
        fun fromApiLevel(level: Int): AndroidMinSdk {
            return entries.minByOrNull { kotlin.math.abs(it.apiLevel - level) } ?: API_24
        }
    }
}

enum class ProjectTemplate(
    val displayName: String,
    val subtitle: String,
    val defaultAppName: String,
    val defaultPackage: String,
) {
    COUNTER_APP(
        displayName = "Counter Studio",
        subtitle = "Interactive counter with state, Material cards, and Toast feedback",
        defaultAppName = "Counter App",
        defaultPackage = "com.example.counter",
    ),
    ARCADE_SHOOTER(
        displayName = "Neon Shooter Game",
        subtitle = "60fps custom Canvas arcade shooter with touch ship control, lasers & waves",
        defaultAppName = "Shooter Pro",
        defaultPackage = "com.example.shooterpro",
    ),
    VOXEL_LAUNCHER_STUDIO(
        displayName = "Voxel World & JVM",
        subtitle = "Original 2.5D voxel sandbox engine with runtime profiles & Cloud CI scaffold",
        defaultAppName = "VoxelCraft Studio",
        defaultPackage = "com.example.voxelstudio",
    ),
    CANVAS_DRAW(
        displayName = "Canvas Sketchpad",
        subtitle = "Touch-enabled custom View painting pad with color swatches & clear",
        defaultAppName = "SketchPad",
        defaultPackage = "com.example.sketchpad",
    ),
    CALCULATOR(
        displayName = "Pocket Calculator",
        subtitle = "Clean GridLayout calculator with expression display & operations",
        defaultAppName = "CalcForge",
        defaultPackage = "com.example.calcforge",
    ),
    NOTES_APP(
        displayName = "Quick Notes",
        subtitle = "Interactive note composer with live list & timestamp cards",
        defaultAppName = "ForgeNotes",
        defaultPackage = "com.example.forgenotes",
    ),
    STOPWATCH_APP(
        displayName = "Pulse Timer",
        subtitle = "Chronometer & lap timer with start, pause, lap, and reset controls",
        defaultAppName = "PulseTimer",
        defaultPackage = "com.example.pulsetimer",
    ),
    BLANK_ACTIVITY(
        displayName = "Blank Activity",
        subtitle = "Minimal clean Android Activity starter ready for custom code",
        defaultAppName = "My App",
        defaultPackage = "com.example.myapp",
    ),
}

data class GitHubRepoLink(
    val owner: String,
    val repo: String,
    val branch: String = "main",
    val lastCommitSha: String = "",
    val lastPushedAtMillis: Long = 0L,
    val lastPushedHashes: Map<String, String> = emptyMap(),
) {
    val fullName: String get() = "$owner/$repo"
    val htmlUrl: String get() = "https://github.com/$owner/$repo"
}

enum class BuildVariant(
    val label: String,
    val shortLabel: String,
    val extension: String,
    val isRelease: Boolean,
    val isBundle: Boolean,
) {
    DEBUG_APK(
        label = "Debug APK",
        shortLabel = "Debug APK",
        extension = "apk",
        isRelease = false,
        isBundle = false,
    ),
    RELEASE_APK(
        label = "Release APK",
        shortLabel = "Release APK",
        extension = "apk",
        isRelease = true,
        isBundle = false,
    ),
    RELEASE_AAB(
        label = "Release App Bundle (.aab)",
        shortLabel = "Release AAB",
        extension = "aab",
        isRelease = true,
        isBundle = true,
    ),
}

data class ReleaseSigningConfig(
    val keystorePath: String = "",
    val alias: String = "release",
    val storePassword: String = "",
    val keyPassword: String = "",
    val commonName: String = "",
    val organization: String = "",
    val sha1Fingerprint: String = "",
    val sha256Fingerprint: String = "",
) {
    val isConfigured: Boolean
        get() = keystorePath.isNotBlank() && alias.isNotBlank() && storePassword.isNotBlank()
}

data class ProjectMetadata(
    val id: String,
    val name: String,
    val packageName: String,
    val template: ProjectTemplate,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val language: ProjectLanguage = ProjectLanguage.JAVA,
    val javaVersion: JavaVersionOption = JavaVersionOption.JAVA_17,
    val minSdkVersion: Int = 24,
    val githubLink: GitHubRepoLink? = null,
    val lastBuildSuccess: Boolean? = null,
    val lastApkSizeBytes: Long? = null,
    val lastBuildTimestamp: Long? = null,
    val lastBuildVariant: BuildVariant? = null,
)

enum class FileLanguage {
    JAVA,
    KOTLIN,
    XML,
    JSON,
    TEXT,
}

data class ProjectFile(
    val relativePath: String,
    val content: String,
    val language: FileLanguage,
    val isBinary: Boolean = false,
) {
    val fileName: String
        get() = relativePath.substringAfterLast('/')
}

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
}

data class BuildDiagnostic(
    val filePath: String,
    val line: Int,
    val column: Int,
    val severity: DiagnosticSeverity,
    val message: String,
)

data class ApkEntryInfo(
    val name: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val isStored: Boolean,
)

data class BuildResult(
    val success: Boolean,
    val variant: BuildVariant = BuildVariant.DEBUG_APK,
    val apkPath: String? = null,
    val apkSizeBytes: Long = 0L,
    val compiledClassCount: Int = 0,
    val durationMs: Long = 0L,
    val logs: List<String> = emptyList(),
    val diagnostics: List<BuildDiagnostic> = emptyList(),
    val apkEntries: List<ApkEntryInfo> = emptyList(),
    val signerSubject: String = "",
    val signerSha256: String = "",
    val artifactSha256: String = "",
)

data class EditorSettings(
    val fontSizeSp: Int = 13,
    val wordWrap: Boolean = false,
    val autoIndent: Boolean = true,
    val darkTheme: Boolean = true,
)

data class ProjectSearchHit(
    val relativePath: String,
    val lineNumber: Int,
    val previewLine: String,
)

data class AiAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val isImage: Boolean,
    val sizeBytes: Long,
    val base64Data: String = "",
    val textContent: String = "",
    val previewUriString: String = "",
)
