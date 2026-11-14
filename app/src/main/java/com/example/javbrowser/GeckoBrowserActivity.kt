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
 * 跳过）；广告拦截靠 Gecko 内建追踪防护（GeckoRuntimeProvider 默认
 * 开着）。搜索与播放主流程不动。
 *
 * 白屏修（2026-11-14 1.2.4-gecko）：1.2.3 用户真机点 JavDB 只见白
 * 屏。核对过，搭建与能播的嵌頁播放器等价（同一 Runtime、同一
 * open → setSession → loadUri 顺序；布局是标准顶栏 + weight=1 的
 * GeckoView；追踪防护只拦第三方追踪器，不拦主文档），不是渲染
 * 搭错。真正的问题是加载慢/失败时零反馈：页面在用户当时 0.75KB/s
 * 的网络下极慢或失败（RST/超时），而旧版没有任何加载进度，失败
 * 只有一个 2 秒就消失的 Toast——慢和失败看起来都是白屏。所以修
 * 的是反馈与重试：可见进度条 +“正在加载…”、30 秒加载超时、失败
 * 时用 onLoadError 拿真实错误原因显示在失败页上 + 重试按钮，不
 * 再白屏静默。
 */
class GeckoBrowserActivity : LocalizedActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        /** 一次页面加载超过这么久没出结果，就当超时，显示失败页（可重试）。 */
        private const val LOAD_TIMEOUT_MS = 30_000L

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
    /** 由 NavigationDelegate.onCanGoBack 维护（GeckoSession 没有 canGoBack() 方法）。 */
    private var canGoBack = false
    /** 当前正在载入的地址（重试用；跳转后由 onLocationChange 更新）。 */
    private var currentUrl = ""
    private var loading = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadTimeoutRunnable = Runnable {
        if (loading) {
            Log.i("GeckoBrowser", "加载超时：$currentUrl")
            showError("加载超时：30 秒没有响应，当前网络很慢或连接被阻断")
        }
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
        val retryButton = Button(this).apply {
            text = "重试"
            setOnClickListener { retryLoad() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        errorView.addView(errorTitle)
        errorView.addView(errorMessage)
        errorView.addView(retryButton)
        contentFrame.addView(errorView)
        root.addView(contentFrame)
        setContentView(root)

        val runtime = GeckoRuntimeProvider.get(this)
        geckoSession = GeckoSession()
        geckoSession.settings.apply {
            allowJavascript = true
            // 不关追踪防护：通用浏览器靠它挡广告/追踪器（嵌頁播放器
            // 才关，怕误拦 VAST/视频 CDN）。
        }
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                urlText.text = url
                currentUrl = url
                showLoading()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                progressBar.progress = progress
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                loading = false
                mainHandler.removeCallbacks(loadTimeoutRunnable)
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
                val failedUrl = uri ?: currentUrl
                Log.i("GeckoBrowser", "载入失败：$failedUrl code=${error.code} category=${error.category}")
                showError("${describeError(error)}\n$failedUrl")
                // 返回 null：不显示 Gecko 默认错误页，用我们自己的失败页。
                return null
            }
        }
        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)
        // Gecko 原生 ECH/DoH，直接载入，不再明文 SNI。
        geckoSession.loadUri(url)
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
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        errorMessage.text = reason
        errorView.visibility = View.VISIBLE
    }

    private fun retryLoad() {
        if (!::geckoSession.isInitialized) return
        errorView.visibility = View.GONE
        geckoSession.loadUri(currentUrl)
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
        if (::geckoSession.isInitialized) {
            geckoSession.close()
        }
        super.onDestroy()
    }
}
