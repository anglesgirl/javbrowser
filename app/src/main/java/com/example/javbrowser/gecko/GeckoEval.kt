package com.example.javbrowser.gecko

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

/**
 * GeckoView 版 evaluateJavascript。
 *
 * GeckoView 151 没有 evaluateJavascript：JS 经通用桥扩展
 * （assets/gecko-bridge/）走 native → background → content script →
 * 页面世界执行，再原路返回结果。页面世界执行用注入的 hook 脚本
 * （content script 是隔离世界，直接 eval 拿不到页面变量）。
 *
 * 语义对齐 WebView.evaluateJavascript：
 * - 字符串结果包 JSON 引号，如 document.title → "\"Example Domain\""；
 * - null / undefined 返回 "null"；
 * - JS 抛错、超时返回 null；
 * - 回调一律主线程。
 *
 * 并发限制（见 NOTES）：eval 全局串行排队，一次只发一个在途请求。
 * 原因是 background 是向全部 tab 广播的，token 门控 + 串行才能保证
 * 结果不错会话。timeoutMs 从请求发出开始计，不含排队与扩展安装
 * 等待；排队过长时调用方会等到前面的请求先超时（各 10 秒默认）。
 * 不要用它做高频轮询（每秒一次的读取可以，间隔建议 ≥ 500ms）。
 *
 * 调用时机：应在页面 onPageStop 之后调。页面导航后 token 会丢，
 * evaluate 每次发之前都会重新注入 token 自愈。
 */
object GeckoEval {
    private const val TAG = "GeckoEval"

    /** token 注入（javascript: URL）后，等这么久再发 eval 请求。 */
    private const val TOKEN_SETTLE_MS = 150L

    private val mainHandler = Handler(Looper.getMainLooper())

    private data class PendingEval(
        val id: String,
        val session: GeckoSession,
        val token: String,
        val js: String,
        val timeoutMs: Long,
        val onResult: (String?) -> Unit,
        var timeoutRunnable: Runnable? = null
    )

    /** 全局串行队列：一次只允许一个在途 eval。 */
    private val queue = ArrayDeque<PendingEval>()
    private var inFlight: PendingEval? = null

    fun evaluate(
        session: GeckoSession,
        js: String,
        timeoutMs: Long = 10_000L,
        onResult: (String?) -> Unit
    ) {
        GeckoBridgeExtension.runOnMain {
            queue.addLast(
                PendingEval(
                    id = UUID.randomUUID().toString(),
                    session = session,
                    token = GeckoBridgeExtension.tokenFor(session),
                    js = js,
                    timeoutMs = timeoutMs,
                    onResult = onResult
                )
            )
            pump()
        }
    }

    /** fire-and-forget：发 JS 不等结果（结果丢弃，仍走串行队列）。 */
    fun runJavascript(session: GeckoSession, js: String) {
        evaluate(session, js) { /* 结果丢弃 */ }
    }

    private fun pump() {
        if (inFlight != null) return
        val next = queue.removeFirstOrNull() ?: return
        inFlight = next
        GeckoBridgeExtension.ensureInstalled(
            onReady = { dispatchEval(next) },
            onError = {
                Log.e(TAG, "eval 失败：桥扩展未安装，id=${next.id}")
                complete(next, null)
            }
        )
    }

    private fun dispatchEval(pending: PendingEval) {
        // 先把 token 重新写进页面世界（导航后丢了也能自愈），
        // 等 javascript: URL 执行完再发请求，否则 content script
        // 那边 token 对不上会直接丢弃。
        GeckoBridgeExtension.injectToken(pending.session)
        mainHandler.postDelayed({
            if (inFlight !== pending) return@postDelayed // 已超时走人
            val message = JSONObject().apply {
                put("type", "eval")
                put("id", pending.id)
                put("token", pending.token)
                put("js", pending.js)
            }
            GeckoBridgeExtension.sendToBackground(message)
            armTimeout(pending)
        }, TOKEN_SETTLE_MS)
    }

    private fun armTimeout(pending: PendingEval) {
        val runnable = Runnable {
            if (inFlight === pending) {
                Log.w(
                    TAG,
                    "eval 超时（${pending.timeoutMs}ms）：id=${pending.id} " +
                        "js=${pending.js.take(120)}"
                )
                complete(pending, null)
            }
        }
        pending.timeoutRunnable = runnable
        mainHandler.postDelayed(runnable, pending.timeoutMs)
    }

    /** 完成一个 eval（成功或失败都走这里），再放下一个出队。 */
    private fun complete(pending: PendingEval, result: String?) {
        if (inFlight === pending) inFlight = null
        pending.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        runCatching { pending.onResult(result) }
            .onFailure { Log.w(TAG, "eval 回调抛错", it) }
        pump()
    }

    /** 扩展侧回来的 eval 结果（GeckoBridgeExtension.dispatch 调）。 */
    internal fun handleEvalResult(message: JSONObject) {
        GeckoBridgeExtension.runOnMain {
            val id = message.optString("id")
            val pending = inFlight
            if (pending == null || pending.id != id) {
                // 迟到/重复的结果（前一个已超时完成）：忽略。
                Log.d(TAG, "eval 结果无对应等待项，已忽略：id=$id")
                return@runOnMain
            }
            val resultToken = message.optString("token").takeIf { it.isNotEmpty() }
            if (resultToken != null && resultToken != pending.token) {
                // 别的会话页面的结果（广播串话）：忽略，等正确结果或超时。
                Log.w(TAG, "eval 结果 token 不匹配，已忽略：id=$id")
                return@runOnMain
            }
            if (resultToken == null) {
                // token 未设时的退化模式：页面 hook 执行了但没带 token，
                // 认第一个回来的结果（见 NOTES 的退化说明）。
                Log.w(TAG, "eval 结果无 token（退化模式，认第一个结果）：id=$id")
            }
            if (!message.optBoolean("ok", false)) {
                Log.w(
                    TAG,
                    "eval 执行失败：${message.optString("error")} " +
                        "js=${pending.js.take(120)}"
                )
                complete(pending, null)
                return@runOnMain
            }
            // result 缺失时按 "null" 计；JS 字符串结果本身带 JSON 引号。
            val result = message.optString("result").takeIf { it.isNotEmpty() } ?: "null"
            complete(pending, result)
        }
    }
}
