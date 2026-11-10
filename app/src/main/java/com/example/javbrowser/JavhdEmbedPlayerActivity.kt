package com.example.javbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * javhd 嵌頁直播播放器（新路，2026-11-10 用戶定）。
 *
 * 嗅探取直鏈 + 原生播放器這條路走不通（播放器 onCreate 跑了、正片就是不出），
 * 改走網頁本身：網頁一定能播（瀏覽器可播），只是廣告太多。做法是把
 * /api/play/ 拿到的嵌頁（video.javhdporn.net）直接裝進全螢幕 WebView，
 * 用站方自己的 JWPlayer 原頁播出；廣告請求在 shouldInterceptRequest 攔掉，
 * VAST 貼片放行（攔了它跳過按鈕不出現、正片不播，vc140 教訓），再用 JS
 * 輪詢自動點跳過。效果與原生播放器一樣，只是不經 ExoPlayer。
 *
 * 嵌頁 HTML 先走 ECH 預載（SearchHttpFetcher），再 loadDataWithBaseURL——
 * 與嗅探同一招，WebView 主框直連 video.javhdporn.net 會被 RST。
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

        /** 與 UnifiedSearchActivity.javhdAdHosts 同步；tsyndicate.com（VAST 貼片）絕不能加進來。 */
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
    }

    private lateinit var webView: WebView
    private var sessionId: Long = -1
    private var enteredAtMs: Long = 0
    private var outcomeRecorded: Boolean = false
    private var skipStarted: Boolean = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var skipRunnable: Runnable? = null

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
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = EMBED_UA
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                if (AD_HOSTS.any { reqUrl.contains(it) }) {
                    return WebResourceResponse(
                        "text/plain", "utf-8",
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                if (request?.method.equals("GET", ignoreCase = true) == true) {
                    EchH3Router.fetchWebViewResource(
                        reqUrl, request?.requestHeaders, request?.isForMainFrame == true,
                    )?.let { return it }
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                startSkipPolling()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && error != null) {
                    PlaybackDataStore.addTrace(
                        sessionId,
                        "嵌頁播放器錯誤：${error.errorCode} ${error.description}"
                    )
                }
            }
        }
        // ECH 預載嵌頁 HTML（與嗅探同一招），失敗回退 WebView 直載。
        PlaybackDataStore.addTrace(sessionId, "嵌頁播放器：載入 ${embedUrl.take(100)}")
        SearchScheduler.workers.execute {
            val site = SearchSiteRegistry.find(this, SearchSiteIds.JAVHD)
            val embed = try {
                site?.let { SearchHttpFetcher().fetch(embedUrl, SearchCancellation(), it) }
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                if (embed != null && embed.transportError == null &&
                    embed.statusCode in 200..299 && embed.body.isNotBlank()
                ) {
                    val baseUrl = embed.finalUrl.ifBlank { embedUrl }
                    webView.loadDataWithBaseURL(baseUrl, embed.body, "text/html", "utf-8", baseUrl)
                } else {
                    webView.loadUrl(embedUrl)
                }
            }
        }
    }

    /**
     * 輪詢點跳過：先點真按鈕 .jw-skip，再點 Skip 文字，最後才試 skipAd() API——
     * 與嗅探的 skipAd 同一順序（vc141 教訓：API 先返回會遮住真按鈕，且不可跳
     * 狀態時 API 是空操作）。貼片 5 秒後按鈕出現，輪詢 2 秒一次，60 秒後停。
     */
    private fun startSkipPolling() {
        if (skipStarted) return
        skipStarted = true
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed || !::webView.isInitialized) return
                webView.evaluateJavascript(
                    "(function(){" +
                        "var btn=document.querySelector('.jw-skip');" +
                        "if(btn){try{btn.click();return 'clicked:.jw-skip';}catch(e){}}" +
                        "var els=document.querySelectorAll('button,div,span,a');" +
                        "for(var i=0;i<els.length;i++){" +
                        "var t=(els[i].textContent||'').trim();" +
                        "if(t==='Skip Ad'||t==='Skip'||t==='跳过'||t==='跳過'){try{els[i].click();return 'clicked:text:'+t;}catch(e){}}}" +
                        "try{if(typeof jwplayer!=='undefined'){var p=jwplayer();if(p&&typeof p.skipAd==='function'){p.skipAd();return 'api:skipAd()';}}}catch(e){}" +
                        "return 'no skip button';})();",
                    null
                )
                handler.postDelayed(this, 2000)
            }
        }
        skipRunnable = poll
        handler.postDelayed(poll, 5000)
        handler.postDelayed({ handler.removeCallbacks(poll) }, 65000)
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onPause() {
        super.onPause()
        recordWebViewPlayback()
    }

    /** 讀 video 元素狀態，回填進播放數據（與內建播放器同一寫法）。 */
    private fun recordWebViewPlayback() {
        if (sessionId < 0 || outcomeRecorded) return
        if (!::webView.isInitialized) return
        outcomeRecorded = true
        try {
            webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(!v) return 'novideo';" +
                    "return JSON.stringify({t:v.currentTime||0,d:v.duration||0,p:v.paused?1:0,e:v.error?v.error.code:0});})();"
            ) { value ->
                try {
                    val wallSeconds = (System.currentTimeMillis() - enteredAtMs) / 1000.0
                    if (value == null || value == "null" || value == "\"novideo\"" || value == "novideo") {
                        PlaybackDataStore.recordPlayback(
                            sessionId,
                            PlaybackOutcome("嵌頁", false, 0.0, wallSeconds, false, "無video元素"),
                        )
                        return@evaluateJavascript
                    }
                    val inner = org.json.JSONTokener(value).nextValue() as? String ?: return@evaluateJavascript
                    val o = org.json.JSONObject(inner)
                    val secondsPlayed = o.optDouble("t", 0.0)
                    val errorCode = o.optInt("e", 0)
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
        } catch (e: Exception) {
            // 讀狀態失敗不影響退出。
        }
    }

    override fun onDestroy() {
        skipRunnable?.let { handler.removeCallbacks(it) }
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
