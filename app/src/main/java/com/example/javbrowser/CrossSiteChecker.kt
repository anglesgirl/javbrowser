package com.example.javbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 番號跨站檢測器：隱藏 WebView 確認各平台是否存在對應頁面。
 * - Jable：HTTP HEAD（無 Cloudflare，快）
 * - MissAV：隱藏 WebView（共用 cf_clearance，繞過 Cloudflare）
 * - AvJoy：隱藏 WebView 解析搜尋頁，取觀看數最高的原版 + 中字版各一條
 */
object CrossSiteChecker {

    const val KEY_JABLE             = "jable"
    const val KEY_PIGAV             = "pigav"
    const val KEY_AVTODAY           = "avtoday"
    const val KEY_MISSAV            = "missav"
    const val KEY_MISSAV_UNCENSORED = "missav_uncensored"
    const val KEY_MISSAV_CHINESE    = "missav_chinese"
    const val KEY_AVJOY             = "avjoy"
    const val KEY_AVJOY_CHINESE     = "avjoy_chinese"
    const val KEY_JAVHD             = "javhd"
    const val KEY_JAVHD_UNCENSORED  = "javhd_uncensored"
    const val KEY_JAVHD_CHINESE     = "javhd_chinese"
    const val KEY_7MMTV             = "7mmtv"
    const val KEY_AVPLE             = "avple"
    const val KEY_WHOS              = "whos"
    const val KEY_JAVDAY            = "javday"
    const val KEY_SUPJAV            = "supjav"
    const val KEY_JAVRATE           = "javrate"
    private val additionalSiteExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    fun checkAll(
        context: Context,
        javCode: String,
        sourceUrl: String,
        callback: (Map<String, String>) -> Unit
    ) {
        val code     = javCode.lowercase().trim()
        val extendedCallback: (Map<String, String>) -> Unit = { existing ->
            val providers = SearchProviderFactory.create(context.applicationContext).values.filter {
                it.site.id in setOf(KEY_JAVDAY, KEY_SUPJAV, KEY_JAVRATE) &&
                    !it.site.isAllowedHost(runCatching { java.net.URI(sourceUrl).host }.getOrNull().orEmpty())
            }
            additionalSiteExecutor.execute {
                val found = existing.toMutableMap()
                providers.forEach { provider ->
                    val request = SearchRequest(java.util.UUID.randomUUID().toString(), code, code.uppercase(),
                        SearchMode.CODE, listOf(provider.site.id), provider.site.configurationKey)
                    runCatching { provider.search(request) }.getOrNull()?.items?.firstOrNull()?.let {
                        found[provider.site.id] = it.detailUrl
                    }
                }
                Handler(Looper.getMainLooper()).post { callback(found) }
            }
        }
        val isMissav = sourceUrl.contains("missav", ignoreCase = true)
        val isJable  = sourceUrl.contains("jable",  ignoreCase = true)
        val isAvjoy  = sourceUrl.contains("avjoy",  ignoreCase = true)
        val is7mmtv  = sourceUrl.contains("7mmtv", ignoreCase = true) ||
            sourceUrl.contains("7tv", ignoreCase = true)
        val isAvple  = sourceUrl.contains("avple.tv", ignoreCase = true)
        val isWhos   = sourceUrl.contains("whos.tv", ignoreCase = true)

        // ── Jable / MissAV 候選 ───────────────────────────────────────────────
        val candidates = mutableMapOf<String, String>()

        if (!isJable) {
            candidates[KEY_JABLE] = "https://jable.tv/videos/$code/"
        }

        if (isMissav) {
            if (!sourceUrl.contains("uncensored"))       candidates[KEY_MISSAV_UNCENSORED] = "https://missav.ai/$code-uncensored-leak"
            if (!sourceUrl.contains("chinese-subtitle")) candidates[KEY_MISSAV_CHINESE]    = "https://missav.ai/$code-chinese-subtitle"
            if (sourceUrl.contains("uncensored") || sourceUrl.contains("chinese-subtitle")) {
                candidates[KEY_MISSAV] = "https://missav.ai/$code"
            }
        } else {
            // Jable、AvJoy 或其他來源：全部 MissAV 變體都查
            candidates[KEY_MISSAV]            = "https://missav.ai/$code"
            candidates[KEY_MISSAV_UNCENSORED] = "https://missav.ai/$code-uncensored-leak"
            candidates[KEY_MISSAV_CHINESE]    = "https://missav.ai/$code-chinese-subtitle"
        }

        val isJavhd = sourceUrl.contains("javhdporn", ignoreCase = true)

        Handler(Looper.getMainLooper()).post {
            // Step 1：依序檢查 Jable / MissAV
            checkSequentially(
                context.applicationContext,
                candidates.entries.toList(),
                mutableMapOf()
            ) { jableMissavFound ->
                // Step 2：AvJoy 搜尋（來源本身是 AvJoy 則跳過）
                if (!isAvjoy) {
                    checkAvJoy(context.applicationContext, code) { avjoyFound ->
                        // Step 3：JavHD 檢查（來源本身是 JavHD 則跳過）
                        if (!isJavhd) {
                            checkJavHD(context, code) { javhdFound ->
                                checkExtendedSites(context, code, is7mmtv, isAvple, isWhos, jableMissavFound + avjoyFound + javhdFound, extendedCallback)
                            }
                        } else {
                            checkExtendedSites(context, code, is7mmtv, isAvple, isWhos, jableMissavFound + avjoyFound, extendedCallback)
                        }
                    }
                } else {
                    // Step 3：JavHD 檢查
                    if (!isJavhd) {
                        checkJavHD(context, code) { javhdFound ->
                        checkExtendedSites(context, code, is7mmtv, isAvple, isWhos, jableMissavFound + javhdFound, extendedCallback)
                        }
                    } else {
                        checkExtendedSites(context, code, is7mmtv, isAvple, isWhos, jableMissavFound, extendedCallback)
                    }
                }
            }
        }
    }

    private fun checkExtendedSites(
        context: Context,
        code: String,
        skip7mmtv: Boolean,
        skipAvple: Boolean,
        skipWhos: Boolean,
        seed: Map<String, String>,
        callback: (Map<String, String>) -> Unit
    ) {
        val domains = DomainConfig(AdFilterRules(context.applicationContext))
        val found = seed.toMutableMap()
        fun checkWhosIfNeeded() {
            if (skipWhos) {
                callback(found)
            } else {
                checkWhos(context.applicationContext, code, domains.getWhosSearchUrl(code)) { url ->
                    if (url != null) found[KEY_WHOS] = url
                    callback(found)
                }
            }
        }
        fun checkAvpleIfNeeded() {
            if (skipAvple) {
                checkWhosIfNeeded()
            } else {
                checkAvple(context.applicationContext, code, domains.getAvpleSearchUrl(code)) { url ->
                    if (url != null) found[KEY_AVPLE] = url
                    checkWhosIfNeeded()
                }
            }
        }
        if (skip7mmtv) {
            checkAvpleIfNeeded()
        } else {
            check7MmTv(context.applicationContext, code, domains.get7MmTvSearchUrl(code)) { url ->
                if (url != null) found[KEY_7MMTV] = url
                checkAvpleIfNeeded()
            }
        }
    }

    /** 7MMTV 搜尋頁的結果網址包含數字 ID，必須從卡片中精確比對番號。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun check7MmTv(
        context: Context,
        code: String,
        searchUrl: String,
        callback: (String?) -> Unit
    ) {
        checkSearchResultWithWebView(
            context, code, searchUrl, is7MmTv = true, callback = callback
        )
    }

    /** Avple 受 Cloudflare 保護，使用隱藏 WebView 共用 Cookie 解析 /video/<id>。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun checkAvple(
        context: Context,
        code: String,
        searchUrl: String,
        callback: (String?) -> Unit
    ) {
        checkSearchResultWithWebView(
            context, code, searchUrl, is7MmTv = false, callback = callback
        )
    }

    private fun checkWhos(
        context: Context,
        code: String,
        searchUrl: String,
        callback: (String?) -> Unit
    ) {
        checkSearchResultWithWebView(context, code, searchUrl, is7MmTv = false, isWhos = true, callback)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun checkSearchResultWithWebView(
        context: Context,
        code: String,
        searchUrl: String,
        is7MmTv: Boolean,
        isWhos: Boolean = false,
        callback: (String?) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        var responded = false
        fun done(result: String?) {
            if (responded) return
            responded = true
            webView.stopLoading()
            webView.destroy()
            callback(result)
        }

        val timeout = Runnable { done(null) }
        handler.postDelayed(timeout, 15_000)

        val targetCode = code.uppercase().trim()
        val script = if (is7MmTv) {
            """
            (function() {
                var target = '$targetCode';
                var compactTarget = target.replace(/[^A-Z0-9]/g, '');
                var links = document.querySelectorAll('a[href]');
                for (var i = 0; i < links.length; i++) {
                    var link = links[i];
                    var href = link.href || link.getAttribute('href') || '';
                    if (!/_content\/\d+\/[^/]+\.html/i.test(href)) continue;
                    var text = [link.innerText, link.getAttribute('title'), href].join(' ').toUpperCase();
                    var compact = text.replace(/[^A-Z0-9]/g, '');
                    if (compact.indexOf(compactTarget) !== -1) return href;
                }
                return '';
            })();
            """.trimIndent()
        } else if (isWhos) {
            """
            (function() {
                var target = '$targetCode';
                var compactTarget = target.replace(/[^A-Z0-9]/g, '');
                var links = document.querySelectorAll('a[href*="/videos/"]');
                for (var i = 0; i < links.length; i++) {
                    var link = links[i];
                    var href = link.getAttribute('href') || '';
                    if (/^\/videos\/?$/i.test(href)) continue;
                    var text = [link.innerText, link.getAttribute('title'), href].join(' ').toUpperCase();
                    if (text.replace(/[^A-Z0-9]/g, '').indexOf(compactTarget) !== -1) {
                        return new URL(href, location.href).href;
                    }
                }
                return '';
            })();
            """.trimIndent()
        } else {
            """
            (function() {
                var target = '$targetCode';
                var compactTarget = target.replace(/[^A-Z0-9]/g, '');
                var links = document.querySelectorAll('a[href*="/video/"]');
                for (var i = 0; i < links.length; i++) {
                    var link = links[i];
                    var text = [link.innerText, link.getAttribute('title'), link.getAttribute('aria-label')].join(' ').toUpperCase();
                    var compact = text.replace(/[^A-Z0-9]/g, '');
                    if (compact.indexOf(compactTarget) !== -1) return new URL(link.getAttribute('href'), location.href).href;
                }
                var body = (document.body && document.body.innerText || '').toUpperCase();
                if (body.replace(/[^A-Z0-9]/g, '').indexOf(compactTarget) === -1) return '';
                var any = document.querySelector('a[href*="/video/"]');
                return any ? new URL(any.getAttribute('href'), location.href).href : '';
            })();
            """.trimIndent()
        }

        fun evaluate(view: WebView?) {
            if (responded) return
            view?.evaluateJavascript(script) { raw ->
                if (responded) return@evaluateJavascript
                val result = runCatching { org.json.JSONTokener(raw ?: "null").nextValue().toString() }
                    .getOrDefault("")
                    .replace("\\/", "/")
                if (result.startsWith("http", ignoreCase = true)) {
                    handler.removeCallbacks(timeout)
                    done(result)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                if (responded) return
                val title = view?.title.orEmpty()
                if (title.contains("just a moment", true) || title.contains("cloudflare", true)) return
                evaluate(view)
                handler.postDelayed({ evaluate(view) }, 1_200)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) in listOf(404, 410)) {
                    handler.removeCallbacks(timeout)
                    done(null)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    handler.removeCallbacks(timeout)
                    done(null)
                }
            }
        }
        webView.loadUrl(searchUrl)
    }

    // ── Jable / MissAV 依序檢測 ──────────────────────────────────────────────

    private fun checkSequentially(
        context: Context,
        remaining: List<Map.Entry<String, String>>,
        found: MutableMap<String, String>,
        callback: (Map<String, String>) -> Unit
    ) {
        if (remaining.isEmpty()) { callback(found); return }
        val (key, url) = remaining.first().toPair()
        checkSingleUrl(context, key, url) { exists ->
            if (exists) found[key] = url
            checkSequentially(context, remaining.drop(1), found, callback)
        }
    }

    private fun checkSingleUrl(
        context: Context,
        key: String,
        url: String,
        callback: (Boolean) -> Unit
    ) {
        if (key == KEY_JABLE) {
            kotlin.concurrent.thread {
                val ok = headRequest(url)
                Handler(Looper.getMainLooper()).post { callback(ok) }
            }
            return
        }
        checkWithHiddenWebView(context, url, callback)
    }

    // ── MissAV：隱藏 WebView（共用 cookie，繞過 CF） ────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun checkWithHiddenWebView(
        context: Context,
        targetUrl: String,
        callback: (Boolean) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)

        var responded = false
        fun done(result: Boolean) {
            if (responded) return
            responded = true
            webView.stopLoading()
            webView.destroy()
            callback(result)
        }

        val timeout = Runnable { done(false) }
        handler.postDelayed(timeout, 15_000)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                if (responded) return
                view?.evaluateJavascript(
                    "(function(){" +
                    "  var t=document.title||'';" +
                    "  var l=document.body?document.body.innerHTML.length:0;" +
                    "  var u=location.href||'';" +
                    "  return t+'|||'+l+'|||'+u;" +
                    "})()"
                ) { raw ->
                    if (responded) return@evaluateJavascript
                    val decoded = raw?.trim()?.removeSurrounding("\"")
                        ?.replace("\\n", "")?.replace("\\\"", "\"") ?: ""
                    val parts   = decoded.split("|||")
                    val title   = parts.getOrElse(0) { "" }
                    val bodyLen = parts.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                    val finalUrl = parts.getOrElse(2) { "" }.trimEnd('/')

                    val isCfChallenge = title.contains("just a moment", ignoreCase = true) ||
                                        title.contains("checking your browser", ignoreCase = true) ||
                                        title.contains("attention required", ignoreCase = true)
                    if (isCfChallenge) return@evaluateJavascript

                    val is404 = title.contains("404") ||
                                title.contains("not found", ignoreCase = true)
                    val isHome = finalUrl == "https://missav.ai" ||
                                 finalUrl == "https://missav.ai/en" ||
                                 finalUrl.endsWith("/404")
                    val tooShort = bodyLen < 5000

                    handler.removeCallbacks(timeout)
                    done(!is404 && !isHome && !tooShort)
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame != true || responded) return
                val status = errorResponse?.statusCode ?: 0
                if (status == 404 || status == 410) { handler.removeCallbacks(timeout); done(false) }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true || responded) return
                handler.removeCallbacks(timeout); done(false)
            }
        }
        webView.loadUrl(targetUrl)
    }

    // ── AvJoy：解析搜尋頁，取原版 + 中字各觀看數最高一條 ────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun checkAvJoy(
        context: Context,
        code: String,
        callback: (Map<String, String>) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        var responded = false
        fun done(result: Map<String, String>) {
            if (responded) return
            responded = true
            webView.stopLoading()
            webView.destroy()
            callback(result)
        }

        val timeout = Runnable { done(emptyMap()) }
        handler.postDelayed(timeout, 15_000)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                if (responded) return
                // JS：分原版 / 中字兩組，各取觀看數最高的 href
                val targetCode = code.uppercase()
                val js = """
                    (function() {
                        var targetCode = '${targetCode}';
                        var rows = document.querySelectorAll('.content-row .content-info');
                        if (!rows.length) return JSON.stringify({original:null,chinese:null});
                        var originals = [], chinese = [];
                        rows.forEach(function(item) {
                            var titleEl = item.querySelector('.content-title');
                            var linkEl  = item.querySelector('a[href]');
                            var viewsEl = item.querySelector('.content-views');
                            if (!titleEl || !linkEl) return;
                            var title = titleEl.textContent.trim();
                            // 嚴格比對：標題必須包含完整番號（不分大小寫）
                            if (title.toUpperCase().indexOf(targetCode) === -1) return;
                            var href  = linkEl.getAttribute('href');
                            var vText = viewsEl ? viewsEl.textContent : '0';
                            var views = parseInt(vText.replace(/[^0-9]/g,'')) || 0;
                            var obj   = {href:href, views:views};
                            if (title.indexOf('\u4e2d\u6587') !== -1 ||
                                title.indexOf('\u5b57\u5e55') !== -1) {
                                chinese.push(obj);
                            } else {
                                originals.push(obj);
                            }
                        });
                        function best(arr) {
                            if (!arr.length) return null;
                            return arr.reduce(function(a,b){return b.views>a.views?b:a;}).href;
                        }
                        return JSON.stringify({original:best(originals), chinese:best(chinese)});
                    })()
                """.trimIndent()

                view?.evaluateJavascript(js) { raw ->
                    if (responded) return@evaluateJavascript
                    handler.removeCallbacks(timeout)
                    val found = mutableMapOf<String, String>()
                    try {
                        val clean = raw?.trim()?.removeSurrounding("\"")
                            ?.replace("\\\"", "\"")
                            ?.replace("\\/", "/") ?: "{}"
                        val json = org.json.JSONObject(clean)
                        if (!json.isNull("original")) {
                            val href = json.getString("original")
                            if (href.isNotEmpty()) found[KEY_AVJOY] = "https://avjoy.me$href"
                        }
                        if (!json.isNull("chinese")) {
                            val href = json.getString("chinese")
                            if (href.isNotEmpty()) found[KEY_AVJOY_CHINESE] = "https://avjoy.me$href"
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CrossSiteChecker", "AvJoy parse: ${e.message}")
                    }
                    done(found)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true || responded) return
                handler.removeCallbacks(timeout); done(emptyMap())
            }
        }

        webView.loadUrl("https://avjoy.me/search/videos/$code")
    }

    // ── JavHD：HEAD 請求檢查原版 + 中字版（中字版 URL = 原版 code 後加 'c'）────

    // ── JavHD：隱藏 WebView 依序檢查三個變體 ────────────────────────────────

    private fun checkJavHD(context: Context, code: String, callback: (Map<String, String>) -> Unit) {
        val candidates = listOf(
            KEY_JAVHD            to "https://www.javhdporn.net/video/$code/",
            KEY_JAVHD_UNCENSORED to "https://www.javhdporn.net/video/$code-decensored/",
            KEY_JAVHD_CHINESE    to "https://www.javhdporn.net/video/${code}c/"
        )
        // 第一次遇到 CF 時顯示暖機對話框，之後直接用 cookie 重試
        var cfWarmedUp = false
        checkJavHDSequentially(context, candidates, mutableMapOf(), cfWarmedUp, { cfWarmedUp = it }, callback)
    }

    private fun checkJavHDSequentially(
        context: Context,
        remaining: List<Pair<String, String>>,
        found: MutableMap<String, String>,
        cfWarmedUp: Boolean,
        setCfWarmedUp: (Boolean) -> Unit,
        callback: (Map<String, String>) -> Unit
    ) {
        if (remaining.isEmpty()) {
            android.util.Log.d("CrossSiteChecker", "JavHD result: $found")
            callback(found)
            return
        }
        val (key, url) = remaining.first()
        android.util.Log.d("CrossSiteChecker", "JavHD WebView checking: $url")
        checkJavHDWithWebView(context, url) { result ->
            when (result) {
                JavHDCheckResult.FOUND -> {
                    found[key] = url
                    checkJavHDSequentially(context, remaining.drop(1), found, cfWarmedUp, setCfWarmedUp, callback)
                }
                JavHDCheckResult.NOT_FOUND -> {
                    checkJavHDSequentially(context, remaining.drop(1), found, cfWarmedUp, setCfWarmedUp, callback)
                }
                JavHDCheckResult.CF_BLOCKED -> {
                    if (!cfWarmedUp) {
                        // 第一次遇到 CF：彈出真實 WebView 讓用戶過驗證，之後重試整個 remaining
                        setCfWarmedUp(true)
                        showJavHDCFWarmup(context) {
                            android.util.Log.d("CrossSiteChecker", "JavHD CF warmup done, retrying")
                            checkJavHDSequentially(context, remaining, found, true, setCfWarmedUp, callback)
                        }
                    } else {
                        // 已暖機還是被擋：略過此 URL
                        android.util.Log.d("CrossSiteChecker", "JavHD still CF after warmup, skip $url")
                        checkJavHDSequentially(context, remaining.drop(1), found, true, setCfWarmedUp, callback)
                    }
                }
            }
        }
    }

    private enum class JavHDCheckResult { FOUND, NOT_FOUND, CF_BLOCKED }

    @SuppressLint("SetJavaScriptEnabled")
    private fun checkJavHDWithWebView(context: Context, targetUrl: String, callback: (JavHDCheckResult) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)

        var responded = false
        fun done(result: JavHDCheckResult) {
            if (responded) return
            responded = true
            webView.stopLoading()
            webView.destroy()
            callback(result)
        }

        val timeout = Runnable {
            android.util.Log.d("CrossSiteChecker", "JavHD timeout: $targetUrl")
            done(JavHDCheckResult.CF_BLOCKED)
        }
        handler.postDelayed(timeout, 15_000)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                if (responded) return
                view?.evaluateJavascript(
                    "(function(){ return document.title + '|||' + location.href; })()"
                ) { raw ->
                    if (responded) return@evaluateJavascript
                    val decoded = raw?.trim()?.removeSurrounding("\"") ?: ""
                    val parts = decoded.split("|||")
                    val title = parts.getOrElse(0) { "" }
                    val finalUrl = parts.getOrElse(1) { "" }
                    android.util.Log.d("CrossSiteChecker", "JavHD page title='$title' url='$finalUrl'")

                    val isCF = title.contains("just a moment", ignoreCase = true) ||
                               title.contains("checking", ignoreCase = true) ||
                               title.contains("請稍候", ignoreCase = true) ||
                               title.contains("attention required", ignoreCase = true) ||
                               title.contains("cloudflare", ignoreCase = true)
                    if (isCF) {
                        android.util.Log.d("CrossSiteChecker", "JavHD CF page detected")
                        handler.removeCallbacks(timeout)
                        done(JavHDCheckResult.CF_BLOCKED)
                        return@evaluateJavascript
                    }

                    handler.removeCallbacks(timeout)
                    val is404 = title.contains("404") ||
                            title.contains("not found", ignoreCase = true) ||
                            title.contains("page not found", ignoreCase = true) ||
                            finalUrl.contains("/404")
                    done(if (is404) JavHDCheckResult.NOT_FOUND else JavHDCheckResult.FOUND)
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame != true || responded) return
                val status = errorResponse?.statusCode ?: 0
                android.util.Log.d("CrossSiteChecker", "JavHD HTTP error $status: $targetUrl")
                when (status) {
                    403 -> { handler.removeCallbacks(timeout); done(JavHDCheckResult.CF_BLOCKED) }
                    404, 410 -> { handler.removeCallbacks(timeout); done(JavHDCheckResult.NOT_FOUND) }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true || responded) return
                handler.removeCallbacks(timeout); done(JavHDCheckResult.CF_BLOCKED)
            }
        }
        webView.loadUrl(targetUrl)
    }

    // ── JavHD CF 暖機對話框：顯示真實 WebView 讓用戶過 CF 驗證 ────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun showJavHDCFWarmup(context: Context, onComplete: () -> Unit) {
        val activity = context as? android.app.Activity ?: run { onComplete(); return }
        activity.runOnUiThread {
            val webView = WebView(activity)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)

            val dialog = android.app.AlertDialog.Builder(activity)
                .setTitle("JavHD 驗證 - 通過後自動繼續")
                .setView(webView)
                .setNegativeButton("略過") { d, _ ->
                    webView.destroy()
                    d.dismiss()
                    onComplete()
                }
                .setCancelable(false)
                .create()

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript("document.title") { raw ->
                        val title = raw?.trim()?.removeSurrounding("\"") ?: ""
                        val isCF = title.contains("請稍候", ignoreCase = true) ||
                                   title.contains("just a moment", ignoreCase = true) ||
                                   title.contains("checking", ignoreCase = true)
                        if (!isCF) {
                            android.util.Log.d("CrossSiteChecker", "JavHD CF warmup passed, title='$title'")
                            // CF 通過，儲存 cookie 後關閉對話框
                            android.webkit.CookieManager.getInstance().flush()
                            activity.runOnUiThread {
                                webView.destroy()
                                dialog.dismiss()
                                onComplete()
                            }
                        }
                    }
                }
            }

            dialog.show()
            dialog.window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.9).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.7).toInt()
            )
            webView.loadUrl("https://www.javhdporn.net/")
        }
    }

    // ── Jable HEAD 請求 ───────────────────────────────────────────────────────

    private fun headRequest(url: String): Boolean = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        conn.connectTimeout = 8_000
        conn.readTimeout    = 8_000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        conn.disconnect()
        android.util.Log.d("CrossSiteChecker", "HEAD $url → $code")
        code in 200..299
    } catch (e: Exception) {
        android.util.Log.e("CrossSiteChecker", "HEAD $url → ERROR: ${e.message}")
        false
    }

    /** 根據 URL 判斷屬於哪個 key（順序重要） */
    fun keyFromUrl(url: String): String? = when {
        url.contains("jable.tv",         ignoreCase = true) -> KEY_JABLE
        url.contains("pigav.ws",         ignoreCase = true) -> KEY_PIGAV
        url.contains("avtoday.io",       ignoreCase = true) -> KEY_AVTODAY
        url.contains("uncensored-leak",  ignoreCase = true) -> KEY_MISSAV_UNCENSORED
        url.contains("chinese-subtitle", ignoreCase = true) -> KEY_MISSAV_CHINESE
        url.contains("missav",           ignoreCase = true) -> KEY_MISSAV
        url.contains("avjoy.me", ignoreCase = true) &&
            (url.contains("中文") || url.contains("字幕") ||
             url.contains("chinese", ignoreCase = true))    -> KEY_AVJOY_CHINESE
        url.contains("avjoy.me",         ignoreCase = true) -> KEY_AVJOY
        url.contains("javhdporn.net", ignoreCase = true) &&
            url.contains("-decensored",  ignoreCase = true) -> KEY_JAVHD_UNCENSORED
        url.contains("javhdporn.net", ignoreCase = true) &&
            Regex("/video/[^/]+c/$").containsMatchIn(url)   -> KEY_JAVHD_CHINESE
        url.contains("javhdporn.net",    ignoreCase = true) -> KEY_JAVHD
        (url.contains("7mmtv", ignoreCase = true) ||
            url.contains("7tv", ignoreCase = true))          -> KEY_7MMTV
        url.contains("avple.tv", ignoreCase = true)          -> KEY_AVPLE
        url.contains("whos.tv", ignoreCase = true)           -> KEY_WHOS
        AdditionalSiteMedia.hostMatches(url, "javday.app")   -> KEY_JAVDAY
        AdditionalSiteMedia.hostMatches(url, "supjav.com")   -> KEY_SUPJAV
        AdditionalSiteMedia.hostMatches(url, "javrate.com")  -> KEY_JAVRATE
        else                                                 -> null
    }
}
