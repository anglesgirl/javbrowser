package com.example.javbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.javbrowser.gecko.GeckoRuntimeProvider
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/**
 * User-opened original page: no scraping, ad-removal scripts or native JavaScript bridge.
 *
 * 2026-11-14 pure-browsers 迁移：WebView → GeckoView + GeckoSession
 * （GeckoBrowserActivity 同款做法：单例 runtime、显式 setActive(true) +
 * setFocused(true)、首个 loadUri 走 geckoView.post、5 秒看门狗重试一次）。
 * UI 与行为不变：标题栏、地址栏、四个操作按钮、域白名单、状态行文案、
 * 状态恢复（savedInstanceState "page"）、返回键回退。
 * UA 仍是 SearchHttpFetcher.USER_AGENT（Pixel 7 手机 UA），不碰
 * /api/play/ 的桌面 UA；不拦 tsyndicate.com（VAST），不装任何扩展。
 */
class SearchSourceActivity : LocalizedActivity() {
    companion object {
        const val EXTRA_SITE = "source_site"
        const val EXTRA_URL = "source_url"
        const val EXTRA_REQUEST = "source_request"
        const val EXTRA_RETRY = "source_retry"

        /** 发 loadUri 后 onPageStart 久久不来，就当加载被丢弃，自动重试（GeckoBrowserActivity 同款）。 */
        private const val LOAD_START_TIMEOUT_MS = 5_000L
        /** geckoView.post 没跑起来时的兜底：最多等这么久就发首个 loadUri。 */
        private const val LOAD_START_FALLBACK_DELAY_MS = 1_000L
        private const val TAG = "SearchSource"
    }

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var address: TextView
    private lateinit var status: TextView

    /** 由 NavigationDelegate.onCanGoBack 维护（GeckoSession 没有 canGoBack() 方法）。 */
    private var canGoBack = false
    /** 当前正在载入/已载入的地址（复制、恢复、重试用；跳转后由 onLocationChange 更新）。 */
    private var currentUrl = ""
    /** onPageStart 是否来过（加载是否真的开始了）。 */
    private var pageStarted = false
    /** 5 秒看门狗是否已经自动重试过一次。 */
    private var loadRetried = false
    /** 首个 loadUri 是否已发出（post 与兜底延时只发一次）。 */
    private var initialLoadStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 首次加载兜底发出（geckoView.post 没跑起来时用）。 */
    private val initialLoadFallbackRunnable = Runnable { startInitialLoad() }
    private val startWatchdogRunnable = Runnable {
        if (pageStarted || !::geckoSession.isInitialized) return@Runnable
        if (!loadRetried) {
            loadRetried = true
            Log.w(TAG, "5 秒未触发 onPageStart，自动重载一次：$currentUrl")
            if (geckoSession.isOpen) {
                activateSession()
                geckoSession.loadUri(currentUrl)
                armStartWatchdog()
            } else {
                status.text = tr(
                    "原始頁面沒有開始載入：瀏覽器會話未打開，可重新載入",
                    "Original page did not start loading: browser session is not open; reload to retry"
                )
            }
        } else {
            Log.w(TAG, "重试后仍未触发 onPageStart：$currentUrl")
            status.text = tr(
                "原始頁面沒有開始載入：瀏覽器會話未就緒，載入請求被丟棄，可重新載入",
                "Original page did not start loading: browser session not ready, load request dropped; reload to retry"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_SITE).orEmpty()
        val site = SearchSiteRegistry.find(this, id)
        val initial = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (site == null || !SearchResultNormalizer.validUrl(initial, site)) { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 18)) }
        fun caption(textValue: String, size: Float) = TextView(this).apply {
            text = textValue; textSize = size; setTextColor(Color.WHITE); setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        root.addView(caption(tr("原始搜尋頁", "Original search page") + " · " + site.displayName, 17f))
        root.addView(caption(tr("直接顯示原站，不代表統一抓取已成功。完成驗證後可返回重試。",
            "This is the original site, not a successful extraction. Return and retry after verification."), 12f))
        address = caption(initial, 12f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setOnClickListener { copyUrl() } }
        root.addView(address)
        val actions = LinearLayout(this)
        fun action(zh: String, en: String, click: () -> Unit) {
            actions.addView(Button(this).apply {
                text = tr(zh, en); textSize = 11f; minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0); setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        action("× 關閉", "× Close") { finish() }
        action("複製網址", "Copy URL") { copyUrl() }
        action("重新載入", "Reload") { reloadPage() }
        action("返回並重試", "Retry site") {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_SITE, id)
                .putExtra(EXTRA_REQUEST, intent.getStringExtra(EXTRA_REQUEST)).putExtra(EXTRA_RETRY, true))
            finish()
        }
        root.addView(actions)
        status = caption(tr("載入原始網頁中", "Loading the original page"), 12f)
        root.addView(status)
        geckoView = GeckoView(this)
        root.addView(geckoView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val restoredUrl = savedInstanceState?.getString("page")?.takeIf { SearchResultNormalizer.validUrl(it, site) }
        currentUrl = restoredUrl ?: initial

        val runtime = GeckoRuntimeProvider.get(this)
        geckoSession = GeckoSession()
        geckoSession.settings.apply {
            // 原 WebView 用 SearchHttpFetcher.USER_AGENT（Pixel 7 手机 UA），照搬。
            userAgentOverride = SearchHttpFetcher.USER_AGENT
            allowJavascript = true
            // 跟 GeckoBrowserActivity / 嵌頁播放器统一：关掉 Gecko 内建追踪防护，
            // 它用 Disconnect 清单，可能误拦站点关键资源；页面能打开优先。
            useTrackingProtection = false
        }
        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) {
                Log.w(TAG, "内容进程崩溃：$currentUrl")
                status.text = tr("原始頁面進程崩潰，可重新載入", "Original page process crashed; reload to retry")
            }

            override fun onKill(session: GeckoSession) {
                Log.w(TAG, "内容进程被系统结束：$currentUrl")
                status.text = tr("原始頁面進程被系統結束，可重新載入", "Original page process was ended by the system; reload to retry")
            }
        }
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                address.text = url
                currentUrl = url
                pageStarted = true
                mainHandler.removeCallbacks(startWatchdogRunnable)
                status.visibility = View.VISIBLE
                status.text = tr("載入原始網頁中", "Loading the original page")
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                mainHandler.removeCallbacks(startWatchdogRunnable)
                // 只在状态行仍是“載入中”时才改写：onLoadError / TLS / 崩溃已写过
                // 具体原因的，不被“已載入”覆盖（原 onPageFinished 里同款守卫）。
                if (status.text == tr("載入原始網頁中", "Loading the original page")) {
                    status.text = if (success) {
                        tr("原始頁面已載入，請查看網站實際訊息", "Original page loaded; check the site's message below")
                    } else {
                        tr("原始頁面載入失敗，可重新載入或返回結果", "Original page failed to load; reload or return to results")
                    }
                }
            }
        }
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                // 不开新窗口：对应原来 setSupportMultipleWindows(false) /
                // javaScriptCanOpenWindowsAutomatically = false。
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                // 主框导航仍守原来的域白名单（shouldOverrideUrlLoading 同款逻辑）：
                // 非本站域名一律不跳，只改状态行提示。返回 null = ALLOW（放行）。
                if (!SearchResultNormalizer.validUrl(request.uri, site)) {
                    status.text = tr("原站轉向其他網域，已停止自動跳轉", "The site redirected to a different domain; navigation stopped")
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }

            override fun onSubframeLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                // 子框只放 http(s)：对应原来 shouldOverrideUrlLoading 里
                // !isForMainFrame 时按 scheme 拦截的分支。
                val scheme = request.uri.substringBefore(":", "").lowercase()
                if (scheme != "http" && scheme != "https") {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url != null) {
                    address.text = url
                    currentUrl = url
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@SearchSourceActivity.canGoBack = canGoBack
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                mainHandler.removeCallbacks(startWatchdogRunnable)
                val failedUrl = uri ?: currentUrl
                Log.i(TAG, "载入失败：$failedUrl code=${error.code} category=${error.category}")
                // TLS 单独区分（原 onReceivedSslError 的行为）；其余统按
                // 原 onReceivedError 的通用文案。注意：Gecko 的
                // NavigationDelegate 不暴露 HTTP 状态码与响应头，所以原
                // onReceivedHttpError 的“HTTP xxx / CF 挑戰”细分丢了（见 NOTES.md）；
                // CF 挑戰頁本身会在 GeckoView 内渲染，用户仍可在页内操作。
                status.text = when (error.code) {
                    WebRequestError.ERROR_SECURITY_SSL,
                    WebRequestError.ERROR_SECURITY_BAD_CERT,
                    WebRequestError.ERROR_BAD_HSTS_CERT ->
                        tr(SearchFailure.zh("TLS_FAILURE"), SearchFailure.en("TLS_FAILURE"))
                    else ->
                        tr("原始頁面載入失敗，可重新載入或返回結果", "Original page failed to load; reload or return to results")
                }
                // 返回 null：不显示 Gecko 默认错误页，状态行就是失败反馈。
                return null
            }
        }
        // 加载 UI 立刻亮出来：用户从第 0 秒就看到状态行，不再是白屏。
        showLoading()
        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)
        // 显式激活会话：不依赖 Surface 路径（GeckoBrowserActivity 同款）。
        activateSession()
        // 首个 loadUri 等视图挂载 + 布局完成后再发；1 秒兜底延时防 post 没跑起来。只发一次。
        geckoView.post { startInitialLoad() }
        mainHandler.postDelayed(initialLoadFallbackRunnable, LOAD_START_FALLBACK_DELAY_MS)
    }

    /** 显式激活 + 聚焦会话（hyper-browser 的 setVisible 做法，GeckoBrowserActivity 同款）。 */
    private fun activateSession() {
        if (!::geckoSession.isInitialized) return
        runCatching {
            geckoSession.setActive(true)
            geckoSession.setFocused(true)
            geckoSession.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        }
    }

    /** 首个 loadUri（只发一次；post 与兜底延时都走这里）。 */
    private fun startInitialLoad() {
        if (initialLoadStarted || isFinishing) return
        initialLoadStarted = true
        mainHandler.removeCallbacks(initialLoadFallbackRunnable)
        startLoad(currentUrl)
    }

    /** 发起一次加载：重置“是否开始”状态，发 loadUri，启动 5 秒看门狗。 */
    private fun startLoad(url: String) {
        if (!::geckoSession.isInitialized || url.isBlank()) return
        currentUrl = url
        pageStarted = false
        loadRetried = false
        activateSession()
        Log.i(TAG, "开始加载：$url (session open=${geckoSession.isOpen})")
        showLoading()
        geckoSession.loadUri(url)
        armStartWatchdog()
    }

    private fun armStartWatchdog() {
        mainHandler.removeCallbacks(startWatchdogRunnable)
        mainHandler.postDelayed(startWatchdogRunnable, LOAD_START_TIMEOUT_MS)
    }

    private fun showLoading() {
        status.visibility = View.VISIBLE
        status.text = tr("載入原始網頁中", "Loading the original page")
    }

    /** “重新載入”按钮：已开始加载过就走 Gecko 原生 reload()，否则重发 loadUri（带看门狗）。 */
    private fun reloadPage() {
        if (!::geckoSession.isInitialized || !geckoSession.isOpen) return
        if (pageStarted) {
            geckoSession.reload()
        } else {
            startLoad(currentUrl)
        }
    }

    private fun copyUrl() {
        // 原来取 browser.url（加载前为 null 时不复制）；现在 currentUrl 初始
        // 就是 initial / 恢复的 URL，加载前复制也能拿到地址（小改进，见 NOTES.md）。
        val copyable = PageUrlClipboard.copyableUrl(currentUrl) ?: return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", copyable))
        Toast.makeText(this, tr("已複製網址", "URL copied"), Toast.LENGTH_SHORT).show()
    }
    private fun tr(zh: String, en: String) = LanguageManager.text(this, zh, en)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::geckoSession.isInitialized && canGoBack) {
            geckoSession.goBack()
        } else {
            finish()
        }
    }
    override fun onResume() {
        super.onResume()
        if (::geckoSession.isInitialized && geckoSession.isOpen) {
            activateSession()
        }
    }
    override fun onPause() {
        if (::geckoSession.isInitialized && geckoSession.isOpen) {
            runCatching { geckoSession.setActive(false) }
        }
        super.onPause()
    }
    override fun onStop() {
        if (::geckoSession.isInitialized && geckoSession.isOpen) {
            runCatching { geckoSession.stop() }
        }
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", currentUrl)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        mainHandler.removeCallbacks(startWatchdogRunnable)
        mainHandler.removeCallbacks(initialLoadFallbackRunnable)
        if (::geckoSession.isInitialized) {
            runCatching { geckoSession.stop() }
            if (::geckoView.isInitialized) {
                (geckoView.parent as? android.view.ViewGroup)?.removeView(geckoView)
            }
            geckoSession.close()
        }
        super.onDestroy()
    }
}
