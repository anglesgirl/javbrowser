package com.example.javbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * javhd 嵌頁直播播放器（WebView）。
 *
 * 嗅探取直鏈 + 原生播放器走不通，改走網頁本身：把 /api/play/ 拿到的嵌頁
 * （video.javhdporn.net）直接裝進全螢幕 WebView，用站方自己的 JWPlayer
 * 原頁播出。廣告攔截走 WebViewClient.shouldInterceptRequest（攔 11 個
 * 廣告域名，見 [AD_HOSTS]）；VAST 貼片（tsyndicate.com）放行（攔了它
 * 跳過按鈕不出現、正片不播，vc140 教訓），注入 JS 輪詢自動點跳過；
 * <video> 狀態每 2 秒 evaluateJavascript 輪詢一次，記播放數據。
 *
 * 廣告域名表須與 UnifiedSearchActivity.javhdAdHosts 同步；tsyndicate.com
 * （VAST 貼片）絕不能加進來。
 * 目前只播第一個 Part（Part1）；多 Part 的選集後續再加。
 */
class JavhdEmbedPlayerActivity : LocalizedActivity() {

    companion object {
        const val EXTRA_EMBED_URL = "embed_url"
        const val EXTRA_SESSION_ID = "playback_session_id"
        const val EXTRA_DETAIL_URL = "detail_url"
        const val EXTRA_TITLE = "title"

        private const val EMBED_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /** 嵌頁廣告域名（shouldInterceptRequest 攔截；須與 UnifiedSearchActivity.javhdAdHosts 同步）。 */
        private val AD_HOSTS = listOf(
            "whitetrafsa", "content-sync.xyz", "alaphoid.com", "udzpel.com",
            "jads.co", "wpadmngr.com", "xlviirdr.com", "xlirdr.com",
            "javhdporn.live", "javhd-trk.com", "vivodemisrentas.net",
        )

        fun intent(context: Context, embedUrl: String, sessionId: Long, detailUrl: String, title: String): Intent =
            Intent(context, JavhdEmbedPlayerActivity::class.java).apply {
                putExtra(EXTRA_EMBED_URL, embedUrl)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_DETAIL_URL, detailUrl)
                putExtra(EXTRA_TITLE, title)
            }

        /** VAST 跳過輪詢：JWPlayer 自帶 .jw-skip 鈕，否則點文字為 Skip 的可見元素；每 1 秒試一次。 */
        private const val SKIP_POLL_JS = """
            (function() {
              function trySkip() {
                try {
                  var skip = document.querySelector('.jw-skip');
                  if (skip && skip.offsetParent !== null) { skip.click(); return true; }
                  var els = document.querySelectorAll('div,button,span,a');
                  for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    if (el.textContent && el.textContent.trim() === 'Skip' && el.offsetParent !== null) {
                      el.click(); return true;
                    }
                  }
                } catch (e) {}
                return false;
              }
              trySkip();
              if (!window.__javSkipTimer) { window.__javSkipTimer = setInterval(trySkip, 1000); }
            })();
        """

        /** 讀 <video> 狀態（currentTime / errorCode 等，與原擴展 videoState 同款欄位）。 */
        private const val VIDEO_STATE_JS = """
            (function() {
              var v = document.querySelector('video');
              if (!v) return null;
              var s = {
                currentTime: v.currentTime || 0,
                duration: v.duration || 0,
                paused: v.paused,
                ended: v.ended,
                readyState: v.readyState,
                src: v.currentSrc || v.src || '',
                title: document.title || ''
              };
              if (v.error) {
                s.errorCode = v.error.code;
                s.errorMessage = v.error.message || '';
              }
              return JSON.stringify(s);
            })();
        """
    }

    private lateinit var webView: WebView
    private var sessionId: Long = -1
    private var enteredAtMs: Long = 0
    private var outcomeRecorded: Boolean = false
    /** 最近一次輪詢到的 <video> 狀態（每 2 秒讀一次）。 */
    @Volatile
    private var latestVideoState: JSONObject? = null
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollStarted = false
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollVideoState()
            pollHandler.postDelayed(this, 2000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaybackDataStore.init(applicationContext)
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1)
        enteredAtMs = System.currentTimeMillis()
        val embedUrl = intent.getStringExtra(EXTRA_EMBED_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isNotBlank()) this.title = title
        if (embedUrl.isBlank()) {
            Toast.makeText(this, "嵌頁地址為空", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.userAgentString = EMBED_UA
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host.orEmpty().lowercase()
                if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    // 廣告請求直接攔掉；tsyndicate.com（VAST）不在表裡，永遠放行。
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // 注入 VAST 跳過輪詢（JWPlayer 自帶跳過鈕 + 文字為 Skip 的可見元素）。
                view.evaluateJavascript(SKIP_POLL_JS, null)
                startVideoStatePoll()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    PlaybackDataStore.addTrace(
                        sessionId,
                        "嵌頁播放器錯誤：頁面載入失敗"
                    )
                }
            }
        }

        PlaybackDataStore.addTrace(sessionId, "嵌頁播放器：載入 ${embedUrl.take(100)}")
        webView.loadUrl(embedUrl)
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onPause() {
        super.onPause()
        recordPlayback()
    }

    private fun startVideoStatePoll() {
        if (pollStarted) return
        pollStarted = true
        pollHandler.post(pollRunnable)
    }

    /** 每 2 秒讀一次 <video> 狀態（原擴展回傳的 videoState 同款欄位）。 */
    private fun pollVideoState() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(VIDEO_STATE_JS) { raw ->
            try {
                if (raw == null || raw == "null") return@evaluateJavascript
                val inner = org.json.JSONTokener(raw).nextValue() as? String ?: return@evaluateJavascript
                latestVideoState = JSONObject(inner)
            } catch (_: Exception) {
                // 讀狀態失敗不影響播放。
            }
        }
    }

    /** 用最近輪詢到的 video 狀態，回填進播放數據（與內建播放器同一寫法）。 */
    private fun recordPlayback() {
        if (sessionId < 0 || outcomeRecorded) return
        outcomeRecorded = true
        try {
            val wallSeconds = (System.currentTimeMillis() - enteredAtMs) / 1000.0
            val state = latestVideoState
            if (state == null) {
                PlaybackDataStore.recordPlayback(
                    sessionId,
                    PlaybackOutcome("嵌頁", false, 0.0, wallSeconds, false, "無video元素"),
                )
                return
            }
            val secondsPlayed = state.optDouble("currentTime", 0.0)
            val errorCode = state.optInt("errorCode", 0)
            val error = if (errorCode != 0) "video error $errorCode" else null
            val started = secondsPlayed > 0.5
            val stalled = error == null && wallSeconds > 15.0 && secondsPlayed < 2.0
            PlaybackDataStore.recordPlayback(
                sessionId,
                PlaybackOutcome("嵌頁", started, secondsPlayed, wallSeconds, stalled, error),
            )
        } catch (e: Exception) {
            // 讀狀態失敗不影響退出。
        }
    }

    override fun onDestroy() {
        pollHandler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
