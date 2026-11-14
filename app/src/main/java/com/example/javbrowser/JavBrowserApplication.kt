package com.example.javbrowser

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process

class JavBrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase 默认不是多进程安全的：在次要进程里初始化 Analytics /
        // Crashlytics 会崩/挂。任何独立进程的 Activity 启动时
        // Application.onCreate() 都会再跑一遍，必须跳过遥测初始化。
        // 2026-11-14 教训：GeckoMinimalTestActivity 曾跑在 :geckotest
        // 独立进程，点了“最小测试”按钮没反应就是这个原因。
        if (!isMainProcess()) return
        AppTelemetry.initialize(this)
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

    /**
     * 主进程 = 进程名等于包名。Application.getProcessName() 需 API 28+，
     * minSdk 26 的老设备走 ActivityManager 回退。
     */
    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            @Suppress("DEPRECATION")
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.processName
        }
        return processName == packageName
    }
}
