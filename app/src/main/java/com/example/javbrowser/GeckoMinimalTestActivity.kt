package com.example.javbrowser

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/**
 * 最小 GeckoView 测试（2026-11-14，1.2.7-gecko 诊断用）。
 *
 * 背景：1.2.6 用户真机自检证实，连 about:blank（不走网络/DNS/ECH）
 * 都 5 秒内不触发 onPageStart。逐行对照 hyper-browser 后，两边
 * GeckoView 151、DoH provider、bootstrap 池、ECH prefs、config
 * 内容、ContentBlocking、runtime 创建方式全部一致，唯一的实质
 * 差异是打包：javbrowser 设了 packaging.jniLibs.useLegacyPackaging
 * = true（.so 全部压缩、安装时解压），hyper-browser 没设（默认
 * 未压缩、直接从 APK 加载）。1.2.7 已把该设置删掉与 hyper-browser
 * 对齐；本 Activity 用于二分定位，以防删掉后仍不行：
 *
 * 本 Activity 跑在独立进程（:geckotest，见 manifest），用
 * GeckoRuntime.create(context) 建一个默认 runtime——不写 config
 * 文件、不设 DoH、不设 ECH、不走 GeckoRuntimeProvider 单例，
 * 只加载 about:blank，并把每一步写进屏幕日志，8 秒后出结论：
 * - 通过：默认设置下 about:blank 能加载 → 内核本身正常，问题在
 *   主进程的配置（config/DoH/ECH）或单例 runtime。
 * - 失败：默认设置下 about:blank 也无法加载 → 问题在更底层
 *   （打包 / native 库 / 运行环境），与 config 无关。
 *
 * 入口：GeckoBrowserActivity 失败页上的“最小测试”按钮。
 * 这是诊断工具，不是正式功能；根因确认后可以删掉。
 */
class GeckoMinimalTestActivity : LocalizedActivity() {

    companion object {
        private const val TAG = "GeckoMinimalTest"
        /** 等 onPageStart 的上限。about:blank 不走网络，8 秒绰绰有余。 */
        private const val VERDICT_DELAY_MS = 8_000L
    }

    private lateinit var logText: TextView
    private lateinit var verdictText: TextView
    private lateinit var geckoView: GeckoView
    private var geckoSession: GeckoSession? = null
    private var pageStarted = false
    private var verdictShown = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val verdictRunnable = Runnable { showVerdict() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Gecko 最小测试"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        verdictText = TextView(this).apply {
            text = "测试中…（8 秒后出结论）"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(verdictText)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        logText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 13f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(logText)
        root.addView(scroll)
        geckoView = GeckoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(120)
            )
        }
        root.addView(geckoView)
        val closeButton = Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(closeButton)
        setContentView(root)

        log("进程：${android.os.Process.myPid()}（独立 :geckotest 进程）")
        log("开始：创建默认 GeckoRuntime（无 config、无 DoH、无 ECH）")
        val runtime = try {
            GeckoRuntime.create(this)
        } catch (e: Exception) {
            Log.e(TAG, "创建默认 runtime 失败", e)
            log("失败：创建默认 GeckoRuntime 抛异常：${e.javaClass.simpleName}: ${e.message}")
            verdictText.text = "结论：失败——默认 runtime 建不起来"
            verdictText.setBackgroundColor(Color.parseColor("#7A1F1F"))
            verdictShown = true
            return
        }
        log("默认 GeckoRuntime 已创建：$runtime")
        val session = GeckoSession()
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                pageStarted = true
                log("onPageStart：$url")
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                log("onPageStop：success=$success")
                if (success) showVerdict()
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                log("onLoadError：uri=$uri code=${error.code} category=${error.category}")
                return null
            }
        }
        geckoSession = session
        log("打开会话…")
        runCatching {
            session.open(runtime)
            geckoView.setSession(session)
            session.setActive(true)
            session.setFocused(true)
            log("会话已打开（isOpen=${session.isOpen}），已激活，加载 about:blank…")
            session.loadUri("about:blank")
            log("loadUri 已发出，等 onPageStart…")
        }.onFailure {
            Log.e(TAG, "最小测试启动失败", it)
            log("失败：启动会话抛异常：${it.javaClass.simpleName}: ${it.message}")
            verdictText.text = "结论：失败——会话启动抛异常"
            verdictText.setBackgroundColor(Color.parseColor("#7A1F1F"))
            verdictShown = true
            return
        }
        mainHandler.postDelayed(verdictRunnable, VERDICT_DELAY_MS)
    }

    private fun showVerdict() {
        if (verdictShown) return
        verdictShown = true
        mainHandler.removeCallbacks(verdictRunnable)
        if (pageStarted) {
            verdictText.text = "结论：通过——默认设置下 about:blank 能加载，内核本身正常，问题在主进程的配置（config/DoH/ECH）或单例 runtime"
            verdictText.setBackgroundColor(Color.parseColor("#1F5C2E"))
            log("结论：通过。默认设置下 about:blank 能加载，内核本身正常。")
        } else {
            verdictText.text = "结论：失败——默认设置下 about:blank 也无法加载，问题在更底层（打包 / native 库 / 运行环境），与 config 无关"
            verdictText.setBackgroundColor(Color.parseColor("#7A1F1F"))
            log("结论：失败。默认设置下 about:blank 也无法加载，与 config 无关。")
        }
        Log.i(TAG, verdictText.text.toString())
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        logText.append(line + "\n")
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    override fun onDestroy() {
        mainHandler.removeCallbacks(verdictRunnable)
        geckoSession?.let { session ->
            runCatching { session.close() }
        }
        geckoSession = null
        super.onDestroy()
    }
}
