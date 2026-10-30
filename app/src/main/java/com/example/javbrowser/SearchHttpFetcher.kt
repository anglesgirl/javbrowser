package com.example.javbrowser

import android.util.Log
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

class SearchHttpFetcher {
    fun fetch(url: String, cancellation: SearchCancellation, site: SearchSite): SearchHttpResponse {
        cancellation.check()
        val host = url.toHttpUrlOrNull()?.host
        if (host != null) {
            // route() already resolved this host via DoH, so this read is
            // cache-warm and adds no network round trip.
            val hasEchConfig = EchDoh.resolve(host).echConfigList != null
            when (EchH3Router.route(host)) {
                EchH3Router.Route.Ech -> return fetchEch(url, cancellation, site, host)
                EchH3Router.Route.Cronet -> {
                    try {
                        return EchH3Router.fetchViaCronet(url, site, cancellation, headerList(url, site), hasEchConfig)
                    } catch (e: EchH3Router.CronetFetchFailed) {
                        if (cancellation.isCancelled) throw IOException("Canceled")
                        // Rescue attempt: this host has no trusted own
                        // ech, but it may still be Cloudflare-fronted
                        // behind a polluted or ech-silent DoH answer
                        // (the Cloudflare-IP gate in EchDoh cannot see
                        // through pollution). Try the official shared
                        // Cloudflare config once; on any failure, fall
                        // through to the original plain-OkHttp fallback
                        // below — never ECH_FAILED, and working paths
                        // pay nothing because this only runs after
                        // Cronet already failed.
                        val sharedEch = EchDoh.sharedEchConfig()
                        if (sharedEch != null) {
                            try {
                                return fetchEchShared(url, cancellation, site, host, sharedEch)
                            } catch (ste: SearchTransportException) {
                                // Allowlist / body cap: propagates, never falls back.
                                throw ste
                            } catch (re: IOException) {
                                if (cancellation.isCancelled) throw IOException("Canceled")
                                Log.d("EchH3", "shared ECH rescue failed for $host, falling back to plain OkHttp: ${re.message}")
                            }
                        }
                        Log.d("EchH3", "Cronet failed for $host, falling back to plain OkHttp: ${e.message}")
                        return fetchPlainLogged(url, cancellation, site, host, hasEchConfig)
                    }
                    // SearchTransportException (allowlist / body cap) and
                    // IOException("Canceled") propagate: never fall back.
                }
            }
        }
        return fetchPlain(url, cancellation, site)
    }

    /**
     * ECH path: the ECH-capable client, fail-closed. A first-attempt
     * failure (DoH/ECH config may not have been ready on the first
     * request) → sleep 700ms, invalidate the cached config, retry once.
     * A second failure becomes transportError "ECH_FAILED" — never a
     * silent plaintext retry (that would leak the SNI ECH exists to
     * hide). The search layer already handles transportError by
     * degrading that provider while others still return results.
     */
    private fun fetchEch(
        url: String,
        cancellation: SearchCancellation,
        site: SearchSite,
        host: String,
    ): SearchHttpResponse {
        val client = EchH3Router.searchEchClient()
            .newBuilder()
            .addNetworkInterceptor(cookieInterceptor(site))
            .build()
        try {
            val response = executeOkHttp(client, url, cancellation, site)
            NetworkLogStore.add(NetworkLogEntry(System.currentTimeMillis(), host, "ECH", true, null, null, EchDoh.sourceFor(host)))
            return response
        } catch (e: IOException) {
            if (cancellation.isCancelled) throw e
            Log.d("EchH3", "ECH attempt failed for $host, retrying after 700ms: ${e.message}")
            try {
                Thread.sleep(700)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Canceled", ie)
            }
            cancellation.check()
            EchDoh.invalidate(host)
            try {
                val response = executeOkHttp(client, url, cancellation, site)
                NetworkLogStore.add(NetworkLogEntry(System.currentTimeMillis(), host, "ECH", true, null, null, EchDoh.sourceFor(host)))
                return response
            } catch (e2: IOException) {
                if (cancellation.isCancelled) throw e2
                Log.w("EchH3", "ECH failed for $host (fail-closed, no plaintext fallback): ${e2.message}")
                NetworkLogStore.add(NetworkLogEntry(System.currentTimeMillis(), host, "ECH", true, null, e2.message, EchDoh.sourceFor(host)))
                return SearchHttpResponse(
                    statusCode = 0,
                    finalUrl = url,
                    body = "",
                    contentType = null,
                    transportError = "ECH_FAILED",
                )
            }
        }
    }

    /**
     * Single-attempt shared-ECH fetch for hosts with no trusted own ech
     * (rescue after a Cronet failure). Uses the official shared
     * Cloudflare config, not the host's own. No retry loop: one attempt,
     * bounded by the rescue client's timeouts. Throws IOException on
     * any transport failure — the caller falls back to the original
     * route; this never returns ECH_FAILED. SearchTransportException
     * (allowlist / body cap) propagates. Logged with source "共享" so
     * a rescue success is distinguishable from an own-ech success.
     */
    private fun fetchEchShared(
        url: String,
        cancellation: SearchCancellation,
        site: SearchSite,
        host: String,
        sharedEch: ByteArray,
    ): SearchHttpResponse {
        val client = EchH3Router.sharedEchClient(sharedEch)
            .newBuilder()
            .addNetworkInterceptor(cookieInterceptor(site))
            .build()
        try {
            val response = executeOkHttp(client, url, cancellation, site)
            NetworkLogStore.add(NetworkLogEntry(System.currentTimeMillis(), host, "ECH", true, null, null, "共享"))
            return response
        } catch (e: SearchTransportException) {
            // Allowlist / body cap: not a transport failure, not logged, propagates.
            throw e
        } catch (e: IOException) {
            if (!cancellation.isCancelled) {
                NetworkLogStore.add(NetworkLogEntry(System.currentTimeMillis(), host, "ECH", true, null, e.message, "共享"))
            }
            throw e
        }
    }

    /**
     * Plain-OkHttp path (Cronet fallback) with transport logging. The
     * host==null edge at the end of [fetch] keeps using [fetchPlain]
     * directly: an unparseable URL has no host worth logging.
     */
    private fun fetchPlainLogged(
        url: String,
        cancellation: SearchCancellation,
        site: SearchSite,
        host: String,
        hasEchConfig: Boolean,
    ): SearchHttpResponse {
        return try {
            val response = fetchPlain(url, cancellation, site)
            NetworkLogStore.add(NetworkLogEntry(System.currentTimeMillis(), host, "OkHttp", hasEchConfig, null, null, EchDoh.sourceFor(host)))
            response
        } catch (e: IOException) {
            if (!cancellation.isCancelled) {
                NetworkLogStore.add(NetworkLogEntry(System.currentTimeMillis(), host, "OkHttp", hasEchConfig, null, e.message, EchDoh.sourceFor(host)))
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

    private fun headerList(url: String, site: SearchSite): List<Pair<String, String>> {
        val headers = mutableListOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "zh-TW,zh;q=0.9,en;q=0.8",
        )
        if (site.parserKind == SearchParserKind.JABLE && url.contains("function=get_block")) {
            headers += "X-Requested-With" to "XMLHttpRequest"
        }
        return headers
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
