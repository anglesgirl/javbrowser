package com.example.javbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

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
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)

        var finished = false
        fun finish(result: MissavResult?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.destroy()
            callback(result)
        }

        val timeout = Runnable { finish(null) }
        handler.postDelayed(timeout, 20_000L)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (finished) return
                evaluatePage(view)
            }

            /** 读页取数据；挑战页 2 秒后重查（覆盖挑战在页内解开、无导航的情况）。 */
            private fun evaluatePage(view: WebView) {
                if (finished) return
                handler.postDelayed({
                    if (finished) return@postDelayed
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
                    view.evaluateJavascript(js) { raw ->
                        if (finished) return@evaluateJavascript
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
                                handler.postDelayed({ evaluatePage(view) }, 2000L)
                                return@evaluateJavascript
                            }

                            if (finalUrl.contains("/search/", true) && searchHit.isNotEmpty() && searchHit != finalUrl) {
                                view.loadUrl(searchHit)
                                return@evaluateJavascript
                            }

                            val is404 = pageTitle.contains("404", true) ||
                                pageTitle.contains("not found", true)
                            val looksValid = !is404 &&
                                finalUrl.contains(javCode.lowercase(), true) &&
                                title.isNotEmpty() &&
                                bodyLen > 100
                            if (!looksValid) {
                                finish(null)
                                return@evaluateJavascript
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
                }, 1200L)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame && errorResponse.statusCode in listOf(404, 410)) {
                    finish(null)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) finish(null)
            }
        }

        webView.loadUrl(targetUrl)
    }
}
