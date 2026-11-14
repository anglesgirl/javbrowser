package com.example.javbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.javbrowser.gecko.GeckoEval
import com.example.javbrowser.gecko.GeckoHeadless
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession

/**
 * MissAV scraper using a headless Gecko session.
 *
 * Gecko 迁移（原隐藏 WebView 版）：加载 → 等渲染 → evaluateJavascript
 * 取数据。挑战等待语义原样保留：页标题是 CF 挑战（just a moment /
 * checking your browser / attention required）时不结束、每 2 秒重查
 * 一次，直到挑战解开或 20 秒总超时；原版是等下一次 onPageFinished，
 * 轮询覆盖了挑战页内直接解开（无导航）的情况。
 * onReceivedHttpError / onReceivedError 的快 fail 由两处覆盖：
 * 加载失败（网络错）→ loadSession 回调 null；404/410 由标题判断。
 */
class MissavScraper(
    private val context: Context,
    private val domainConfig: DomainConfig
) {

    data class MissavResult(
        val title: String,
        val coverUrl: String,
        val pageUrl: String,
        val releaseDate: String = "",
        val maker: String = "",
        val series: String = "",
        val genres: List<String> = emptyList(),
        val actors: List<String> = emptyList()
    )

    fun scrapeByCode(javCode: String, callback: (MissavResult?) -> Unit) {
        val normalizedCode = JavDbScraper.normalizeJavCode(javCode).uppercase()
        val slug = normalizedCode.lowercase()
        val candidates = listOf(
            domainConfig.getMissAvBaseUrl() + slug,
            domainConfig.getMissAvSearchUrl(normalizedCode)
        ).distinct()
        scrapeCandidates(candidates, normalizedCode, callback)
    }

    private fun scrapeCandidates(
        candidates: List<String>,
        javCode: String,
        callback: (MissavResult?) -> Unit
    ) {
        if (candidates.isEmpty()) {
            callback(null)
            return
        }
        scrapeSingle(candidates.first(), javCode) { result ->
            if (result != null) callback(result)
            else scrapeCandidates(candidates.drop(1), javCode, callback)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun scrapeSingle(
        targetUrl: String,
        javCode: String,
        callback: (MissavResult?) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        // 20 秒总超时（原 WebView 版同款），从加载开始算。
        val deadlineMs = SystemClock.uptimeMillis() + TIMEOUT_MS
        var sessionRef: GeckoSession? = null

        var finished = false
        fun finish(result: MissavResult?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            sessionRef?.let { runCatching { it.close() } }
            sessionRef = null
            callback(result)
        }

        GeckoHeadless.loadSession(context, targetUrl, TIMEOUT_MS) { session ->
            handler.post {
                if (session == null) {
                    finish(null)
                    return@post
                }
                sessionRef = session

                fun evaluatePage() {
                    if (finished) return
                    if (SystemClock.uptimeMillis() >= deadlineMs) {
                        finish(null)
                        return
                    }
                    handler.postDelayed({
                        if (finished) return@postDelayed
                        if (SystemClock.uptimeMillis() >= deadlineMs) {
                            finish(null)
                            return@postDelayed
                        }
                        val js = """
                        (function() {
                            function metaValue(selector) {
                                var el = document.querySelector(selector);
                                return el ? (el.getAttribute('content') || '').trim() : '';
                            }
                            function textFromSelector(selector) {
                                var el = document.querySelector(selector);
                                return el ? (el.textContent || '').trim() : '';
                            }
                            function collectByHref(parts) {
                                var out = [];
                                var seen = {};
                                var nodes = document.querySelectorAll('a[href]');
                                for (var i = 0; i < nodes.length; i++) {
                                    var href = nodes[i].getAttribute('href') || '';
                                    var text = (nodes[i].textContent || '').trim();
                                    if (!text) continue;
                                    var matched = false;
                                    for (var j = 0; j < parts.length; j++) {
                                        if (href.indexOf(parts[j]) >= 0) {
                                            matched = true;
                                            break;
                                        }
                                    }
                                    if (!matched || seen[text]) continue;
                                    seen[text] = true;
                                    out.push(text);
                                }
                                return out;
                            }
                            function findValueByLabel(labels) {
                                var nodes = document.querySelectorAll('div, span, p, li');
                                for (var i = 0; i < nodes.length; i++) {
                                    var text = (nodes[i].textContent || '').replace(/\s+/g, ' ').trim();
                                    if (!text) continue;
                                    for (var j = 0; j < labels.length; j++) {
                                        var label = labels[j];
                                        if (text.indexOf(label) !== 0) continue;
                                        var value = text.substring(label.length).replace(/^[:：\s]+/, '').trim();
                                        if (value) return value;
                                    }
                                }
                                return '';
                            }
                            function parseSearchResult(target) {
                                var cards = document.querySelectorAll('a[href]');
                                var targetUpper = target.toUpperCase();
                                for (var i = 0; i < cards.length; i++) {
                                    var href = cards[i].href || '';
                                    var text = (cards[i].textContent || '').toUpperCase();
                                    if (href.indexOf('/search/') >= 0) continue;
                                    if (text.indexOf(targetUpper) >= 0 && href.indexOf(location.origin) === 0) {
                                        return href;
                                    }
                                }
                                return '';
                            }
                            var href = location.href || '';
                            var title = textFromSelector('h1');
                            if (!title) title = metaValue('meta[property="og:title"]');
                            if (!title) title = document.title || '';
                            title = title.replace(/\s+/g, ' ').trim();
                            var coverUrl = metaValue('meta[property="og:image"]');
                            if (!coverUrl) coverUrl = metaValue('meta[name="twitter:image"]');
                            if (!coverUrl) {
                                var poster = document.querySelector('video[poster]');
                                if (poster) coverUrl = poster.getAttribute('poster') || '';
                            }
                            if (!coverUrl) {
                                var img = document.querySelector('img');
                                if (img) coverUrl = img.currentSrc || img.src || '';
                            }
                            var searchHit = '';
                            if (href.indexOf('/search/') >= 0) {
                                searchHit = parseSearchResult('${javCode}');
                            }
                            return JSON.stringify({
                                href: href,
                                title: title,
                                coverUrl: coverUrl,
                                bodyLen: document.body ? document.body.innerText.length : 0,
                                pageTitle: document.title || '',
                                searchHit: searchHit,
                                releaseDate: findValueByLabel(['Release date', 'Released date', '發行日期', '日期']),
                                maker: findValueByLabel(['Maker', '片商', '發行商']),
                                series: findValueByLabel(['Series', '系列']),
                                actors: collectByHref(['/actress/', '/actor/', '/star/', '/performer/']),
                                genres: collectByHref(['/genre/', '/tag/', '/tags/'])
                            });
                        })();
                    """.trimIndent()
                        GeckoEval.evaluate(session, js) { raw ->
                            if (finished) return@evaluate
                            try {
                                val decoded = raw?.takeIf { it != "null" } ?: ""
                                val obj = JSONObject(decoded.removeSurrounding("\"").replace("\\\"", "\""))
                                val bodyLen = obj.optInt("bodyLen", 0)
                                val pageTitle = obj.optString("pageTitle", "")
                                val finalUrl = obj.optString("href", "").trim()
                                val title = obj.optString("title", "").trim()
                                val searchHit = obj.optString("searchHit", "").trim()
                                val isChallenge = pageTitle.contains("just a moment", true) ||
                                    pageTitle.contains("checking your browser", true) ||
                                    pageTitle.contains("attention required", true)
                                // 挑战页：不等下一次 onPageFinished，2 秒后重查。
                                if (isChallenge) {
                                    handler.postDelayed({ evaluatePage() }, CHALLENGE_RECHECK_MS)
                                    return@evaluate
                                }

                                if (finalUrl.contains("/search/", true) && searchHit.isNotEmpty() && searchHit != finalUrl) {
                                    // 搜尋頁命中详情：站内跳转（同 session，Referer 自动带上）。
                                    GeckoEval.runJavascript(session, "location.href = '${searchHit.replace("'", "\\'")}';")
                                    waitForSessionPage(handler, session, searchHit, deadlineMs) { ok ->
                                        if (finished) return@waitForSessionPage
                                        if (!ok) {
                                            finish(null)
                                            return@waitForSessionPage
                                        }
                                        evaluatePage()
                                    }
                                    return@evaluate
                                }

                                val is404 = pageTitle.contains("404", true) ||
                                    pageTitle.contains("not found", true)
                                val looksValid = !is404 &&
                                    finalUrl.contains(javCode.lowercase(), true) &&
                                    title.isNotEmpty() &&
                                    bodyLen > 100
                                if (!looksValid) {
                                    finish(null)
                                    return@evaluate
                                }

                                fun parseArray(name: String): List<String> {
                                    val arr = obj.optJSONArray(name) ?: JSONArray()
                                    return buildList {
                                        for (i in 0 until arr.length()) {
                                            val value = arr.optString(i).trim()
                                            if (value.isNotEmpty()) add(value)
                                        }
                                    }
                                }

                                finish(
                                    MissavResult(
                                        title = title,
                                        coverUrl = obj.optString("coverUrl", "").trim(),
                                        pageUrl = finalUrl,
                                        releaseDate = obj.optString("releaseDate", "").trim(),
                                        maker = obj.optString("maker", "").trim(),
                                        series = obj.optString("series", "").trim(),
                                        genres = parseArray("genres"),
                                        actors = parseArray("actors")
                                    )
                                )
                            } catch (_: Exception) {
                                finish(null)
                            }
                        }
                    }, SETTLE_DELAY_MS)
                }

                evaluatePage()
            }
        }
    }

    /**
     * 等待同一 session 内的 JS 导航完成：轮询 location.href +
     * document.readyState，直到地址含 [marker] 且 readyState 为 complete，
     * 或到 [deadlineMs]。替代原 WebViewClient.onPageFinished。
     */
    private fun waitForSessionPage(
        handler: Handler,
        session: GeckoSession,
        marker: String,
        deadlineMs: Long,
        onReady: (Boolean) -> Unit
    ) {
        var done = false
        fun poll() {
            if (done) return
            if (SystemClock.uptimeMillis() >= deadlineMs) {
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
                    handler.postDelayed({ poll() }, 500L)
                }
            }
        }
        handler.postDelayed({ poll() }, 500L)
    }

    companion object {
        private const val TIMEOUT_MS = 20_000L
        /** 原 onPageFinished 后等渲染稳定的延时，原样保留。 */
        private const val SETTLE_DELAY_MS = 1200L
        /** 挑战页重查间隔（原版靠下一次 onPageFinished 触发，无固定间隔）。 */
        private const val CHALLENGE_RECHECK_MS = 2000L
    }
}
