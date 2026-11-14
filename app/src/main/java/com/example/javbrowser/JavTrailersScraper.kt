package com.example.javbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.javbrowser.gecko.GeckoEval
import com.example.javbrowser.gecko.GeckoHeadless
import com.example.javbrowser.gecko.JavhdGeckoExtension
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession

/**
 * Scrapes javtrailers.com for cover image + title only.
 * Used during bulk bookmark import to avoid hitting JavDB rate limits.
 *
 * URL pattern:  MIDA-271  →  /ja/video/mida00271
 *               OFJE-489  →  /ja/video/ofje00489
 *               FC2-PPV-* →  null (not on JavTrailers)
 *
 * Gecko 迁移（原隐藏 WebView 版）：
 * - shouldInterceptRequest 拦 media.javtrailers.com 的 .m3u8/.mp4 改为
 *   JavhdGeckoExtension.attachMediaSniffer（扩展 background.js 的
 *   webRequest 观察者，非阻塞）。content.js 只注入
 *   video.javhdporn.net / video1.javhdporn.net，不会跑到本站页面；
 *   background 的 11 广告域拦截与本站无关。注意扩展的过滤规则会丢掉
 *   含 "sample" token 的 URL（见 NOTES.md），sample.mgstage.com 的
 *   预告片只能走下面 JS 兜底（videojs currentSources / data-setup）。
 * - 搜尋 → 影片頁的跳转改同一 session 内 location.href（Referer
 *   自动带上，原 loadUrl(url, headers) 的 Referer 同款语义；Accept /
 *   Accept-Language 自定义头随 loadSession 一起丢掉，见 NOTES.md）。
 * - onReceivedHttpError 的快 fail 没有对等物：404 页走提取逻辑自然
 *   落空（搜尋页无链接 / 影片页空标题+空封面 → null），只是不再
 *   提前结束。
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
        var handled = false
        var phase = "search"   // "search" → "video"
        val capturedTrailer = arrayOf("")  // m3u8 or direct mp4
        var sessionRef: GeckoSession? = null

        // 终结：只关一次 session（含 detach 嗅探），再回调。
        fun teardown() {
            sessionRef?.let { session ->
                JavhdGeckoExtension.detachMediaSniffer(session)
                runCatching { session.close() }
            }
            sessionRef = null
        }

        // Hard timeout: 30 seconds（兩次載頁需要更多時間）
        val timeoutRunnable = Runnable {
            if (!handled) {
                handled = true
                android.util.Log.w("JavTrailers", "timeout at phase=$phase url=$searchUrl")
                teardown()
                callback(null)
            }
        }
        mainHandler.postDelayed(timeoutRunnable, 30_000L)

        // 階段一：搜尋結果頁 → 抽出第一個 /video/ 連結
        fun runSearchExtract(session: GeckoSession) {
            if (handled) return
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
            GeckoEval.evaluate(session, js) { raw ->
                if (handled) return@evaluate
                val path = raw?.trim('"')
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                if (path != null) {
                    val videoUrl = if (path.startsWith("http")) path
                                   else "https://javtrailers.com$path"
                    android.util.Log.d("JavTrailers", "found video URL: $videoUrl")
                    phase = "video"
                    // 站内跳转：同一 session 里改 location.href，Referer 自动
                    // 为搜尋页（原 loadUrl(videoUrl, headers) 的 Referer 同款）。
                    GeckoEval.runJavascript(session, "location.href = '${videoUrl.replace("'", "\\'")}';")
                    waitForSessionPage(session, "/video/", 15_000L) { ok ->
                        if (handled) return@waitForSessionPage
                        if (!ok) {
                            handled = true
                            mainHandler.removeCallbacks(timeoutRunnable)
                            android.util.Log.w("JavTrailers", "video page did not load: $videoUrl")
                            teardown()
                            callback(null)
                            return@waitForSessionPage
                        }
                        // 階段二：影片頁 → 抽出 gallery + trailer
                        val delay = (1800L..2800L).random()
                        mainHandler.postDelayed({
                            if (!handled) {
                                handled = true
                                mainHandler.removeCallbacks(timeoutRunnable)
                                extractData(session, videoUrl, capturedTrailer, callback)
                            }
                        }, delay)
                    }
                } else {
                    android.util.Log.w("JavTrailers", "no video link found on search page")
                    handled = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    teardown()
                    callback(null)
                }
            }
        }

        // loadSession 没有自定义请求头参数：原搜尋页的 Accept /
        // Accept-Language / Cache-Control 头随之丢掉（见 NOTES.md）。
        GeckoHeadless.loadSession(context, searchUrl, 30_000L) { session ->
            mainHandler.post {
                if (handled) {
                    // 30 秒总超时已先触发：直接关掉迟到的 session。
                    runCatching { session?.close() }
                    return@post
                }
                if (session == null) {
                    if (!handled) {
                        handled = true
                        mainHandler.removeCallbacks(timeoutRunnable)
                        callback(null)
                    }
                    return@post
                }
                sessionRef = session
                // 挂实时嗅探：替代原 shouldInterceptRequest 对
                // media.javtrailers.com / sample.mgstage.com 的 .m3u8/.mp4
                // 拦截（扩展只观察、不阻塞；tsyndicate.com VAST 不在
                // 本站出现，也不受影响）。
                JavhdGeckoExtension.attachMediaSniffer(session) { url ->
                    if (capturedTrailer[0].isEmpty() &&
                        (url.contains("media.javtrailers.com") || url.contains("sample.mgstage.com")) &&
                        (url.contains(".m3u8") || url.contains(".mp4"))) {
                        capturedTrailer[0] = url
                        android.util.Log.d("JavTrailers", "captured trailer: $url")
                    }
                }
                JavhdGeckoExtension.ensureInstalled(context)
                // 原 onPageFinished 后等 2000ms 再抽搜尋结果。
                mainHandler.postDelayed({ runSearchExtract(session) }, 2000L)
            }
        }
    }

    // ── JS extraction ─────────────────────────────────────────────────────────

    private fun extractData(session: GeckoSession, pageUrl: String,
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
        GeckoEval.evaluate(session, clickJs) { btnResult ->
            android.util.Log.d("JavTrailers", "click result: $btnResult  page=$pageUrl")
            // 等 3500ms：Vue gallery 渲染 + video.js 初始化並發出 m3u8 請求
            mainHandler.postDelayed({
                doExtractAllData(session, pageUrl, capturedTrailer, callback)
            }, 3500L)
        }
    }

    private fun doExtractAllData(session: GeckoSession, pageUrl: String,
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

        GeckoEval.evaluate(session, js) { raw ->
            JavhdGeckoExtension.detachMediaSniffer(session)
            runCatching { session.close() }
            android.util.Log.d("JavTrailers", "raw JS result: $raw")
            if (raw == null || raw == "null") {
                android.util.Log.w("JavTrailers", "JS returned null for $pageUrl")
                callback(null)
                return@evaluate
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
                // 優先用嗅探到的（精確），其次用 JS 解析的
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

    /**
     * 等待同一 session 内的 JS 导航完成：轮询 location.href +
     * document.readyState，直到地址含 [marker] 且 readyState 为 complete。
     * 替代原 WebViewClient.onPageFinished。
     */
    private fun waitForSessionPage(
        session: GeckoSession,
        marker: String,
        timeoutMs: Long,
        initialDelayMs: Long = 500L,
        onReady: (Boolean) -> Unit
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var done = false
        fun poll() {
            if (done) return
            if (SystemClock.uptimeMillis() >= deadline) {
                done = true
                onReady(false)
                return
            }
            GeckoEval.evaluate(session, "(location.href + '|' + document.readyState)", 5_000L) { raw ->
                if (done) return@evaluate
                val text = raw?.trim()?.removeSurrounding("\"")?.replace("\\/", "/") ?: ""
                val parts = text.split("|")
                val href = parts.getOrElse(0) { "" }
                val state = parts.getOrElse(1) { "" }
                if (href.contains(marker, ignoreCase = true) && state == "complete") {
                    done = true
                    onReady(true)
                } else {
                    mainHandler.postDelayed({ poll() }, 500L)
                }
            }
        }
        mainHandler.postDelayed({ poll() }, initialDelayMs)
    }
}
