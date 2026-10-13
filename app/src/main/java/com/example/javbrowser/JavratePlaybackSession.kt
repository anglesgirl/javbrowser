package com.example.javbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.IOException

/** Browser-owned authorization, independent of the visible player's controls and decoder. */
class JavratePlaybackSession(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Object()
    private var browser: WebView? = null
    private var source: String? = null
    private var validUntil = 0L
    private var closed = false
    private var ready = false
    private var started: ((String) -> Unit)? = null
    private var failed: (() -> Unit)? = null
    private var tracksChanged: ((String) -> Unit)? = null
    private val timeout = Runnable { if (!ready && !closed) { failed?.invoke(); close() } }

    @SuppressLint("SetJavaScriptEnabled")
    fun start(embed: String, page: String, onReady: (String) -> Unit, onFailure: () -> Unit,
              onSubtitles: (String) -> Unit = {}) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!JavrateStreams.isPlayer(embed) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            onFailure(); return
        }
        started = onReady
        failed = onFailure
        tracksChanged = onSubtitles
        val view = WebView(context)
        browser = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.addJavascriptInterface(object {
            @JavascriptInterface fun onSubtitles(raw: String) {
                val filtered = runCatching {
                    val input = org.json.JSONArray(raw)
                    org.json.JSONArray().apply {
                        for (i in 0 until minOf(input.length(), 20)) {
                            val item = input.optJSONObject(i) ?: continue
                            if (JavrateSubtitles.isAllowed(item.optString("src"))) put(org.json.JSONObject().apply {
                                put("src", item.getString("src"))
                                put("label", item.optString("label").take(80))
                                put("lang", item.optString("lang").take(30))
                            })
                        }
                    }.toString()
                }.getOrNull() ?: return
                handler.post { if (!closed) tracksChanged?.invoke(filtered) }
            }
            @JavascriptInterface fun onSource(url: String, remainingMs: Long) {
                if (!JavrateStreams.isSigned(url) || remainingMs < 2000) return
                synchronized(lock) {
                    if (closed || (source != null && !JavrateStreams.sameAsset(source!!, url))) return
                    source = url
                    validUntil = System.nanoTime() + remainingMs.coerceAtMost(60_000) * 1_000_000
                    lock.notifyAll()
                }
                handler.post {
                    if (!closed && !ready) {
                        ready = true
                        handler.removeCallbacks(timeout)
                        started?.invoke(url)
                        started = null
                        failed = null
                    }
                }
            }
            @JavascriptInterface fun onFailure() {
                // Keep a still-valid token on a transient failure; the timer retries.
                synchronized(lock) { lock.notifyAll() }
            }
        }, "JavrateAuth")
        WebViewCompat.addDocumentStartJavaScript(view,
            context.assets.open("javrate-session.js").bufferedReader(Charsets.UTF_8).use { it.readText() },
            setOf("https://www.javrate.com", "https://javrate.com"))
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                request?.isForMainFrame == true && !JavrateStreams.isPlayer(request.url.toString())
        }
        handler.postDelayed(timeout, 25_000)
        view.loadUrl(embed, mapOf("Referer" to page))
    }

    /** Called only by proxy/download worker threads, never blocks the UI thread. */
    fun authorize(url: String): String {
        synchronized(lock) {
            val existing = source ?: throw IOException("JavRate authorization not ready")
            if (!JavrateStreams.belongsToAsset(existing, url)) return url
            if (closed) throw IOException("JavRate playback session closed")
            if (validUntil - System.nanoTime() < 2_000_000_000L) {
                handler.post { browser?.evaluateJavascript("window.__javrateRefresh && window.__javrateRefresh()", null) }
                val deadline = System.nanoTime() + 10_000_000_000L
                while (!closed && validUntil - System.nanoTime() < 2_000_000_000L) {
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0) throw IOException("JavRate authorization refresh timed out")
                    lock.wait(remaining)
                }
            }
            if (closed) throw IOException("JavRate playback session closed")
            return JavrateStreams.authorize(source!!, url)
        }
    }

    fun close() {
        synchronized(lock) { closed = true; source = null; lock.notifyAll() }
        handler.removeCallbacks(timeout)
        browser?.destroy()
        browser = null
        started = null
        failed = null
        tracksChanged = null
    }

    fun clearSubtitleListener() { tracksChanged = null }
}
