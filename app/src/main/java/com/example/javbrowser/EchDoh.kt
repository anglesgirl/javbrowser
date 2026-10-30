package com.example.javbrowser

import android.util.Log
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS (RFC 8484 wire format) resolver for the two record families
 * the app-layer ECH path needs:
 *
 *  - HTTPS (type 65): its `ech` SvcParam carries the ECHConfigList that
 *    [org.conscrypt.Conscrypt.setEchConfigList] consumes verbatim.
 *  - A / AAAA: the addresses to dial, so an ECH handshake never depends on
 *    the system resolver for the target host.
 *
 * Endpoint is the user's private Cloudflare Gateway only. Ali and
 * 360 were dropped (2026-10-30): both return polluted answers for
 * blocked domains, and the multi-resolver chain added latency
 * instead of speeding ECH acquisition up. It is reached by pinned
 * IP + SNI (the DoH
 * hostname), never through the system resolver: the DoH client below
 * overrides DNS for exactly those hosts. The Gateway hostname must
 * never appear in logs — only the short name "Gateway".
 * Results are cached per host with the records' TTL, clamped to [30s, 10min];
 * failures and empty answers are cached briefly so a dead DoH endpoint
 * cannot stall every request.
 *
 * Blocking calls: call only from worker threads (search runs on
 * SearchScheduler.workers; OkHttp dispatcher threads for the socket factory).
 * Never throws for lookup failures: [resolve] returns empty records instead.
 *
 * Wire parsing is adapted from the proven EchConfigResolver used by the
 * web-to-app ECH work (same message format, same CNAME/AliasMode chains).
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

    // Private Cloudflare Gateway only (Ali and 360 removed
    // 2026-10-30: polluted answers and extra latency, see header).
    // Pinned IP is the benign Cloudflare edge bootstrap IP; OkHttp still
    // verifies the certificate against the DoH hostname, and SNI is
    // the hostname (OkHttp passes the URL host to createSocket), so
    // dialing a pinned IP does not weaken TLS. The Gateway hostname
    // must NEVER go through system DNS (it would be polluted): its
    // entry pins the benign Cloudflare edge bootstrap IP below, so
    // the Gateway DoH is reached by direct IP + SNI like the others.
    // PRIVACY: the Gateway is the user's private resolver — logs
    // (logcat and the in-app transport log) use only the short name
    // "Gateway", never its hostname. Never log endpoint.url.
    private val ENDPOINTS = listOf(
        DohEndpoint("Gateway", "https://0kbpekmcr1.cloudflare-gateway.com/dns-query", listOf("172.64.147.104")),
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
        /** DoH endpoint that provided this record: "Gateway" / "Gateway+共享" / "" (not via DoH). */
        val dohSource: String = "",
    )

    private class CacheEntry(val records: HostRecords, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val random = SecureRandom()

    /** ECHConfigList bytes for [host], or null when the host publishes none. */
    fun configFor(host: String): ByteArray? = resolve(host).echConfigList

    /**
     * DoH endpoint that provided the cached record for [host] ("Gateway"),
     * or "" when nothing is cached. Cache-only read:
     * never triggers network, so transport logging can use it freely.
     */
    /**
     * Official shared Cloudflare ECH config, fetched live from
     * [SHARED_ECH_HOST]'s HTTPS record. Goes through [resolve], so it
     * is cached under the normal TTL and re-fetched live on a cache
     * miss — never a hardcoded constant, because keys rotate. Null
     * when unavailable. Used only for the opportunistic rescue
     * attempt in SearchHttpFetcher; the resolve-time shared fallback
     * keeps its own Cloudflare-IP gate and fail-closed semantics.
     * No recursion risk: [resolve] for the shared host itself returns
     * at stage 1 when its own ech is present, and its shared-fallback
     * block queries [echFromEndpoint] directly, never [resolve].
     */
    fun sharedEchConfig(): ByteArray? = resolve(SHARED_ECH_HOST).echConfigList

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
     * Resolves [host] through the user's private Gateway only (single
     * endpoint: no multi-resolver chain). A valid answer is
     * adopted immediately when it carries an ech config (the common
     * case: no second query). When a valid answer has NO ech, there is
     * no second resolver to ask (single endpoint): the merge loop
     * below is a no-op and resolution proceeds directly to the
     * shared-config last resort. (The loop is kept iterating ENDPOINTS
     * so a future second endpoint would work unchanged: it would keep
     * the first endpoint's addresses and supplement only the ech,
     * since ECH configs are published per zone, not per resolver.)
     *
     * Last resort, after the Gateway's HTTPS record lacks ech: if
     * the site's dial addresses are Cloudflare edge IPs, fetch the
     * shared Cloudflare ECH config live from [SHARED_ECH_HOST]'s HTTPS
     * record (verified identical across Cloudflare-fronted hosts; a
     * differing key is a rotation generation, not a different model).
     * The shared value is fetched on every cache miss, never hardcoded
     * into the code, because keys rotate. A rejected handshake still
     * fails closed; this never falls back to plaintext. Note: a host
     * on a different key generation (e.g. config_id 249 vs the shared
     * 82) may reject the shared config; that fails closed and is left
     * to real-device verification — no further fallback is added for it.
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
                for (next in ENDPOINTS) {
                    if (next == endpoint) continue
                    try {
                        val (ech, echTtl, _) = echFromEndpoint(next, host)
                        if (ech != null) {
                            val merged = HostRecords(
                                records.addresses, ech, true,
                                "${records.dohSource}+${next.name}",
                            )
                            cacheResolved(key, merged, minOf(minTtl, echTtl))
                            Log.d(
                                TAG,
                                "resolved $host: addresses via ${endpoint.name}, " +
                                    "ech via ${next.name}",
                            )
                            return merged
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "DoH HTTPS ech lookup via ${next.name} failed for $host: ${e.message}")
                    }
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
     * endpoint, following AliasMode/CNAME chains. Returns the ech
     * bytes (or null), the smallest TTL seen, and whether any HTTPS
     * query answered. Throws on transport/parse failure like [query].
     */
    private fun echFromEndpoint(endpoint: DohEndpoint, host: String): Triple<ByteArray?, Long, Boolean> {
        var name = host
        var minTtl = 300L
        var dohOk = false
        for (hop in 0..MAX_CHAIN_HOPS) {
            val msg = query(endpoint, name, TYPE_HTTPS) ?: break
            dohOk = true
            minTtl = minOf(minTtl, msg.minTtlSeconds)
            if (msg.echConfigList != null) return Triple(msg.echConfigList, minTtl, dohOk)
            val next = msg.aliasTarget ?: msg.cnameTarget ?: break
            if (next.equals(name, ignoreCase = true)) break
            name = next
        }
        return Triple(null, minTtl, dohOk)
    }

    private fun resolveFromEndpoint(endpoint: DohEndpoint, host: String): Pair<HostRecords, Long> {
        var minTtl = 300L
        var dohOk = false
        // HTTPS chain via the shared helper: ServiceMode records carry
        // the ech param directly; AliasMode (priority 0) and plain
        // CNAMEs point at the next name.
        val (ech, httpsTtl, httpsOk) = echFromEndpoint(endpoint, host)
        minTtl = minOf(minTtl, httpsTtl)
        dohOk = dohOk || httpsOk
        // Address chains (A and AAAA are independent CNAME chains).
        val collected = mutableListOf<InetAddress>()
        for (qtype in intArrayOf(TYPE_A, TYPE_AAAA)) {
            var addrName = host
            for (hop in 0..MAX_CHAIN_HOPS) {
                val msg = query(endpoint, addrName, qtype) ?: break
                dohOk = true
                minTtl = minOf(minTtl, msg.minTtlSeconds)
                collected += msg.addresses
                if (msg.addresses.isNotEmpty()) break
                val next = msg.cnameTarget ?: break
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

    private fun query(endpoint: DohEndpoint, name: String, qtype: Int): ParsedMessage? {
        val id = random.nextInt(0x10000)
        val request = Request.Builder()
            .url(endpoint.url)
            .header("Accept", "application/dns-message")
            .post(buildQuery(name, qtype, id).toRequestBody("application/dns-message".toMediaType()))
            .build()
        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("DoH HTTP ${response.code} for $name/$qtype")
            val bytes = response.body?.bytes() ?: throw IOException("DoH empty body for $name/$qtype")
            return parseMessage(bytes, id)
        }
    }

    internal fun buildQuery(name: String, qtype: Int, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, id)
        writeU16(out, 0x0100) // RD set
        writeU16(out, 1) // QDCOUNT
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, 0)
        for (label in name.trimEnd('.').split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        writeU16(out, qtype)
        writeU16(out, 1) // IN
        return out.toByteArray()
    }

    internal class ParsedMessage(
        val addresses: List<InetAddress> = emptyList(),
        val echConfigList: ByteArray? = null,
        val aliasTarget: String? = null,
        val cnameTarget: String? = null,
        val minTtlSeconds: Long = 300,
    )

    /**
     * Parses one DNS message for the record families we care about. Pure
     * (beyond InetAddress).
     */
    internal fun parseMessage(message: ByteArray, expectedId: Int): ParsedMessage {
        val reader = Reader(message)
        val id = reader.u16()
        val flags = reader.u16()
        val qd = reader.u16()
        val an = reader.u16()
        val ns = reader.u16()
        val ar = reader.u16()
        if (id != expectedId) throw IOException("DoH response id mismatch")
        if (flags and 0xF != 0) return ParsedMessage() // NXDOMAIN / errors: an empty answer

        repeat(qd) {
            reader.readName()
            reader.u16()
            reader.u16()
        }

        val addresses = mutableListOf<InetAddress>()
        var bestEch: ByteArray? = null
        var bestEchPriority = Int.MAX_VALUE
        var aliasTarget: String? = null
        var cnameTarget: String? = null
        var minTtl = Long.MAX_VALUE

        repeat(an + ns + ar) {
            reader.readName()
            val type = reader.u16()
            reader.u16() // class
            val ttl = reader.u32()
            val rdLength = reader.u16()
            val rdStart = reader.offset
            val rdEnd = rdStart + rdLength
            if (rdEnd > message.size) throw IOException("DNS record overruns message")
            when (type) {
                TYPE_A -> if (rdLength == 4) {
                    addresses += InetAddress.getByAddress(message.copyOfRange(rdStart, rdEnd))
                }
                TYPE_AAAA -> if (rdLength == 16) {
                    addresses += InetAddress.getByAddress(message.copyOfRange(rdStart, rdEnd))
                }
                TYPE_CNAME -> {
                    cnameTarget = Reader(message, rdStart).readName().first
                }
                TYPE_HTTPS -> {
                    val rdata = Reader(message, rdStart)
                    val priority = rdata.u16()
                    val (target, paramsStart) = rdata.readName()
                    if (priority == 0) {
                        aliasTarget = target
                    } else {
                        var p = paramsStart
                        var ech: ByteArray? = null
                        while (p + 4 <= rdEnd) {
                            val key = u16At(message, p)
                            val len = u16At(message, p + 2)
                            p += 4
                            if (p + len > rdEnd) break
                            if (key == SVC_PARAM_ECH) {
                                ech = message.copyOfRange(p, p + len)
                            }
                            p += len
                        }
                        if (ech != null && priority < bestEchPriority) {
                            bestEch = ech
                            bestEchPriority = priority
                        }
                    }
                }
            }
            if (ttl in 1 until minTtl) minTtl = ttl
            reader.offset = rdEnd
        }

        return ParsedMessage(
            addresses = addresses,
            echConfigList = bestEch,
            aliasTarget = aliasTarget,
            cnameTarget = cnameTarget,
            minTtlSeconds = if (minTtl == Long.MAX_VALUE) 300 else minTtl,
        )
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun u16At(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /** Sequential reader over a DNS message with compression-aware name decoding. */
    internal class Reader(private val buf: ByteArray, var offset: Int = 0) {

        fun u8(): Int {
            if (offset >= buf.size) throw IOException("DNS message truncated")
            return buf[offset++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        /**
         * Reads a possibly compressed name starting at [offset]. Returns the
         * dotted name and the offset just past its wire form at the original
         * position (past the first pointer, when one was followed). Advances
         * [offset] to that same position.
         */
        fun readName(): Pair<String, Int> {
            val sb = StringBuilder()
            var pos = offset
            var next = -1
            var jumps = 0
            while (true) {
                if (pos >= buf.size) throw IOException("DNS name out of bounds")
                val len = buf[pos].toInt() and 0xFF
                when {
                    len == 0 -> {
                        if (next == -1) next = pos + 1
                        break
                    }
                    len and 0xC0 == 0xC0 -> {
                        if (pos + 1 >= buf.size) throw IOException("DNS pointer out of bounds")
                        val pointer = ((len and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                        if (next == -1) next = pos + 2
                        pos = pointer
                        if (++jumps > 10) throw IOException("DNS name pointer loop")
                    }
                    else -> {
                        if (pos + 1 + len > buf.size) throw IOException("DNS label out of bounds")
                        if (sb.isNotEmpty()) sb.append('.')
                        sb.append(String(buf, pos + 1, len, Charsets.US_ASCII))
                        pos += 1 + len
                    }
                }
            }
            offset = next
            return sb.toString() to next
        }
    }
}
