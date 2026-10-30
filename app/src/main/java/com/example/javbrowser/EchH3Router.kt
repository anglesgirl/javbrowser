package com.example.javbrowser

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
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
 * The single factory and router for app-layer network access (search and
 * page fetching only — WebView and video playback/download never come
 * here).
 *
 * Routing (ECH first, security over speed): for every host, [EchDoh]
 * resolves the HTTPS record first. A host that publishes an ECH config
 * goes through OkHttp + Conscrypt ECH; a host that does not goes through
 * Cronet to grab HTTP/3. The Cronet path is allowed to fall back to
 * plain OkHttp on failure; the ECH path is not — a failed ECH handshake
 * must never silently degrade to plaintext SNI (see SearchHttpFetcher).
 *
 * Cronet preserves the exact semantics SearchHttpFetcher's OkHttp path
 * has: host allowlist (including on cross-host redirects), CookieManager
 * bidirectional sync, 15s timeout, [SearchCancellation], and the 2MB
 * body cap. Known parity gap: on a cross-host redirect inside the
 * allowlist, Cronet cannot refresh the Cookie header the way OkHttp's
 * network interceptor can, so cookies may be stale on the redirected
 * request.
 *
 * Verification is by logcat: tag "EchH3" logs every route decision, every
 * ECH setup, and every Cronet negotiated protocol.
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
            Log.d(TAG, "route $host -> ECH (${records.echConfigList.size}B config, ${records.addresses.size} addrs)")
            Route.Ech
        } else {
            Log.d(
                TAG,
                "route $host -> Cronet (no ECH config, resolvedViaDoh=${records.resolvedViaDoh})",
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

    /** SearchHttpFetcher's old baseClient timeouts, routed through ECH. */
    fun searchEchClient(): OkHttpClient =
        newOkHttpClient(10, 15, callSec = 15, followRedirects = true, retryOnConnectionFailure = false)

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
        val callback = CronetCallback(site, cancellation, latch, holder, url)
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
    ) : UrlRequest.Callback() {

        private var finalUrl: String = initialUrl
        private var statusCode: Int = 0
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
            holder.response = buildResponse()
            latch.countDown()
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
            if (holder.error == null) {
                holder.error = CronetFetchFailed("Cronet failed: ${error.message}", error)
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
