package com.example.javbrowser

import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 表单 POST 的 JS 桥（1.2.29，移植 Han1meViewer 的 EchWebBridge，
 * 由只劫持 /login 改成通用表单）。
 *
 * 为什么必须这么做：Android 的 `shouldInterceptRequest` **拿不到 POST
 * body**，所以 ECH host 上的表单 POST 只能由原生侧代发；而只有原生
 * 侧代发才能走 ECH 引擎。
 *
 * 流程：
 * 1. 注入的 JS 劫持 urlencoded 表单 submit，先调 shouldIntercept
 *    问原生：action 的 host 是否走 ECH；不是就放行给 WebView 原生提交。
 * 2. 是 ECH host 才 preventDefault，序列化表单后调 postForm(action, body)。
 * 3. 原生在后台线程经 ECH 发送 POST（不跟随重定向），Set-Cookie 写回
 *    CookieManager（真实域名），再让 WebView loadUrl 跳转目标
 *    （Location；无 Location 则重载 action URL，后续 GET 走 ECH 拦截器）。
 *
 * 只处理 application/x-www-form-urlencoded 表单；multipart/文件上传
 * 一律放行原生提交。body 内容绝不记日志（可能含密码）。
 */
class EchWebBridge(private val webView: WebView) {

    private val tag = "EchWebBridge"

    /**
     * JS 注入的表单劫持脚本（幂等：window.__echBridgeHijack）。
     * 同步问原生 shouldIntercept，ECH host 才拦截。
     */
    fun injectHijackScript(): String = """
        (function(){
          if (window.__echBridgeHijack) return;
          window.__echBridgeHijack = 1;
          document.addEventListener('submit', function(e){
            try {
              var f = e.target;
              if (!f || !f.getAttribute) return;
              var enc = (f.enctype || 'application/x-www-form-urlencoded');
              if (enc !== 'application/x-www-form-urlencoded') return;
              var action = f.action || '';
              if (!action) return;
              if (typeof EchBridge === 'undefined') return;
              if (!EchBridge.shouldIntercept(action)) return;
              e.preventDefault();
              e.stopPropagation();
              var parts = [];
              var els = f.elements || [];
              for (var i = 0; i < els.length; i++) {
                var el = els[i];
                if (!el || !el.name || el.disabled) continue;
                if ((el.type === 'checkbox' || el.type === 'radio') && !el.checked) continue;
                parts.push(encodeURIComponent(el.name) + '=' + encodeURIComponent(el.value === undefined || el.value === null ? '' : el.value));
              }
              EchBridge.postForm(action, parts.join('&'));
            } catch (err) {}
          }, true);
        })();
    """.trimIndent()

    /** action 的 host 是否走 ECH（同步调，JS 提交事件里问；EchDoh 自带缓存）。 */
    @JavascriptInterface
    fun shouldIntercept(action: String): Boolean {
        val host = runCatching { java.net.URI(action).host }.getOrNull() ?: return false
        return EchWebViewHelper.routeIsEch(host)
    }

    /** ECH host 的表单 POST：原生代发。调用在 JS 桥后台线程，阻塞无妨。 */
    @JavascriptInterface
    fun postForm(action: String, body: String) {
        val host = runCatching { java.net.URI(action).host }.getOrNull() ?: "unknown"
        Log.d(tag, "postForm host=$host bodyLen=${body.length}")
        Thread {
            try {
                doPost(action, body)
            } catch (e: Exception) {
                Log.e(tag, "postForm failed for host=$host (${e.javaClass.simpleName})")
                // 失败：回到 action 页，让用户看到服务端/网络错误而不是卡住
                webView.post { runCatching { webView.loadUrl(action) } }
            }
        }.start()
    }

    private fun doPost(absoluteUrl: String, body: String) {
        val cm = CookieManager.getInstance()
        val cookie = runCatching { cm.getCookie(absoluteUrl) }.getOrNull().orEmpty()
        val origin = Regex("^(https?://[^/]+)").find(absoluteUrl)?.groupValues?.get(1).orEmpty()

        val builder = Request.Builder().url(absoluteUrl)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", SearchHttpFetcher.USER_AGENT)
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
        if (origin.isNotEmpty()) {
            builder.header("Origin", origin)
            builder.header("Referer", absoluteUrl)
        }
        if (cookie.isNotEmpty()) builder.header("Cookie", cookie)

        val reqBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        val request = builder.post(reqBody).build()

        val useEch = EchWebViewHelper.routeIsEch(
            runCatching { java.net.URI(absoluteUrl).host }.getOrNull().orEmpty(),
        )
        val client = if (useEch) {
            EchWebViewHelper.echClientOrNull() ?: EchWebViewHelper.directClient
        } else {
            EchWebViewHelper.directClient
        }

        var code = -1
        var location: String? = null
        client.newCall(request).execute().use { resp ->
            code = resp.code
            Log.d(tag, "postForm <- $code host=${runCatching { java.net.URI(absoluteUrl).host }.getOrNull()}")
            resp.headers("Set-Cookie").forEach { raw ->
                var fixed = raw
                fixed = fixed.replace(Regex("(?i);\\s*Domain=[^;]+"), "")
                fixed = fixed.replace(Regex("(?i);\\s*Secure(?=;|$)"), "")
                fixed = fixed.replace(Regex("(?i);\\s*SameSite=[^;]+"), "; SameSite=Lax")
                runCatching { cm.setCookie(absoluteUrl, fixed) }
            }
            runCatching { cm.flush() }
            location = resp.header("Location")?.trim()?.ifEmpty { null }
        }

        webView.post {
            try {
                val target = when {
                    location != null && location!!.startsWith("http") -> location!!
                    location != null && origin.isNotEmpty() -> origin + location!!
                    else -> absoluteUrl
                }
                // 跳转目标由 WebView 重新加载，后续 GET 走 ECH 拦截器，CookieManager 会话生效
                webView.loadUrl(target)
            } catch (e: Exception) {
                Log.e(tag, "postForm loadUrl failed (${e.javaClass.simpleName})")
            }
        }
    }
}
