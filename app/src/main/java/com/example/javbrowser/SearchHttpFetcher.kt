package com.example.javbrowser

import android.webkit.CookieManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SearchHttpResponse(val statusCode: Int, val finalUrl: String, val body: String,
                              val contentType: String?, val retryAfterUntil: Long = 0L,
                              val transportError: String? = null,
                              val cfMitigated: String? = null,
                              val cloudflareServed: Boolean = false,
                              val rendered: Boolean = false)

/**
 * 搜索 HTML 抓取（GeckoView 遷移後：純 OkHttp 明文）。
 *
 * 原先的 ECH（Conscrypt）/ Cronet H3 路徑已隨 EchH3Router、EchDoh、
 * ConscryptEch 一起刪除。瀏覽器載入（嵌頁播放器）走 GeckoView 原生
 * ECH/DoH；搜索抓取仍走 OkHttp，但已是明文 SNI——在用戶網絡上
 * 明文 SNI 可能被 RST，搜索受影響時應把抓取也改走 GeckoView
 * （見 docs/geckoview-migration.md）。
 *
 * 保留：CookieManager 雙向同步、host allowlist、2MB 體 cap、
 * 超時、取消、retryAfter / cf-mitigated 標記。
 */
class SearchHttpFetcher {
    fun fetch(url: String, cancellation: SearchCancellation, site: SearchSite): SearchHttpResponse {
        cancellation.check()
        val host = url.toHttpUrlOrNull()?.host
        return try {
            val response = fetchPlain(url, cancellation, site)
            if (host != null) {
                NetworkLogStore.add(NetworkLogEntry(System.currentTimeMillis(), host, "OkHttp", false, null, null, ""))
            }
            response
        } catch (e: IOException) {
            if (host != null && !cancellation.isCancelled) {
                NetworkLogStore.add(NetworkLogEntry(System.currentTimeMillis(), host, "OkHttp", false, null, e.message, ""))
            }
            throw e
        }
    }

    private fun fetchPlain(
        url: String,
        cancellation: SearchCancellation,
        site: SearchSite,
    ): SearchHttpResponse {
        val client = baseClient.newBuilder()
            .addNetworkInterceptor(cookieInterceptor(site))
            .build()
        return executeOkHttp(client, url, cancellation, site)
    }

    /** The original fetch body, unchanged: request, call, 2MB cap, response mapping. */
    private fun executeOkHttp(
        client: OkHttpClient,
        url: String,
        cancellation: SearchCancellation,
        site: SearchSite,
    ): SearchHttpResponse {
        cancellation.check()
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
        if (site.parserKind == SearchParserKind.JABLE && url.contains("function=get_block"))
            request.header("X-Requested-With", "XMLHttpRequest")
        val call = client.newCall(request.build())
        val detach = cancellation.onCancel { call.cancel() }
        try {
            call.execute().use { response ->
                val source = response.body?.source()
                source?.request(MAX_BODY + 1)
                if ((source?.buffer?.size ?: 0L) > MAX_BODY) throw SearchTransportException("RESPONSE_TOO_LARGE")
                val charset = response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                return SearchHttpResponse(response.code, response.request.url.toString(),
                    source?.readByteArray()?.toString(charset).orEmpty(), response.header("Content-Type"),
                    retryAfter(response.header("Retry-After"), System.currentTimeMillis()),
                    cfMitigated = response.header("cf-mitigated"),
                    cloudflareServed = response.header("Server").orEmpty().contains("cloudflare", true) || response.header("cf-ray") != null)
            }
        } finally { detach() }
    }

    /** The original network interceptor, factored out so both OkHttp paths share it. */
    private fun cookieInterceptor(site: SearchSite): Interceptor = Interceptor { chain ->
        val target = chain.request().url
        if (!site.isAllowedHost(target.host)) throw SearchTransportException("UNEXPECTED_REDIRECT_HOST")
        val cookies = CookieManager.getInstance().getCookie(target.toString())
        val request = chain.request().newBuilder().removeHeader("Cookie")
        if (!cookies.isNullOrBlank()) request.header("Cookie", cookies)
        val response = chain.proceed(request.build())
        response.headers("Set-Cookie").forEach { CookieManager.getInstance().setCookie(target.toString(), it) }
        response
    }

    companion object {
        private const val MAX_BODY = 2L * 1024 * 1024
        private val baseClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        fun retryAfter(value: String?, now: Long): Long {
            if (value == null) return 0L
            value.trim().toLongOrNull()?.let {
                return now + it.coerceIn(0, (Long.MAX_VALUE - now) / 1000) * 1000
            }
            return runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time ?: 0L
            }.getOrDefault(0L).coerceAtLeast(now)
        }
    }
}
