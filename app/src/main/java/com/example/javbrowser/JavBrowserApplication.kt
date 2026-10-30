package com.example.javbrowser

import android.app.Activity
import android.app.Application
import android.os.Bundle

class JavBrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppTelemetry.initialize(this)
        // App-layer ECH+H3: install Conscrypt (provider #1) so the EchH3
        // factory can build ECH-capable clients. CronetEngine itself stays
        // lazy (built on first non-ECH search). Telemetry untouched.
        EchH3Router.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (state != null) return
                val feature = when (activity) {
                    is MainActivity -> AppTelemetry.Feature.BROWSER
                    is FullscreenInternalPlayerActivity -> AppTelemetry.Feature.PLAYER
                    is DownloadsActivity -> AppTelemetry.Feature.DOWNLOADS
                    is PlaybackHistoryActivity -> AppTelemetry.Feature.HISTORY
                    is UnifiedSearchActivity -> AppTelemetry.Feature.SEARCH
                    is SettingsActivity -> AppTelemetry.Feature.SETTINGS
                    else -> return
                }
                AppTelemetry.featureOpened(feature)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
