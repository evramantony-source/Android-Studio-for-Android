package com.example.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckToken
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"
private const val PROVISIONED_FIREBASE_PROJECT_ID = "gen-lang-client-0552974152"
private const val PROVISIONED_RECAPTCHA_SITE_KEY = "6LepvR8uAAAAANbf8BVV-o5L6Abjj0gQjsINpfMf"
private const val DUMMY_ERROR_TOKEN = "eyJlcnJvciI6IlVOS05PV05fRVJST1IifQ=="

enum class AppCheckProviderMode(val label: String) {
    UNCONFIGURED("Not configured"),
    DEBUG_INTENT_TOKEN("Debug Token (Intent)"),
    DEBUG_SAVED_TOKEN("Debug Token (Saved)"),
    RECAPTCHA_ENTERPRISE("reCAPTCHA Enterprise"),
    DEBUG_UNREGISTERED("Debug Provider (No Key)"),
}

data class AppCheckDiagnosticSnapshot(
    val mode: AppCheckProviderMode = AppCheckProviderMode.UNCONFIGURED,
    val hasSiteKeyResource: Boolean = false,
    val isDebugBuild: Boolean = BuildConfig.DEBUG,
    val projectId: String = "",
    val certSha1: String = "",
    val lastTokenError: String? = null,
) {
    fun summaryLabel(): String {
        val buildKind = if (isDebugBuild) "debug" else "release"
        val certPart = if (certSha1.isNotBlank()) " • cert ${certSha1.take(11)}…" else ""
        val keyPart = if (mode == AppCheckProviderMode.RECAPTCHA_ENTERPRISE && !hasSiteKeyResource) {
            " • fallback site key"
        } else {
            ""
        }
        return "${mode.label} ($buildKind$certPart$keyPart)"
    }
}

sealed interface AppCheckPreflightResult {
    data class Valid(val tokenPrefix: String, val diagnostic: AppCheckDiagnosticSnapshot) : AppCheckPreflightResult
    data class Invalid(val reason: String, val diagnostic: AppCheckDiagnosticSnapshot) : AppCheckPreflightResult
}

object AppCheckDiagnostics {
    @Volatile
    var snapshot: AppCheckDiagnosticSnapshot = AppCheckDiagnosticSnapshot()
        internal set

    fun isAppCheckError(error: Throwable?): Boolean {
        val msg = error?.message ?: error?.localizedMessage ?: return false
        return isAppCheckMessage(msg)
    }

    fun isAppCheckMessage(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lower = message.lowercase()
        return lower.contains("app check") ||
            lower.contains("appcheck") ||
            lower.contains("attestation") ||
            lower.contains("recaptcha")
    }
}

fun configureAppCheck(context: Context, intent: Intent?) {
    val certSha1 = readApkSigningSha1(context)
    try {
        val firebaseApp = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
        if (firebaseApp == null) {
            Log.w(TAG, "FirebaseApp not configured (no google-services.json); skipping App Check setup.")
            AppCheckDiagnostics.snapshot = AppCheckDiagnosticSnapshot(
                mode = AppCheckProviderMode.UNCONFIGURED,
                certSha1 = certSha1,
            )
            return
        }

        val projectId = firebaseApp.options.projectId.orEmpty()
        val persistenceKey = firebaseApp.persistenceKey
        val debugPrefs = context.getSharedPreferences(
            "$DEBUG_STORE_PREFIX$persistenceKey",
            Context.MODE_PRIVATE,
        )

        val intentDebugToken = if (BuildConfig.DEBUG) intent?.getStringExtra(DEBUG_TOKEN_EXTRA) else null
        val savedDebugToken = if (BuildConfig.DEBUG) debugPrefs.getString(DEBUG_SECRET_KEY, null) else null

        if (BuildConfig.DEBUG && !intentDebugToken.isNullOrEmpty()) {
            // Synchronous commit() ensures the secret is written before DebugAppCheckProviderFactory reads it.
            debugPrefs.edit()
                .putString(DEBUG_SECRET_KEY, intentDebugToken)
                .commit()
            Log.d(TAG, "App Check debug secret configured from launch Intent.")

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance(),
            )
            AppCheckDiagnostics.snapshot = AppCheckDiagnosticSnapshot(
                mode = AppCheckProviderMode.DEBUG_INTENT_TOKEN,
                hasSiteKeyResource = false,
                isDebugBuild = BuildConfig.DEBUG,
                projectId = projectId,
                certSha1 = certSha1,
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed (intent token).")
        } else if (BuildConfig.DEBUG && !savedDebugToken.isNullOrEmpty()) {
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance(),
            )
            AppCheckDiagnostics.snapshot = AppCheckDiagnosticSnapshot(
                mode = AppCheckProviderMode.DEBUG_SAVED_TOKEN,
                hasSiteKeyResource = false,
                isDebugBuild = BuildConfig.DEBUG,
                projectId = projectId,
                certSha1 = certSha1,
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed (persisted debug token).")
        } else {
            val resId = context.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", context.packageName)
            val resourceSiteKey = if (resId != 0) context.getString(resId).trim() else ""
            val resolvedSiteKey = when {
                resourceSiteKey.isNotBlank() -> resourceSiteKey
                projectId == PROVISIONED_FIREBASE_PROJECT_ID -> PROVISIONED_RECAPTCHA_SITE_KEY
                else -> ""
            }
            if (resolvedSiteKey.isNotBlank()) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(resolvedSiteKey),
                )
                AppCheckDiagnostics.snapshot = AppCheckDiagnosticSnapshot(
                    mode = AppCheckProviderMode.RECAPTCHA_ENTERPRISE,
                    hasSiteKeyResource = resourceSiteKey.isNotBlank(),
                    isDebugBuild = BuildConfig.DEBUG,
                    projectId = projectId,
                    certSha1 = certSha1,
                )
                Log.d(
                    TAG,
                    "RecaptchaAppCheckProviderFactory installed (fromResource=${resourceSiteKey.isNotBlank()}, debug=${BuildConfig.DEBUG}).",
                )
            } else if (BuildConfig.DEBUG) {
                Log.w(TAG, "No debug token or recaptcha_site_key found; falling back to unregistered DebugAppCheckProviderFactory.")
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance(),
                )
                AppCheckDiagnostics.snapshot = AppCheckDiagnosticSnapshot(
                    mode = AppCheckProviderMode.DEBUG_UNREGISTERED,
                    hasSiteKeyResource = false,
                    isDebugBuild = BuildConfig.DEBUG,
                    projectId = projectId,
                    certSha1 = certSha1,
                )
            } else {
                Log.e(TAG, "recaptcha_site_key resource missing or blank in release build.")
                AppCheckDiagnostics.snapshot = AppCheckDiagnosticSnapshot(
                    mode = AppCheckProviderMode.UNCONFIGURED,
                    hasSiteKeyResource = false,
                    isDebugBuild = false,
                    projectId = projectId,
                    certSha1 = certSha1,
                    lastTokenError = "recaptcha_site_key resource missing",
                )
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
        AppCheckDiagnostics.snapshot = AppCheckDiagnostics.snapshot.copy(
            lastTokenError = e.localizedMessage ?: e.javaClass.simpleName,
            certSha1 = certSha1,
        )
    }
}

suspend fun ensureValidAppCheckToken(timeoutMs: Long = 15_000L): AppCheckPreflightResult {
    val first = fetchAppCheckTokenOnce(forceRefresh = false, timeoutMs = timeoutMs)
    if (first is AppCheckPreflightResult.Valid) return first

    // Retry once with forceRefresh=true to evict any stale cached/dummy token from an earlier install
    delay(300L)
    return fetchAppCheckTokenOnce(forceRefresh = true, timeoutMs = timeoutMs)
}

suspend fun forceRefreshAppCheckToken(timeoutMs: Long = 15_000L): AppCheckPreflightResult {
    return fetchAppCheckTokenOnce(forceRefresh = true, timeoutMs = timeoutMs)
}

private suspend fun fetchAppCheckTokenOnce(
    forceRefresh: Boolean,
    timeoutMs: Long,
): AppCheckPreflightResult {
    val diag = AppCheckDiagnostics.snapshot
    return try {
        val token: AppCheckToken = withTimeout(timeoutMs) {
            awaitGmsTask(FirebaseAppCheck.getInstance().getAppCheckToken(forceRefresh))
        }
        val raw = token.token
        if (raw.isBlank() || raw == DUMMY_ERROR_TOKEN) {
            val reason = "App Check returned placeholder error token (${diag.summaryLabel()})."
            AppCheckDiagnostics.snapshot = diag.copy(lastTokenError = reason)
            AppCheckPreflightResult.Invalid(reason, AppCheckDiagnostics.snapshot)
        } else {
            AppCheckDiagnostics.snapshot = diag.copy(lastTokenError = null)
            AppCheckPreflightResult.Valid(raw.take(12), AppCheckDiagnostics.snapshot)
        }
    } catch (e: CancellationException) {
        if (e is TimeoutCancellationException) {
            val reason = "App Check attestation timed out after ${timeoutMs / 1000}s (${diag.summaryLabel()})."
            AppCheckDiagnostics.snapshot = diag.copy(lastTokenError = reason)
            return AppCheckPreflightResult.Invalid(reason, AppCheckDiagnostics.snapshot)
        }
        throw e
    } catch (e: Exception) {
        val rawMsg = e.localizedMessage?.ifBlank { null } ?: e.javaClass.simpleName
        Log.w(TAG, "App Check token fetch failed (forceRefresh=$forceRefresh, ${diag.summaryLabel()}): $rawMsg", e)
        AppCheckDiagnostics.snapshot = diag.copy(lastTokenError = rawMsg)
        AppCheckPreflightResult.Invalid(rawMsg, AppCheckDiagnostics.snapshot)
    }
}

private suspend fun <T> awaitGmsTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
    task.addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }.addOnFailureListener { ex ->
        if (cont.isActive) cont.resumeWithException(ex)
    }.addOnCanceledListener {
        cont.cancel()
    }
}

@Suppress("DEPRECATION")
private fun readApkSigningSha1(context: Context): String {
    return runCatching {
        val pm = context.packageManager
        val pkgName = context.packageName
        val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pkgInfo = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = pkgInfo.signingInfo
            val sig = signingInfo?.apkContentsSigners?.firstOrNull()
                ?: signingInfo?.signingCertificateHistory?.firstOrNull()
            sig?.toByteArray()
        } else {
            val pkgInfo = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
            pkgInfo.signatures?.firstOrNull()?.toByteArray()
        }
        if (certBytes == null) {
            ""
        } else {
            val digest = MessageDigest.getInstance("SHA-1").digest(certBytes)
            digest.joinToString(":") { "%02X".format(it) }
        }
    }.getOrDefault("")
}
