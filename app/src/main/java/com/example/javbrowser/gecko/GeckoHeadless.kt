package com.example.javbrowser.gecko

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError

/**
 * 无界面 GeckoSession：加载一个 URL，等页面停稳后把 session 交出来。
 *
 * 用途：搜索抓取、后台嗅探这类不需要把页面画出来的活。
 * 复用 GeckoRuntimeProvider 单例（TRR_ONLY + 自家 Gateway DoH，
 * 不重写 DoH/主机名），与 GeckoBrowserActivity 同款配置
 * （allowJavascript = true，useTrackingProtection = false）。
 *
 * 关键点（见 GeckoBrowserActivity 的教训）：无界面也要
 * open + setActive(true) + setFocused(true) 才能加载；只 open
 * 不激活，loadUri 会被静默丢弃。onReady 之前已把 token 注入、
 * 把桥扩展预装好，调用方拿到 session 就能直接调
 * GeckoEval.evaluate / GeckoBridge.attachBridge。
 *
 * 约定：
 * - onReady 主线程回调，且只调一次；
 * - 成功时回调 session，调用方负责 session.close()；
 * - 超时 / onPageStop(success=false) / onLoadError / 内容进程
 *   崩溃或被杀时回调 null（session 已由这里关掉，调用方不用关）；
 * - onReady 的“成功”指首个文档 onPageStop(success=true)：页面若
 *   有重定向，onReady 可能在最终文档之前触发，调用方如需等最终
 *   地址，可在 evaluate 里自查 location.href / document.readyState。
 *
 * 已知风险（待真机验，见 NOTES）：Mozilla 架构文档说“只有挂到
 * GeckoView 上的 session 才是活的（能收事件、跑计时器）”。这里
 * 按任务要求不挂 GeckoView；若真机上 onPageStop 迟迟不来导致
 * 总是超时，退路是给 session 配一个 1px 的离屏 GeckoView。
 */
object GeckoHeadless {
    private const val TAG = "GeckoHeadless"

    fun loadSession(
        context: Context,
        url: String,
        timeoutMs: Long = 30_000L,
        onReady: (GeckoSession?) -> Unit
    ) {
        val appContext = context.applicationContext
        GeckoBridgeExtension.rememberContext(appContext)
        GeckoBridgeExtension.runOnMain {
            val mainHandler = Handler(Looper.getMainLooper())
            val runtime = GeckoRuntimeProvider.get(appContext)
            val session = GeckoSession()
            session.settings.apply {
                allowJavascript = true
                // 跟 GeckoBrowserActivity / 嵌頁播放器统一：关 Gecko 内建
                // 追踪防护，页面能打开优先于挡广告。
                useTrackingProtection = false
            }
            var done = false
            val timeoutRunnable = Runnable {
                if (!done) {
                    done = true
                    Log.w(TAG, "无界面加载超时（${timeoutMs}ms）：$url")
                    runCatching { session.close() }
                    onReady(null)
                }
            }
            fun finish(result: GeckoSession?) {
                if (done) return
                done = true
                mainHandler.removeCallbacks(timeoutRunnable)
                onReady(result)
            }
            session.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    Log.d(TAG, "无界面加载开始：$url")
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    if (success) {
                        // 页面停稳后注入 token、预装桥扩展，调用方拿到
                        // 就能直接 evaluate，不用再等安装。
                        GeckoBridgeExtension.injectToken(session)
                        GeckoBridgeExtension.ensureInstalled(appContext)
                        finish(session)
                    } else {
                        Log.w(TAG, "无界面加载 onPageStop(success=false)：$url")
                        runCatching { session.close() }
                        finish(null)
                    }
                }
            }
            session.navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLoadError(
                    session: GeckoSession,
                    uri: String?,
                    error: WebRequestError
                ): GeckoResult<String>? {
                    Log.w(TAG, "无界面加载失败：$uri code=${error.code}")
                    runCatching { session.close() }
                    finish(null)
                    return null
                }
            }
            session.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) {
                    Log.w(TAG, "无界面会话内容进程崩溃：$url")
                    runCatching { session.close() }
                    finish(null)
                }

                override fun onKill(session: GeckoSession) {
                    Log.w(TAG, "无界面会话内容进程被系统结束：$url")
                    runCatching { session.close() }
                    finish(null)
                }
            }
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)
            runCatching {
                session.open(runtime)
                // 无界面也要显式激活，否则加载不开始。
                session.setActive(true)
                session.setFocused(true)
                session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
                session.loadUri(url)
            }.onFailure {
                Log.e(TAG, "无界面会话启动失败：$url", it)
                mainHandler.removeCallbacks(timeoutRunnable)
                runCatching { session.close() }
                finish(null)
            }
        }
    }
}
