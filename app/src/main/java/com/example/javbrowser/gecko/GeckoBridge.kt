package com.example.javbrowser.gecko

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.util.Collections
import java.util.WeakHashMap

/**
 * GeckoView 版 addJavascriptInterface。
 *
 * GeckoView 151 没有 addJavascriptInterface：页面 JS 调
 * window.Android.<method>(...args)，经通用桥扩展回传：
 * 页面 hook（Proxy，任意方法名都接）→ content script →
 * background → native port → 这里按会话 token 分发到 handler。
 *
 * handler 收到 JSONArray 参数（页面侧已 JSON 序列化过，只传得过
 * JSON 安全的值：字符串/数字/布尔/null/数组/普通对象；函数、
 * undefined、循环引用会被页面侧转成字符串或丢弃，见 content.js）。
 * handler 在主线程调；handler 抛错会被 catch 记日志，不会崩。
 *
 * 注意：
 * - attachBridge 没有 Context 参数：扩展安装用的 Context 须由调用方
 *   在别处先给一次（GeckoHeadless.loadSession 或
 *   GeckoBridgeExtension.ensureInstalled(context)，幂等）。
 * - window.Android 是页面 hook 用 Proxy 现造的：页面若自己已定义
 *   window.Android（且不是我们的），hook 不覆盖，调用不会到达这里
 *   （记一条 bridgeLog）。页面脚本在 document_start 之前就调
 *   window.Android 的话会报 undefined——hook 在 document_start 注
 *   入，正常页面加载完再调没问题。
 * - 本对象不挂 session 级 MessageDelegate：消息全部经 background
 *   的唯一 native port 到 runtime 级 delegate，一处收，不双份。
 */
object GeckoBridge {
    private const val TAG = "GeckoBridge"

    /** 每会话的方法表。WeakHashMap：session 关掉后自动丢。 */
    private val handlersBySession: MutableMap<GeckoSession, Map<String, (JSONArray) -> Unit>> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * 最近处理过的 bridge call id（去重用）。
     * 背景：background 的消息有 port 与 onMessage 两条上行路径，
     * 同一调用可能到两次；callId 由页面 hook 生成，见 content.js。
     */
    private val seenCallIds = LinkedHashSet<String>()
    private const val SEEN_CALL_IDS_MAX = 200

    fun attachBridge(session: GeckoSession, handlers: Map<String, (JSONArray) -> Unit>) {
        handlersBySession[session] = handlers
        // token 立即注入一次（页面已加载好的情况）；后续每次
        // evaluate 也会重新注入，导航后自愈。
        GeckoBridgeExtension.injectToken(session)
        // 若之前已有人给过 Context，顺手把扩展装上；没给过也不报错，
        // evaluate/getCookie 时再按缺 Context 处理。
        GeckoBridgeExtension.ensureInstalled()
    }

    fun detachBridge(session: GeckoSession) {
        handlersBySession.remove(session)
        GeckoBridgeExtension.forgetSession(session)
    }

    /** 扩展侧回来的 bridge 调用（GeckoBridgeExtension.dispatch 调）。 */
    internal fun handleBridgeMessage(message: JSONObject) {
        GeckoBridgeExtension.runOnMain {
            val callId = message.optString("callId")
            if (callId.isNotEmpty()) {
                val duplicate = synchronized(seenCallIds) {
                    if (!seenCallIds.add(callId)) {
                        true
                    } else {
                        while (seenCallIds.size > SEEN_CALL_IDS_MAX) {
                            seenCallIds.remove(seenCallIds.first())
                        }
                        false
                    }
                }
                if (duplicate) {
                    Log.d(TAG, "bridge 调用重复，已去重：$callId")
                    return@runOnMain
                }
            }
            val method = message.optString("method")
            val token = message.optString("token").takeIf { it.isNotEmpty() }
            val args = runCatching { JSONArray(message.optString("argsJson")) }
                .getOrElse { JSONArray() }
            // 按 token 找会话；无 token 时退化为广播给全部已挂会话。
            val targets: List<Map<String, (JSONArray) -> Unit>> = if (token != null) {
                synchronized(handlersBySession) {
                    handlersBySession.entries
                        .filter { GeckoBridgeExtension.tokenOf(it.key) == token }
                        .map { it.value }
                }
            } else {
                Log.w(
                    TAG,
                    "bridge 调用无 token（退化模式）：广播给全部 " +
                        "${handlersBySession.size} 个会话 method=$method"
                )
                synchronized(handlersBySession) { handlersBySession.values.toList() }
            }
            if (targets.isEmpty()) {
                Log.w(TAG, "bridge 调用无会话认领：method=$method token=${token ?: "null"}")
                return@runOnMain
            }
            targets.forEach { handlers ->
                val handler = handlers[method]
                if (handler == null) {
                    Log.w(TAG, "bridge 方法未注册：$method（已注册：${handlers.keys}）")
                    return@forEach
                }
                runCatching { handler(args) }
                    .onFailure { Log.w(TAG, "bridge handler 抛错：$method", it) }
            }
        }
    }
}
