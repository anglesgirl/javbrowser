package com.example.javbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.javbrowser.gecko.GeckoEval
import com.example.javbrowser.gecko.GeckoHeadless
import org.json.JSONObject
import org.json.JSONTokener
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Rendered search fetch, Gecko headless session edition (2026-11-14,
 * search-cluster GeckoView migration).
 *
 * Same contract as the old WebView edition: fetch a search page with a
 * real browser engine (JS runs, dynamic results render), poll the DOM
 * until [SearchDomReadiness] says the result/empty snapshot is stable,
 * and return the rendered HTML as a [SearchHttpResponse] with
 * `rendered = true` so [SearchHtmlParser] treats
 * DYNAMIC_RESULTS_NOT_READY as a timeout, not a parse error.
 * The caller ([SiteHtmlSearchProvider]) only uses this as the
 * `browserFallback` render path; the plain-OkHttp fetch stays primary.
 *
 * What changed (WebView -> Gecko, only the pipe):
 * - Loading: [GeckoHeadless.loadSession] (headless GeckoSession, Gecko
 *   native DoH + ECH) instead of a hidden WebView + loadUrl.
 * - DOM polling: [GeckoEval.evaluate] with the same script, same 400ms
 *   cadence, same result semantics (JSON-quoted string, "null" for
 *   null/undefined, null on timeout/failure) as evaluateJavascript.
 * - Redirect guard: the old shouldOverrideUrlLoading (block a main-frame
 *   navigation to a non-allowed host) has no equivalent in the shared
 *   headless API, so the poll script now also returns `location.href`
 *   and the Kotlin side finishes 599 UNEXPECTED_REDIRECT_HOST when the
 *   live URL leaves the site's allowlist (checked every poll, <=400ms
 *   after the navigation).
 *
 * What the shared API cannot express (known loss, see
 * NOTES.md in the migration folder):
 * - No HTTP status / headers: no onReceivedHttpError equivalent, so
 *   `cf-mitigated: challenge`, Cloudflare detection and Retry-After are
 *   no longer populated (always null/false/0). Challenge pages are still
 *   caught by the DOM `blocked` check in the poll script and by
 *   [SearchPageClassifier] on the returned body. A 4xx page that loads
 *   with content now reports status 200 instead of its real code, and
 *   one with no matches runs to the full timeout (598) instead of
 *   finishing early on its status.
 * - No load-error codes: a failed load surfaces only as
 *   `onReady(null)` (timeout or failure, indistinguishable) and maps to
 *   598, where the old code distinguished DNS/CONNECT/TLS/timeout
 *   (599 + DNS_FAILURE/CONNECT_FAILURE/TLS_FAILURE/HTTP_TIMEOUT).
 * - No UA override in the shared API: the session runs Gecko's default
 *   (Firefox) UA, where the old WebView sent
 *   [SearchHttpFetcher.USER_AGENT] (Pixel 7 Chrome 124).
 * - Images load (the old WebView had loadsImagesAutomatically=false).
 * - Cookies: the Gecko session has its own cookie store. It neither
 *   sends the OkHttp/webview jar's cookies nor contributes any back;
 *   the task forbids bridging the two stores, so this stays split.
 */
class SearchWebViewFetcher(context: Context) {
    private val appContext = context.applicationContext

    fun fetchBlocking(url: String, site: SearchSite, rules: SearchParserRules,
                      cancellation: SearchCancellation, timeoutMs: Long = 25_000): SearchHttpResponse {
        cancellation.check()
        SearchScheduler.browserSlot.acquire()
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val response = AtomicReference<SearchHttpResponse?>()
        var session: GeckoSession? = null
        var lastHtml = ""
        var lastFinalUrl = url
        var pageLoaded = false
        val readiness = SearchDomReadiness()
        fun finish(status: Int, body: String = lastHtml, code: String? = null, retryAfter: Long = 0L) {
            if (!finished.compareAndSet(false, true)) return
            response.set(SearchHttpResponse(status, lastFinalUrl, body, "text/html",
                retryAfter, code, null, false, rendered = true))
            handler.removeCallbacksAndMessages(null)
            try {
                session?.let { runCatching { it.close() } }
                session = null
            } finally {
                SearchScheduler.browserSlot.release()
                latch.countDown()
            }
        }
        val detach = cancellation.onCancel { handler.post { finish(499, "") } }
        handler.post {
            if (finished.get()) return@post
            if (cancellation.isCancelled) { finish(499, ""); return@post }
            try {
                val script = """
                    (function() {
                      var hosts = [${site.allowedHosts.joinToString(",") { JSONObject.quote(it) }}];
                      var detailPath = new RegExp(${JSONObject.quote("^(?:" + rules.detailPath.pattern + ")$")}, 'i');
                      function detailUrl(a) {
                        try {
                          var u = new URL(a.getAttribute('href'), document.baseURI);
                          if (!/^https?:$/.test(u.protocol) || u.username || u.password || (u.port && u.port !== '80' && u.port !== '443')) return '';
                          if (!hosts.some(function(h) { return u.hostname === h || u.hostname.endsWith('.' + h); }) || !detailPath.test(u.pathname)) return '';
                          u.hash = '';
                          return u.href;
                        } catch (_) { return ''; }
                      }
                      function visible(e) {
                        for (var n = e; n; n = n.parentElement) {
                          var style = getComputedStyle(n);
                          if (n.hidden || n.getAttribute('aria-hidden') === 'true' || style.display === 'none' || style.visibility === 'hidden') return false;
                        }
                        return true;
                      }
                      var text = document.body ? document.body.innerText : '';
                      var blocked = new RegExp(${JSONObject.quote(SearchPageClassifier.challengeWords.pattern)}, 'i').test(document.title + ' ' + text)
                        || Array.from(document.querySelectorAll(${JSONObject.quote(SearchPageClassifier.CHALLENGE_SELECTORS + ", #cf-error-details")})).some(visible)
                        || /sign in to continue|login to continue|請先登入|you have been blocked|access denied/i.test(text);
                      var matches = Array.from(document.querySelectorAll(${JSONObject.quote(rules.links)})).map(function(a) {
                        var href = detailUrl(a);
                        if (!href || !visible(a) || a.closest('nav, .advertisement')) return '';
                        var card = a.closest(${JSONObject.quote(rules.card)});
                        if (!card) {
                          card = a;
                          for (var depth = 0; depth < 3; depth++) {
                            var parent = card.parentElement;
                            if (!parent || /^(BODY|HTML|MAIN|NAV|HEADER|FOOTER)$/.test(parent.tagName)) break;
                            var targets = Array.from(parent.querySelectorAll(${JSONObject.quote(rules.links)})).map(detailUrl).filter(Boolean);
                            if (new Set(targets).size !== 1) break;
                            card = parent;
                          }
                        }
                        if (a.closest('header, footer') && !card.closest('article')) return '';
                        var title = card.querySelector('.title, .video-title, .video-name, .content-title, h2, h3, h4');
                        var image = card.querySelector('img[alt]');
                        var label = [a.getAttribute('title'), a.textContent, title && title.textContent, image && image.alt]
                          .find(function(value) { return value && value.trim().length > 0; });
                        return label ? href + ':' + label.trim() : '';
                      }).filter(Boolean);
                      var empty = Array.from(document.querySelectorAll(${JSONObject.quote(rules.empty + ", " + SearchPageClassifier.EMPTY_SELECTORS + ", main h1, main h2")}))
                        .some(function(e) {
                          return visible(e) && !e.closest('nav, header, footer, .comments, #comments, .recommendations, .related, .related-videos')
                            && !e.closest(${JSONObject.quote(rules.card)})
                            && new RegExp(${JSONObject.quote(SearchPageClassifier.emptyWords.pattern)}, 'i').test(e.textContent.trim());
                        });
                      return JSON.stringify({ready: matches.length > 0 || empty, blocked: blocked,
                        signature: matches.length > 0 ? matches.join('|') : (empty ? 'empty' : ''), loaded: document.readyState === 'complete',
                        url: location.href,
                        html: document.documentElement ? document.documentElement.outerHTML.substring(0, 2000000) : ''});
                    })()
                """.trimIndent()
                val poll = object : Runnable {
                    override fun run() {
                        if (finished.get()) return
                        val current = session ?: return
                        GeckoEval.evaluate(current, script) { raw ->
                            if (finished.get()) return@evaluate
                            val json = runCatching { JSONObject(JSONTokener(raw).nextValue().toString()) }.getOrNull()
                            lastHtml = json?.optString("html").orEmpty()
                            pageLoaded = json?.optBoolean("loaded") == true
                            // Redirect guard (replaces shouldOverrideUrlLoading):
                            // the live main-frame URL must stay on an
                            // allowed host with an http(s) scheme.
                            json?.optString("url")?.takeIf { it.isNotBlank() }?.let { liveUrl ->
                                lastFinalUrl = liveUrl
                                val allowed = try {
                                    val parsed = java.net.URL(liveUrl)
                                    (parsed.protocol == "http" || parsed.protocol == "https") &&
                                        site.isAllowedHost(parsed.host.orEmpty())
                                } catch (_: Exception) {
                                    false
                                }
                                if (!allowed) {
                                    finish(599, "", "UNEXPECTED_REDIRECT_HOST")
                                    return@evaluate
                                }
                            }
                            val ready = readiness.observe(pageLoaded, json?.optBoolean("ready") == true,
                                json?.optBoolean("blocked") == true, json?.optString("signature").orEmpty())
                            if (ready) finish(200)
                            else handler.postDelayed(this, 400)
                        }
                    }
                }
                GeckoHeadless.loadSession(appContext, url, timeoutMs) { loaded ->
                    if (finished.get()) return@loadSession
                    if (loaded == null) {
                        // Load never completed (timeout or failure — the
                        // shared API does not distinguish them, and
                        // reports no error code). Same 598 the old code
                        // used when the page never became readable.
                        finish(598)
                        return@loadSession
                    }
                    session = loaded
                    handler.post(poll)
                }
                handler.postDelayed({ finish(if (pageLoaded && lastHtml.isNotBlank()) 200 else 598) }, timeoutMs)
            } catch (_: Exception) { finish(599, "") }
        }
        try {
            if (!latch.await(timeoutMs + 1500, TimeUnit.MILLISECONDS)) handler.post { finish(598) }
            return response.get() ?: SearchHttpResponse(598, url, "", null)
        } catch (e: InterruptedException) {
            handler.post { finish(499, "") }
            throw e
        } finally { detach() }
    }
}
