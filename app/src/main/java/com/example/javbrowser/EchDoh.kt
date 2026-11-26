package com.example.javbrowser

import android.util.Base64
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS (user's own Cloudflare Gateway JSON API) resolver
 * for the two record families the app-layer ECH path needs:
 *
 *  - HTTPS (type 65): its `ech` SvcParam carries the ECHConfigList that
 *    [org.conscrypt.Conscrypt.setEchConfigList] consumes verbatim.
 *  - A / AAAA: the addresses to dial, so an ECH handshake never depends on
 *    the system resolver for the target host.
 *
 * Endpoints are the user's own Cloudflare Gateway DoH hostnames
 * (2026-11-22 user decision — the same Gateway DoH hyper-browser
 * and co3 use). Ali was dropped in 1.2.24: it is not the user's
 * own resolver. 360 was already dropped in 1.2.23 (its TLS
 * handshake fails on the user's network). Five Gateway endpoints
 * are tried in order; the first that answers wins.
 *
 * Transport (aligned 1.2.26 with Han1meViewer's proven ECH stack
 * and co3's proven DoH client — same principle, both work on the
 * user's phone; javbrowser's 1.2.23–1.2.25 transport never
 * produced an ECH config there):
 *  - Bootstrap IPs are cloudflare-ech.com's edge IPs
 *    (104.18.10.118 / 104.18.11.118, resolved 2026-11-26).
 *    Any edge IP serves any *.cloudflare-gateway.com hostname via
 *    SNI routing. Do NOT use the 172.64.229/24 range here: it only
 *    serves SaaS / CNAME-connected domains, and a regular domain
 *    dialed via it gets Cloudflare Error 1103 (whos.tv, 2026-11-26).
 *    The previous pins (172.64.229.69 + 172.64.229.1–8, then
 *    162.159.36.5/162.159.36.20 + 176.24.239.99) answered in the
 *    sandbox but never produced a config on the phone — route()
 *    always fell through to Cronet/plain and both RST.
 *  - The DoH client pins every lookup to those bootstrap IPs
 *    (Han1meViewer query() pattern), speaks HTTP/1.1 only and
 *    never goes through a proxy (co3: H2 misbehaves on flaky
 *    networks; a proxy silently stalls DoH), 3s connect/read,
 *    8s call timeout, no retries.
 *  - The ech= extractor is Han1meViewer's: quoted or unquoted,
 *    standard or URL-safe base64, and the decoded bytes are
 *    validated as an ECHConfigList before being trusted.
 *
 * Transport is the Gateway JSON API (GET /dns-query?name=...&type=...),
 * the same JSON shape Ali's /resolve returned (Status / Answer
 * array, HTTPS records in textual SVCB form
 * `1 . alpn="h2" ... ech="BASE64" ...`); a `\# <len> <hex>`
 * wire-form answer is also handled as a fallback. Results are
 * cached per host with the records' TTL, clamped to [30s, 10min];
 * failures and empty answers are cached briefly so a dead DoH
 * endpoint cannot stall every request.
 *
 * Blocking calls: call only from worker threads (search runs on
 * SearchScheduler.workers; OkHttp dispatcher threads for the socket factory).
 * Never throws for lookup failures: [resolve] returns empty records instead.
 *
 * Last resort (same as the deleted code): when a host's own HTTPS
 * record has no ech but its dial addresses are Cloudflare edge IPs,
 * the shared Cloudflare ECH config is fetched live from
 * cloudflare-ech.com's HTTPS record. A rejected handshake still
 * fails closed; this never falls back to plaintext.
 *
 * The ech is only trusted when it is backed by a Cloudflare edge:
 * either the HTTPS record's ipv4hint/ipv6hint or the A/AAAA
 * addresses must be Cloudflare edge IPs. When the hints are
 * Cloudflare, the dial addresses are the hints, not the A/AAAA
 * addresses — the browser's behavior (avjoy.me: A record
 * 8.39.125.92 is a non-CF origin, its ipv4hint 104.18.10.118 is
 * the CF edge; dialing the A record with ECH gets ECH_REJECTED,
 * dialing the hint works). A CNAME'd HTTPS record can carry
 * someone else's ech; without a Cloudflare edge behind it the
 * ech is ignored.
 *
 * A shared config (public_name cloudflare-ech.com) is never trusted
 * from a site's own record: that record is a snapshot and CF rotates
 * the shared keys hourly (5h expiry), so it is fetched live from
 * cloudflare-ech.com on every cache miss instead. Own configs
 * (public_name is the site itself) are used as-is.
 */
object EchDoh {

    private const val TAG = "EchDoh"

    private const val TYPE_A = 1
    private const val TYPE_CNAME = 5
    private const val TYPE_AAAA = 28
    private const val TYPE_HTTPS = 65
    private const val SVC_PARAM_ECH = 5
    private const val MAX_CHAIN_HOPS = 2

    private data class DohEndpoint(val name: String, val url: String)

    // The user's own Cloudflare Gateway DoH, in try order (first
    // answer wins). Logs use only the short "GW-<id>" names, never
    // the URL. All five share one bootstrap pool (see the class
    // KDoc): any Cloudflare edge IP serves any gateway hostname.
    // Bootstrap IPs are cloudflare-ech.com's edge IPs (104.18.10.118 /
    // 104.18.11.118), resolved 2026-11-26. Do NOT use the 172.64.229/24
    // range here: that range only serves SaaS / CNAME-connected
    // domains — a regular domain dialed via it gets Cloudflare
    // Error 1103 (whos.tv hit this: its service IP is
    // 2606:4700::6812:782 / 104.18.7.130, not 172.64.229.x).
    // ECH-capable domains CNAME to cloudflare-ech.com, so its IPs
    // are general Cloudflare edge IPs that serve any Cloudflare
    // hostname, including the Gateway DoH endpoints below.
    // (2026-11-26 user correction: "cloudflare-ech.com 直接用这个
    // 好了，这样又稳又安全".)
    private val BOOTSTRAP_IPS = listOf(
        "104.18.10.118", "104.18.11.118",
    )

    private val ENDPOINTS = listOf(
        DohEndpoint("GW-m2b4x7vw98", "https://m2b4x7vw98.cloudflare-gateway.com/dns-query"),
        DohEndpoint("GW-pieqllv9i7", "https://pieqllv9i7.cloudflare-gateway.com/dns-query"),
        DohEndpoint("GW-dz1598pphb", "https://dz1598pphb.cloudflare-gateway.com/dns-query"),
        DohEndpoint("GW-0kbpekmcr1", "https://0kbpekmcr1.cloudflare-gateway.com/dns-query"),
        DohEndpoint("GW-82sew1c85i", "https://82sew1c85i.cloudflare-gateway.com/dns-query"),
    )

    private val BOOTSTRAP_ADDRS: List<InetAddress> =
        BOOTSTRAP_IPS.map { InetAddress.getByName(it) }

    private val dohClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1)) // co3: H2 misbehaves on flaky networks
        .proxy(Proxy.NO_PROXY) // DoH must never go through a proxy (2026-09-22 lesson)
        .retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .dns(object : Dns {
            // Han1meViewer query() pattern: the gateway hostname must
            // never go through system DNS (poisoned); every lookup on
            // this client answers with the bootstrap pins.
            override fun lookup(hostname: String): List<InetAddress> = BOOTSTRAP_ADDRS
        })
        .build()

    class HostRecords(
        val addresses: List<InetAddress>,
        val echConfigList: ByteArray?,
        val resolvedViaDoh: Boolean,
        /** DoH endpoint that provided this record: "GW-..." / "GW-...+共享" / "" (not via DoH). */
        val dohSource: String = "",
        /**
         * ipv4hint/ipv6hint from the HTTPS record that provided
         * [echConfigList] (IPv4 first). Empty when no ech: hints are
         * only meaningful together with the ech they belong to.
         */
        val echHints: List<InetAddress> = emptyList(),
    )

    private class CacheEntry(val records: HostRecords, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** ECHConfigList bytes for [host], or null when the host publishes none. */
    fun configFor(host: String): ByteArray? = resolve(host).echConfigList

    /**
     * Official shared Cloudflare ECH config, fetched live from
     * [SHARED_ECH_HOST]'s HTTPS record. Goes through [resolve], so it
     * is cached under the normal TTL and re-fetched live on a cache
     * miss — never a hardcoded constant, because keys rotate. Null
     * when unavailable.
     */
    fun sharedEchConfig(): ByteArray? = resolve(SHARED_ECH_HOST).echConfigList

    /**
     * DoH endpoint that provided the cached record for [host] ("GW-..."),
     * or "" when nothing is cached. Cache-only read:
     * never triggers network, so transport logging can use it freely.
     */
    fun sourceFor(host: String): String = cache[host.lowercase()]?.records?.dohSource ?: ""

    /** Outcome of the last DoH resolution for a host (fresh or cached). */
    data class DohDiagnosis(
        val endpoint: String, // "GW-..." that answered, "" when all failed
        val ok: Boolean, // any endpoint answered
        val error: String?, // last error when all endpoints failed
        val hasEch: Boolean,
        val echBytes: Int,
        val source: String, // dohSource of the records ("GW-..." / "GW-...+共享" / "")
    )

    private val lastDiagnosis = ConcurrentHashMap<String, DohDiagnosis>()

    /** Cache-only read: never triggers network. */
    fun diagnosisFor(host: String): DohDiagnosis? = lastDiagnosis[host.lowercase()]

    fun invalidate(host: String) {
        cache.remove(host.lowercase())
    }

    /**
     * Host whose HTTPS record carries the shared Cloudflare ECH config:
     * Cloudflare's own ECH hostname (the public_name itself), the
     * canonical source — it does not depend on any personal site
     * keeping ECH enabled, and its generation is the official current
     * one. Fetched live on every cache miss, never hardcoded.
     */
    private const val SHARED_ECH_HOST = "cloudflare-ech.com"

    // Published Cloudflare edge ranges (cloudflare.com/ips). Only used
    // to decide whether the shared Cloudflare ECH config may apply to a
    // host whose own ech is unavailable from every resolver. Literals
    // only: InetAddress.getByName parses them without touching DNS.
    private val CLOUDFLARE_RANGES: List<Pair<ByteArray, Int>> =
        listOf(
            "173.245.48.0" to 20, "103.21.244.0" to 22, "103.22.200.0" to 22,
            "103.31.4.0" to 22, "141.101.64.0" to 18, "108.162.192.0" to 18,
            "190.93.240.0" to 20, "188.114.96.0" to 20, "197.234.240.0" to 22,
            "198.41.128.0" to 17, "162.158.0.0" to 15, "104.16.0.0" to 13,
            "104.24.0.0" to 14, "172.64.0.0" to 13, "131.0.72.0" to 22,
            "2400:cb00::" to 32, "2606:4700::" to 32, "2803:f800::" to 32,
            "2405:8100::" to 32, "2405:b500::" to 32, "2a06:98c0::" to 29,
            "2c0f:f248::" to 32,
        ).map { (network, bits) -> InetAddress.getByName(network).address to bits }

    private fun isCloudflareIp(addr: InetAddress): Boolean {
        val bytes = addr.address
        return CLOUDFLARE_RANGES.any { (network, bits) ->
            network.size == bytes.size && prefixMatches(bytes, network, bits)
        }
    }

    private fun prefixMatches(addr: ByteArray, network: ByteArray, bits: Int): Boolean {
        var remaining = bits
        var i = 0
        while (remaining > 0) {
            val take = minOf(8, remaining)
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((addr[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            remaining -= take
            i++
        }
        return true
    }

    /**
     * Resolves [host] through the Gateway endpoints in order. The
     * first endpoint that answers wins: a valid answer is adopted
     * when it carries an ech config AND the ech is backed by a
     * Cloudflare edge — either the HTTPS record's ipv4hint/ipv6hint
     * or the A/AAAA addresses must be Cloudflare edge IPs. When the
     * hints are Cloudflare, the dial addresses are the hints, not
     * the A/AAAA addresses (the browser's behavior: avjoy.me's A
     * record 8.39.125.92 is a non-CF origin while its ipv4hint
     * 104.18.10.118 is the CF edge — dialing the A record with ECH
     * gets ECH_REJECTED, dialing the hint works). When an HTTPS
     * record carries an ech but neither the hints nor the A/AAAA
     * addresses are Cloudflare, the ech belongs to someone else's
     * edge (reached via CNAME) and is ignored; the host goes
     * Cronet/plain instead of failing ECH.
     *
     * Last resort, after the Gateway's HTTPS record lacks ech: if the site's
     * dial addresses are Cloudflare edge IPs, fetch the shared
     * Cloudflare ECH config live from [SHARED_ECH_HOST]'s HTTPS
     * record. The shared value is fetched on every cache miss, never
     * hardcoded into the code, because keys rotate. A rejected
     * handshake still fails closed; this never falls back to
     * plaintext. Note: a host on a different key generation (e.g.
     * config_id 249 vs the shared 82) may reject the shared config;
     * that fails closed and is left to real-device verification — no
     * further fallback is added for it.
     */
    fun resolve(host: String): HostRecords {
        val key = host.lowercase()
        cache[key]?.let { if (System.currentTimeMillis() < it.expiresAtMs) return it.records }
        var lastError: String? = null
        for (endpoint in ENDPOINTS) {
            try {
                val (records, minTtl) = resolveFromEndpoint(endpoint, host)
                if (!records.resolvedViaDoh) continue
                if (records.echConfigList != null) {
                    val recordEch = records.echConfigList
                    val hintIsCf = records.echHints.any { isCloudflareIp(it) }
                    val addrIsCf = records.addresses.any { isCloudflareIp(it) }
                    if (hintIsCf || addrIsCf) {
                        // A shared config (public_name
                        // cloudflare-ech.com) from a site's record is
                        // a snapshot: CF rotates the shared keys
                        // hourly (5h expiry) and a manually-placed
                        // record is never refreshed, so fetch the
                        // current shared config live from
                        // cloudflare-ech.com instead. Fall back to
                        // the snapshot when the live fetch fails.
                        // Own configs (public_name is the site
                        // itself) are used as-is. (cloudflare-ech.com
                        // itself is exempt: its record IS the live
                        // value.)
                        val ech = if (isSharedEchConfig(recordEch) && key != SHARED_ECH_HOST) {
                            fetchLiveSharedEch() ?: recordEch.also {
                                Log.w(TAG, "live shared ech fetch failed for $host, using record snapshot")
                            }
                        } else {
                            recordEch
                        }
                        // Dial the edge the ech is valid for: the
                        // HTTPS hints when they are Cloudflare,
                        // otherwise the A/AAAA addresses. (avjoy.me:
                        // A record 8.39.125.92 is a non-CF origin,
                        // its ipv4hint 104.18.10.118 is the CF edge;
                        // dialing the A record with ECH gets
                        // ECH_REJECTED, dialing the hint works.)
                        val dialAddrs = if (hintIsCf) {
                            records.echHints.distinct().sortedBy { it.address.size }
                        } else {
                            records.addresses
                        }
                        val adopted = HostRecords(
                            dialAddrs, ech, true, records.dohSource, records.echHints,
                        )
                        lastDiagnosis[key] = DohDiagnosis(
                            endpoint.name, true, null, true,
                            ech.size, adopted.dohSource,
                        )
                        cacheResolved(key, adopted, minTtl)
                        if (hintIsCf) {
                            Log.d(
                                TAG,
                                "resolved $host: dialing HTTPS hints (${dialAddrs.size} addrs) instead of A/AAAA",
                            )
                        }
                        return adopted
                    }
                    // HTTPS carried an ech but neither the hints nor
                    // the A/AAAA addresses are Cloudflare: the ech
                    // belongs to someone else's edge (reached via
                    // CNAME) and cannot be used for this host.
                    Log.d(
                        TAG,
                        "resolved $host: HTTPS ech ignored, neither hints nor A/AAAA are Cloudflare " +
                            "(addrs=${records.addresses}, hints=${records.echHints})",
                    )
                    val noEch = HostRecords(
                        records.addresses, null, true, records.dohSource, records.echHints,
                    )
                    lastDiagnosis[key] = DohDiagnosis(
                        endpoint.name, true, null, false, 0, records.dohSource,
                    )
                    cacheResolved(key, noEch, minTtl)
                    return noEch
                }
                // Last resort: shared Cloudflare ECH config. Only when
                // the site's own ech is missing from every endpoint AND
                // its dial addresses are Cloudflare edge IPs. Fetched
                // live from cloudflare-ech.com's HTTPS record on every cache
                // miss — never hardcoded, because keys rotate. A
                // rejected handshake still fails closed.
                if (records.addresses.any { isCloudflareIp(it) }) {
                    for (sharedEndpoint in ENDPOINTS) {
                        try {
                            val shared = echFromEndpoint(sharedEndpoint, SHARED_ECH_HOST)
                            val sharedEch = shared.ech
                            if (sharedEch != null) {
                                val merged = HostRecords(
                                    records.addresses, sharedEch, true,
                                    "${records.dohSource}+共享",
                                )
                                lastDiagnosis[key] = DohDiagnosis(
                                    sharedEndpoint.name, true, null, true,
                                    sharedEch.size, merged.dohSource,
                                )
                                cacheResolved(key, merged, minOf(minTtl, shared.minTtl))
                                Log.d(
                                    TAG,
                                    "resolved $host: no own ech, using shared Cloudflare ech " +
                                        "via ${sharedEndpoint.name}",
                                )
                                return merged
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "shared ech lookup via ${sharedEndpoint.name} failed for $host: ${e.message}")
                        }
                    }
                }
                // No endpoint had ech and no shared config applied: the first valid record stands.
                lastDiagnosis[key] = DohDiagnosis(
                    endpoint.name, true, null, false, 0, records.dohSource,
                )
                cacheResolved(key, records, minTtl)
                return records
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "DoH via ${endpoint.name} failed for $host: ${e.message}")
            }
        }
        lastDiagnosis[key] = DohDiagnosis("", false, lastError, false, 0, "")
        val empty = HostRecords(emptyList(), null, false, "")
        cache[key] = CacheEntry(empty, System.currentTimeMillis() + 30_000L)
        return empty
    }

    private fun cacheResolved(key: String, records: HostRecords, minTtl: Long) {
        val ttlMs = when {
            records.echConfigList == null && records.addresses.isEmpty() -> 60_000L
            else -> minTtl.coerceIn(30, 600) * 1000
        }
        cache[key] = CacheEntry(records, System.currentTimeMillis() + ttlMs)
        Log.d(
            TAG,
            "resolved $key via ${records.dohSource}: " +
                "addrs=${records.addresses.size} ech=${records.echConfigList?.size ?: 0}B",
        )
    }

    /**
     * Result of one HTTPS-record chain lookup: the ech bytes (if the
     * terminal record carries one) plus the ipv4hint/ipv6hint from
     * the same record. Hints are only returned together with an ech;
     * without an ech they are meaningless for ECH.
     */
    private class HttpsResult(
        val ech: ByteArray?,
        val ipv4Hint: List<InetAddress>,
        val ipv6Hint: List<InetAddress>,
        val minTtl: Long,
        val dohOk: Boolean,
    )

    /**
     * HTTPS-chain-only lookup: the ech SvcParam for [host] from one
     * endpoint, following CNAME chains, plus the ipv4hint/ipv6hint
     * from the same terminal record. Returns the ech bytes (or
     * null), the hints, the smallest TTL seen, and whether any HTTPS
     * query answered. Throws on transport/parse failure like [queryJson].
     */
    private fun echFromEndpoint(endpoint: DohEndpoint, host: String): HttpsResult {
        var name = host
        var minTtl = 300L
        var dohOk = false
        for (hop in 0..MAX_CHAIN_HOPS) {
            val ans = queryJson(endpoint, name, TYPE_HTTPS) ?: break
            dohOk = true
            minTtl = minOf(minTtl, ans.minTtl)
            if (ans.echConfigList != null) {
                return HttpsResult(ans.echConfigList, ans.ipv4Hint, ans.ipv6Hint, minTtl, dohOk)
            }
            val next = ans.aliasTarget ?: ans.cnameTarget ?: break
            if (next.equals(name, ignoreCase = true)) break
            name = next
        }
        return HttpsResult(null, emptyList(), emptyList(), minTtl, dohOk)
    }

    private fun resolveFromEndpoint(endpoint: DohEndpoint, host: String): Pair<HostRecords, Long> {
        var minTtl = 300L
        var dohOk = false
        // HTTPS chain via the shared helper: ServiceMode records carry
        // the ech param directly; a plain CNAME points at the next name.
        val https = echFromEndpoint(endpoint, host)
        val ech = https.ech
        minTtl = minOf(minTtl, https.minTtl)
        dohOk = dohOk || https.dohOk
        // Address chains (A and AAAA are independent CNAME chains).
        val collected = mutableListOf<InetAddress>()
        for (qtype in intArrayOf(TYPE_A, TYPE_AAAA)) {
            var addrName = host
            for (hop in 0..MAX_CHAIN_HOPS) {
                val ans = queryJson(endpoint, addrName, qtype) ?: break
                dohOk = true
                minTtl = minOf(minTtl, ans.minTtl)
                collected += ans.addresses
                if (ans.addresses.isNotEmpty()) break
                val next = ans.cnameTarget ?: break
                if (next.equals(addrName, ignoreCase = true)) break
                addrName = next
            }
        }
        // IPv4 first: no happy-eyeballs racing, and many devices have no
        // working IPv6 route even when AAAA answers exist.
        val records = HostRecords(
            addresses = collected.sortedBy { it.address.size },
            echConfigList = ech,
            resolvedViaDoh = dohOk,
            dohSource = endpoint.name,
            echHints = (https.ipv4Hint + https.ipv6Hint).distinct(),
        )
        return records to minTtl
    }

    private class JsonAnswer(
        val addresses: List<InetAddress> = emptyList(),
        val echConfigList: ByteArray? = null,
        val cnameTarget: String? = null,
        val aliasTarget: String? = null,
        val minTtl: Long = 300,
        val ipv4Hint: List<InetAddress> = emptyList(),
        val ipv6Hint: List<InetAddress> = emptyList(),
    )

    /**
     * One Gateway JSON-API query. Returns null only when the response has
     * no usable Answer section; a DNS error status (NXDOMAIN etc.)
     * yields an empty answer, not a throw. Throws on transport or
     * HTTP failure so the caller can try the next endpoint.
     */
    private fun queryJson(endpoint: DohEndpoint, name: String, qtype: Int): JsonAnswer? {
        val url = "${endpoint.url}?name=${URLEncoder.encode(name, "UTF-8")}&type=$qtype"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()
        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("DoH HTTP ${response.code} for $name/$qtype")
            val body = response.body?.string() ?: throw IOException("DoH empty body for $name/$qtype")
            return parseJsonAnswer(body)
        }
    }

    private fun parseJsonAnswer(body: String): JsonAnswer {
        val json = JSONObject(body)
        if (json.optInt("Status", -1) != 0) return JsonAnswer()
        val answers = json.optJSONArray("Answer") ?: return JsonAnswer()
        val addresses = mutableListOf<InetAddress>()
        var ech: ByteArray? = null
        var cname: String? = null
        var aliasTarget: String? = null
        val ipv4Hint = mutableListOf<InetAddress>()
        val ipv6Hint = mutableListOf<InetAddress>()
        var minTtl = Long.MAX_VALUE
        for (i in 0 until answers.length()) {
            val a = answers.optJSONObject(i) ?: continue
            val type = a.optInt("type", -1)
            val ttl = a.optLong("TTL", 300)
            if (ttl in 1 until minTtl) minTtl = ttl
            val data = a.optString("data", "")
            when (type) {
                TYPE_A, TYPE_AAAA -> {
                    runCatching { InetAddress.getByName(data) }.getOrNull()?.let { addresses += it }
                }
                TYPE_CNAME -> {
                    cname = data.trimEnd('.')
                }
                TYPE_HTTPS -> {
                    if (ech == null) ech = extractEchFromHttpsData(data)
                    val (v4, v6) = extractHintsFromHttpsData(data)
                    ipv4Hint += v4
                    ipv6Hint += v6
                    if (aliasTarget == null) aliasTarget = extractAliasTarget(data)
                }
            }
        }
        return JsonAnswer(
            addresses = addresses,
            echConfigList = ech,
            cnameTarget = cname,
            aliasTarget = aliasTarget,
            minTtl = if (minTtl == Long.MAX_VALUE) 300 else minTtl,
            ipv4Hint = ipv4Hint,
            ipv6Hint = ipv6Hint,
        )
    }

    /**
     * AliasMode target of one HTTPS record's JSON-API `data` string
     * (`0 <target>.`): the next name in the chain, mirroring the
     * pre-2026-11-13 wire parser's AliasMode handling. Null for
     * ServiceMode records (priority > 0) and for wire-form answers.
     */
    private fun extractAliasTarget(data: String): String? {
        val trimmed = data.trim()
        if (trimmed.startsWith("\\#")) return null
        val parts = trimmed.split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) return null
        if (parts[0].toIntOrNull() != 0) return null
        return parts[1].trimEnd('.').ifEmpty { null }
    }

    /**
     * Extracts the ech SvcParam from one HTTPS record's JSON-API
     * `data` string. Ported from Han1meViewer's proven extractor:
     * quoted or unquoted ech=, standard or URL-safe base64, and the
     * decoded bytes are validated as an ECHConfigList before being
     * trusted. Textual SVCB form (`1 . alpn="h2" ech="BASE64"
     * ...`) is the common case; a `\# <len> <hex>` RFC 3597
     * wire-form answer is handled as a fallback.
     */
    internal fun extractEchFromHttpsData(data: String): ByteArray? {
        val trimmed = data.trim()
        if (trimmed.startsWith("\\#")) {
            val hex = trimmed.removePrefix("\\#").trim().split(Regex("\\s+"))
            if (hex.size >= 2) {
                val wire = extractEchFromWireHex(hex.drop(1).joinToString(""))
                if (wire != null && isValidEchConfigList(wire)) return wire
            }
            return null
        }
        val b64 = extractEchParam(trimmed) ?: return null
        val flags = if ('-' in b64 || '_' in b64) Base64.URL_SAFE else Base64.DEFAULT
        val wire = runCatching { Base64.decode(b64, flags) }.getOrNull() ?: return null
        return wire.takeIf { isValidEchConfigList(it) }
    }

    private fun extractEchParam(data: String): String? {
        val match = Regex(
            """(?:^|\s)ech=(?:"([A-Za-z0-9+/_=-]+)"|([A-Za-z0-9+/_=-]+))(?=\s|$)""",
            RegexOption.IGNORE_CASE,
        ).find(data) ?: return null
        return (match.groupValues[1].ifEmpty { match.groupValues[2] }).takeIf { it.isNotEmpty() }
    }

    /**
     * Extracts the ipv4hint / ipv6hint SvcParams from one HTTPS
     * record's JSON-API `data` string. Textual SVCB form is the
     * common case (`ipv4hint=104.18.10.118,104.18.11.118 ...`);
     * a `\# <len> <hex>` wire-form answer is handled as a fallback
     * (keys 4 and 6). Returns empty lists when the record carries
     * no hints. Unparseable entries are dropped, never fatal.
     */
    internal fun extractHintsFromHttpsData(data: String): Pair<List<InetAddress>, List<InetAddress>> {
        val trimmed = data.trim()
        if (trimmed.startsWith("\\#")) {
            val hex = trimmed.removePrefix("\\#").trim().split(Regex("\\s+"))
            if (hex.size >= 2) return extractHintsFromWireHex(hex.drop(1).joinToString(""))
            return emptyList<InetAddress>() to emptyList()
        }
        val v4 = Regex("""(?:^|\s)ipv4hint="?([0-9.,]+)"?""", RegexOption.IGNORE_CASE)
            .findAll(trimmed)
            .flatMap { it.groupValues[1].split(",") }
            .mapNotNull { runCatching { InetAddress.getByName(it.trim()) }.getOrNull() }
            .toList()
        val v6 = Regex("""(?:^|\s)ipv6hint="?([0-9a-fA-F:,]+)"?""", RegexOption.IGNORE_CASE)
            .findAll(trimmed)
            .flatMap { it.groupValues[1].split(",") }
            .mapNotNull { runCatching { InetAddress.getByName(it.trim()) }.getOrNull() }
            .toList()
        return v4 to v6
    }

    /**
     * Extracts ipv4hint (key 4, 4 bytes per address) and ipv6hint
     * (key 6, 16 bytes per address) from an HTTPS RDATA hex string.
     * Same SvcParam walk as [extractEchFromWireHex].
     */
    private fun extractHintsFromWireHex(hex: String): Pair<List<InetAddress>, List<InetAddress>> {
        val bytes = runCatching {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull() ?: return emptyList<InetAddress>() to emptyList()
        if (bytes.size < 3) return emptyList<InetAddress>() to emptyList()
        var pos = 2 // skip priority
        // Skip the target name (plain labels, no compression in RDATA).
        while (pos < bytes.size) {
            val len = bytes[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                break
            }
            pos += 1 + len
            if (pos > bytes.size) return emptyList<InetAddress>() to emptyList()
        }
        val v4 = mutableListOf<InetAddress>()
        val v6 = mutableListOf<InetAddress>()
        while (pos + 4 <= bytes.size) {
            val key = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            val len = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            if (pos + len > bytes.size) break
            val value = bytes.copyOfRange(pos, pos + len)
            when (key) {
                4 -> {
                    var i = 0
                    while (i + 4 <= value.size) {
                        runCatching { InetAddress.getByAddress(value.copyOfRange(i, i + 4)) }
                            .getOrNull()?.let { v4 += it }
                        i += 4
                    }
                }
                6 -> {
                    var i = 0
                    while (i + 16 <= value.size) {
                        runCatching { InetAddress.getByAddress(value.copyOfRange(i, i + 16)) }
                            .getOrNull()?.let { v6 += it }
                        i += 16
                    }
                }
            }
            pos += len
        }
        return v4 to v6
    }

    /** Validates an ECHConfigList: 2-byte list length + version/length-delimited entries. */
    private fun isValidEchConfigList(wire: ByteArray): Boolean {
        if (wire.size < 7) return false
        val listLength = ((wire[0].toInt() and 0xff) shl 8) or (wire[1].toInt() and 0xff)
        if (listLength != wire.size - 2) return false
        var offset = 2
        var configCount = 0
        while (offset < wire.size) {
            if (wire.size - offset < 4) return false
            val configLength = ((wire[offset + 2].toInt() and 0xff) shl 8) or (wire[offset + 3].toInt() and 0xff)
            if (configLength == 0 || configLength > wire.size - offset - 4) return false
            offset += 4 + configLength
            configCount++
        }
        return configCount > 0 && offset == wire.size
    }

    /**
     * Extracts SvcParam key 5 (ech) from an HTTPS RDATA hex string:
     * priority (2B), target name (uncompressed labels), then
     * key/len/value params. Returns null when no ech param is present.
     */
    internal fun extractEchFromWireHex(hex: String): ByteArray? {
        val bytes = runCatching {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull() ?: return null
        if (bytes.size < 3) return null
        var pos = 2 // skip priority
        // Skip the target name (plain labels, no compression in RDATA).
        while (pos < bytes.size) {
            val len = bytes[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                break
            }
            pos += 1 + len
            if (pos > bytes.size) return null
        }
        while (pos + 4 <= bytes.size) {
            val key = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            val len = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            if (pos + len > bytes.size) break
            if (key == SVC_PARAM_ECH) {
                return bytes.copyOfRange(pos, pos + len)
            }
            pos += len
        }
        return null
    }

    /**
     * public_name of the first entry of an ECHConfigList, or null
     * when the bytes do not parse. Wire layout (RFC 9846): list
     * length u16, then entries (version u16, length u16, contents);
     * contents are key_config (config_id u8, kem_id u16, public_key
     * u16-len + bytes, cipher_suites u16-len + bytes),
     * maximum_name_length u8, public_name u8-len + bytes.
     */
    private fun echPublicName(wire: ByteArray): String? {
        if (wire.size < 2 + 4) return null
        var p = 2
        if (p + 4 > wire.size) return null
        val contentLen = ((wire[p + 2].toInt() and 0xFF) shl 8) or (wire[p + 3].toInt() and 0xFF)
        p += 4
        if (contentLen <= 0 || p + contentLen > wire.size) return null
        val end = p + contentLen
        if (p + 1 + 2 + 2 > end) return null
        p += 1 // config_id
        p += 2 // kem_id
        val pkLen = ((wire[p].toInt() and 0xFF) shl 8) or (wire[p + 1].toInt() and 0xFF)
        p += 2 + pkLen
        if (p + 2 > end) return null
        val csLen = ((wire[p].toInt() and 0xFF) shl 8) or (wire[p + 1].toInt() and 0xFF)
        p += 2 + csLen
        if (p + 1 > end) return null
        p += 1 // maximum_name_length
        if (p + 1 > end) return null
        val nameLen = wire[p].toInt() and 0xFF
        p += 1
        if (nameLen == 0 || p + nameLen > end) return null
        return runCatching { String(wire, p, nameLen, Charsets.US_ASCII) }.getOrNull()
    }

    /** Whether an ECHConfigList is Cloudflare's shared config. */
    private fun isSharedEchConfig(wire: ByteArray): Boolean =
        echPublicName(wire)?.equals(SHARED_ECH_HOST, ignoreCase = true) == true

    /**
     * Current shared Cloudflare ECH config, fetched live from
     * [SHARED_ECH_HOST]'s HTTPS record via the Gateway endpoints in
     * order. Never the snapshot from a site's own record: CF rotates
     * the shared keys hourly (5h expiry, IETF 126 measured) and a
     * manually-placed record goes stale within hours. Null when
     * unavailable. The result is cached as part of the caller's
     * HostRecords, so this runs at most once per cache TTL.
     */
    private fun fetchLiveSharedEch(): ByteArray? {
        for (endpoint in ENDPOINTS) {
            try {
                val shared = echFromEndpoint(endpoint, SHARED_ECH_HOST)
                if (shared.ech != null) return shared.ech
            } catch (e: Exception) {
                Log.w(TAG, "live shared ech via ${endpoint.name} failed: ${e.message}")
            }
        }
        return null
    }
}
