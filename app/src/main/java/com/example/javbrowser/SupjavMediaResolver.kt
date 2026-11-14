package com.example.javbrowser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebSettings
import com.example.javbrowser.gecko.GeckoEval
import com.example.javbrowser.gecko.GeckoHeadless
import com.example.javbrowser.gecko.JavhdGeckoExtension
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Each Server selection owns an isolated player chain and cancellation token.
 *
 * GeckoView 版：無界面 GeckoSession 取代 WebView（見 playback/NOTES.md）。
 * 掃描輪詢（jwplayer / video 元素 / iframe 發現）原樣保留，只是
 * evaluateJavascript 改走 GeckoEval；子框媒體改由 JavhdGeckoExtension
 * 的即時嗅探（webRequest 觀察全框的 .m3u8/.mp4）補上——原
 * WebMessageListener + document-start 腳本在給定的共享 API 裡沒有
 * 等價物。下列原版能力沒有等價物，已拿掉，見 NOTES.md：
 * ① shouldInterceptRequest 的請求級媒體偵測與 1 秒延遲 pendingMedia；
 * ② shouldOverrideUrlLoading 的導航守衛與廣告攔截；
 * ③ 子框內的 streamtape 覆蓋層點擊（跨域點不到）。
 * 初始載入仍是包 embed 的 wrapper HTML，但改走 data: URL——
 * Loader.data 沒有 baseUri，iframe 的 Referer 會丟失（驗證項）。
 */
class SupjavMediaResolver(private val context: Context,
    private val userAgent: () -> String = { WebSettings.getDefaultUserAgent(context) }) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: GeckoSession? = null
    private var done = AtomicBoolean(true)
    private var timeout: Runnable? = null
    private var poll: Runnable? = null

    fun close() {
        done.set(true)
        listOfNotNull(timeout, poll).forEach(handler::removeCallbacks)
        timeout = null; poll = null
        player?.let { JavhdGeckoExtension.detachMediaSniffer(it) }
        player?.let { runCatching { it.close() } }
        player = null
    }

    fun resolve(embed: String, page: String, callback: (String, String) -> Unit, failure: () -> Unit) {
        close()
        if (!AdditionalSiteMedia.hostMatches(page, "supjav.com") || SupjavSourceSession.origin(embed) == null) return
        val rules = AdFilterRules(context)
        val session = SupjavSourceSession(page, embed, rules.getCommonBlockList() + rules.getNetworkBlockList())
        if (!session.registerPlayer(embed)) { failure(); return }
        val completed = AtomicBoolean(false)
        done = completed
        var documentUrl = embed
        var promotions = 0
        val activatedPlayers = mutableSetOf<String>()
        val visited = mutableSetOf(embed)
        fun finish(media: String, referer: String) {
            if (done !== completed || !session.accepts(media, referer) || !completed.compareAndSet(false, true)) return
            close()
            callback(media, referer)
        }
        /** 經 Loader 帶 Referer 換頁（loadUri 的 referrer 參數在 151 已廢棄）。 */
        fun promote(url: String, referer: String) {
            if (done !== completed || completed.get()) return
            val current = player ?: return
            if (promotions >= 5 || url in visited || !session.registerPlayer(url)) return
            promotions++; visited.add(url)
            documentUrl = url
            runCatching {
                current.load(GeckoSession.Loader().uri(url).referrer(Uri.parse(referer)))
            }
        }
        /** 擴展嗅探到的媒體候選：referer 用目前頂層文件（origin 已在允許清單內）。 */
        fun onMediaCandidate(url: String) {
            if (done !== completed || completed.get()) return
            if (session.accepts(url, documentUrl)) finish(url, documentUrl)
        }
        val scan = object : Runnable {
            override fun run() {
                if (done !== completed || completed.get()) return
                val current = player ?: return
                val inspectedDocument = documentUrl
                if ((AdditionalSiteMedia.hostMatches(documentUrl, "streamtape.com") ||
                        AdditionalSiteMedia.hostMatches(documentUrl, "streamtape.to")) && activatedPlayers.add(documentUrl)) {
                    GeckoEval.runJavascript(current,
                        "document.querySelector('#overlay, #mainvideo, .vjs-big-play-button')?.click()")
                }
                GeckoEval.evaluate(current, """
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
                    if (done !== completed || completed.get()) return@evaluate
                    val json = runCatching { JSONObject(org.json.JSONTokener(raw).nextValue() as String) }.getOrNull()
                    val sources = json?.optJSONArray("media")
                    if (sources != null) for (i in 0 until sources.length()) {
                        val media = sources.optString(i)
                        if (session.accepts(media, inspectedDocument)) { finish(media, inspectedDocument); return@evaluate }
                    }
                    val next = json?.optString("frame").orEmpty()
                    if (next.isNotBlank()) promote(next, documentUrl)
                    handler.postDelayed(this, 650)
                }
            }
        }
        poll = scan
        timeout = Runnable {
            if (done === completed && !completed.get()) { close(); failure() }
        }.also { handler.postDelayed(it, 35_000) }
        // 擴展裝一次即可（冪等）；嗅探回呼在 onReady 掛上該 session。
        JavhdGeckoExtension.ensureInstalled(context)
        val frameUrl = embed.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val wrapperHtml =
            "<html><body style=\"margin:0\"><iframe id=\"video\" src=\"$frameUrl\" style=\"width:100%;height:240px;border:0\" allow=\"autoplay; fullscreen\"></iframe></body></html>"
        val wrapperUrl = "data:text/html;base64," +
            Base64.encodeToString(wrapperHtml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        GeckoHeadless.loadSession(context, wrapperUrl, 30_000L) { loaded ->
            if (done !== completed || completed.get()) {
                loaded?.close()
                return@loadSession
            }
            if (loaded == null) {
                if (!completed.get()) { close(); failure() }
                return@loadSession
            }
            player = loaded
            // UA 盡力而為：初始載入的 UA 由 GeckoHeadless 決定，改不了；
            // 此後換頁（promote）沿用 MainActivity 的 WebView UA。
            runCatching { loaded.settings.userAgentOverride = userAgent() }
            loaded.navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    geckoSession: GeckoSession,
                    url: String?,
                    perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean
                ) {
                    if (url != null && url != page && session.registerPlayer(url)) documentUrl = url
                }
            }
            JavhdGeckoExtension.attachMediaSniffer(loaded, ::onMediaCandidate)
            handler.postDelayed(scan, 500)
        }
    }
}
