package com.example.model

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
        subtitle = "Minimal clean Android Java Activity starter ready for custom code",
        defaultAppName = "My App",
        defaultPackage = "com.example.myapp",
    ),
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
    val lastBuildSuccess: Boolean? = null,
    val lastApkSizeBytes: Long? = null,
    val lastBuildTimestamp: Long? = null,
    val lastBuildVariant: BuildVariant? = null,
)

enum class FileLanguage {
    JAVA,
    XML,
    JSON,
    TEXT,
}

data class ProjectFile(
    val relativePath: String,
    val content: String,
    val language: FileLanguage,
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
