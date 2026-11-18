package com.example.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        val firebaseApp = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
        if (firebaseApp == null) {
            Log.w(TAG, "FirebaseApp not configured (no google-services.json); skipping App Check setup.")
            return
        }

        val debugToken = if (BuildConfig.DEBUG) intent?.getStringExtra(DEBUG_TOKEN_EXTRA) else null
        if (BuildConfig.DEBUG && !debugToken.isNullOrEmpty()) {
            val persistenceKey = FirebaseApp.getInstance().persistenceKey
            val prefs = context.getSharedPreferences(
                "$DEBUG_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE,
            )
            // Synchronous commit() ensures the secret is written before DebugAppCheckProviderFactory reads it.
            prefs.edit()
                .putString(DEBUG_SECRET_KEY, debugToken)
                .commit()
            Log.d(TAG, "App Check debug secret configured successfully.")

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance(),
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val resId = context.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", context.packageName)
            val siteKey = if (resId != 0) context.getString(resId) else ""
            if (siteKey.isNotBlank()) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey),
                )
                Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
            } else if (BuildConfig.DEBUG) {
                Log.w(TAG, "No debug token or recaptcha_site_key found; falling back to DebugAppCheckProviderFactory.")
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance(),
                )
            } else {
                Log.e(TAG, "recaptcha_site_key resource missing or blank.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
