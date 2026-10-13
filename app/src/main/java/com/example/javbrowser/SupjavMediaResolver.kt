package com.example.javbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Each Server selection owns an isolated player chain and cancellation token. */
class SupjavMediaResolver(private val context: Context,
    private val userAgent: () -> String = { WebSettings.getDefaultUserAgent(context) }) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: WebView? = null
    private var done = AtomicBoolean(true)
    private var timeout: Runnable? = null
    private var poll: Runnable? = null
    private var pendingMedia: Runnable? = null

    fun close() {
        done.set(true)
        listOfNotNull(timeout, poll, pendingMedia).forEach(handler::removeCallbacks)
        timeout = null; poll = null; pendingMedia = null
        player?.stopLoading()
        player?.destroy()
        player = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(embed: String, page: String, callback: (String, String) -> Unit, failure: () -> Unit) {
        close()
        if (!AdditionalSiteMedia.hostMatches(page, "supjav.com") || SupjavSourceSession.origin(embed) == null) return
        val rules = AdFilterRules(context)
        val session = SupjavSourceSession(page, embed, rules.getCommonBlockList() + rules.getNetworkBlockList())
        if (!session.registerPlayer(embed)) { failure(); return }
        val completed = AtomicBoolean(false)
        done = completed
        val browser = WebView(context)
        player = browser
        var documentUrl = embed
        var promotions = 0
        val activatedPlayers = mutableSetOf<String>()
        val visited = mutableSetOf(embed)
        fun finish(media: String, referer: String) {
            if (done !== completed || !session.accepts(media, referer) || !completed.compareAndSet(false, true)) return
            close()
            callback(media, referer)
        }
        browser.settings.apply {
            javaScriptEnabled = true
            userAgentString = userAgent()
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
        }
        browser.layout(0, 0, (360 * context.resources.displayMetrics.density).toInt(),
            (240 * context.resources.displayMetrics.density).toInt())
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        val frameReports = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER) &&
            androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
        if (frameReports) {
            androidx.webkit.WebViewCompat.addWebMessageListener(browser, "SupjavMedia", setOf("*")) { _, message, sourceOrigin, _, _ ->
                if (done !== completed || completed.get()) return@addWebMessageListener
                val report = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull() ?: return@addWebMessageListener
                val url = report.optString("url")
                val ref = report.optString("ref")
                if (SupjavSourceSession.origin(url) != SupjavSourceSession.origin(sourceOrigin.toString())) return@addWebMessageListener
                if (!session.trustsPlayer(url)) {
                    val playerPath = runCatching { Regex("^/(e|embed|player)/", RegexOption.IGNORE_CASE)
                        .containsMatchIn(java.net.URI(url).path.orEmpty()) }.getOrDefault(false)
                    if (!playerPath || !session.trustsPlayer(ref) || !session.registerPlayer(url)) return@addWebMessageListener
                }
                val sources = report.optJSONArray("media") ?: return@addWebMessageListener
                for (i in 0 until sources.length()) {
                    val media = sources.optString(i)
                    if (session.accepts(media, url)) { finish(media, url); break }
                }
            }
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(browser, """
                (function(){
                    var activated=false;
                    function report(){
                        if (!activated && /(^|\.)streamtape\.(com|to)$/i.test(location.hostname)) {
                            var play=document.querySelector('#overlay, #mainvideo, .vjs-big-play-button');
                            if(play){activated=true;play.click();}
                        }
                        var media=[];
                        function add(u){if(u && /^https?:\/\//i.test(u))media.push(u);}
                        try { var e=jwplayer().getPlaylist()[0]||{}; add(e.file); (e.sources||[]).forEach(function(s){add(s.file);}); } catch(e) {}
                        document.querySelectorAll('video').forEach(function(v){add(v.currentSrc);add(v.src);v.querySelectorAll('source[src]').forEach(function(s){add(s.src);});});
                        try { SupjavMedia.postMessage(JSON.stringify({url:location.href,ref:document.referrer,media:media})); } catch(e) {}
                    }
                    setInterval(report,500);
                })();
            """.trimIndent(), setOf("*"))
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (session.isBlocked(url) || SupjavSourceSession.origin(url) == null) return true
                if (request?.isForMainFrame == true) {
                    if (session.accepts(url, documentUrl)) { finish(url, documentUrl); return true }
                    if (request.isRedirect) return !session.registerPlayer(url)
                    return SupjavSourceSession.origin(url) != SupjavSourceSession.origin(documentUrl)
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, icon: android.graphics.Bitmap?) {
                if (url != null && url != page && session.registerPlayer(url)) documentUrl = url
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val media = request?.url?.toString().orEmpty()
                if (session.isBlocked(media)) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                val referer = request?.requestHeaders.orEmpty().entries.firstOrNull { it.key.equals("Referer", true) }?.value
                // The SUPJAV wrapper returns 404 when loaded as a top-level document.
                // Observe its actual iframe navigation, then inspect that selected child player.
                if (!frameReports && request?.isForMainFrame == false && referer != null && session.trustsPlayer(referer) &&
                    runCatching { Regex("^/(e|embed|player)/", RegexOption.IGNORE_CASE)
                        .containsMatchIn(java.net.URI(media).path.orEmpty()) }.getOrDefault(false)) handler.post {
                    if (done === completed && !completed.get() && promotions < 5 && media !in visited && session.registerPlayer(media)) {
                        promotions++; visited.add(media)
                        browser.loadUrl(media, mapOf("Referer" to referer))
                    }
                }
                if (referer != null && session.accepts(media, referer)) handler.post {
                    if (done === completed && !completed.get() && pendingMedia == null) {
                        // Let the declared content playlist take priority over a preroll request.
                        pendingMedia = Runnable { finish(media, referer) }.also { handler.postDelayed(it, 1000) }
                    }
                }
                return null
            }
        }
        val scan = object : Runnable {
            override fun run() {
                if (done !== completed || completed.get()) return
                val inspectedDocument = documentUrl
                if ((AdditionalSiteMedia.hostMatches(documentUrl, "streamtape.com") ||
                        AdditionalSiteMedia.hostMatches(documentUrl, "streamtape.to")) && activatedPlayers.add(documentUrl)) {
                    browser.evaluateJavascript("document.querySelector('#overlay, #mainvideo, .vjs-big-play-button')?.click()", null)
                }
                browser.evaluateJavascript("""
                    (function(){
                        var media = [], frames = [];
                        function add(url) { if (url && /^https?:\/\//i.test(url)) media.push(url); }
                        try { if (window.jwplayer) {
                            var entry = jwplayer().getPlaylist()[0] || {};
                            add(entry.file); (entry.sources || []).forEach(function(s){add(s.file);});
                        }} catch(e) {}
                        document.querySelectorAll('video').forEach(function(v){
                            add(v.currentSrc); add(v.src); v.querySelectorAll('source[src]').forEach(function(s){add(s.src);});
                            try { var p=v.play(); if(p) p.catch(function(){}); } catch(e) {}
                        });
                        document.querySelectorAll('iframe[src]').forEach(function(f){
                            if (/(adframe|banner|advert|popup|recaptcha|imasdk|metrika)/i.test(f.id+' '+f.className+' '+f.src)) return;
                            var r=f.getBoundingClientRect(), area=r.width*r.height;
                            var primary = /^(video|player)$/.test(f.id) || /\/(e|embed|player)\//i.test(f.src);
                            if (area >= 16000 || primary) frames.push({url:f.src,area:area+(primary?1000000:0)});
                        });
                        frames.sort(function(a,b){return b.area-a.area;});
                        return JSON.stringify({media:media,frame:frames.length?frames[0].url:''});
                    })();
                """.trimIndent()) { raw ->
                    if (done !== completed || completed.get()) return@evaluateJavascript
                    val json = runCatching { JSONObject(org.json.JSONTokener(raw).nextValue() as String) }.getOrNull()
                    val sources = json?.optJSONArray("media")
                    if (sources != null) for (i in 0 until sources.length()) {
                        val media = sources.optString(i)
                        if (session.accepts(media, inspectedDocument)) { finish(media, inspectedDocument); return@evaluateJavascript }
                    }
                    val next = json?.optString("frame").orEmpty()
                    if (promotions < 5 && next !in visited && session.registerPlayer(next)) {
                        promotions++; visited.add(next)
                        browser.loadUrl(next, mapOf("Referer" to documentUrl))
                    }
                    handler.postDelayed(this, 650)
                }
            }
        }
        poll = scan
        timeout = Runnable {
            if (done === completed && !completed.get()) { close(); failure() }
        }.also { handler.postDelayed(it, 35_000) }
        val frameUrl = embed.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        browser.loadDataWithBaseURL(page,
            "<html><body style=\"margin:0\"><iframe id=\"video\" src=\"$frameUrl\" style=\"width:100%;height:240px;border:0\" allow=\"autoplay; fullscreen\"></iframe></body></html>",
            "text/html", "UTF-8", null)
        handler.postDelayed(scan, 500)
    }
}
