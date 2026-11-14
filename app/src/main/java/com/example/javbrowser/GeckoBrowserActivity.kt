package com.example.javbrowser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.javbrowser.gecko.GeckoRuntimeProvider
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

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
 */
class GeckoBrowserActivity : LocalizedActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        fun intent(context: Context, url: String, title: String = ""): Intent =
            Intent(context, GeckoBrowserActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
    }

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var urlText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isNotBlank()) this.title = title
        if (url.isBlank()) {
            Toast.makeText(this, "地址为空", Toast.LENGTH_LONG).show()
            finish()
            return
        }

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

        geckoView = GeckoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        root.addView(geckoView)
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
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) {
                    Toast.makeText(
                        this@GeckoBrowserActivity,
                        "页面载入失败，请重试",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?) {
                if (url != null) urlText.text = url
            }
        }
        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)
        // Gecko 原生 ECH/DoH，直接载入，不再明文 SNI。
        geckoSession.loadUri(url)
    }

    override fun onBackPressed() {
        if (::geckoSession.isInitialized && geckoSession.canGoBack()) {
            geckoSession.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (::geckoSession.isInitialized) {
            geckoSession.close()
        }
        super.onDestroy()
    }
}
