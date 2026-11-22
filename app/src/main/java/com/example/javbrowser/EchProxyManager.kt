package com.example.javbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import echproxy.Echproxy
import java.io.File
import java.util.concurrent.Executors

/**
 * Go 前置代理（co3/ech/echproxy，gomobile 导出）+ WebView 代理接线。
 *
 * 架构：App 启动时在后台线程起一个本机 HTTP 代理（127.0.0.1:PORT），
 * 它经 DoH 解析、只对 Cloudflare AS13335 且有自己 HTTPS ECH 记录的
 * 目标用 ECH，其余普通 TLS；WebView 经 androidx.webkit.ProxyController
 * 把全部流量（GET/POST、Cookie 全走 WebView 原生栈）指到该代理。
 * 代理起不来或 WebView 不支持 PROXY_OVERRIDE 时不设 override，
 * WebView 直接联网（无 ECH 但可用），绝不因此断网。
 *
 * DoH 用阿里 + 360（国内可达），与用户 DoH 基底一致。
 */
object EchProxyManager {
    private const val TAG = "EchProxy"
    private const val DOH = "https://dns.alidns.com/resolve,https://doh.360.cn/resolve"
    private val PORT_CANDIDATES = intArrayOf(18080, 18081, 18082, 18083, 18084)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    @Volatile var proxyPort: Int = -1
        private set
    @Volatile var started: Boolean = false
        private set
    @Volatile var startError: String? = null
        private set
    @Volatile var proxyOverrideApplied: Boolean = false
        private set

    // 代理就绪后要做的事（主线程回调）。applyProxyOverride() 在代理
    // 还没起好时登记回调，起好后自动执行。
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private var startRequested = false

    /**
     * App 启动时调一次（主进程）。后台线程起代理；成功后在主线程
     * 通知已登记的回调。重复调用无操作。
     */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (startRequested) return
        startRequested = true
        val appContext = context.applicationContext
        io.execute {
            val cacheDir = File(appContext.filesDir, "echproxy").apply { mkdirs() }
            var lastErr: Exception? = null
            for (port in PORT_CANDIDATES) {
                try {
                    // Start(listen, target, echB64, doh, ipList, cachePath, insecure)
                    // target 空（通用模式按 host 懒解析）；echB64 空（每 host
                    // 经 DoH 取自己的 ECH 配置）；ipList 空（代理自己优选 IP）。
                    Echproxy.start(
                        "127.0.0.1:$port",
                        "",
                        "",
                        DOH,
                        "",
                        cacheDir.absolutePath,
                        false
                    )
                    proxyPort = port
                    started = true
                    Log.i(TAG, "Go 代理已起：127.0.0.1:$port")
                    break
                } catch (e: Exception) {
                    lastErr = e
                    Log.w(TAG, "Go 代理起在 $port 失败：${e.message}")
                }
            }
            if (!started) {
                startError = lastErr?.message ?: "unknown"
                Log.w(TAG, "Go 代理全部端口失败，不设 WebView 代理：$startError")
            }
            val callbacks: List<() -> Unit>
            synchronized(this) {
                callbacks = readyCallbacks.toList()
                readyCallbacks.clear()
            }
            if (started) {
                mainHandler.post { callbacks.forEach { runCatching { it() } } }
            }
        }
    }

    /**
     * WebView 建好后调（MainActivity.setupWebView 末尾经 applyWebViewProxy
     * 调）。代理已起好立即设 override；还在起则登记回调，起好后自动设。
     * 代理失败或 WebView 不支持 PROXY_OVERRIDE 时静默跳过（直接联网）。
     */
    fun applyProxyOverride() {
        if (proxyOverrideApplied) return
        if (started) {
            setOverrideNow()
            return
        }
        if (startError != null) return // 代理已失败，不等了
        synchronized(this) {
            if (started) {
                mainHandler.post { setOverrideNow() }
            } else if (startError == null) {
                readyCallbacks.add { setOverrideNow() }
            }
        }
    }

    private fun setOverrideNow() {
        if (proxyOverrideApplied || !started || proxyPort <= 0) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "WebView 不支持 PROXY_OVERRIDE，不设代理")
            return
        }
        try {
            val config = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:$proxyPort")
                .build()
            ProxyController.getInstance().setProxyOverride(
                config,
                { it.run() },
                {
                    proxyOverrideApplied = true
                    Log.i(TAG, "WebView 代理已指到 127.0.0.1:$proxyPort")
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "设 WebView 代理失败：${e.message}")
        }
    }

    /** 诊断用：Go 代理最近状态（LastStatus）。 */
    fun status(): String = runCatching { Echproxy.lastStatus() }.getOrDefault("unknown")

    /** 诊断用：Go 代理最近日志环形缓冲。 */
    fun drainLogs(): String = runCatching { Echproxy.drainLogs() }.getOrDefault("")

    /** App 退出时调（一般不需要，进程死代理随之死）。 */
    fun stop() {
        runCatching { Echproxy.stop() }
        started = false
        proxyOverrideApplied = false
    }
}
