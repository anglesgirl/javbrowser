package com.example.javbrowser.gecko

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.UUID

/**
 * GeckoView 版 CookieManager（读/写 Gecko 自己的 cookie 仓）。
 *
 * GeckoView 151 没有直接读 Cookie 的 API：读/写走通用桥扩展的
 * background.js，用 browser.cookies API（manifest 里已加
 * "cookies" 权限与 "<all_urls>" host 权限；cookies 在 GeckoView
 * 扩展 API 兼容清单里是 ✔）。
 *
 * 语义对齐 android.webkit.CookieManager：
 * - getCookie 返回 "name=value; name2=value2"；没有返回 ""；
 * - 失败/超时也返回 ""（不抛错、不挂起）；
 * - setCookie 的 onDone 在写完或超时后都会调一次（不挂起调用方）。
 * 回调一律主线程。
 *
 * 注意（与 WebView CookieManager 的差别）：
 * - 能读到 HttpOnly cookie（扩展 cookies API 不受 document.cookie
 *   的限制）；写时 HttpOnly 属性会被忽略（扩展 API 设不了）。
 * - 只支持 http(s) URL，其他 scheme 直接返回 "" / 记日志走人。
 * - 读/写走默认 cookie 仓（未指定 storeId）；Total Cookie Protection
 *   下第三方上下文分区的 cookie 可能读不到（见 NOTES）。
 * - 与 OkHttp 侧的 android.webkit.CookieManager 仍不互通：Gecko 仓
 *   与 WebView/OkHttp 仓是两套，登录态同步仍需调用方自己搬运
 *   （getCookie 读出 → OkHttp CookieManager 写进去，或反向）。
 */
object GeckoCookies {
    private const val TAG = "GeckoCookies"

    /** 单次 cookie 读/写的最长等待。 */
    private const val COOKIE_OP_TIMEOUT_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private data class PendingGet(
        val id: String,
        val onResult: (String) -> Unit,
        var timeoutRunnable: Runnable? = null
    )

    private data class PendingSet(
        val id: String,
        val onDone: (() -> Unit)?,
        var timeoutRunnable: Runnable? = null
    )

    private val pendingGets = mutableMapOf<String, PendingGet>()
    private val pendingSets = mutableMapOf<String, PendingSet>()

    fun getCookie(url: String, onResult: (String) -> Unit) {
        GeckoBridgeExtension.runOnMain {
            if (!isHttpUrl(url)) {
                Log.w(TAG, "getCookie 只支持 http(s) URL：$url")
                onResult("")
                return@runOnMain
            }
            val id = UUID.randomUUID().toString()
            val pending = PendingGet(id, onResult)
            pendingGets[id] = pending
            val timeoutRunnable = Runnable {
                pendingGets.remove(id)?.let {
                    Log.w(TAG, "getCookie 超时：$url")
                    it.onResult("")
                }
            }
            pending.timeoutRunnable = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, COOKIE_OP_TIMEOUT_MS)
            GeckoBridgeExtension.ensureInstalled(
                onReady = {
                    GeckoBridgeExtension.sendToBackground(
                        JSONObject().apply {
                            put("type", "getCookies")
                            put("id", id)
                            put("url", url)
                        }
                    )
                },
                onError = {
                    pendingGets.remove(id)?.let {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        Log.e(TAG, "getCookie 失败：桥扩展未安装（先调 ensureInstalled(context)）")
                        it.onResult("")
                    }
                }
            )
        }
    }

    /**
     * cookie 格式与 CookieManager.setCookie 一致：
     * "name=value; Path=/; Domain=.example.com; Expires=...; Max-Age=3600; Secure; SameSite=Lax"。
     * name=value 之外的属性由 background.js 解析后传给 browser.cookies.set；
     * HttpOnly 会被忽略（扩展 API 设不了），其他不认得的属性也忽略。
     * onDone 在写完、写失败、超时后都会调一次（可为 null）。
     */
    fun setCookie(url: String, cookie: String, onDone: (() -> Unit)? = null) {
        GeckoBridgeExtension.runOnMain {
            if (!isHttpUrl(url) || cookie.substringBefore(";").trim().indexOf("=") <= 0) {
                Log.w(TAG, "setCookie 参数无效：url=$url cookie=$cookie")
                onDone?.invoke()
                return@runOnMain
            }
            val id = UUID.randomUUID().toString()
            val pending = PendingSet(id, onDone)
            pendingSets[id] = pending
            val timeoutRunnable = Runnable {
                pendingSets.remove(id)?.let {
                    Log.w(TAG, "setCookie 超时：$url")
                    it.onDone?.invoke()
                }
            }
            pending.timeoutRunnable = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, COOKIE_OP_TIMEOUT_MS)
            GeckoBridgeExtension.ensureInstalled(
                onReady = {
                    GeckoBridgeExtension.sendToBackground(
                        JSONObject().apply {
                            put("type", "setCookie")
                            put("id", id)
                            put("url", url)
                            put("cookie", cookie)
                        }
                    )
                },
                onError = {
                    pendingSets.remove(id)?.let {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        Log.e(TAG, "setCookie 失败：桥扩展未安装（先调 ensureInstalled(context)）")
                        it.onDone?.invoke()
                    }
                }
            )
        }
    }

    /** 扩展侧回来的 cookie 消息（GeckoBridgeExtension.dispatch 调）。 */
    internal fun handleCookieMessage(message: JSONObject) {
        GeckoBridgeExtension.runOnMain {
            when (message.optString("type")) {
                "cookieResult" -> {
                    val id = message.optString("id")
                    pendingGets.remove(id)?.let { pending ->
                        pending.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                        pending.onResult(message.optString("value"))
                    } ?: Log.d(TAG, "cookie 结果无对应等待项，已忽略：id=$id")
                }
                "cookieSetDone" -> {
                    val id = message.optString("id")
                    pendingSets.remove(id)?.let { pending ->
                        pending.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                        if (!message.optBoolean("ok", false)) {
                            Log.w(TAG, "setCookie 扩展侧返回失败：id=$id")
                        }
                        pending.onDone?.invoke()
                    } ?: Log.d(TAG, "cookieSetDone 无对应等待项，已忽略：id=$id")
                }
            }
        }
    }

    private fun isHttpUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}
