package com.example.javbrowser

import android.util.Base64
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
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
 * Each endpoint is reached by pinned bootstrap IP + SNI (the
 * gateway hostname), never through the system resolver and never
 * with an IP in the URL: the Gateway certificate's SAN carries no
 * IP, so SNI must be the gateway domain — for routing and for
 * certificate validation. The DoH client below overrides DNS for
 * exactly the gateway hosts. Bootstrap IPs: 162.159.36.5 /
 * 162.159.36.20 / 176.24.239.99.
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
 */
object EchDoh {

    private const val TAG = "EchDoh"

    private const val TYPE_A = 1
    private const val TYPE_CNAME = 5
    private const val TYPE_AAAA = 28
    private const val TYPE_HTTPS = 65
    private const val SVC_PARAM_ECH = 5
    private const val MAX_CHAIN_HOPS = 2

    private data class DohEndpoint(val name: String, val url: String, val pinnedIps: List<String>)

    // The user's own Cloudflare Gateway DoH, in try order (first
    // answer wins). Logs use only the short "GW-<id>" names, never
    // the URL. Each host is pinned to bootstrap IPs (direct IP +
    // SNI = gateway domain); the first three share the pool, the
    // last two have their own known bootstrap IPs.
    private val BOOTSTRAP_IPS = listOf("162.159.36.5", "162.159.36.20", "176.24.239.99")

    private val ENDPOINTS = listOf(
        DohEndpoint("GW-m2b4x7vw98", "https://m2b4x7vw98.cloudflare-gateway.com/dns-query", BOOTSTRAP_IPS),
        DohEndpoint("GW-pieqllv9i7", "https://pieqllv9i7.cloudflare-gateway.com/dns-query", BOOTSTRAP_IPS),
        DohEndpoint("GW-dz1598pphb", "https://dz1598pphb.cloudflare-gateway.com/dns-query", BOOTSTRAP_IPS),
        DohEndpoint("GW-0kbpekmcr1", "https://0kbpekmcr1.cloudflare-gateway.com/dns-query", listOf("176.24.239.99")),
        DohEndpoint("GW-82sew1c85i", "https://82sew1c85i.cloudflare-gateway.com/dns-query", listOf("162.159.36.20", "162.159.36.5")),
    )

    private val pinnedByHost: Map<String, List<String>> = ENDPOINTS.associate { endpoint ->
        val host = endpoint.url.substringAfter("https://").substringBefore("/")
        host to endpoint.pinnedIps
    }

    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                pinnedByHost[hostname.lowercase()]?.let { ips ->
                    // IP literals parse without touching the system resolver.
                    return ips.map { InetAddress.getByName(it) }
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        })
        .build()

    class HostRecords(
        val addresses: List<InetAddress>,
        val echConfigList: ByteArray?,
        val resolvedViaDoh: Boolean,
        /** DoH endpoint that provided this record: "GW-..." / "GW-...+共享" / "" (not via DoH). */
        val dohSource: String = "",
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
     * immediately when it carries an ech config (the common case:
     * no second query). When a valid answer has NO ech, resolution
     * proceeds directly to the shared-config last resort below
     * (there is no point asking another endpoint of the same
     * Gateway — it would answer identically); a transport failure
     * moves on to the next endpoint.
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
        for (endpoint in ENDPOINTS) {
            try {
                val (records, minTtl) = resolveFromEndpoint(endpoint, host)
                if (!records.resolvedViaDoh) continue
                if (records.echConfigList != null) {
                    cacheResolved(key, records, minTtl)
                    return records
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
                            val (sharedEch, sharedTtl, _) = echFromEndpoint(sharedEndpoint, SHARED_ECH_HOST)
                            if (sharedEch != null) {
                                val merged = HostRecords(
                                    records.addresses, sharedEch, true,
                                    "${records.dohSource}+共享",
                                )
                                cacheResolved(key, merged, minOf(minTtl, sharedTtl))
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
                cacheResolved(key, records, minTtl)
                return records
            } catch (e: Exception) {
                Log.w(TAG, "DoH via ${endpoint.name} failed for $host: ${e.message}")
            }
        }
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
     * HTTPS-chain-only lookup: the ech SvcParam for [host] from one
     * endpoint, following CNAME chains. Returns the ech bytes (or
     * null), the smallest TTL seen, and whether any HTTPS query
     * answered. Throws on transport/parse failure like [queryJson].
     */
    private fun echFromEndpoint(endpoint: DohEndpoint, host: String): Triple<ByteArray?, Long, Boolean> {
        var name = host
        var minTtl = 300L
        var dohOk = false
        for (hop in 0..MAX_CHAIN_HOPS) {
            val ans = queryJson(endpoint, name, TYPE_HTTPS) ?: break
            dohOk = true
            minTtl = minOf(minTtl, ans.minTtl)
            if (ans.echConfigList != null) return Triple(ans.echConfigList, minTtl, dohOk)
            val next = ans.cnameTarget ?: break
            if (next.equals(name, ignoreCase = true)) break
            name = next
        }
        return Triple(null, minTtl, dohOk)
    }

    private fun resolveFromEndpoint(endpoint: DohEndpoint, host: String): Pair<HostRecords, Long> {
        var minTtl = 300L
        var dohOk = false
        // HTTPS chain via the shared helper: ServiceMode records carry
        // the ech param directly; a plain CNAME points at the next name.
        val (ech, httpsTtl, httpsOk) = echFromEndpoint(endpoint, host)
        minTtl = minOf(minTtl, httpsTtl)
        dohOk = dohOk || httpsOk
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
        )
        return records to minTtl
    }

    private class JsonAnswer(
        val addresses: List<InetAddress> = emptyList(),
        val echConfigList: ByteArray? = null,
        val cnameTarget: String? = null,
        val minTtl: Long = 300,
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
                }
            }
        }
        return JsonAnswer(
            addresses = addresses,
            echConfigList = ech,
            cnameTarget = cname,
            minTtl = if (minTtl == Long.MAX_VALUE) 300 else minTtl,
        )
    }

    /**
     * Extracts the ech SvcParam from one HTTPS record's JSON-API
     * `data` string. Textual SVCB form (`1 . alpn="h2" ech="BASE64"
     * ...`) is the common case; a `\# <len> <hex>` RFC 3597
     * wire-form answer is handled as a fallback.
     */
    internal fun extractEchFromHttpsData(data: String): ByteArray? {
        val trimmed = data.trim()
        if (trimmed.startsWith("\\#")) {
            val hex = trimmed.removePrefix("\\#").trim().split(Regex("\\s+"))
            if (hex.size >= 2) {
                return extractEchFromWireHex(hex.drop(1).joinToString(""))
            }
            return null
        }
        val match = Regex("""ech="([^"]+)"""").find(trimmed) ?: return null
        return runCatching {
            Base64.decode(match.groupValues[1], Base64.DEFAULT)
        }.getOrNull()
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
}
