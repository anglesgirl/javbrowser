package com.example.javbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.javbrowser.gecko.GeckoEval
import com.example.javbrowser.gecko.GeckoHeadless
import java.net.URLEncoder
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession

/**
 * JavDB scraper using a headless Gecko session (no paid API needed).
 * All Gecko operations run on the main thread internally;
 * [enrichFavorite] may be called from any thread.
 *
 * Gecko 迁移（原隐藏 WebView 版）：加载 → 等渲染 → evaluateJavascript
 * 取数据。任务简报给定的共享 API 里没有写 cookie 的接口，over18 cookie
 * 改为首载后用 JS 在 javdb.com 域内种下再重载一次（见
 * [seedOver18AndReload]）。注：核对 infra 簇落地实现后，infra 实际还公开
 * 了 `GeckoCookies.setCookie`（见 NOTES.md），后续可简化为先 setCookie
 * 再加载；本簇按简报 API 写，未用它。
 */
class JavDbWebViewScraper(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun enrichFavorite(javCode: String, callback: (JavVideoDetail?) -> Unit) {
        mainHandler.post { doSearch(javCode, callback) }
    }

    /** 直接用指定 JavDB 頁面 URL 補全（跳過搜尋，避免誤選第一筆） */
    fun enrichFromUrl(javCode: String, detailUrl: String, callback: (JavVideoDetail?) -> Unit) {
        mainHandler.post {
            val urlWithLocale = withLocale(detailUrl)
            GeckoHeadless.loadSession(context, urlWithLocale, LOAD_TIMEOUT_MS) { session ->
                mainHandler.post {
                    if (session == null) {
                        callback(null)
                        return@post
                    }
                    seedOver18AndReload(session, detailUrl) { ok ->
                        if (!ok) {
                            close(session)
                            callback(null)
                            return@seedOver18AndReload
                        }
                        mainHandler.postDelayed({
                            extractDetailData(session, javCode, detailUrl, callback)
                        }, SETTLE_DELAY_MS)
                    }
                }
            }
        }
    }

    // ── Step 1: search page ───────────────────────────────────────────────────

    private fun doSearch(javCode: String, callback: (JavVideoDetail?) -> Unit) {
        val searchUrl = "https://javdb.com/search?q=${URLEncoder.encode(javCode, "UTF-8")}&locale=zh"
        GeckoHeadless.loadSession(context, searchUrl, LOAD_TIMEOUT_MS) { session ->
            mainHandler.post {
                if (session == null) {
                    callback(null)
                    return@post
                }
                seedOver18AndReload(session, "javdb.com/search") { ok ->
                    if (!ok) {
                        close(session)
                        callback(null)
                        return@seedOver18AndReload
                    }
                    mainHandler.postDelayed({
                        extractHref(session, javCode) { href ->
                            if (href.isNullOrEmpty()) {
                                android.util.Log.w("JavDbWebView", "No search result for $javCode")
                                close(session)
                                callback(null)
                            } else {
                                doDetail(session, javCode, "https://javdb.com$href", callback)
                            }
                        }
                    }, SETTLE_DELAY_MS)
                }
            }
        }
    }

    private fun extractHref(session: GeckoSession, javCode: String, callback: (String?) -> Unit) {
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

        GeckoEval.evaluate(session, js) { result ->
            if (result == null || result == "null") {
                callback(null)
            } else {
                // evaluateJavascript wraps string result in JSON quotes
                callback(result.trim('"').replace("\\/", "/"))
            }
        }
    }

    // ── Step 2: detail page ───────────────────────────────────────────────────

    private fun doDetail(session: GeckoSession, javCode: String, detailUrl: String, callback: (JavVideoDetail?) -> Unit) {
        val urlWithLocale = withLocale(detailUrl)
        // 站内跳转：同一 session 里改 location.href，浏览器自动带上
        // Referer（原 WebView loadUrl 的同款语义）。
        GeckoEval.runJavascript(session, "location.href = '${urlWithLocale.replace("'", "\\'")}';")
        waitForSessionPage(session, detailUrl, LOAD_TIMEOUT_MS / 2) { ok ->
            if (!ok) {
                close(session)
                callback(null)
                return@waitForSessionPage
            }
            mainHandler.postDelayed({
                extractDetailData(session, javCode, detailUrl, callback)
            }, SETTLE_DELAY_MS)
        }
    }

    private fun extractDetailData(session: GeckoSession, javCode: String, detailUrl: String, callback: (JavVideoDetail?) -> Unit) {
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

        GeckoEval.evaluate(session, js) { result ->
            close(session)
            if (result == null || result == "null") {
                android.util.Log.w("JavDbWebView", "Null result for $javCode")
                callback(null)
                return@evaluate
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

    /**
     * 首载后在 javdb.com 域内用 JS 种 over18 cookie，再重载一次，等页面
     * 重新就绪后继续。原 WebView 版是在加载前用 CookieManager.setCookie
     * 预置；任务简报给定的共享 API 里没有写 cookie 的接口，只能先载一个
     * 同域页面再种 cookie（infra 实际还公开了 GeckoCookies.setCookie，
     * 后续可简化，见 NOTES.md）。
     * （若首载已是正常结果页，这次重载是多余的，但无害。）
     */
    private fun seedOver18AndReload(session: GeckoSession, marker: String, onReady: (Boolean) -> Unit) {
        GeckoEval.runJavascript(session, "document.cookie = 'over18=1; path=/; domain=.javdb.com';")
        GeckoEval.runJavascript(session, "location.reload();")
        waitForSessionPage(session, marker, LOAD_TIMEOUT_MS / 2, initialDelayMs = 800L, onReady = onReady)
    }

    /**
     * 等待同一 session 内的 JS 导航完成：轮询 location.href +
     * document.readyState，直到地址含 [marker] 且 readyState 为 complete。
     * 替代原 WebViewClient.onPageFinished（GeckoHeadless.loadSession 只
     * 负责首次加载，站内跳转要自己等）。
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

    private fun withLocale(detailUrl: String): String =
        if (detailUrl.contains("?")) "$detailUrl&locale=zh" else "$detailUrl?locale=zh"

    private fun close(session: GeckoSession?) {
        runCatching { session?.close() }
    }

    companion object {
        /** 单次页面加载上限。原 WebView 版没有总超时；Gecko 版以此为界。 */
        private const val LOAD_TIMEOUT_MS = 30_000L
        /** 原 onPageFinished 后等渲染稳定的延时，原样保留。 */
        private const val SETTLE_DELAY_MS = 2500L
    }
}
