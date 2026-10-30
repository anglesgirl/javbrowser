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
 * Endpoints are Ali + 360 only (the user's standing DoH choice). Both are
 * reached by pinned IP + SNI (the DoH hostname), never through the system
 * resolver: the DoH client below overrides DNS for exactly those two hosts.
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

    private data class DohEndpoint(val url: String, val pinnedIps: List<String>)

    // Ali + 360 only. Pinned IPs are the providers' anycast / documented
    // addresses; OkHttp still verifies the certificate against the DoH
    // hostname, and SNI is the hostname (OkHttp passes the URL host to
    // createSocket), so dialing a pinned IP does not weaken TLS.
    private val ENDPOINTS = listOf(
        DohEndpoint("https://dns.alidns.com/dns-query", listOf("223.5.5.5", "223.6.6.6")),
        DohEndpoint("https://doh.360.cn/dns-query", listOf("101.198.193.29", "101.198.192.33")),
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
    )

    private class CacheEntry(val records: HostRecords, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val random = SecureRandom()

    /** ECHConfigList bytes for [host], or null when the host publishes none. */
    fun configFor(host: String): ByteArray? = resolve(host).echConfigList

    fun invalidate(host: String) {
        cache.remove(host.lowercase())
    }

    fun resolve(host: String): HostRecords {
        val key = host.lowercase()
        cache[key]?.let { if (System.currentTimeMillis() < it.expiresAtMs) return it.records }
        for (endpoint in ENDPOINTS) {
            try {
                val (records, minTtl) = resolveFromEndpoint(endpoint, host)
                if (records.resolvedViaDoh) {
                    val ttlMs = when {
                        records.echConfigList == null && records.addresses.isEmpty() -> 60_000L
                        else -> minTtl.coerceIn(30, 600) * 1000
                    }
                    cache[key] = CacheEntry(records, System.currentTimeMillis() + ttlMs)
                    Log.d(
                        TAG,
                        "resolved $host via ${endpoint.url}: " +
                            "addrs=${records.addresses.size} ech=${records.echConfigList?.size ?: 0}B",
                    )
                    return records
                }
            } catch (e: Exception) {
                Log.w(TAG, "DoH via ${endpoint.url} failed for $host: ${e.message}")
            }
        }
        val empty = HostRecords(emptyList(), null, false)
        cache[key] = CacheEntry(empty, System.currentTimeMillis() + 30_000L)
        return empty
    }

    private fun resolveFromEndpoint(endpoint: DohEndpoint, host: String): Pair<HostRecords, Long> {
        var ech: ByteArray? = null
        val collected = mutableListOf<InetAddress>()
        var minTtl = 300L
        var dohOk = false
        // HTTPS chain: ServiceMode records carry the ech param directly;
        // AliasMode (priority 0) and plain CNAMEs point at the next name.
        var name = host
        for (hop in 0..MAX_CHAIN_HOPS) {
            val msg = query(endpoint, name, TYPE_HTTPS) ?: break
            dohOk = true
            minTtl = minOf(minTtl, msg.minTtlSeconds)
            if (msg.echConfigList != null) {
                ech = msg.echConfigList
                break
            }
            val next = msg.aliasTarget ?: msg.cnameTarget ?: break
            if (next.equals(name, ignoreCase = true)) break
            name = next
        }
        // Address chains (A and AAAA are independent CNAME chains).
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
