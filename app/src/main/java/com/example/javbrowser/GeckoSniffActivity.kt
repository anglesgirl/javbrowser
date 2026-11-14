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
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * 嗅探播放地址（GeckoView，2026-11-14）。
 *
 * 路线史：① 静态 HTML 解析（VideoPageSniffer）只能捡广告，正片是
 * JS 动态加载的；② webRequest 抓包（1.2.3–1.2.9，两轮）——先只认
 * URL 后缀，后加猫抓三信号 + 内容脚本读 <video>——用户真机仍失败：
 * 视频已经在播，嗅探没出视频、还报“页面失败”，弹回只有广告的
 * “选择播放地址”页。③ 1.2.10 换路线：DOM 直读是主路。
 *
 * 前两轮失败的根因（已定位）：扩展是异步装的，Activity 调
 * ensureInstalled 后不等装好就 loadUri，页面、播放器、manifest
 * 请求可能全在扩展装好之前发生——webRequest 监听器还没注册、
 * 内容脚本还没注入，等装好时正片早已用 blob:/MSE 播起来了，
 * currentSrc 是 blob:（不可播）、manifest 请求已错过，于是零
 * 候选。修法是先装好扩展再加载页面（install-before-load）。
 *
 * 为什么不是 Activity 侧 evaluateJS：GeckoView 151 没有这个
 * API（对 AAR 的 GeckoSession 类查过，常量池里连 "eval" 都没有；
 * Mozilla 官方文档也写明“唯一受支持的办法就是写一个内置
 * WebExtension 并安装它”；loadUri("javascript:...") 经用户报告
 * 在 GeckoView 里跑不起来）。所以“Activity 侧 JS 求值”的等价
 * 实现是：Activity 控制时序（扩展装好再加载页面），JS 执行仍走
 * 内置扩展的内容脚本 + 页面探针，Activity 侧按内容脚本每 2 秒
 * 上报的 domScan 做轮询与收尾决策——不依赖扩展“恰好及时装好”。
 *
 * 主路（DOM 直读）：内容脚本每 2 秒读全部 <video>/<audio> 的
 * currentSrc/src/<source>；页面探针（page-probe.js，注入页面自己
 * 的 JS 世界）读 jwplayer()/videojs/hls.js 实例的地址（hls.js 播
 * HLS 时 currentSrc 是 blob:，真 m3u8 在 hls.url 里；内容脚本的
 * 隔离世界看不到这些对象）。读到非广告地址即候选；读到“视频在
 * 播”（paused=false、currentTime>0）即收尾信号。webRequest 三
 * 信号降为辅助（留着，不删）。
 *
 * 状态诚实：onPageStop(success=false) 只记不报——视频在播时它也
 * 可能报 false（重定向/子资源），以 DOM 里有没有 <video> 为准；
 * 只有超时时既无候选、DOM 里又没见过 video，才报“未嗅探到播放
 * 地址”；视频在播但没读到地址时报“视频在播，但未读到播放地址”。
 * tsyndicate.com VAST 永远不拦、也不当候选；javhd 的 /api/play/
 * 桌面 UA 铁律不碰（本页不走 /api/play/）。
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

        /** 首个非广告候选到后等多久收尾（收更多清晰度/分段）。 */
        private const val GRACE_MS = 3000L
        /** 开始加载后的总超时。 */
        private const val TIMEOUT_MS = 20000L
        /** 正片已播但尚无候选时，超时延长一次的时长。 */
        private const val TIMEOUT_EXTENSION_MS = 10000L
        /** 等扩展装好的上限；超时仍加载页面（扩展晚装上后内容脚本
         *  还能注入已加载的页面，domScan 随后会到）。 */
        private const val INSTALL_TIMEOUT_MS = 8000L

        // 与 VideoPageSniffer.adSegment 同款：整段匹配 ad/ads/
        // preview/trailer/thumb/sample/snapshot，"load"/"download"
        // 不会误伤。另加 tsyndicate.com（VAST 本身就是广告视频）。
        private val AD_SEGMENT =
            Regex("""(?<![a-z0-9])(ad|ads|preview|trailer|thumb|sample|snapshot)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

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
    private lateinit var detailUrl: String
    private val handler = Handler(Looper.getMainLooper())
    private val candidates = linkedSetOf<String>()
    private var finished = false
    private var graceScheduled = false
    private var timeoutExtended = false
    private var loadStarted = false
    /** DOM 里见过 <video>/<audio> 元素（地面真相，见 domScan）。 */
    private var sawVideoElement = false
    /** DOM 里见过正在播放的视频。 */
    private var sawPlaying = false
    /** 最近一次 onPageStop 的结果；null 表示正在加载。只记不报。 */
    private var lastPageStopSuccess: Boolean? = null
    private val finishRunnable = Runnable { finishWithResults() }
    private val timeoutRunnable = Runnable {
        if (!finished) {
            when {
                candidates.isNotEmpty() -> {
                    // 只有广告时 grace 从不排期，走到这里是正常回包。
                }
                sawVideoElement -> {
                    Toast.makeText(this, "视频在播，但未读到播放地址", Toast.LENGTH_SHORT).show()
                }
                lastPageStopSuccess == false -> {
                    // 页面真的没加载出来，且 DOM 里也读不到 video。
                    Toast.makeText(this, "页面载入失败", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Toast.makeText(this, "未嗅探到播放地址", Toast.LENGTH_SHORT).show()
                }
            }
            finishWithResults()
        }
    }
    private val installTimeoutRunnable = Runnable { startLoading() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detailUrl = intent.getStringExtra(EXTRA_DETAIL_URL).orEmpty()
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
            text = "正在准备浏览器…"
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
            override fun onPageStart(session: GeckoSession, url: String) {
                lastPageStopSuccess = null
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                // 只记，不直接报“页面载入失败”：视频在播时它也可
                // 能报 false（重定向/子资源失败），以 DOM 里有没
                // 有 <video> 为准（见 domScan 与超时时的判定）。
                lastPageStopSuccess = success
            }
        }
        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)

        JavhdGeckoExtension.attachMediaSniffer(geckoSession) { url ->
            onCandidate(url)
        }
        JavhdGeckoExtension.attachVideoPlayingListener(geckoSession) {
            onPlayingDetected()
        }
        JavhdGeckoExtension.attachDomScanListener(geckoSession) { json ->
            onDomScan(json)
        }

        // 主路的关键时序：扩展装好再加载页面。扩展是异步装的，
        // 不等装好就 loadUri，页面、播放器、manifest 请求会全在
        // 扩展就绪之前发生——前两轮就是这么漏掉正片的。扩展在
        // 本进程里只装一次，后续嗅探 onReady 立即回调，不多等。
        // 8 秒还没装好也加载（扩展晚装上后内容脚本还能注入已
        // 加载的页面，domScan 随后会到），不等死。
        JavhdGeckoExtension.ensureInstalled(this) { startLoading() }
        handler.postDelayed(installTimeoutRunnable, INSTALL_TIMEOUT_MS)
    }

    /** 扩展就绪（或安装超时）后开始加载详情页，同时启动嗅探总超时。 */
    private fun startLoading() {
        if (loadStarted) return
        loadStarted = true
        handler.removeCallbacks(installTimeoutRunnable)
        statusText.text = "正在读播放地址…"
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        geckoSession.loadUri(detailUrl)
    }

    private fun onCandidate(url: String) {
        if (finished) return
        val added = candidates.add(url)
        if (!added) return
        updateStatus()
        // 旧逻辑的坑：首个候选（常是广告）到后 3 秒就收尾，正片
        // 常在 VAST 跳过后才来，容易被截断。改成只有非广告候选
        // 到后才开始 3 秒收尾；只来广告时等满 20 秒超时再回。
        if (!graceScheduled && !looksAd(url)) {
            graceScheduled = true
            handler.postDelayed(finishRunnable, GRACE_MS)
        }
    }

    /**
     * 内容脚本每 2 秒上报一次 DOM 扫描（主路的轮询节拍）。
     * videoCount：页面里 <video>/<audio> 的个数；playing：有
     * 视频在播；playingSrc：在播视频的地址（可能是 blob:）；
     * srcs：全部 http(s) 的元素地址（已逐条当候选上报，这里
     * 再收一遍防漏）。
     */
    private fun onDomScan(json: JSONObject) {
        if (finished) return
        val videoCount = json.optInt("videoCount", 0)
        if (videoCount > 0) sawVideoElement = true
        val playing = json.optBoolean("playing", false)
        val srcs: JSONArray? = json.optJSONArray("srcs")
        if (srcs != null) {
            for (i in 0 until srcs.length()) {
                val url = srcs.optString(i)
                if (url.isNotBlank()) onCandidate(url)
            }
        }
        if (finished) return
        if (playing) {
            onPlayingDetected()
        } else {
            updateStatus()
        }
    }

    /**
     * 正片已开始播放（playing 事件，或 domScan 发现已在播的视频
     * ——后者补“视频在内容脚本注入前就已开播、事件已错过”的情况）。
     * 有候选就立刻收尾（正片地址已在手里）；没候选就把超时延长
     * 一次——正片在播，说明播放器已拿到流，它的地址可能还在路上
     * （或走了一条刚补上的检测路径）。
     */
    private fun onPlayingDetected() {
        if (finished) return
        sawPlaying = true
        if (candidates.isNotEmpty()) {
            statusText.text = "正片已开始播放，收尾…"
            finishWithResults()
            return
        }
        if (!timeoutExtended) {
            timeoutExtended = true
            handler.removeCallbacks(timeoutRunnable)
            handler.postDelayed(timeoutRunnable, TIMEOUT_EXTENSION_MS)
            statusText.text = "视频已在播，正在读播放地址…"
        } else {
            updateStatus()
        }
    }

    /** 按地面真相写状态，绝不报与事实不符的“失败”。 */
    private fun updateStatus() {
        if (finished) return
        statusText.text = when {
            sawPlaying && candidates.isEmpty() -> "视频已在播，正在读播放地址…"
            candidates.isNotEmpty() -> "正在读播放地址… 已找到 ${candidates.size} 个"
            !loadStarted -> "正在准备浏览器…"
            else -> "正在读播放地址…"
        }
    }

    private fun looksAd(url: String): Boolean {
        val lower = url.lowercase()
        if ("tsyndicate.com" in lower) return true
        return AD_SEGMENT.containsMatchIn(url)
    }

    private fun finishWithResults() {
        if (finished) return
        finished = true
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(installTimeoutRunnable)
        // 去重已由 linkedSetOf 保证；回包时非广告排前面（下游
        // 选择器同分同排，顺序只是保底），广告沉底。
        val (feature, ads) = candidates.partition { !looksAd(it) }
        val result = Intent().apply {
            putStringArrayListExtra(EXTRA_CANDIDATE_URLS, ArrayList(feature + ads))
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
            JavhdGeckoExtension.detachVideoPlayingListener(geckoSession)
            JavhdGeckoExtension.detachDomScanListener(geckoSession)
            geckoSession.close()
        }
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(installTimeoutRunnable)
        super.onDestroy()
    }
}
