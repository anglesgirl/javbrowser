package com.example.javbrowser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.javbrowser.gecko.GeckoRuntimeProvider
import com.example.javbrowser.gecko.JavhdGeckoExtension
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * 实时网络嗅探（GeckoView，2026-11-14，修“嗅探 7mmtv 只能得到广告”）。
 *
 * 根因：VideoPageSniffer 是静态 HTML 解析（UnifiedSearchActivity 搜
 * 详情时调 sniffScored），没有 7mmtv 专属解析器，退到通用 video 标
 * 签/宽松匹配，只能捡到广告视频——正片地址是 JS 动态加载的，静态
 * HTML 里根本没有。
 *
 * 新方法（用户定）：进入播放页面的时候开始嗅探。把详情/播放页装进
 * GeckoView（Gecko 原生 DoH + ECH，JS 正常跑、播放器正常发请求），
 * 内置扩展的 background 用 webRequest 实时观察播放器实际请求的
 * .m3u8/.mp4（非阻塞，同款广告过滤），抓到的正片地址回传给调用方，
 * 走现有选择器/播放逻辑，不再只依赖静态 HTML。
 *
 * 流程：首个候选到后等 3 秒收尾（收更多清晰度/分段），20 秒超时无
 * 候选则空手返回，调用方回落原有逻辑。tsyndicate.com VAST 永远
 * 不拦、也不当候选；javhd 的 /api/play/ 桌面 UA 铁律不碰（本页不
 * 走 /api/play/）。
 */
class GeckoSniffActivity : LocalizedActivity() {

    companion object {
        const val EXTRA_DETAIL_URL = "detail_url"
        const val EXTRA_SITE_ID = "site_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CODE = "code"
        const val EXTRA_CANDIDATE_URLS = "candidate_urls"

        /** 移动端 Chrome UA（与嵌頁播放器同款，站方按移动端出播放器）。 */
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /** 首个候选到后等多久收尾（收更多清晰度/分段）。 */
        private const val GRACE_MS = 3000L
        /** 无候选时的总超时。 */
        private const val TIMEOUT_MS = 20000L

        fun intent(
            context: Context,
            detailUrl: String,
            siteId: String,
            title: String,
            code: String = ""
        ): Intent =
            Intent(context, GeckoSniffActivity::class.java).apply {
                putExtra(EXTRA_DETAIL_URL, detailUrl)
                putExtra(EXTRA_SITE_ID, siteId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CODE, code)
            }
    }

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val candidates = linkedSetOf<String>()
    private var finished = false
    private var graceScheduled = false
    private val finishRunnable = Runnable { finishWithResults() }
    private val timeoutRunnable = Runnable {
        if (!finished) {
            Toast.makeText(this, "未嗅探到播放地址", Toast.LENGTH_SHORT).show()
            finishWithResults()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val detailUrl = intent.getStringExtra(EXTRA_DETAIL_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isNotBlank()) this.title = title
        if (detailUrl.isBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

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
        statusText = TextView(this).apply {
            text = "正在嗅探播放地址…"
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeButton = Button(this).apply {
            text = "✕"
            setOnClickListener { finishWithResults() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        bar.addView(statusText)
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
            userAgentOverride = MOBILE_UA
            allowJavascript = true
            // 关掉追踪防护：它可能误拦视频 CDN，而我们要抓的正是
            // 播放器发出的视频请求（嵌頁播放器同款做法）。
            useTrackingProtection = false
        }
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) {
                    statusText.text = "页面载入失败"
                }
            }
        }
        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)

        JavhdGeckoExtension.ensureInstalled(this)
        JavhdGeckoExtension.attachMediaSniffer(geckoSession) { url ->
            onCandidate(url)
        }

        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        geckoSession.loadUri(detailUrl)
    }

    private fun onCandidate(url: String) {
        if (finished) return
        val added = candidates.add(url)
        if (!added) return
        statusText.text = "正在嗅探播放地址… 已找到 ${candidates.size} 个"
        if (!graceScheduled) {
            graceScheduled = true
            handler.postDelayed(finishRunnable, GRACE_MS)
        }
    }

    private fun finishWithResults() {
        if (finished) return
        finished = true
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(timeoutRunnable)
        val result = Intent().apply {
            putStringArrayListExtra(EXTRA_CANDIDATE_URLS, ArrayList(candidates))
        }
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onBackPressed() {
        finishWithResults()
    }

    override fun onDestroy() {
        if (::geckoSession.isInitialized) {
            JavhdGeckoExtension.detachMediaSniffer(geckoSession)
            geckoSession.close()
        }
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(timeoutRunnable)
        super.onDestroy()
    }
}
