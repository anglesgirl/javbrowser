package com.example.javbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.javbrowser.gecko.GeckoBridge
import com.example.javbrowser.gecko.GeckoEval
import com.example.javbrowser.gecko.GeckoHeadless
import org.json.JSONArray
import org.mozilla.geckoview.GeckoSession
import java.io.IOException

/** Browser-owned authorization, independent of the visible player's controls and decoder. */
class JavratePlaybackSession(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Object()
    private var session: GeckoSession? = null
    private var source: String? = null
    private var validUntil = 0L
    private var closed = false
    private var ready = false
    private var started: ((String) -> Unit)? = null
    private var failed: (() -> Unit)? = null
    private var tracksChanged: ((String) -> Unit)? = null
    private val timeout = Runnable { if (!ready && !closed) { failed?.invoke(); close() } }

    /**
     * GeckoView 版：無界面 GeckoSession 取代 WebView（見 playback/NOTES.md）。
     *
     * 對外行為與原版相同：start 拿授權、authorize 在 worker 執行緒等 token、
     * close 釋放。兩處與原版不同，皆因給定的共享 API 沒有等價能力：
     * ① document-start 注入沒有等價 API——javrate-session.js 改在載入
     *    完成後經 GeckoEval 注入，站方若在注入前已呼叫 createPlayer，
     *    授權就拿不到（25 秒逾時走 onFailure）；
     * ② GeckoHeadless.loadSession 不帶 Referer 參數，嵌頁載入不帶 Referer。
     * 橋名由 JavrateAuth 改為 Android（GeckoBridge 的 window.Android.*），
     * 注入前把 asset 內的 "JavrateAuth." 字面改寫為 "Android."；
     * window.__javrateRefresh（authorize 的刷新入口）名字不變。
     * 原 shouldOverrideUrlLoading 的導航守衛（只放行播放器主框導航）
     * 在無界面會話上沒有等價物，已拿掉；嵌頁本身不會主動跳走。
     */
    fun start(embed: String, page: String, onReady: (String) -> Unit, onFailure: () -> Unit,
              onSubtitles: (String) -> Unit = {}) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!JavrateStreams.isPlayer(embed)) {
            onFailure(); return
        }
        started = onReady
        failed = onFailure
        tracksChanged = onSubtitles
        handler.postDelayed(timeout, 25_000)
        // page（詳情頁）在原版只用來當 loadUrl 的 Referer；loadSession
        // 不帶 Referer 參數，目前傳不進去，見上面註解 ②。
        GeckoHeadless.loadSession(context, embed, 25_000L) { loaded ->
            if (closed) {
                loaded?.close()
                return@loadSession
            }
            if (loaded == null) {
                if (!ready) {
                    failed?.invoke()
                    close()
                }
                return@loadSession
            }
            session = loaded
            GeckoBridge.attachBridge(loaded, mapOf(
                "onSubtitles" to { args: JSONArray -> onSubtitlesBridge(args) },
                "onSource" to { args: JSONArray -> onSourceBridge(args) },
                "onFailure" to { onFailureBridge() }
            ))
            injectSessionScript(loaded)
        }
    }

    /**
     * 注入 javrate-session.js（原 addDocumentStartJavaScript 的內容）。
     * 注入時機是載入完成後，不是 document-start——見 start() 的註解 ①。
     */
    private fun injectSessionScript(geckoSession: GeckoSession) {
        val script = runCatching {
            context.assets.open("javrate-session.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return
        // 橋名改寫：asset 內呼叫 JavrateAuth.*，Gecko 版橋掛在 window.Android.*。
        // 只改寫橋呼叫，不改站方邏輯（createPlayer 攔截、token 刷新原樣）。
        GeckoEval.runJavascript(geckoSession, script.replace("JavrateAuth.", "Android."))
    }

    private fun onSubtitlesBridge(args: JSONArray) {
        val filtered = runCatching {
            val input = org.json.JSONArray(args.optString(0))
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

    private fun onSourceBridge(args: JSONArray) {
        val url = args.optString(0)
        val remainingMs = args.optLong(1)
        val signed = JavrateStreams.isSigned(url)
        if ((!signed && !JavrateStreams.isDirect(url)) || (signed && remainingMs < 2000)) return
        synchronized(lock) {
            if (closed || (source != null && source != url && !JavrateStreams.sameAsset(source!!, url))) return
            source = url
            validUntil = if (signed) System.nanoTime() + remainingMs.coerceAtMost(60_000) * 1_000_000 else Long.MAX_VALUE
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

    private fun onFailureBridge() {
        // Keep a still-valid token on a transient failure; the timer retries.
        synchronized(lock) { lock.notifyAll() }
    }

    /** Called only by proxy/download worker threads, never blocks the UI thread. */
    fun authorize(url: String): String {
        synchronized(lock) {
            val existing = source ?: throw IOException("JavRate authorization not ready")
            if (!JavrateStreams.belongsToAsset(existing, url)) return url
            if (closed) throw IOException("JavRate playback session closed")
            if (validUntil - System.nanoTime() < 2_000_000_000L) {
                handler.post {
                    session?.let {
                        GeckoEval.runJavascript(it, "window.__javrateRefresh && window.__javrateRefresh()")
                    }
                }
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
        session?.let { GeckoBridge.detachBridge(it); runCatching { it.close() } }
        session = null
        started = null
        failed = null
        tracksChanged = null
    }

    fun clearSubtitleListener() { tracksChanged = null }
}
