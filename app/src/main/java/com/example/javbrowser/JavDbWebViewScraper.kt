package com.example.javbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLEncoder
import org.json.JSONObject

/**
 * JavDB scraper using a hidden WebView (no paid API needed).
 * All WebView operations run on the main thread internally;
 * [enrichFavorite] may be called from any thread.
 */
class JavDbWebViewScraper(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun enrichFavorite(javCode: String, callback: (JavVideoDetail?) -> Unit) {
        mainHandler.post { doSearch(javCode, callback) }
    }

    /** 直接用指定 JavDB 頁面 URL 補全（跳過搜尋，避免誤選第一筆） */
    fun enrichFromUrl(javCode: String, detailUrl: String, callback: (JavVideoDetail?) -> Unit) {
        mainHandler.post {
            val webView = createWebView()
            doDetail(webView, javCode, detailUrl, callback)
        }
    }

    // ── Step 1: search page ───────────────────────────────────────────────────

    private fun doSearch(javCode: String, callback: (JavVideoDetail?) -> Unit) {
        val webView = createWebView()
        val searchUrl = "https://javdb.com/search?q=${URLEncoder.encode(javCode, "UTF-8")}&locale=zh"
        var handled = false

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (handled) return
                handled = true
                mainHandler.postDelayed({
                    extractHref(webView, javCode) { href ->
                        if (href.isNullOrEmpty()) {
                            android.util.Log.w("JavDbWebView", "No search result for $javCode")
                            webView.destroy()
                            callback(null)
                        } else {
                            doDetail(webView, javCode, "https://javdb.com$href", callback)
                        }
                    }
                }, 2500)
            }
        }
        webView.loadUrl(searchUrl)
    }

    private fun extractHref(webView: WebView, javCode: String, callback: (String?) -> Unit) {
        val escapedCode = javCode.uppercase().replace("'", "\\'")
        val js = """
            (function() {
                var items = document.querySelectorAll('.item');
                if (!items.length) return null;
                for (var i = 0; i < items.length; i++) {
                    var s = items[i].querySelector('.video-title strong');
                    if (s && s.textContent.trim().toUpperCase() === '$escapedCode') {
                        var a = items[i].querySelector('a.box');
                        if (a) return a.getAttribute('href');
                    }
                }
                return null;
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result == null || result == "null") {
                callback(null)
            } else {
                // evaluateJavascript wraps string result in JSON quotes
                callback(result.trim('"').replace("\\/", "/"))
            }
        }
    }

    // ── Step 2: detail page ───────────────────────────────────────────────────

    private fun doDetail(webView: WebView, javCode: String, detailUrl: String, callback: (JavVideoDetail?) -> Unit) {
        val urlWithLocale = if (detailUrl.contains("?")) "$detailUrl&locale=zh" else "$detailUrl?locale=zh"
        var handled = false

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (handled) return
                handled = true
                mainHandler.postDelayed({
                    extractDetailData(webView, javCode, detailUrl, callback)
                }, 2500)
            }
        }
        webView.loadUrl(urlWithLocale)
    }

    private fun extractDetailData(webView: WebView, javCode: String, detailUrl: String, callback: (JavVideoDetail?) -> Unit) {
        // Returns a JS object; WebView serialises it as JSON for the callback
        val js = """
            (function() {
                function findStrong(lbl) {
                    var els = document.querySelectorAll('strong');
                    for (var i = 0; i < els.length; i++) {
                        if (els[i].textContent.indexOf(lbl) !== -1) return els[i];
                    }
                    return null;
                }
                function metaVal() {
                    for (var i = 0; i < arguments.length; i++) {
                        var el = findStrong(arguments[i]);
                        if (el && el.nextElementSibling) return el.nextElementSibling.textContent.trim();
                    }
                    return '';
                }
                function metaList() {
                    for (var i = 0; i < arguments.length; i++) {
                        var el = findStrong(arguments[i]);
                        if (el && el.nextElementSibling) {
                            var links = el.nextElementSibling.querySelectorAll('a');
                            var r = [];
                            for (var k = 0; k < links.length; k++) {
                                var t = links[k].textContent.trim();
                                if (t) r.push(t);
                            }
                            if (r.length) return r;
                        }
                    }
                    return [];
                }
                function extractActors() {
                    for (var i = 0; i < arguments.length; i++) {
                        var el = findStrong(arguments[i]);
                        if (!el || !el.nextElementSibling) continue;
                        var links = el.nextElementSibling.querySelectorAll('a');
                        var r = [];
                        for (var k = 0; k < links.length; k++) {
                            var name = links[k].textContent.trim();
                            if (!name) continue;
                            var sym = links[k].nextElementSibling;
                            var s = (sym && sym.classList && sym.classList.contains('symbol'))
                                ? sym.textContent.trim() : '';
                            r.push(name + s);
                        }
                        if (r.length) return r;
                    }
                    return [];
                }
                // .current-title = 正文標題；第一個 strong = 番號，不要混入
                var currentTitleEl = document.querySelector('.title .current-title') ||
                                     document.querySelector('.current-title');
                var coverImg = document.querySelector('.video-cover');
                return {
                    title:       currentTitleEl ? currentTitleEl.textContent.trim() : '',
                    coverUrl:    coverImg ? (coverImg.getAttribute('src') || '') : '',
                    releaseDate: metaVal('\u65E5\u671F:', '\u767C\u884C\u65E5\u671F:', 'Released Date:'),
                    rating:      metaVal('\u8A55\u5206:', 'Rating:').trim(),
                    maker:       metaVal('\u7247\u5546:', '\u767C\u884C\u5546:', 'Maker:'),
                    series:      metaVal('\u7CFB\u5217:', 'Series:'),
                    genres:      metaList('\u985E\u5225:', '\u6A19\u7C64:', 'Tags:'),
                    actors:      extractActors('\u6F14\u54E1:', '\u5973\u512A:', 'Actor(s):')
                };
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            webView.destroy()
            if (result == null || result == "null") {
                android.util.Log.w("JavDbWebView", "Null result for $javCode")
                callback(null)
                return@evaluateJavascript
            }
            try {
                val obj = JSONObject(result)
                val genres = mutableListOf<String>()
                val gArr = obj.optJSONArray("genres")
                if (gArr != null) for (i in 0 until gArr.length()) genres.add(gArr.getString(i))
                val actors = mutableListOf<String>()
                val aArr = obj.optJSONArray("actors")
                if (aArr != null) for (i in 0 until aArr.length()) actors.add(aArr.getString(i))

                val detail = JavVideoDetail(
                    code = javCode,
                    title = obj.optString("title", ""),
                    coverUrl = obj.optString("coverUrl", ""),
                    date = obj.optString("releaseDate", ""),
                    duration = "",
                    maker = obj.optString("maker", ""),
                    series = obj.optString("series", ""),
                    rating = obj.optString("rating", ""),
                    genres = genres,
                    actors = actors,
                    detailUrl = detailUrl
                )
                android.util.Log.d(
                    "JavDbWebView",
                    "Enriched $javCode → date=${detail.date} maker=${detail.maker} actors=${detail.actors}"
                )
                callback(detail)
            } catch (e: Exception) {
                android.util.Log.e("JavDbWebView", "Parse error for $javCode: ${e.message}")
                callback(null)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createWebView(): WebView {
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("https://javdb.com", "over18=1; path=/; domain=.javdb.com")
            flush()
        }
        return wv
    }
}
