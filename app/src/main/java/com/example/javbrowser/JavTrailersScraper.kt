package com.example.javbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Scrapes javtrailers.com for cover image + title only.
 * Used during bulk bookmark import to avoid hitting JavDB rate limits.
 *
 * URL pattern:  MIDA-271  →  /ja/video/mida00271
 *               OFJE-489  →  /ja/video/ofje00489
 *               FC2-PPV-* →  null (not on JavTrailers)
 */
class JavTrailersScraper(private val context: Context) {

    data class TrailersResult(
        val title: String,
        val coverUrl: String,
        val galleryImages: List<String> = emptyList(),
        val pageUrl: String = "",   // 該影片在 JavTrailers 的 URL
        val trailerUrl: String = "" // 預告片直接 MP4 連結
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Public entry ──────────────────────────────────────────────────────────

    fun scrape(javCode: String, callback: (TrailersResult?) -> Unit) {
        val searchUrl = javCodeToSearchUrl(javCode)
        if (searchUrl == null) { callback(null); return }

        // 若搜尋失敗，自動用另一種格式 fallback
        // REBD-1028 ↔ REBD-01-028（4位數補零5位後切割成 2+3 格式）
        val altCode = toAlternateCode(javCode)
        val altUrl  = if (altCode != null) javCodeToSearchUrl(altCode) else null

        mainHandler.post {
            doScrape(searchUrl, javCode) { result ->
                if (result != null || altUrl == null) {
                    callback(result)
                } else {
                    android.util.Log.d("JavTrailers", "fallback to alt code: $altCode")
                    mainHandler.post { doScrape(altUrl, altCode!!, callback) }
                }
            }
        }
    }

    // ── URL converter：改用搜尋 URL，不直接拼 video URL ────────────────────────

    private fun javCodeToSearchUrl(code: String): String? {
        val upper = code.trim().uppercase()
        if (upper.startsWith("FC2")) return null          // FC2 不在站上
        // 支援 REBD-912、REBD-01-022 等各種格式
        if (!Regex("^[A-Za-z]+-[\\d-]+$").matches(upper)) return null
        return "https://javtrailers.com/ja/search/${upper.lowercase()}"
    }

    /**
     * 在兩段式與三段式之間互轉：
     *   REBD-1028  →  REBD-01-028  （4位數補零到5位，切成 2+3）
     *   REBD-01-028 →  REBD-1028   （合併後去前導零）
     * 如果已是三段式則回傳兩段式，如果是兩段式且補零後恰為5位則回傳三段式，否則 null。
     */
    private fun toAlternateCode(code: String): String? {
        val upper = code.trim().uppercase()
        // 三段式 REBD-01-028 → 兩段式 REBD-1028
        val three = Regex("^([A-Z]+)-(\\d{2})-(\\d{3})$").find(upper)
        if (three != null) {
            val prefix  = three.groupValues[1]
            val combined = (three.groupValues[2] + three.groupValues[3])
                .trimStart('0').ifEmpty { "0" }
            return "$prefix-$combined"
        }
        // 兩段式 REBD-1028 → 三段式 REBD-01-028（僅限補零後恰為5位的情況）
        val two = Regex("^([A-Z]+)-(\\d+)$").find(upper)
        if (two != null) {
            val prefix = two.groupValues[1]
            val num    = two.groupValues[2]
            val padded = num.padStart(5, '0')
            if (padded.length == 5 && padded != num) {   // 有補零才轉，避免無限循環
                return "$prefix-${padded.substring(0, 2)}-${padded.substring(2)}"
            }
        }
        return null
    }

    // ── Scraping（兩階段：搜尋 → 影片頁）────────────────────────────────────────

    private fun doScrape(searchUrl: String, javCode: String, callback: (TrailersResult?) -> Unit) {
        val wv = createWebView()
        var handled = false
        var phase = "search"   // "search" → "video"
        val capturedTrailer = arrayOf("")  // m3u8 or direct mp4

        // Hard timeout: 30 seconds（兩次載頁需要更多時間）
        val timeoutRunnable = Runnable {
            if (!handled) {
                handled = true
                android.util.Log.w("JavTrailers", "timeout at phase=$phase url=$searchUrl")
                wv.destroy()
                callback(null)
            }
        }
        mainHandler.postDelayed(timeoutRunnable, 30_000L)

        wv.webViewClient = object : WebViewClient() {

            // 攔截 media.javtrailers.com 的 .m3u8 / .mp4 請求
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                val reqUrl = request.url.toString()
                if (capturedTrailer[0].isEmpty() &&
                    (reqUrl.contains("media.javtrailers.com") || reqUrl.contains("sample.mgstage.com")) &&
                    (reqUrl.contains(".m3u8") || reqUrl.contains(".mp4"))) {
                    capturedTrailer[0] = reqUrl
                    android.util.Log.d("JavTrailers", "captured trailer: $reqUrl")
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, finishedUrl: String) {
                if (handled) return
                when {
                    // 階段一：搜尋結果頁 → 抽出第一個 /video/ 連結
                    phase == "search" && finishedUrl.contains("/search/") -> {
                        phase = "transitioning"
                        mainHandler.postDelayed({
                            if (handled) return@postDelayed
                            // 正規化番號用於比對（去連字符 + 去前導零）
                            // REBD-1022 → REBD1022；頁面上 rebd01022 也會正規化成 REBD1022
                            val codeNorm = run {
                                val stripped = javCode.replace(Regex("[-\\s]"), "").uppercase()
                                val m = Regex("^([A-Z]+)(\\d+)$").find(stripped)
                                if (m != null) m.groupValues[1] + m.groupValues[2].trimStart('0').ifEmpty { "0" }
                                else stripped
                            }.lowercase()
                            val js = """
                                (function(){
                                    function normCode(s) {
                                        var clean = s.replace(/[-\s]/g,'').toUpperCase();
                                        var m = clean.match(/^([A-Z]+)(\d+)$/);
                                        if (!m) return clean;
                                        return m[1] + parseInt(m[2], 10).toString();
                                    }
                                    var target = '${codeNorm.uppercase()}';
                                    var links = document.querySelectorAll('a[href*="/video/"]');
                                    for (var i = 0; i < links.length; i++) {
                                        var href = links[i].getAttribute('href') || '';
                                        var card = links[i].closest('.card, article, li') || links[i].parentElement;
                                        var cardText = card ? card.textContent : '';
                                        // 正規化 href 裡的番號部分
                                        var hrefPart = href.replace(/.*\/video\//,'');
                                        var hrefNorm = normCode(hrefPart).toUpperCase();
                                        // 正規化卡片文字中出現的番號
                                        var cardNorm = normCode(cardText.replace(/[^A-Za-z0-9]/g,'')).toUpperCase();
                                        if (hrefNorm === target || cardNorm.indexOf(target) >= 0) {
                                            return href;
                                        }
                                    }
                                    return null;
                                })()
                            """.trimIndent()
                            wv.evaluateJavascript(js) { raw ->
                                val path = raw?.trim('"')
                                    ?.takeIf { it.isNotEmpty() && it != "null" }
                                if (path != null) {
                                    val videoUrl = if (path.startsWith("http")) path
                                                   else "https://javtrailers.com$path"
                                    android.util.Log.d("JavTrailers", "found video URL: $videoUrl")
                                    phase = "video"
                                    val headers = mapOf(
                                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                        "Accept-Language" to "ja-JP,ja;q=0.9",
                                        "Referer" to "https://javtrailers.com/"
                                    )
                                    wv.loadUrl(videoUrl, headers)
                                } else {
                                    android.util.Log.w("JavTrailers", "no video link found on search page")
                                    handled = true
                                    mainHandler.removeCallbacks(timeoutRunnable)
                                    wv.destroy()
                                    callback(null)
                                }
                            }
                        }, 2000L)
                    }

                    // 階段二：影片頁 → 抽出 gallery + trailer
                    phase == "video" && finishedUrl.contains("/video/") -> {
                        val delay = (1800L..2800L).random()
                        mainHandler.postDelayed({
                            if (!handled) {
                                handled = true
                                mainHandler.removeCallbacks(timeoutRunnable)
                                extractData(wv, finishedUrl, capturedTrailer, callback)
                            }
                        }, delay)
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame && !handled) {
                    handled = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    android.util.Log.w("JavTrailers", "HTTP ${errorResponse.statusCode}: ${request.url}")
                    wv.destroy()
                    callback(null)
                }
            }
        }

        val headers = mapOf(
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control"   to "no-cache",
            "Pragma"          to "no-cache",
            "Upgrade-Insecure-Requests" to "1"
        )
        wv.loadUrl(searchUrl, headers)
    }

    // ── JS extraction ─────────────────────────────────────────────────────────

    private fun extractData(wv: WebView, pageUrl: String,
                            capturedTrailer: Array<String>,
                            callback: (TrailersResult?) -> Unit) {
        // 同時點 gallery 按鈕 + 播放鍵，讓 video.js 發出 m3u8 請求
        val clickJs = """
            (function(){
                var gallery = document.querySelector('.gallery-button, [class*="gallery"], button[data-tab*="gallery"]');
                if (gallery) gallery.click();
                var play = document.querySelector('.vjs-big-play-button, .vjs-play-control');
                if (play) play.click();
                return (gallery ? gallery.className : 'NO_GALLERY') + '|' + (play ? 'PLAY_CLICKED' : 'NO_PLAY');
            })()
        """.trimIndent()
        wv.evaluateJavascript(clickJs) { btnResult ->
            android.util.Log.d("JavTrailers", "click result: $btnResult  page=$pageUrl")
            // 等 3500ms：Vue gallery 渲染 + video.js 初始化並發出 m3u8 請求
            mainHandler.postDelayed({
                doExtractAllData(wv, pageUrl, capturedTrailer, callback)
            }, 3500L)
        }
    }

    private fun doExtractAllData(wv: WebView, pageUrl: String,
                                 capturedTrailer: Array<String>,
                                 callback: (TrailersResult?) -> Unit) {
        val js = """
            (function() {
                try {
                    // Title from <h1 class="lead">
                    var h1 = document.querySelector('h1.lead');
                    var title = h1 ? h1.textContent.trim() : '';

                    // Cover: prefer large poster (pl.jpg)
                    var cover = '';
                    var largeImg = document.querySelector('img[data-src*="pl.jpg"]')
                                || document.querySelector('img[src*="pl.jpg"]')
                                || document.querySelector('img[lazy="loaded"][src*="pl.jpg"]');
                    if (largeImg) {
                        cover = largeImg.getAttribute('data-src') || largeImg.getAttribute('src') || '';
                    }
                    // Fallback: thumbnail container small image (ps.jpg)
                    if (!cover) {
                        var thumb = document.querySelector('#thumbnailContainer img');
                        if (thumb) cover = thumb.getAttribute('src') || thumb.getAttribute('data-src') || '';
                    }
                    // Last resort: video player poster background-image
                    if (!cover) {
                        var poster = document.querySelector('.vjs-poster');
                        if (poster) {
                            var bg = poster.style.backgroundImage || '';
                            var m = bg.match(/url\(["']?([^"')]+)["']?\)/);
                            if (m) cover = m[1];
                        }
                    }

                    // Gallery: 優先從 DOM 抓，失敗時從 cover URL 推導
                    var gallery = [];
                    var slideSelectors = [
                        '#gallery-wrapper .swiper-slide img',
                        '.gallery-wrapper .swiper-slide img',
                        '[id*="gallery"] .swiper-slide img',
                        '.swiper-wrapper .swiper-slide img'
                    ];
                    var slides = null;
                    for (var s = 0; s < slideSelectors.length; s++) {
                        var found = document.querySelectorAll(slideSelectors[s]);
                        if (found.length > 0) { slides = found; break; }
                    }
                    var debugSlides = slides ? slides.length : 0;
                    if (slides && slides.length > 0) {
                        for (var i = 0; i < slides.length; i++) {
                            var img = slides[i];
                            // 優先用 src（jp-N.jpg 高清版），fallback 才用 data-loading（低清版）
                            var hiRes = img.getAttribute('src') || '';
                            var loRes = img.getAttribute('data-loading')
                                     || img.getAttribute('data-src')
                                     || img.getAttribute('data-lazy')
                                     || img.getAttribute('data-original') || '';
                            var src = (hiRes && hiRes.indexOf('http') === 0) ? hiRes
                                    : (loRes && loRes.indexOf('http') === 0) ? loRes : '';
                            if (src) gallery.push(src);
                        }
                    }

                    // Fallback: 從 cover URL 的 DMM 路徑推導 gallery（例如 veq00277pl.jpg → veq00277-1.jpg ...）
                    if (gallery.length === 0 && cover) {
                        var coverMatch = cover.match(/^(https:\/\/pics\.dmm\.co\.jp\/[^?#]+\/)([a-z0-9]+)pl\.jpg$/i);
                        if (coverMatch) {
                            // 計算 slide 數量（slide element 可能存在但 img 為空）
                            var slideCount = 0;
                            for (var ss = 0; ss < slideSelectors.length; ss++) {
                                var sc = document.querySelectorAll(slideSelectors[ss].replace(' img',''));
                                if (sc.length > 0) { slideCount = sc.length; break; }
                            }
                            if (slideCount === 0) slideCount = 20;
                            var base = coverMatch[1] + coverMatch[2];
                            for (var n = 1; n <= slideCount; n++) {
                                gallery.push(base + 'jp-' + n + '.jpg');  // 高清版（jp-N.jpg）
                            }
                        }
                    }

                    // 預告片 MP4 來源：用 videojs API 讀 currentSources()，拿到 blob 之前的原始 URL
                    var trailerUrl = '';
                    try {
                        if (window.videojs) {
                            var players = window.videojs.players;
                            for (var pid in players) {
                                var p = players[pid];
                                if (!p) continue;
                                // currentSources() 回傳 [{src, type}]，取第一個非 blob 的
                                var srcs = p.currentSources ? p.currentSources() : [];
                                for (var si = 0; si < srcs.length; si++) {
                                    var s = srcs[si].src || '';
                                    if (s && s.indexOf('blob:') < 0 && s.indexOf('http') === 0) {
                                        trailerUrl = s; break;
                                    }
                                }
                                if (!trailerUrl) {
                                    // 嘗試 cache 中的 source list
                                    var cache = p.cache_ || {};
                                    var cacheSrcs = cache.sources || cache.source || [];
                                    if (typeof cacheSrcs === 'object' && cacheSrcs.src) cacheSrcs = [cacheSrcs];
                                    for (var ci = 0; ci < cacheSrcs.length; ci++) {
                                        var cs = cacheSrcs[ci].src || '';
                                        if (cs && cs.indexOf('blob:') < 0 && cs.indexOf('http') === 0) {
                                            trailerUrl = cs; break;
                                        }
                                    }
                                }
                                if (trailerUrl) break;
                            }
                        }
                    } catch(ve) {}
                    // fallback: data-setup JSON
                    if (!trailerUrl) {
                        var vjsEl = document.querySelector('[data-setup]');
                        if (vjsEl) {
                            try {
                                var cfg = JSON.parse(vjsEl.getAttribute('data-setup'));
                                if (cfg && cfg.sources && cfg.sources[0]) {
                                    var ds = cfg.sources[0].src || '';
                                    if (ds && ds.indexOf('blob:') < 0) trailerUrl = ds;
                                }
                            } catch(ex) {}
                        }
                    }

                    return { title: title, cover: cover, gallery: gallery, debugSlides: debugSlides, trailerUrl: trailerUrl };
                } catch (e) {
                    return { title: '', cover: '', gallery: [], debugSlides: -1, trailerUrl: '', error: e.message };
                }
            })()
        """.trimIndent()

        wv.evaluateJavascript(js) { raw ->
            wv.destroy()
            android.util.Log.d("JavTrailers", "raw JS result: $raw")
            if (raw == null || raw == "null") {
                android.util.Log.w("JavTrailers", "JS returned null for $pageUrl")
                callback(null)
                return@evaluateJavascript
            }
            try {
                val obj = JSONObject(raw)
                val title = obj.optString("title", "").trim()
                val cover = obj.optString("cover", "").trim()
                val debugSlides = obj.optInt("debugSlides", -1)
                val gallery = mutableListOf<String>()
                obj.optJSONArray("gallery")?.let { arr ->
                    for (i in 0 until arr.length()) gallery.add(arr.getString(i))
                }
                android.util.Log.d("JavTrailers",
                    "title='$title' cover=${cover.isNotEmpty()} slides=$debugSlides gallery=${gallery.size} page=$pageUrl")
                // 優先用 shouldInterceptRequest 攔截到的（精確），其次用 JS 解析的
                val jsTrailerUrl = obj.optString("trailerUrl", "").trim()
                val trailerUrl = capturedTrailer[0].ifEmpty {
                    jsTrailerUrl.takeIf { it.isNotEmpty() && !it.startsWith("blob:") } ?: ""
                }
                android.util.Log.d("JavTrailers", "trailerUrl=$trailerUrl (intercepted=${capturedTrailer[0].isNotEmpty()})")
                if (title.isEmpty() && cover.isEmpty()) {
                    android.util.Log.w("JavTrailers", "empty title+cover → null")
                    callback(null)
                } else {
                    callback(TrailersResult(title = title, coverUrl = cover,
                        galleryImages = gallery, pageUrl = pageUrl, trailerUrl = trailerUrl))
                }
            } catch (e: Exception) {
                android.util.Log.e("JavTrailers", "parse error: ${e.message}")
                callback(null)
            }
        }
    }

    // ── WebView factory ───────────────────────────────────────────────────────

    private fun createWebView(): WebView {
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false  // 允許 JS 觸發播放，讓 m3u8 請求可被攔截
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // Realistic recent Chrome on Android — Pixel 8 Pro, Chrome 128
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/AD1A.240905.004) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/128.0.6613.127 Mobile Safari/537.36"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        return wv
    }
}
