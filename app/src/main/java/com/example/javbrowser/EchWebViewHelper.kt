package com.example.javbrowser

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * WebView ECH 拦截器（1.2.29，移植 Han1meViewer 的 HyWebViewHelper，
 * 按 javbrowser 的 EchH3Router/EchDoh 栈改）。
 *
 * 架构（co3/Han1meViewer 验证过）：
 * 1. WebView 直接加载真实 URL，不经过本地代理服务器。
 * 2. shouldInterceptRequest 拦截 GET，在进程内直接调 ECH 引擎
 *    （Conscrypt + OkHttp），返回 WebResourceResponse。
 * 3. POST 交给 JS 桥（EchWebBridge）：shouldInterceptRequest 拿不到
 *    POST body。
 * 4. Cookie 全部由 WebView 的 CookieManager 管理，域名是真实的，
 *    无需改写。
 *
 * 路由：EchDoh 有 ech 配置的 host 走 ECH 引擎；没有的（Cronet 路）
 * 返回 null 交回 WebView 原生栈（这些站没被 RST，明文能通）。
 * ECH host 握手失败时 fail-closed（返回 502 错误页），绝不静默
 * 降级明文 SNI。
 *
 * 注意：这里不调 EchH3Router.route()——它每次都会往 NetworkLogStore
 * 写一条 DoH 诊断行；WebView 每个子资源都调一次会把传输日志刷爆。
 * 直接调 EchDoh.resolve() 看 echConfigList，判定结果与 route() 一致，
 * 且 resolve 自带缓存。
 */
object EchWebViewHelper {

    private const val TAG = "EchWebView"

    /** ECH 首次请求必重试：DoH/ECH 配置首次未就绪，直接 fail-closed 会误杀。 */
    private const val RETRY_DELAY_MS = 700L
    private const val MAX_REDIRECTS = 5

    /** ECH 引擎客户端：不跟随重定向（手动处理），不管 Cookie（CookieManager 管）。 */
    private val echClient: OkHttpClient? by lazy {
        runCatching {
            EchH3Router.newOkHttpClient(
                connectSec = 15,
                readSec = 30,
                callSec = null,
                followRedirects = false,
                retryOnConnectionFailure = true,
            )
        }.onFailure { Log.e(TAG, "ECH client unavailable: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /** 普通 TLS 客户端：走 DoH 解析并保留证书校验，仅用于非 ECH host 的直连回退。 */
    val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(EchDns)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** EchH3Router.EchDns 的等价实现（那是 private）：DoH 解析优先，系统 DNS 只作兜底。 */
    private object EchDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val records = EchDoh.resolve(hostname)
            if (records.addresses.isNotEmpty()) return records.addresses
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /** 该 host 是否走 ECH（EchDoh 有 ech 配置）。无日志副作用，可每个子资源调。 */
    fun routeIsEch(host: String): Boolean {
        if (!ConscryptEch.ensureProvider()) return false
        return runCatching { EchDoh.resolve(host).echConfigList != null }.getOrDefault(false)
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull() ?: "unknown"

    private fun safeErrorSummary(error: Throwable?): String {
        val raw = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.takeIf { message -> message.isNotBlank() } }
            .firstOrNull() ?: error?.javaClass?.simpleName ?: "unknown"
        return raw
            .replace(Regex("""https?://[^\s"'<>]+"""), "<URL redacted>")
            .replace(Regex("""[?&][^\s"'<>]+"""), "?<query redacted>")
            .take(180)
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun errorResponse(error: Throwable?): WebResourceResponse {
        val details = safeErrorSummary(error)
        val page = "<!DOCTYPE html><html><body><h3>连接失败</h3>" +
            "<p>该主机要求 ECH；ECH 请求失败（$details）。请稍后重试。</p></body></html>"
        return WebResourceResponse(
            "text/html", "utf-8", 502, "Bad Gateway",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(page.toByteArray()),
        )
    }

    /**
     * shouldInterceptRequest 唯一入口。
     * 只拦截 GET（POST 返回 null 交给 JS 桥）；只处理 HTTPS；ECH host
     * 走进程内 ECH 引擎，失败 fail-closed；非 ECH host 返回 null 交回
     * WebView 原生栈。
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        val host = request.url.host ?: return null
        val method = request.method ?: "GET"

        // POST 交给 JS 桥：shouldInterceptRequest 拿不到 POST body
        if (method == "POST") return null
        // 只处理 HTTPS（HTTP 无需 ECH，也不走 DoH，直接放行）
        if (!url.startsWith("https://", ignoreCase = true)) return null

        // 非 GET 方法：ECH host 拒绝明文（fail-closed），其余放行给 WebView
        if (method != "GET") {
            if (routeIsEch(host)) {
                Log.w(TAG, "non-GET $method blocked from direct TLS for ECH host=$host")
                return errorResponse(null)
            }
            return null
        }

        if (!routeIsEch(host)) return null

        val client = echClient ?: return errorResponse(null)
        var lastError: Exception? = null
        repeat(2) { attempt ->
            var fetchUrl = url
            var redirects = 0
            try {
                while (true) {
                    val resp = doGet(client, fetchUrl, request) ?: break
                    if (resp.code in 300..399 && redirects < MAX_REDIRECTS) {
                        val loc = resp.header("Location")?.trim()?.ifEmpty { null }
                        if (loc == null) {
                            // 无 Location 的 3xx：原样返回会让 WebView 抛异常，包成 200 空体
                            resp.close()
                            return WebResourceResponse(
                                "text/html", "utf-8", 200, "OK",
                                mapOf("Cache-Control" to "no-store"),
                                ByteArrayInputStream(ByteArray(0)),
                            )
                        }
                        val next = resolveUrl(fetchUrl, loc)
                        val nextHost = runCatching { java.net.URI(next).host }.getOrNull()
                        if (nextHost == null || !isSameOrSubdomain(nextHost, host)) {
                            // 跨站跳转：原样返回 3xx + Location，WebView 自己跟进
                            // （下一次请求重新进 shouldInterceptRequest，该走 ECH 的走 ECH）
                            return toWebResourceResponse(fetchUrl, resp)
                        }
                        resp.close()
                        fetchUrl = next
                        redirects++
                        continue
                    }
                    return toWebResourceResponse(fetchUrl, resp)
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "ECH GET attempt ${attempt + 1}/2 failed: host=${hostOf(fetchUrl)} (${e.javaClass.simpleName})")
                if (attempt == 0) {
                    try { Thread.sleep(RETRY_DELAY_MS) } catch (_: Exception) {}
                }
            }
        }
        Log.e(TAG, "ECH required for host=$host; refusing direct-TLS fallback (${lastError?.javaClass?.simpleName ?: "unknown"})")
        return errorResponse(lastError)
    }

    /** 在进程内经 ECH 发送 GET，返回 OkHttp Response（调用方负责 close）。 */
    private fun doGet(client: OkHttpClient, url: String, request: WebResourceRequest): okhttp3.Response? {
        val builder = Request.Builder().url(url)

        // 透传 WebView 的请求头（Cookie/Host 单独处理）
        request.requestHeaders.forEach { (k, v) ->
            if (k.equals("Cookie", ignoreCase = true)) return@forEach
            if (k.equals("Host", ignoreCase = true)) return@forEach
            if (k.contains('\n') || k.contains('\r')) return@forEach
            builder.header(k, v)
        }

        // Cookie 全部走 CookieManager（真实域名，无需改写）
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (!cookie.isNullOrEmpty()) builder.header("Cookie", cookie)

        val resp = client.newCall(builder.get().build()).execute()

        // Set-Cookie 写回 CookieManager（真实域名，属性改写保证 WebView 能存下）
        resp.headers("Set-Cookie").forEach { raw ->
            var fixed = raw
            fixed = fixed.replace(Regex("(?i);\\s*Domain=[^;]+"), "")
            fixed = fixed.replace(Regex("(?i);\\s*Secure(?=;|$)"), "")
            fixed = fixed.replace(Regex("(?i);\\s*SameSite=[^;]+"), "; SameSite=Lax")
            runCatching { CookieManager.getInstance().setCookie(url, fixed) }
        }
        runCatching { CookieManager.getInstance().flush() }

        // 5xx 视为可重试失败
        if (resp.code in 500..599) {
            resp.close()
            throw java.io.IOException("upstream ${resp.code}")
        }
        return resp
    }

    /** OkHttp Response → WebResourceResponse（流式，不进内存，防 OOM）。 */
    private fun toWebResourceResponse(url: String, resp: okhttp3.Response): WebResourceResponse {
        val headers = LinkedHashMap<String, String>()
        for ((name, value) in resp.headers) {
            when {
                name.equals("Set-Cookie", ignoreCase = true) -> Unit // 已写回 CookieManager
                name.equals("Content-Length", ignoreCase = true) -> Unit
                name.equals("Transfer-Encoding", ignoreCase = true) -> Unit
                name.isNotEmpty() -> headers[name] = value
            }
        }
        val contentType = headers.entries
            .firstOrNull { e -> e.key.equals("Content-Type", ignoreCase = true) }
            ?.value ?: "text/html"
        var mimeType = "text/html"
        var encoding = "utf-8"
        contentType.split(";").forEachIndexed { idx, part ->
            if (idx == 0) mimeType = part.trim().ifEmpty { "text/html" }
            else if (part.trim().startsWith("charset=", ignoreCase = true)) {
                encoding = part.trim().substringAfter("=").trim()
            }
        }
        val code = resp.code
        val reason = resp.message.ifBlank { "OK" }
        val bodyStream = resp.body?.byteStream()?.let { raw ->
            object : java.io.FilterInputStream(raw) {
                override fun close() {
                    try { super.close() } catch (_: Exception) {}
                    try { resp.close() } catch (_: Exception) {}
                }
            }
        } ?: ByteArrayInputStream(ByteArray(0))
        Log.d(TAG, "ECH GET ok: $code ${resp.body?.contentLength() ?: -1}b host=${hostOf(url)}")
        return WebResourceResponse(mimeType, encoding, code, reason, headers, bodyStream)
    }

    private fun isSameOrSubdomain(host: String, base: String): Boolean {
        val h = host.lowercase()
        val b = base.lowercase()
        return h == b || h.endsWith(".$b")
    }

    private fun resolveUrl(base: String, location: String): String =
        runCatching { java.net.URI(base).resolve(location).toString() }.getOrNull() ?: location

    /** 供 EchWebBridge 复用：ECH 引擎客户端（null 表示引擎不可用）。 */
    fun echClientOrNull(): OkHttpClient? = echClient
}
