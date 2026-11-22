package com.example.javbrowser

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * The factory and router for app-layer ECH network access (search).
 * Restored in 1.2.23 from the pre-deletion code (74085d6), Cronet/H3
 * stripped: the Cronet engine, fetchViaCronet, and the WebView
 * fetchWebViewResource are NOT restored (H3 deferred). Non-ECH hosts
 * use plain OkHttp (the pre-restore behaviour); ECH hosts use OkHttp
 * + Conscrypt ECH and fail closed.
 *
 * Routing (ECH first, security over speed): for every host, [EchDoh]
 * resolves the HTTPS record first (Gateway DoH). A host that publishes
 * an ECH config — its own, or the shared Cloudflare config when its
 * dial addresses are Cloudflare edge IPs — goes through OkHttp +
 * Conscrypt ECH; a host that does not goes through plain OkHttp.
 * The ECH path never silently degrades to plaintext SNI (see
 * SearchHttpFetcher): a failed ECH handshake ends as ECH_FAILED.
 *
 * Verification is by the in-app transport log ([NetworkLogStore],
 * shown in NetworkLogActivity) as well as logcat: tag "EchH3" logs
 * every route decision and every ECH setup.
 */
object EchH3Router {

    private const val TAG = "EchH3"

    sealed interface Route {
        data object Ech : Route
        data object Plain : Route
    }

    /**
     * Installs Conscrypt as provider #1 so the ECH factory can build
     * ECH-capable clients. Idempotent; call once from Application.
     * (The old init also stored a Context for the Cronet engine; the
     * engine is gone with the H3 deferral, so no Context is needed.)
     */
    fun init() {
        ConscryptEch.ensureProvider()
    }

    /**
     * Decides the transport for [host]. DoH runs first: the decision
     * itself needs the HTTPS record, and the ECH client later reuses
     * the cached config.
     */
    fun route(host: String): Route {
        if (!ConscryptEch.ensureProvider()) {
            Log.d(TAG, "route $host -> Plain (Conscrypt unavailable)")
            return Route.Plain
        }
        val records = EchDoh.resolve(host)
        return if (records.echConfigList != null) {
            Log.d(TAG, "route $host -> ECH (${records.echConfigList.size}B config, ${records.addresses.size} addrs, src=${records.dohSource})")
            Route.Ech
        } else {
            Log.d(
                TAG,
                "route $host -> Plain (no ECH config, resolvedViaDoh=${records.resolvedViaDoh}, src=${records.dohSource})",
            )
            Route.Plain
        }
    }

    /**
     * OkHttp client that can do ECH: Conscrypt sockets + the policy
     * trust manager + DoH-resolved addresses. Throws IOException
     * when the Conscrypt pair cannot be built — unlike the deleted
     * code, there is deliberately NO plain-OkHttp fallback here: a
     * caller that asked for the ECH route must never silently get a
     * plaintext client (that would leak the SNI ECH exists to hide).
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
        val (factory, trustManager) = try {
            ConscryptEch.newSslPair()
        } catch (e: Exception) {
            throw IOException("ECH client unavailable", e)
        }
        return builder.sslSocketFactory(factory, trustManager).dns(EchDns).build()
    }

    /**
     * SearchHttpFetcher's baseClient timeouts, routed through ECH.
     *
     * retryOnConnectionFailure = true (unlike the plain baseClient):
     * this client is used ONLY by SearchHttpFetcher.fetchEch, which
     * builds header-only GET requests. GET retries are safe, and
     * OkHttp's RouteSelector then walks every address [EchDns]
     * returned instead of dying on the first one. Retries stay
     * inside this ECH client — same host, same ECH config on every
     * attempt — so a failed ECH handshake still ends as ECH_FAILED,
     * never as a plaintext fallback. The 15s call timeout bounds
     * the walk.
     */
    fun searchEchClient(): OkHttpClient =
        newOkHttpClient(10, 15, callSec = 15, followRedirects = true, retryOnConnectionFailure = true)

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
}
