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
 * 扩展做四件事（见 background.js / content.js）：
 * ① webRequest 拦 11 个广告域名，tsyndicate.com（VAST）永远不拦；
 * ② content script 轮询点跳过（DOM 点击，内容脚本访问不到页面
 *    的 jwplayer() 对象，故只用 DOM 方式）；
 * ③ content script 读 <video> 状态，经 background → native 消息
 *    回传给 Activity，记播放进度；
 * ④ background 实时嗅探播放器实际请求的 .m3u8/.mp4（非阻塞观察，
 *    与 VideoPageSniffer 同款广告过滤），经 native 消息回传给嗅探
 *    会话（GeckoSniffActivity）。静态 HTML 只得广告的站（如 7mmtv）
 *    靠它拿正片。
 *
 * 消息流：content script / background --runtime.sendMessage /
 * connectNative("javhdPlayer")--> Kotlin MessageDelegate.onMessage。
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

    /**
     * 该 session 的实时嗅探回调（GeckoSniffActivity）。background 的
     * webRequest 观察者不知道请求属于哪个 session，故 mediaCandidate
     * 会广播给全部已挂回调——同一时间只有一个嗅探会话在跑，不会串。
     */
    private val mediaCandidateHandlers = mutableMapOf<GeckoSession, (String) -> Unit>()

    /** 该 session 的“正片已开始播放”回调（GeckoSniffActivity）。 */
    private val videoPlayingHandlers = mutableMapOf<GeckoSession, (JSONObject) -> Unit>()

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
                } else if (type == "mediaCandidate") {
                    val url = json.optString("url")
                    if (url.isNotBlank()) {
                        val handlers = synchronized(mediaCandidateHandlers) {
                            mediaCandidateHandlers.values.toList()
                        }
                        Handler(Looper.getMainLooper()).post {
                            handlers.forEach { it(url) }
                        }
                    }
                } else if (type == "videoPlaying") {
                    // 内容脚本报“正片已开始播放”（playing 事件）。同
                    // mediaCandidate 一样广播给已挂回调——同一时间只
                    // 有一个嗅探会话在跑，不会串。
                    val handlers = synchronized(videoPlayingHandlers) {
                        videoPlayingHandlers.values.toList()
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
                    mediaCandidateHandlers.keys.forEach { attachSessionDelegate(it, installed) }
                    videoPlayingHandlers.keys.forEach { attachSessionDelegate(it, installed) }
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

    /** 把实时嗅探挂到一个 session 上（GeckoSniffActivity）。 */
    fun attachMediaSniffer(session: GeckoSession, onCandidate: (String) -> Unit) {
        mediaCandidateHandlers[session] = onCandidate
        extension?.let { attachSessionDelegate(session, it) }
    }

    fun detachMediaSniffer(session: GeckoSession) {
        mediaCandidateHandlers.remove(session)
    }

    /** 把“正片已开始播放”信号挂到一个 session 上（GeckoSniffActivity）。 */
    fun attachVideoPlayingListener(session: GeckoSession, onVideoPlaying: (JSONObject) -> Unit) {
        videoPlayingHandlers[session] = onVideoPlaying
        extension?.let { attachSessionDelegate(session, it) }
    }

    fun detachVideoPlayingListener(session: GeckoSession) {
        videoPlayingHandlers.remove(session)
    }

    private fun attachSessionDelegate(session: GeckoSession, installed: WebExtension) {
        try {
            session.webExtensionController.setMessageDelegate(installed, messageDelegate, NATIVE_APP)
        } catch (e: Exception) {
            Log.w(TAG, "attach session delegate 失败", e)
        }
    }
}
