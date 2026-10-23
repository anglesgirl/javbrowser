package com.example.javbrowser

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.io.IOException

/** Closed event/parameter sets: webpage content and raw errors must never enter this API. */
object AppTelemetry {
    enum class Feature { BROWSER, PLAYER, DOWNLOADS, HISTORY, SEARCH, SETTINGS }
    enum class DownloadOutcome { STARTED, COMPLETED, CANCELED, FAILED }
    enum class PlaybackPhase { STARTUP, PLAYBACK, AUTHORIZATION }

    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun initialize(context: Context) {
        analytics = FirebaseAnalytics.getInstance(context).apply {
            setAnalyticsCollectionEnabled(true)
            setConsent(mapOf(
                FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to FirebaseAnalytics.ConsentStatus.GRANTED,
                FirebaseAnalytics.ConsentType.AD_STORAGE to FirebaseAnalytics.ConsentStatus.DENIED,
                FirebaseAnalytics.ConsentType.AD_USER_DATA to FirebaseAnalytics.ConsentStatus.DENIED,
                FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to FirebaseAnalytics.ConsentStatus.DENIED
            ))
        }
        crashlytics = FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(true)
            setCustomKey("distribution", "github_apk")
            setCustomKey("telemetry_schema", 1)
        }
        // Firebase's crash handler is installed before Application.onCreate. Pass it a
        // sanitized copy; exception messages can otherwise contain media URLs or tokens.
        val firebaseHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (firebaseHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                firebaseHandler.uncaughtException(thread, TelemetryErrors.sanitized(error))
            }
        }
    }

    fun featureOpened(feature: Feature) = event("feature_open", Bundle().apply {
        putString("feature", feature.name.lowercase(java.util.Locale.ROOT))
    })

    fun playerAttempt(local: Boolean) = event("player_attempt", playbackParams(local))

    fun playerStarted(local: Boolean) = event("player_started", playbackParams(local))

    fun playerFailed(local: Boolean, phase: PlaybackPhase, code: Int) =
        event("player_error", playbackParams(local).apply {
            putString("phase", phase.name.lowercase(java.util.Locale.ROOT))
            // HTMLMediaElement error codes are 1..4; 0 represents a native setup failure.
            putLong("media_error_code", code.takeIf { it in 1..4 }?.toLong() ?: 0L)
        })

    fun download(outcome: DownloadOutcome, error: Throwable? = null) {
        event("download_result", Bundle().apply {
            putString("outcome", outcome.name.lowercase(java.util.Locale.ROOT))
            if (error != null) putString("error_category", when (error) {
                is SecurityException -> "permission"
                is IOException -> "io"
                else -> "other"
            })
        })
        if (outcome == DownloadOutcome.FAILED && error != null) {
            runCatching { crashlytics?.recordException(TelemetryErrors.sanitized(error)) }
        }
    }

    private fun playbackParams(local: Boolean) = Bundle().apply {
        putString("mode", if (local) "offline" else "online")
    }

    private fun event(name: String, params: Bundle) {
        // Telemetry failures must not interfere with browsing, playback, or downloads.
        runCatching { analytics?.logEvent(name, params) }
    }
}
