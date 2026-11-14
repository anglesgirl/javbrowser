package com.example.javbrowser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.javbrowser.gecko.GeckoRuntimeProvider
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/**
 * 通用 GeckoView 浏览器（2026-11-14，修“首页点网站断网”）。
 *
 * 根因：MainActivity 仍用 WebView，迁移时 shouldInterceptRequest 的
 * ECH 拦截改成放行（明文 SNI），在用户网络上直接被 RST。首页“直接
 * 前往”的站（navigateToUrl：JavDB、JavTrailers、Stripchat、Pornhub、
 * XVideos 等无原生解析器的站）走这个 GeckoView 打开——Gecko 原生
 * DoH + ECH，SNI 不明文，不会被 RST。
 *
 * 只做浏览，不装 javhd 扩展（那是嵌頁播放器专用的广告拦截/VAST
 * 跳过）。搜索与播放主流程不动。
 *
 * 白屏根治 1.2.6-gecko：1.2.5 用户真机失败页终于出来了，写的是
 * “页面没有开始加载：浏览器会话未就绪，加载请求被丢弃”——延时
 * 500ms + 5 秒看门狗重调一次都没让 onPageStart 触发，而且看门狗
 * 第一跳时 session.isOpen 已是 true（否则会先报“会话未打开”）。
 * 所以 session 是打开的，但加载就是不开始。已核对 GeckoView 151
 * 的 mozilla-central 源码，两个静默丢弃的关卡：
 *
 * ① open() 是异步的：isOpen() 只表示 Java 层 mWindow 已建好，
 * 原生 Window.open 要等 GeckoThread.PROFILE_READY 才执行；
 * loadUri() 发出的 GeckoView:LoadUri 事件进 NativeQueue 排队，
 * 内核没就绪就一直排着，不回调、不报错。runtime 若迟迟不到
 * PROFILE_READY，加载就永远不开始。
 * ② setActive(true) 只由 Surface 触发：GeckoView.setSession()
 * 只在 view 已挂载时才 acquire display；onAttachedToWindow() 也
 * 只 acquire；真正调 session.setActive(true) 的是底层 Surface
 * 的 onSurfaceChanged/acquire（SurfaceListener 里 mValid 置真后
 * 才调）。javbrowser 从没显式调过 setActive，全靠 Surface 路径
 * 激活——而 hyper-browser 是显式调的（GeckoSessionController
 * .setVisible/focusForUserInteraction：setActive(true) +
 * setFocused(true) + PRIORITY_HIGH）。会话没激活，加载不开始，
 * 同样静默。
 *
 * 修法（只改本文件 + MainActivity 预热一行，不动其他流程）：
 * ① setSession 后立刻显式 setActive(true) + setFocused(true) +
 * setPriorityHint(PRIORITY_HIGH)，onResume() 再断言一次，
 * onPause() 置 inactive——不依赖 Surface 路径；
 * ② 首个 loadUri 改走 geckoView.post（挂载 + 布局完成后再发，
 * 保证视图已有真实尺寸/Surface），另加 1 秒兜底延时（只发一次）；
 * ③ MainActivity.onCreate 里 decorView.post 预热 GeckoRuntime，
 * 让内核在首页停留期间就开始初始化，到点网站时已 PROFILE_READY；
 * ④ ContentDelegate.onCrash/onKill 进失败页（内容进程死了也不再
 * 静默）；⑤失败页加“自检”按钮：用同一 runtime 新开一个探测
 * session 加载 about:blank（不走网络），5 秒内 onPageStart 来了
 * 就是内核/会话正常、问题在目标网址或网络，没来就是内核或运行
 * 环境异常——定位写进失败页；失败页同时显示诊断快照
 * （会话是否打开 / 视图是否挂载 / 视图尺寸）。
 * 搜索、播放、嗅探、/api/play/ 桌面 UA、VAST 放行均未动。
 */
class GeckoBrowserActivity : LocalizedActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        /** 一次页面加载超过这么久没出结果，就当超时，显示失败页（可重试）。 */
        private const val LOAD_TIMEOUT_MS = 30_000L
        /** 发 loadUri 后 onPageStart 久久不来，就当加载被丢弃，自动重试。 */
        private const val LOAD_START_TIMEOUT_MS = 5_000L
        /** geckoView.post 没跑起来时的兜底：最多等这么久就发首个 loadUri。 */
        private const val LOAD_START_FALLBACK_DELAY_MS = 1_000L
        /** 自检（about:blank）等 onPageStart 的上限。 */
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val TAG = "GeckoBrowser"

        fun intent(context: Context, url: String, title: String = ""): Intent =
            Intent(context, GeckoBrowserActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
    }

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var urlText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var errorView: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button
    private lateinit var selfCheckButton: Button
    private lateinit var minimalTestButton: Button
    /** 由 NavigationDelegate.onCanGoBack 维护（GeckoSession 没有 canGoBack() 方法）。 */
    private var canGoBack = false
    /** 当前正在载入的地址（重试用；跳转后由 onLocationChange 更新）。 */
    private var currentUrl = ""
    private var loading = false
    /** onPageStart 是否来过（加载是否真的开始了）。 */
    private var pageStarted = false
    /** 5 秒看门狗是否已经自动重试过一次。 */
    private var loadRetried = false
    /** 首个 loadUri 是否已发出（post 与兜底延时只发一次）。 */
    private var initialLoadStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadTimeoutRunnable = Runnable {
        if (loading) {
            Log.i(TAG, "加载超时：$currentUrl")
            showError("加载超时：30 秒没有响应，当前网络很慢或连接被阻断")
        }
    }
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
                showError("页面没有开始加载：浏览器会话未打开\n$currentUrl")
            }
        } else {
            Log.w(TAG, "重试后仍未触发 onPageStart：$currentUrl")
            showError("页面没有开始加载：浏览器会话未就绪，加载请求被丢弃\n$currentUrl")
        }
    }
    /** 自检探测用的 session（用完即关，不影响主会话）。 */
    private var probeSession: GeckoSession? = null
    private var probeRunning = false
    private var probeStarted = false
    private val probeTimeoutRunnable = Runnable {
        if (probeRunning && !probeStarted) finishSelfCheck(false, "about:blank 5 秒内没有开始加载")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isNotBlank()) this.title = title
        if (url.isBlank()) {
            finish()
            return
        }
        currentUrl = url

        // 顶栏：地址 + 关闭。
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        urlText = TextView(this).apply {
            text = url
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeButton = Button(this).apply {
            text = "✕"
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        bar.addView(urlText)
        bar.addView(closeButton)
        root.addView(bar)

        // 加载进度条 + 状态行（加载中可见，加载完/失败时隐藏）。
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4)
            )
        }
        root.addView(progressBar)
        statusText = TextView(this).apply {
            text = "正在加载…"
            setTextColor(Color.parseColor("#999999"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(statusText)

        // 内容区：GeckoView + 失败页（失败页平时隐藏，盖在 GeckoView 上面）。
        val contentFrame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        geckoView = GeckoView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        contentFrame.addView(geckoView)
        errorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val errorTitle = TextView(this).apply {
            text = "页面载入失败"
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        errorMessage = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        retryButton = Button(this).apply {
            text = "重试"
            setOnClickListener { retryLoad() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        selfCheckButton = Button(this).apply {
            text = "自检"
            setOnClickListener { runSelfCheck() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }
        }
        minimalTestButton = Button(this).apply {
            text = "最小测试"
            setOnClickListener {
                startActivity(
                    Intent(this@GeckoBrowserActivity, GeckoMinimalTestActivity::class.java)
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }
        }
        errorView.addView(errorTitle)
        errorView.addView(errorMessage)
        errorView.addView(retryButton)
        errorView.addView(selfCheckButton)
        errorView.addView(minimalTestButton)
        contentFrame.addView(errorView)
        root.addView(contentFrame)
        setContentView(root)

        val runtime = GeckoRuntimeProvider.get(this)
        geckoSession = GeckoSession()
        geckoSession.settings.apply {
            allowJavascript = true
            // 跟嵌頁播放器/嗅探统一：关掉 Gecko 内建追踪防护。它用
            // Disconnect 清单，可能误拦站点关键资源；页面能打开优先
            // 于浏览器挡广告（广告拦截以后再说）。
            useTrackingProtection = false
        }
        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) {
                Log.w(TAG, "内容进程崩溃：$currentUrl")
                showError("页面进程崩溃：浏览器内核的内容进程已崩溃，点“重试”重新加载")
            }

            override fun onKill(session: GeckoSession) {
                Log.w(TAG, "内容进程被系统结束：$currentUrl")
                showError("页面进程被系统结束：内存不足或被系统回收，点“重试”重新加载")
            }
        }
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                urlText.text = url
                currentUrl = url
                pageStarted = true
                mainHandler.removeCallbacks(startWatchdogRunnable)
                showLoading()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                progressBar.progress = progress
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                loading = false
                mainHandler.removeCallbacks(loadTimeoutRunnable)
                mainHandler.removeCallbacks(startWatchdogRunnable)
                if (success) {
                    hideOverlays()
                } else if (errorView.visibility != View.VISIBLE) {
                    // onLoadError 没给具体原因时的兜底（一般不会走到这里，
                    // 失败多半先走 onLoadError）。
                    showError("页面载入失败，请检查网络后重试")
                }
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
                    urlText.text = url
                    currentUrl = url
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@GeckoBrowserActivity.canGoBack = canGoBack
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                loading = false
                mainHandler.removeCallbacks(loadTimeoutRunnable)
                mainHandler.removeCallbacks(startWatchdogRunnable)
                val failedUrl = uri ?: currentUrl
                Log.i(TAG, "载入失败：$failedUrl code=${error.code} category=${error.category}")
                showError("${describeError(error)}\n$failedUrl")
                // 返回 null：不显示 Gecko 默认错误页，用我们自己的失败页。
                return null
            }
        }
        // 加载 UI 立刻亮出来：用户从第 0 秒就看到进度条，不再是白屏。
        showLoading()
        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)
        // 显式激活会话：不依赖 Surface 路径（GeckoView 只在底层
        // Surface 就绪后才调 setActive(true)，而首个 loadUri 必须
        // 在激活的会话上发）。hyper-browser 同款做法。
        activateSession()
        // 首个 loadUri 等视图挂载 + 布局完成后再发（geckoView.post
        // 在挂载与测量后执行，保证视图已有真实尺寸/Surface）；1 秒
        // 兜底延时防 post 没跑起来。只发一次。
        geckoView.post { startInitialLoad() }
        mainHandler.postDelayed(initialLoadFallbackRunnable, LOAD_START_FALLBACK_DELAY_MS)
    }

    override fun onResume() {
        super.onResume()
        // 视图 detach/reattach 后再断言一次激活（Surface 重建时
        // GeckoView 也会自己调，但显式断言不依赖它）。
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

    /** 显式激活 + 聚焦会话（hyper-browser 的 setVisible 做法）。 */
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
        if (initialLoadStarted) return
        initialLoadStarted = true
        mainHandler.removeCallbacks(initialLoadFallbackRunnable)
        startLoad(currentUrl)
    }

    /** 发起一次加载：重置“是否开始”状态，发 loadUri，启动 5 秒看门狗。 */
    private fun startLoad(url: String) {
        if (!::geckoSession.isInitialized) return
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
        loading = true
        errorView.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        statusText.visibility = View.VISIBLE
        statusText.text = "正在加载…"
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        mainHandler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
    }

    private fun hideOverlays() {
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        errorView.visibility = View.GONE
    }

    private fun showError(reason: String) {
        loading = false
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        mainHandler.removeCallbacks(startWatchdogRunnable)
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        errorMessage.text = "$reason\n\n${diagnosticState()}"
        errorView.visibility = View.VISIBLE
    }

    /** 失败页上的诊断快照：直接验证“会话/视图/Surface”三关。 */
    private fun diagnosticState(): String {
        val open = if (::geckoSession.isInitialized) geckoSession.isOpen else false
        val attached = if (::geckoView.isInitialized) geckoView.isAttachedToWindow else false
        val w = if (::geckoView.isInitialized) geckoView.width else -1
        val h = if (::geckoView.isInitialized) geckoView.height else -1
        val hasSession = if (::geckoView.isInitialized) geckoView.session != null else false
        return "诊断：会话已打开=$open，视图已挂载=$attached，视图有会话=$hasSession，视图尺寸=${w}x$h"
    }

    private fun retryLoad() {
        if (!::geckoSession.isInitialized) return
        errorView.visibility = View.GONE
        startLoad(currentUrl)
    }

    /**
     * 自检：用同一 runtime 新开一个探测 session 加载 about:blank
     * （不走网络）。5 秒内 onPageStart 来了就是内核与会话正常，
     * 问题在目标网址或网络；没来就是内核或运行环境异常。
     * 探测完把主会话挂回视图，不影响主会话（它一直开着）。
     */
    private fun runSelfCheck() {
        if (probeRunning) return
        if (!::geckoSession.isInitialized) return
        probeRunning = true
        probeStarted = false
        selfCheckButton.isEnabled = false
        selfCheckButton.text = "自检中…"
        errorMessage.text = "${errorMessage.text}\n\n自检中：新会话加载 about:blank…"
        val runtime = GeckoRuntimeProvider.get(this)
        val probe = GeckoSession()
        probe.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (!probeStarted) {
                    probeStarted = true
                    mainHandler.removeCallbacks(probeTimeoutRunnable)
                    finishSelfCheck(true, "about:blank 已开始加载")
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!probeStarted) {
                    probeStarted = true
                    mainHandler.removeCallbacks(probeTimeoutRunnable)
                    finishSelfCheck(true, "about:blank 已加载完成")
                }
            }
        }
        probeSession = probe
        runCatching {
            probe.open(runtime)
            geckoView.setSession(probe)
            probe.setActive(true)
            probe.setFocused(true)
            probe.loadUri("about:blank")
        }.onFailure {
            Log.w(TAG, "自检启动失败", it)
            finishSelfCheck(false, "探测会话启动失败：${it.message}")
            return
        }
        mainHandler.postDelayed(probeTimeoutRunnable, PROBE_TIMEOUT_MS)
    }

    private fun finishSelfCheck(ok: Boolean, detail: String) {
        mainHandler.removeCallbacks(probeTimeoutRunnable)
        // 先把主会话挂回视图并激活，再关探测会话。
        if (::geckoSession.isInitialized) {
            runCatching {
                geckoView.setSession(geckoSession)
                activateSession()
            }
        }
        probeSession?.let { probe ->
            runCatching { probe.close() }
        }
        probeSession = null
        probeRunning = false
        selfCheckButton.isEnabled = true
        selfCheckButton.text = "自检"
        val verdict = if (ok) {
            "自检：内核与新会话正常（$detail），问题在目标网址或网络，点“重试”再载一次"
        } else {
            "自检：新会话也无法加载（$detail）——内核或运行环境异常，请把这段话发给开发者"
        }
        Log.i(TAG, verdict)
        errorMessage.text = "${errorMessage.text}\n\n$verdict"
    }

    /** 把 WebRequestError 的错误码翻译成人话（附错误码，方便报障）。 */
    private fun describeError(error: WebRequestError): String {
        val reason = when (error.code) {
            WebRequestError.ERROR_UNKNOWN_HOST -> "域名解析失败：找不到该网站的服务器"
            WebRequestError.ERROR_NET_TIMEOUT -> "连接超时：服务器长时间无响应，网络可能很慢"
            WebRequestError.ERROR_NET_RESET -> "连接被重置：中途被 RST（网络阻断或服务器断开）"
            WebRequestError.ERROR_NET_INTERRUPT -> "连接被中断"
            WebRequestError.ERROR_CONNECTION_REFUSED -> "服务器拒绝了连接"
            WebRequestError.ERROR_SECURITY_SSL -> "TLS 握手失败（SSL 协商问题）"
            WebRequestError.ERROR_SECURITY_BAD_CERT,
            WebRequestError.ERROR_BAD_HSTS_CERT -> "证书无效或不可信"
            WebRequestError.ERROR_OFFLINE -> "设备没有网络连接"
            WebRequestError.ERROR_REDIRECT_LOOP -> "重定向过多（死循环）"
            WebRequestError.ERROR_MALFORMED_URI -> "地址无效"
            WebRequestError.ERROR_PROXY_CONNECTION_REFUSED -> "代理服务器拒绝了连接"
            WebRequestError.ERROR_HTTPS_ONLY -> "HTTPS-only 模式下无法连接"
            WebRequestError.ERROR_CONTENT_CRASHED -> "页面进程崩溃"
            WebRequestError.ERROR_CORRUPTED_CONTENT,
            WebRequestError.ERROR_INVALID_CONTENT_ENCODING -> "页面内容损坏或编码错误"
            else -> "载入失败"
        }
        return "$reason（错误码 ${error.code}）"
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    override fun onBackPressed() {
        if (::geckoSession.isInitialized && canGoBack) {
            geckoSession.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        mainHandler.removeCallbacks(startWatchdogRunnable)
        mainHandler.removeCallbacks(initialLoadFallbackRunnable)
        mainHandler.removeCallbacks(probeTimeoutRunnable)
        probeSession?.let { probe ->
            runCatching { probe.close() }
        }
        probeSession = null
        if (::geckoSession.isInitialized) {
            geckoSession.close()
        }
        super.onDestroy()
    }
}
