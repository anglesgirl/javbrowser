package com.example.javbrowser.gecko

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * 通用桥扩展（assets/gecko-bridge/）的 Kotlin 侧宿主。
 *
 * 只做三件事（不碰业务）：
 * ① 装扩展（幂等，照抄 JavhdGeckoExtension.ensureInstalled 的写法）；
 * ② 拿住 background 的 connectNative 连上来的 Port——native → background
 *    的唯一通道（Port.postMessage(JSONObject)，见 Mozilla 官方文档的
 *    connectNative 例子）；
 * ③ 按消息 type 分发：evalResult → GeckoEval，cookieResult /
 *    cookieSetDone → GeckoCookies，bridgeCall / bridgeLog → GeckoBridge。
 *
 * 关键点（已核对 Mozilla 文档，待真机验）：
 * - background 发来的消息有两条路上报：PortDelegate.onPortMessage
 *   （connectNative 的 port 路径，官方例子用的就是它）和
 *   MessageDelegate.onMessage（sendNativeMessage 路径）。两条都接，
 *   各消费方自己去重：eval/cookie 靠待办表查到即删（第二次查不到
 *   直接丢），bridge 调用靠 callId 去重（见 GeckoBridge）。
 * - 只挂 runtime 级 delegate，不挂 session 级 delegate：background
 *   经唯一 native port 中继全部会话的消息，挂两份会收到双份。
 *   若真机上消息收不到，再考虑补 session 级 delegate（见 NOTES）。
 * - token：每会话一个 UUID，存在 WeakHashMap 里（session 关掉后
 *   自动丢），经 javascript: URL 写进页面世界 window.__jbToken。
 *   javascript: URL 能执行 JS 是 mozilla/geckoview#214 确认过的
 *   做法；表达式包一层 void(...)，避免求值结果串替换页面内容。
 *
 * Context 说明：GeckoSession 不带 Context，扩展安装必须有个 Context。
 * 首次使用前，调用方必须先走一次带 Context 的入口——
 * GeckoHeadless.loadSession(context, ...) 或
 * GeckoBridgeExtension.ensureInstalled(context)（幂等）。之后
 * GeckoEval / GeckoCookies 会用存下来的 applicationContext。
 * 若从未传过 Context，eval 直接回调 null、getCookie 直接回调 ""，
 * 并记一条可行动的错误日志。
 */
internal object GeckoBridgeExtension {
    private const val TAG = "GeckoBridgeExt"

    const val EXTENSION_LOCATION = "resource://android/assets/gecko-bridge/"
    const val EXTENSION_ID = "gecko-bridge@javbrowser"

    /**
     * nativeApp 名：必须与 background.js 里 connectNative 的参数一致，
     * 且与 "javhdPlayer" 不同。只能含 [A-Za-z0-9_]+（可点分）。
     */
    const val NATIVE_APP = "geckoBridge"

    @Volatile
    private var extension: WebExtension? = null
    private var installing = false
    private val pendingReady = mutableListOf<(WebExtension) -> Unit>()
    private val pendingError = mutableListOf<() -> Unit>()

    @Volatile
    private var appContext: Context? = null

    /** background 的 connectNative 连上来的 port（native → background）。 */
    @Volatile
    private var nativePort: WebExtension.Port? = null

    /** port 还没连上时暂存的外发消息，onConnect 后 flush。 */
    private val outboundQueue = ArrayDeque<JSONObject>()
    private const val OUTBOUND_QUEUE_MAX = 32

    /** 每会话 token（页面世界里的 window.__jbToken）。 */
    private val sessionTokens: MutableMap<GeckoSession, String> =
        Collections.synchronizedMap(WeakHashMap())

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 线程工具：GeckoSession / Port 的方法都要求主线程调。 */
    internal fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    /** 存 applicationContext，供没有 Context 参数的调用（evaluate / cookies）用。 */
    internal fun rememberContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any> {
            dispatch(message)
            return GeckoResult.fromValue(null)
        }

        override fun onConnect(port: WebExtension.Port) {
            Log.i(TAG, "native port connected: ${port.name}")
            nativePort = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, p: WebExtension.Port) {
                    dispatch(message)
                }

                override fun onDisconnect(p: WebExtension.Port) {
                    if (nativePort === p) nativePort = null
                    // background.js 侧 1 秒后重连；外发消息先排队等 flush。
                    Log.w(TAG, "native port disconnected（background 会重连）")
                }
            })
            flushOutbound()
        }
    }

    /** 装扩展（幂等）。装好后回调 onReady；装失败回调 onError。 */
    fun ensureInstalled(
        context: Context,
        onReady: (WebExtension) -> Unit = {},
        onError: () -> Unit = {}
    ) {
        rememberContext(context)
        ensureInstalled(onReady, onError)
    }

    /** 用已存的 applicationContext 装扩展；从未存过则直接走 onError。 */
    internal fun ensureInstalled(
        onReady: (WebExtension) -> Unit = {},
        onError: () -> Unit = {}
    ) {
        val context = appContext
        if (context == null) {
            Log.e(
                TAG,
                "gecko-bridge 未安装且无 Context：调用方须先调一次 " +
                    "GeckoBridgeExtension.ensureInstalled(context) 或 " +
                    "GeckoHeadless.loadSession(context, ...)"
            )
            runOnMain(onError)
            return
        }
        extension?.let {
            runOnMain { onReady(it) }
            return
        }
        synchronized(this) {
            extension?.let {
                runOnMain { onReady(it) }
                return
            }
            pendingReady += onReady
            pendingError += onError
            if (installing) return
            installing = true
        }

        GeckoRuntimeProvider.get(context).webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept({ installed ->
                if (installed == null) {
                    Log.e(TAG, "gecko-bridge 扩展安装返回 null")
                    failInstall()
                    return@accept
                }
                runOnMain {
                    installed.setMessageDelegate(messageDelegate, NATIVE_APP)
                    val callbacks = synchronized(this) {
                        extension = installed
                        installing = false
                        pendingReady.toList().also { pendingReady.clear() }
                        pendingError.clear()
                    }
                    callbacks.forEach { it(installed) }
                }
            }, { throwable ->
                Log.e(TAG, "gecko-bridge 扩展安装失败", throwable)
                failInstall()
            })
    }

    private fun failInstall() {
        val errors = synchronized(this) {
            installing = false
            pendingReady.clear()
            pendingError.toList().also { pendingError.clear() }
        }
        runOnMain { errors.forEach { it() } }
    }

    /**
     * native → background 发消息。port 未连上时先排队（onConnect 后
     * flush），调用方靠自己的超时兜底（如 eval 的 timeoutMs）。
     * 返回 true 仅表示“已接单”，不表示对方已收到。
     */
    fun sendToBackground(message: JSONObject): Boolean {
        runOnMain {
            val port = nativePort
            if (port != null) {
                runCatching { port.postMessage(message) }
                    .onFailure { Log.w(TAG, "port postMessage 失败：$message", it) }
            } else {
                synchronized(outboundQueue) {
                    if (outboundQueue.size >= OUTBOUND_QUEUE_MAX) outboundQueue.removeFirst()
                    outboundQueue.addLast(message)
                }
                Log.d(TAG, "port 未就绪，外发消息已排队：type=${message.optString("type")}")
            }
        }
        return true
    }

    private fun flushOutbound() {
        runOnMain {
            val port = nativePort ?: return@runOnMain
            val queued = synchronized(outboundQueue) {
                outboundQueue.toList().also { outboundQueue.clear() }
            }
            queued.forEach { message ->
                runCatching { port.postMessage(message) }
                    .onFailure { Log.w(TAG, "flush 外发消息失败：$message", it) }
            }
            if (queued.isNotEmpty()) Log.d(TAG, "flush 外发消息 ${queued.size} 条")
        }
    }

    /** 该会话的 token，没有就生成一个。 */
    fun tokenFor(session: GeckoSession): String =
        sessionTokens[session]
            ?: UUID.randomUUID().toString().also { sessionTokens[session] = it }

    /** 该会话已存的 token（没有返回 null，不生成）。 */
    fun tokenOf(session: GeckoSession): String? = sessionTokens[session]

    /**
     * 用 javascript: URL 把 token 写进页面世界（window.__jbToken）。
     * 每次 evaluate 前都会重新注入一次，导航后 token 丢了也能自愈。
     * 注意：host Activity 若在 NavigationDelegate.onLoadRequest 里拦了
     * 非 http(s) 加载，注入会被拦下，eval 会退化（见 NOTES）。
     */
    fun injectToken(session: GeckoSession) {
        val token = tokenFor(session)
        // 无空格、无分号：javascript:void(window.__jbToken="...")。
        val js = "javascript:void(window.__jbToken=" + JSONObject.quote(token) + ")"
        runOnMain {
            runCatching { session.loadUri(js) }
                .onFailure { Log.w(TAG, "token 注入失败", it) }
        }
    }

    /** 会话不用了就忘掉它的 token（detachBridge 时调）。 */
    fun forgetSession(session: GeckoSession) {
        sessionTokens.remove(session)
    }

    /** background / content script 发来的消息，按 type 分发。 */
    private fun dispatch(message: Any) {
        val json = when (message) {
            is String -> runCatching { JSONObject(message) }.getOrNull()
            is JSONObject -> message
            else -> runCatching { JSONObject(message.toString()) }.getOrNull()
        } ?: run {
            Log.w(TAG, "收到无法解析的扩展消息：$message")
            return
        }
        when (json.optString("type")) {
            "evalResult" -> GeckoEval.handleEvalResult(json)
            "cookieResult", "cookieSetDone" -> GeckoCookies.handleCookieMessage(json)
            "bridgeCall" -> GeckoBridge.handleBridgeMessage(json)
            "bridgeLog" -> Log.d(TAG, "bridge: ${json.optString("message")}")
            else -> Log.d(TAG, "忽略未知类型扩展消息：${json.optString("type")}")
        }
    }
}
