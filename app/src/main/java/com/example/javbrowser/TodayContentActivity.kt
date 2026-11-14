package com.example.javbrowser

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.javbrowser.gecko.GeckoRuntimeProvider
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/**
 * 2026-11-14 pure-browsers 迁移：WebView → GeckoView + GeckoSession
 * （GeckoBrowserActivity 同款做法：单例 runtime、显式 setActive(true) +
 * setFocused(true)、首个 loadUri 走 geckoView.post、5 秒看门狗重试一次）。
 * UI 与行为不变：标题栏、进度条、返回/收藏/跨站搜尋按钮、收藏切换、
 * 标题回填、返回键回退。UA 照搬原来内联的 Pixel 8 Chrome 122 手机 UA，
 * 不碰 /api/play/ 的桌面 UA；不拦 tsyndicate.com（VAST），不装任何扩展。
 *
 * 注意：activity_today_content.xml 里仍是 <WebView
 * android:id="@+id/webview_today_content">，本文件不改 layout：setupGeckoView()
 * 在启动时把这个 WebView 换成 GeckoView（同位置、同尺寸，跨站搜尋浮钮仍
 * 盖在上面）。想彻底干净可把 layout 里的 WebView 改成
 * <org.mozilla.geckoview.GeckoView>，再把下面改成 findViewById<GeckoView>。
 */
class TodayContentActivity : LocalizedActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_THUMB = "thumb"
        const val EXTRA_JAV_CODE = "jav_code"

        /** 原 WebView 内联的 UA，照搬（Pixel 8、Chrome 122 手机 UA）。 */
        private const val TODAY_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240105.004) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.6261.119 Mobile Safari/537.36"
        /** 发 loadUri 后 onPageStart 久久不来，就当加载被丢弃，自动重试（GeckoBrowserActivity 同款）。 */
        private const val LOAD_START_TIMEOUT_MS = 5_000L
        /** geckoView.post 没跑起来时的兜底：最多等这么久就发首个 loadUri。 */
        private const val LOAD_START_FALLBACK_DELAY_MS = 1_000L
        private const val TAG = "TodayContent"
    }

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: Button
    private lateinit var btnBookmark: Button
    private lateinit var btnCrossSearch: Button
    private lateinit var favoritesManager: FavoritesManager

    private var targetUrl: String = ""
    private var targetTitle: String = ""
    private var targetThumb: String? = null
    private var targetJavCode: String? = null

    /** 由 NavigationDelegate.onCanGoBack 维护（GeckoSession 没有 canGoBack() 方法）。 */
    private var canGoBack = false
    /** 当前正在载入/已载入的地址（跳转后由 onLocationChange 更新）。 */
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
                progressBar.visibility = View.GONE
                Toast.makeText(this, "頁面沒有開始載入：瀏覽器會話未打開", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.w(TAG, "重试后仍未触发 onPageStart：$currentUrl")
            progressBar.visibility = View.GONE
            Toast.makeText(this, "頁面沒有開始載入，請檢查網絡後重試", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PrivacySettings(this).isScreenSecure) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        setContentView(R.layout.activity_today_content)

        favoritesManager = FavoritesManager(this)
        // 注意：这里不再取 R.id.webview_today_content，GeckoView 在 setupGeckoView()
        // 里替换 layout 中的 WebView 占位（见该方法注释）。
        progressBar = findViewById(R.id.progress_today_content)
        tvTitle = findViewById(R.id.tv_today_content_title)
        btnBack = findViewById(R.id.btn_back_today_content)
        btnBookmark = findViewById(R.id.btn_close_today_content)
        btnCrossSearch = findViewById(R.id.btn_today_content_cross_search)

        targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        targetTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: targetUrl
        targetThumb = intent.getStringExtra(EXTRA_THUMB)?.takeIf { it.isNotBlank() }
        targetJavCode = intent.getStringExtra(EXTRA_JAV_CODE)?.takeIf { it.isNotBlank() }

        if (targetJavCode.isNullOrBlank()) {
            targetJavCode = CrossSiteSearchUi.extractCode(targetTitle + " " + targetUrl)
        }

        tvTitle.text = targetTitle.ifBlank { "新着內容" }

        btnBack.setOnClickListener {
            if (::geckoSession.isInitialized && canGoBack) geckoSession.goBack() else finish()
        }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnCrossSearch.visibility = if (targetJavCode.isNullOrBlank()) View.GONE else View.VISIBLE
        btnCrossSearch.setOnClickListener {
            CrossSiteSearchUi.show(this, targetJavCode.orEmpty().ifBlank { targetTitle + " " + targetUrl })
        }
        refreshBookmarkButton()

        // 原顺序是先 setupWebView() 再判空 finish；这里把判空提前到建会话之前，
        // 避免给一个即将 finish 的页面白开一个 Gecko 会话，无用户可见变化。
        if (targetUrl.isBlank()) {
            finish()
            return
        }
        setupGeckoView()
    }

    private fun setupGeckoView() {
        // activity_today_content.xml 里仍是 <WebView android:id="@+id/webview_today_content">：
        // 启动时把它换成 GeckoView（同位置、同尺寸，跨站搜尋浮钮仍盖在上面），
        // layout 文件本身不用改。WebView 占位只 inflate、不加载，换下后立即 destroy。
        val oldWebView = findViewById<WebView>(R.id.webview_today_content)
        val container = oldWebView.parent as ViewGroup
        val index = container.indexOfChild(oldWebView)
        container.removeView(oldWebView)
        oldWebView.destroy()
        geckoView = GeckoView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(geckoView, index)

        currentUrl = targetUrl
        val runtime = GeckoRuntimeProvider.get(this)
        geckoSession = GeckoSession()
        geckoSession.settings.apply {
            userAgentOverride = TODAY_UA
            allowJavascript = true
            // 跟 GeckoBrowserActivity / 嵌頁播放器统一：关掉 Gecko 内建追踪防护，
            // 它用 Disconnect 清单，可能误拦站点关键资源；页面能打开优先。
            useTrackingProtection = false
        }
        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrBlank()) {
                    tvTitle.text = title
                    if (targetTitle.isBlank() || targetTitle == targetUrl) {
                        targetTitle = title
                    }
                }
            }

            override fun onCrash(session: GeckoSession) {
                Log.w(TAG, "内容进程崩溃：$currentUrl")
                progressBar.visibility = View.GONE
                Toast.makeText(this@TodayContentActivity, "頁面進程崩潰，請返回重試", Toast.LENGTH_SHORT).show()
            }

            override fun onKill(session: GeckoSession) {
                Log.w(TAG, "内容进程被系统结束：$currentUrl")
                progressBar.visibility = View.GONE
                Toast.makeText(this@TodayContentActivity, "頁面進程被系統結束，請返回重試", Toast.LENGTH_SHORT).show()
            }
        }
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                currentUrl = url
                pageStarted = true
                mainHandler.removeCallbacks(startWatchdogRunnable)
                progressBar.visibility = View.VISIBLE
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                progressBar.visibility = if (progress in 0..99) View.VISIBLE else View.GONE
                progressBar.progress = progress
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                mainHandler.removeCallbacks(startWatchdogRunnable)
                progressBar.visibility = View.GONE
            }
        }
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url != null) {
                    currentUrl = url
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@TodayContentActivity.canGoBack = canGoBack
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                // 不开新窗口：对应原来 setSupportMultipleWindows(false)。
                // 其余一律放行（原 WebViewClient 没有任何 URL 拦截）。返回 null = ALLOW。
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                mainHandler.removeCallbacks(startWatchdogRunnable)
                progressBar.visibility = View.GONE
                val failedUrl = uri ?: currentUrl
                Log.i(TAG, "载入失败：$failedUrl code=${error.code} category=${error.category}")
                // 原来加载失败时没有任何反馈（进度条直接消失）；现在给一句 Toast。
                Toast.makeText(this@TodayContentActivity, describeError(error), Toast.LENGTH_SHORT).show()
                // 返回 null：不显示 Gecko 默认错误页。
                return null
            }
        }
        // 加载 UI 立刻亮出来：用户从第 0 秒就看到进度条，不再是白屏。
        progressBar.visibility = View.VISIBLE
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
        progressBar.visibility = View.VISIBLE
        geckoSession.loadUri(url)
        armStartWatchdog()
    }

    private fun armStartWatchdog() {
        mainHandler.removeCallbacks(startWatchdogRunnable)
        mainHandler.postDelayed(startWatchdogRunnable, LOAD_START_TIMEOUT_MS)
    }

    /** 把 WebRequestError 的错误码翻译成人话（附错误码，方便报障；GeckoBrowserActivity 同款精简版）。 */
    private fun describeError(error: WebRequestError): String {
        val reason = when (error.code) {
            WebRequestError.ERROR_UNKNOWN_HOST -> "域名解析失敗：找不到該網站的服務器"
            WebRequestError.ERROR_NET_TIMEOUT -> "連接超時：服務器長時間無響應"
            WebRequestError.ERROR_NET_RESET -> "連接被重置（網絡阻斷或服務器斷開）"
            WebRequestError.ERROR_CONNECTION_REFUSED -> "服務器拒絕了連接"
            WebRequestError.ERROR_SECURITY_SSL,
            WebRequestError.ERROR_SECURITY_BAD_CERT,
            WebRequestError.ERROR_BAD_HSTS_CERT -> "TLS 握手失敗或證書無效"
            WebRequestError.ERROR_OFFLINE -> "設備沒有網絡連接"
            else -> "頁面載入失敗"
        }
        return "$reason（錯誤碼 ${error.code}）"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::geckoSession.isInitialized && canGoBack) {
            geckoSession.goBack()
        } else {
            super.onBackPressed()
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

    override fun onDestroy() {
        mainHandler.removeCallbacks(startWatchdogRunnable)
        mainHandler.removeCallbacks(initialLoadFallbackRunnable)
        if (::geckoSession.isInitialized) {
            runCatching { geckoSession.stop() }
            if (::geckoView.isInitialized) {
                (geckoView.parent as? ViewGroup)?.removeView(geckoView)
            }
            geckoSession.close()
        }
        super.onDestroy()
    }

    private fun toggleBookmark() {
        val isSaved = favoritesManager.getFavorites().any { it.url == targetUrl }
        if (isSaved) {
            favoritesManager.removeFavorite(targetUrl)
            Toast.makeText(this, "已移除書籤", Toast.LENGTH_SHORT).show()
        } else {
            favoritesManager.addFavorite(
                title = targetTitle.ifBlank { targetUrl },
                url = targetUrl,
                thumbnailUrl = targetThumb,
                javCode = targetJavCode
            )
            Toast.makeText(this, "已加入書籤", Toast.LENGTH_SHORT).show()
        }
        refreshBookmarkButton()
    }

    private fun refreshBookmarkButton() {
        val isSaved = targetUrl.isNotBlank() && favoritesManager.getFavorites().any { it.url == targetUrl }
        btnBookmark.text = if (isSaved) "已收藏" else "加入書籤"
        btnBookmark.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (isSaved) "#7B1FA2" else "#1565C0")
        )
    }
}
