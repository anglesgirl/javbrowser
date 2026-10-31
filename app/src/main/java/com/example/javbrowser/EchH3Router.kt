package com.example.javbrowser

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The single factory and router for app-layer network access (search,
 * page fetching, the video proxy's upstream fetch, and the main browser
 * WebView's intercepted GETs — WebView's own default loads, and POSTs,
 * never come here; their TLS is system-controlled).
 *
 * Routing (ECH first, security over speed): for every host, [EchDoh]
 * resolves the HTTPS record first. A host that publishes an ECH config
 * goes through OkHttp + Conscrypt ECH; a host that does not goes through
 * Cronet to grab HTTP/3. The Cronet path is allowed to fall back to
 * plain OkHttp on failure; the ECH path is not — a failed ECH handshake
 * must never silently degrade to plaintext SNI (see SearchHttpFetcher).
 *
 * One exception to "no ECH without own config": hosts without a
 * trusted own config get one opportunistic shared-ECH attempt in
 * SearchHttpFetcher AFTER a Cronet failure (official cloudflare-ech.com
 * config). That attempt is speculative, so unlike the ECH route it
 * falls back to the original route on failure and never produces
 * ECH_FAILED — it only runs on an already-failing path, so working
 * paths pay nothing for it.
 *
 * Cronet preserves the exact semantics SearchHttpFetcher's OkHttp path
 * has: host allowlist (including on cross-host redirects), CookieManager
 * bidirectional sync, 15s timeout, [SearchCancellation], and the 2MB
 * body cap. Known parity gap: on a cross-host redirect inside the
 * allowlist, Cronet cannot refresh the Cookie header the way OkHttp's
 * network interceptor can, so cookies may be stale on the redirected
 * request.
 *
 * Verification is by the in-app transport log ([NetworkLogStore],
 * shown in NetworkLogActivity) as well as logcat: tag "EchH3" logs every
 * route decision, every ECH setup, and every Cronet negotiated protocol.
 */
object EchH3Router {

    private const val TAG = "EchH3"

    /** Same 2MB cap as SearchHttpFetcher's OkHttp path. */
    const val MAX_BODY: Long = 2L * 1024 * 1024

    /** Matches SearchHttpFetcher's 15s call timeout. */
    private const val CRONET_TIMEOUT_SEC = 15L

    /** Safety bound above the scheduled cancel, so a stuck callback can never hang search. */
    private const val LATCH_TIMEOUT_SEC = 20L

    private const val READ_BUFFER = 32 * 1024

    sealed interface Route {
        data object Ech : Route
        data object Cronet : Route
    }

    /**
     * Cronet transport failure (network error, timeout, engine unavailable).
     * Callers may fall back to plain OkHttp for non-ECH hosts. Distinct
     * from [SearchTransportException] (allowlist / body cap) and from a
     * user cancellation, which must propagate unchanged, never fall back.
     */
    class CronetFetchFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

    @Volatile
    private var appContext: Context? = null

    private val cronetLock = Any()
    private var cronetEngine: CronetEngine? = null

    private val cronetCallbacks = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "echh3-cronet-cb").apply { isDaemon = true }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        ConscryptEch.ensureProvider()
    }

    /**
     * Decides the transport for [host]. DoH runs first even for the
     * Cronet route: the decision itself needs the HTTPS record, and the
     * ECH client later reuses the cached config.
     */
    fun route(host: String): Route {
        if (!ConscryptEch.ensureProvider()) {
            Log.d(TAG, "route $host -> Cronet (Conscrypt unavailable)")
            return Route.Cronet
        }
        val records = EchDoh.resolve(host)
        return if (records.echConfigList != null) {
            Log.d(TAG, "route $host -> ECH (${records.echConfigList.size}B config, ${records.addresses.size} addrs, src=${records.dohSource})")
            Route.Ech
        } else {
            Log.d(
                TAG,
                "route $host -> Cronet (no ECH config, resolvedViaDoh=${records.resolvedViaDoh}, src=${records.dohSource})",
            )
            Route.Cronet
        }
    }

    /**
     * OkHttp client that can do ECH: Conscrypt sockets + the policy trust
     * manager + DoH-resolved addresses. If Conscrypt is unavailable, a
     * plain OkHttp client with the same timeouts is returned instead —
     * callers must only use this builder for the ECH route or for
     * best-effort paths (SalesRanking), never as a plaintext fallback
     * for a failed ECH handshake.
     */
    fun newOkHttpClient(
        connectSec: Long,
        readSec: Long,
        callSec: Long? = null,
        followRedirects: Boolean = true,
        retryOnConnectionFailure: Boolean = true,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .retryOnConnectionFailure(retryOnConnectionFailure)
        if (callSec != null) builder.callTimeout(callSec, TimeUnit.SECONDS)
        return try {
            val (factory, trustManager) = ConscryptEch.newSslPair()
            builder.sslSocketFactory(factory, trustManager).dns(EchDns).build()
        } catch (e: Exception) {
            Log.w(TAG, "ECH client unavailable, plain OkHttp with same timeouts: ${e.message}")
            builder.build()
        }
    }

    /**
     * SearchHttpFetcher's old baseClient timeouts, routed through ECH.
     *
     * retryOnConnectionFailure = true (unlike the plain baseClient):
     * this client is used ONLY by SearchHttpFetcher.fetchEch, which
     * builds header-only GET requests (SalesRanking uses
     * [newOkHttpClient] directly, also GET-only, and already retries).
     * GET retries are safe, and OkHttp's RouteSelector then walks every
     * address [EchDns] returned instead of dying on the first one: a
     * single dead first IP (pigav.com: 69.171.234.48 timing out after
     * 10s while the browser, which tries each address, connects fine)
     * must not kill the whole request. Retries stay inside this ECH
     * client — same host, same ECH config on every attempt — so a
     * failed ECH handshake still ends as ECH_FAILED, never as a
     * plaintext fallback. The 15s call timeout bounds the walk.
     */
    fun searchEchClient(): OkHttpClient =
        newOkHttpClient(10, 15, callSec = 15, followRedirects = true, retryOnConnectionFailure = true)

    /**
     * Rescue-attempt client for the shared Cloudflare ECH config: same
     * shape as [searchEchClient] but the SSL pair injects the given
     * shared config for every host instead of the host's own config
     * (so the policy answers REQUIRED for the target host). Bounded
     * timeouts: a rescue attempt must fail fast so the caller can
     * fall back to the original route. Unlike [newOkHttpClient], a
     * missing Conscrypt pair throws instead of degrading to plaintext:
     * a rescue that "succeeds" without ECH would be a lie in the log.
     */
    fun sharedEchClient(sharedEch: ByteArray): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        return try {
            val (factory, trustManager) = ConscryptEch.newSslPair { _ -> sharedEch }
            builder.sslSocketFactory(factory, trustManager).dns(EchDns).build()
        } catch (e: Exception) {
            throw IOException("shared ECH client unavailable", e)
        }
    }

    /**
     * Shared client for browser (WebView) resource fetches: same ECH
     * shape as the proxy's client, no call timeout (a page resource or
     * video stream must never be killed by a call bound), connect/read
     * mirroring the proxy (15s/30s, read is per-read idle). Built only
     * after route() has verified Conscrypt is up for an ECH host, so
     * the newOkHttpClient plain-fallback trap cannot fire here.
     */
    private val webClient: OkHttpClient by lazy {
        newOkHttpClient(
            connectSec = 15,
            readSec = 30,
            callSec = null,
            followRedirects = true,
            retryOnConnectionFailure = true,
        )
    }

    /**
     * Fetches one browser GET request through ECH, for
     * WebViewClient.shouldInterceptRequest. Call only from that
     * callback's background thread, never the main thread (blocking).
     *
     * Returns null when the URL is not HTTPS or its host publishes no
     * ECH config — the caller MUST then fall through to WebView
     * default (plaintext is correct for non-ECH sites). Any non-null
     * result MUST be returned as-is: on ECH failure (after one 700ms
     * retry with the cached config invalidated, mirroring
     * SearchHttpFetcher) it is a fail-closed 502 error page, never a
     * plaintext fallback. A route() exception (DoH itself broken) also
     * returns null: with no config obtainable, ECH is impossible
     * anyway, so plaintext is the only transport left.
     *
     * Request headers are forwarded except Host/Connection/
     * Content-Length/Transfer-Encoding (OkHttp sets those) and
     * Accept-Encoding (OkHttp's transparent gzip handles it, so WebView
     * always gets plain bytes). Set-Cookie is written back to
     * CookieManager so ECH-fetched pages share login state with
     * WebView's own requests. Only main-frame successes and all
     * failures are logged, so image-heavy pages do not spam logcat.
     */
    fun fetchWebViewResource(
        url: String,
        requestHeaders: Map<String, String>?,
        isMainFrame: Boolean,
    ): WebResourceResponse? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https") return null
        val isEch = runCatching { route(parsed.host) == Route.Ech }.getOrDefault(false)
        if (!isEch) return null
        val builder = okhttp3.Request.Builder().url(url)
        requestHeaders?.forEach { (name, value) ->
            if (!name.equals("Host", ignoreCase = true) &&
                !name.equals("Connection", ignoreCase = true) &&
                !name.equals("Accept-Encoding", ignoreCase = true) &&
                !name.equals("Content-Length", ignoreCase = true) &&
                !name.equals("Transfer-Encoding", ignoreCase = true) &&
                // Cache validators must not be forwarded: an intercepted
                // response used to be able to enter WebView's HTTP cache
                // via upstream cache headers, so an upstream 304 ("use
                // your cache") had no cached body to use and surfaced
                // as a response-code failure. Forcing a full 200 avoids
                // that trap (reloads included). The response side now
                // also forces no-store (see below), so intercepted
                // responses never poison the cache in the first place.
                !name.equals("If-Modified-Since", ignoreCase = true) &&
                !name.equals("If-None-Match", ignoreCase = true)
            ) {
                builder.header(name, value)
            }
        }
        val request = builder.build()
        var lastError: Exception? = null
        repeat(2) { attempt ->
            if (attempt > 0) {
                EchDoh.invalidate(parsed.host)
                try {
                    Thread.sleep(700)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return errorResponse()
                }
            }
            try {
                val response = webClient.newCall(request).execute()
                val finalUrl = response.request.url.toString()
                response.headers("Set-Cookie").forEach { value ->
                    CookieManager.getInstance().setCookie(finalUrl, value)
                }
                val contentType = response.header("Content-Type")
                val mime = contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "application/octet-stream"
                val encoding = contentType?.split(';')?.map { it.trim() }
                    ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                    ?.substringAfter('=', "")?.trim()?.takeIf { it.isNotEmpty() } ?: "utf-8"
                val headers = mutableMapOf<String, String>()
                response.headers.toMultimap().forEach { (name, values) ->
                    // Hop-by-hop and framing headers are consumed by the
                    // transport and must not reach WebView: Set-Cookie is
                    // already synced to CookieManager above, Content-Type
                    // is carried by the mimeType parameter, and a stale
                    // Content-Encoding/Transfer-Encoding would make
                    // WebView decode already-decoded bytes.
                    // Cache headers are stripped too and replaced by
                    // no-store below: an intercepted error page (or any
                    // intercepted response) must never sit in WebView's
                    // HTTP cache, or a refresh keeps serving the stale
                    // error and only a cache clear recovers (seen live:
                    // ERR_HTTP_RESPONSE_CODE_FAILURE stuck until the
                    // app cache was cleared).
                    if (!name.equals("Set-Cookie", ignoreCase = true) &&
                        !name.equals("Content-Encoding", ignoreCase = true) &&
                        !name.equals("Transfer-Encoding", ignoreCase = true) &&
                        !name.equals("Connection", ignoreCase = true) &&
                        !name.equals("Content-Type", ignoreCase = true) &&
                        !name.equals("Cache-Control", ignoreCase = true) &&
                        !name.equals("Expires", ignoreCase = true) &&
                        !name.equals("ETag", ignoreCase = true) &&
                        !name.equals("Last-Modified", ignoreCase = true)
                    ) {
                        headers[name] = values.joinToString(", ")
                    }
                }
                // Intercepted responses are always re-fetched: WebView
                // must not cache them (see above).
                headers["Cache-Control"] = "no-store, no-cache, must-revalidate"
                headers["Pragma"] = "no-cache"
                headers["Expires"] = "0"
                val body = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                if (isMainFrame) {
                    Log.d(TAG, "webview ECH $finalUrl status=${response.code}")
                }
                return WebResourceResponse(
                    mime, encoding, response.code, reasonFor(response.code, response.message), headers, body,
                )
            } catch (e: Exception) {
                lastError = e
                Log.d(TAG, "webview ECH attempt $attempt failed for ${redactForLog(url)}: ${e.message}")
            }
        }
        Log.w(TAG, "webview ECH failed for ${redactForLog(url)}: ${lastError?.message}")
        return errorResponse()
    }

    /**
     * HTTP/2 responses carry no reason phrase (OkHttp reports ""), and
     * an empty reason on an intercepted response is a malformed status
     * line candidate — default it by code instead of passing "" through.
     */
    private fun reasonFor(code: Int, message: String): String {
        if (message.isNotBlank()) return message
        return when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> ""
        }
    }

    private fun errorResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8", 502, "Bad Gateway",
            mapOf(
                "Cache-Control" to "no-store, no-cache, must-revalidate",
                "Pragma" to "no-cache",
                "Expires" to "0",
            ),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun redactForLog(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.take(160)
        return "${parsed.scheme}://${parsed.host}${parsed.encodedPath}".take(160)
    }

    /**
     * DNS for the ECH client: DoH-resolved addresses when present, system
     * DNS only for the address fallback. The ECH config itself always
     * comes from DoH, never from system DNS.
     */
    private object EchDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val records = EchDoh.resolve(hostname)
            if (records.addresses.isNotEmpty()) return records.addresses
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private fun engine(): CronetEngine {
        synchronized(cronetLock) {
            cronetEngine?.let { return it }
            val context = appContext ?: throw IllegalStateException("EchH3Router not initialized")
            val built = CronetEngine.Builder(context)
                .enableQuic(true)
                .setUserAgent(SearchHttpFetcher.USER_AGENT)
                .build()
            cronetEngine = built
            return built
        }
    }

    /**
     * Runs one fetch through Cronet and returns the same response shape
     * SearchHttpFetcher's OkHttp path returns.
     *
     * Throws [CronetFetchFailed] for transport failures (caller falls back
     * to plain OkHttp for non-ECH hosts), [SearchTransportException] for
     * allowlist violations and body-cap overflow, and IOException("Canceled")
     * for a user cancellation. [headers] are the request headers
     * (User-Agent, Accept, Accept-Language, X-Requested-With); the Cookie
     * header is added here from CookieManager, and Set-Cookie is written
     * back to it, mirroring the OkHttp network interceptor.
     */
    fun fetchViaCronet(
        url: String,
        site: SearchSite,
        cancellation: SearchCancellation,
        headers: List<Pair<String, String>>,
        hasEchConfig: Boolean,
    ): SearchHttpResponse {
        cancellation.check()
        val parsed = url.toHttpUrlOrNull() ?: throw CronetFetchFailed("unparseable URL: $url")
        if (!site.isAllowedHost(parsed.host)) {
            throw SearchTransportException("UNEXPECTED_REDIRECT_HOST")
        }
        val engine = try {
            engine()
        } catch (e: Exception) {
            throw CronetFetchFailed("Cronet unavailable", e)
        }
        val holder = CronetHolder()
        val latch = CountDownLatch(1)
        val callback = CronetCallback(site, cancellation, latch, holder, url, hasEchConfig)
        val request = engine.newUrlRequestBuilder(url, callback, cronetCallbacks).apply {
            for ((name, value) in headers) addHeader(name, value)
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) addHeader("Cookie", cookie)
        }.build()
        val detach = cancellation.onCancel { request.cancel() }
        val timer = SearchScheduler.timers.schedule(
            { request.cancel() },
            CRONET_TIMEOUT_SEC,
            TimeUnit.SECONDS,
        )
        try {
            request.start()
            val done = latch.await(LATCH_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!done) {
                request.cancel()
                throw CronetFetchFailed("Cronet timed out")
            }
            holder.error?.let { throw it }
            holder.response?.let { return it }
            throw CronetFetchFailed("Cronet ended without a response")
        } finally {
            timer.cancel(false)
            detach()
        }
    }

    private class CronetHolder {
        @Volatile var response: SearchHttpResponse? = null

        @Volatile var error: Exception? = null
    }

    private class CronetCallback(
        private val site: SearchSite,
        private val cancellation: SearchCancellation,
        private val latch: CountDownLatch,
        private val holder: CronetHolder,
        initialUrl: String,
        private val hasEchConfig: Boolean,
    ) : UrlRequest.Callback() {

        private val initialHost: String = initialUrl.toHttpUrlOrNull()?.host ?: initialUrl
        private var finalUrl: String = initialUrl
        private var statusCode: Int = 0
        private var negotiated: String? = null
        private var headers: Map<String, List<String>> = emptyMap()
        private val body = ByteArrayOutputStream()

        override fun onRedirectReceived(
            request: UrlRequest,
            info: UrlResponseInfo,
            newLocationString: String,
        ) {
            val newHost = newLocationString.toHttpUrlOrNull()?.host
            if (newHost == null || !site.isAllowedHost(newHost)) {
                // Same rule as the OkHttp interceptor: a redirect outside
                // the site's allowlist is a hard failure, not a fallback.
                holder.error = SearchTransportException("UNEXPECTED_REDIRECT_HOST")
                request.cancel()
                return
            }
            finalUrl = newLocationString
            request.followRedirect()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            statusCode = info.httpStatusCode
            finalUrl = info.url
            negotiated = info.negotiatedProtocol
            headers = info.allHeaders
            // Bidirectional cookie sync, mirroring the OkHttp interceptor.
            headerValues("set-cookie").forEach { value ->
                CookieManager.getInstance().setCookie(info.url, value)
            }
            Log.d(
                TAG,
                "Cronet ${info.url} negotiated=${info.negotiatedProtocol} status=$statusCode",
            )
            request.read(ByteBuffer.allocateDirect(READ_BUFFER))
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            byteBuffer.clear()
            if (body.size().toLong() + bytes.size > MAX_BODY) {
                holder.error = SearchTransportException("RESPONSE_TOO_LARGE")
                request.cancel()
                return
            }
            body.write(bytes)
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            val logHost = info.url.toHttpUrlOrNull()?.host ?: initialHost
            NetworkLogStore.add(
                NetworkLogEntry(
                    System.currentTimeMillis(),
                    logHost,
                    "Cronet", hasEchConfig, negotiated, null, EchDoh.sourceFor(logHost),
                ),
            )
            holder.response = buildResponse()
            latch.countDown()
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
            if (holder.error == null) {
                holder.error = CronetFetchFailed("Cronet failed: ${error.message}", error)
                val logHost = info?.url?.toHttpUrlOrNull()?.host ?: initialHost
                NetworkLogStore.add(
                    NetworkLogEntry(
                        System.currentTimeMillis(),
                        logHost,
                        "Cronet", hasEchConfig, negotiated, error.message, EchDoh.sourceFor(logHost),
                    ),
                )
            }
            latch.countDown()
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            if (holder.error == null) {
                holder.error = if (cancellation.isCancelled) {
                    IOException("Canceled")
                } else {
                    // Our 15s timer fired: a transport failure, eligible for
                    // the plain-OkHttp fallback.
                    CronetFetchFailed("Cronet canceled (timeout)")
                }
                // User cancellations are not failures and are not logged;
                // our own timeout is, so the fallback chain stays visible.
                if (!cancellation.isCancelled) {
                    val logHost = info?.url?.toHttpUrlOrNull()?.host ?: initialHost
                    NetworkLogStore.add(
                        NetworkLogEntry(
                            System.currentTimeMillis(),
                            logHost,
                            "Cronet", hasEchConfig, negotiated, "Cronet canceled (timeout)", EchDoh.sourceFor(logHost),
                        ),
                    )
                }
            }
            latch.countDown()
        }

        private fun headerValue(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        private fun headerValues(name: String): List<String> =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()

        private fun buildResponse(): SearchHttpResponse {
            val contentType = headerValue("content-type")
            val charset = parseCharset(contentType) ?: Charsets.UTF_8
            val server = headerValue("server")
            return SearchHttpResponse(
                statusCode = statusCode,
                finalUrl = finalUrl,
                body = body.toByteArray().toString(charset),
                contentType = contentType,
                retryAfterUntil = SearchHttpFetcher.retryAfter(headerValue("retry-after"), System.currentTimeMillis()),
                cfMitigated = headerValue("cf-mitigated"),
                cloudflareServed = (server?.contains("cloudflare", ignoreCase = true) == true) ||
                    headerValue("cf-ray") != null,
            )
        }

        private fun parseCharset(contentType: String?): Charset? {
            if (contentType == null) return null
            for (part in contentType.split(";")) {
                val trimmed = part.trim()
                if (trimmed.startsWith("charset=", ignoreCase = true)) {
                    return try {
                        Charset.forName(trimmed.removePrefix("charset=").trim())
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            return null
        }
    }
}
