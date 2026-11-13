package com.example.javbrowser.gecko

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * 嵌頁播放器专用的内置 WebExtension（assets/javhd-gecko/）。
 *
 * 扩展做三件事（见 background.js / content.js）：
 * ① webRequest 拦 11 个广告域名，tsyndicate.com（VAST）永远不拦；
 * ② content script 轮询点跳过（DOM 点击，内容脚本访问不到页面
 *    的 jwplayer() 对象，故只用 DOM 方式）；
 * ③ content script 读 <video> 状态，经 background → native 消息
 *    回传给 Activity，记播放进度。
 *
 * 消息流：content script --runtime.sendMessage--> background
 * --connectNative("javhdPlayer")--> Kotlin MessageDelegate.onMessage。
 * 扩展只装一次（全局单例），每个 GeckoSession 另挂一个
 * session 级 MessageDelegate 才能收到该 session 里内容脚本的消息。
 */
object JavhdGeckoExtension {
    private const val TAG = "JavhdGeckoExt"
    const val EXTENSION_LOCATION = "resource://android/assets/javhd-gecko/"
    const val EXTENSION_ID = "javhd-player@javbrowser"
    const val NATIVE_APP = "javhdPlayer"

    @Volatile
    private var extension: WebExtension? = null
    private var installing = false
    private val pendingReady = mutableListOf<(WebExtension) -> Unit>()

    /** 该 session 的 videoState 回调（Activity 在 onPause 时用最新状态记进度）。 */
    private val videoStateHandlers = mutableMapOf<GeckoSession, (JSONObject) -> Unit>()

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any> {
            return try {
                val json = when (message) {
                    is String -> JSONObject(message)
                    is JSONObject -> message
                    else -> JSONObject(message.toString())
                }
                val type = json.optString("type")
                if (type == "videoState") {
                    // 找到属于该 sender session 的回调；sender 没有直接给
                    // session，退而把状态发给全部已挂回调（嵌頁播放器同一
                    // 时间只有一个 session 在跑，不会串）。
                    val handlers = synchronized(videoStateHandlers) {
                        videoStateHandlers.values.toList()
                    }
                    Handler(Looper.getMainLooper()).post {
                        handlers.forEach { it(json) }
                    }
                }
                GeckoResult.fromValue(null)
            } catch (e: Exception) {
                Log.w(TAG, "onMessage 解析失败: $message", e)
                GeckoResult.fromValue(null)
            }
        }

        override fun onConnect(port: WebExtension.Port) {
            // background 的 connectNative 會走到這裡；消息經 onMessage 收，
            // 這裡只記一筆，不主動斷開（sender.url 在 native port 上
            // 不保證有值，依它斷開會誤殺 background 連線）。
            Log.d(TAG, "native port connected: ${port.name}")
        }
    }

    /** 装扩展（幂等）。装好后回调 onReady。 */
    fun ensureInstalled(context: Context, onReady: (WebExtension) -> Unit = {}) {
        extension?.let {
            onReady(it)
            return
        }
        synchronized(this) {
            extension?.let {
                onReady(it)
                return
            }
            pendingReady += onReady
            if (installing) return
            installing = true
        }

        GeckoRuntimeProvider.get(context).webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept({ installed ->
                if (installed == null) {
                    synchronized(this) {
                        installing = false
                        pendingReady.clear()
                    }
                    Log.e(TAG, "javhd 扩展安装返回 null")
                    return@accept
                }
                Handler(Looper.getMainLooper()).post {
                    installed.setMessageDelegate(messageDelegate, NATIVE_APP)
                    val callbacks = synchronized(this) {
                        extension = installed
                        installing = false
                        pendingReady.toList().also { pendingReady.clear() }
                    }
                    videoStateHandlers.keys.forEach { attachSessionDelegate(it, installed) }
                    callbacks.forEach { it(installed) }
                }
            }, { throwable ->
                synchronized(this) {
                    installing = false
                    pendingReady.clear()
                }
                Log.e(TAG, "javhd 扩展安装失败", throwable)
            })
    }

    /** 把扩展挂到一个 session 上，并注册该 session 的 videoState 回调。 */
    fun attachToSession(session: GeckoSession, onVideoState: (JSONObject) -> Unit) {
        videoStateHandlers[session] = onVideoState
        extension?.let { attachSessionDelegate(session, it) }
    }

    fun detachFromSession(session: GeckoSession) {
        videoStateHandlers.remove(session)
        extension?.let {
            try {
                session.webExtensionController.setMessageDelegate(it, null, NATIVE_APP)
            } catch (e: Exception) {
                Log.w(TAG, "detach session delegate 失败", e)
            }
        }
    }

    private fun attachSessionDelegate(session: GeckoSession, installed: WebExtension) {
        try {
            session.webExtensionController.setMessageDelegate(installed, messageDelegate, NATIVE_APP)
        } catch (e: Exception) {
            Log.w(TAG, "attach session delegate 失败", e)
        }
    }
}
