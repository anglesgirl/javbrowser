package com.example.javbrowser

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 本地 HTTP Proxy Server
 * 用途：為受 CDN 防盜鏈保護的影片（如 avjoy.me、missav.ai）加上正確的 Request Headers，
 *       讓外部播放器（MX Player / VLC）不需要自行帶 headers 就能正常播放。
 *
 * 針對 HLS (.m3u8)：會自動重寫 m3u8 內所有 URL，使每層請求（master→stream→ts）
 * 都透過 proxy 帶上 Referer/Cookie，解決防盜鏈問題。
 *
 * 使用方式：
 *   val proxy = VideoProxyServer()
 *   proxy.start()
 *   val localUrl = proxy.buildProxyUrl(realVideoUrl, referer, cookies)
 *   // 將 localUrl 傳給外部播放器
 */
class VideoProxyServer(private val authorizeUrl: (String) -> String = { it }) : NanoHTTPD(0) { // port=0 讓系統自動選空閒 port

    companion object {
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
        private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private data class ProxyTarget(
        val realUrl: String,
        val referer: String,
        val cookies: String
    )

    private val proxyTargets = ConcurrentHashMap<String, ProxyTarget>()
    private val upstreamCookies = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    /**
     * One upstream fetch, transport-agnostic: the plain HttpURLConnection
     * path and the ECH OkHttp path both produce this shape, so the
     * response-building code below (status, ROU unwrap, m3u8 rewrite,
     * fixed/chunked body) stays identical for both.
     * [body] is null only for the plain path's >= 400 with no error
     * stream; the caller turns that into a fixed 500 as before.
     * [close] releases the upstream connection (disconnect / close the
     * OkHttp response). The streaming body path deliberately does not
     * close, exactly like the old code (NanoHTTPD drains the stream).
     */
    private class Upstream(
        val code: Int,
        val contentType: String,
        val contentEncoding: String,
        val contentLength: Long,
        val contentRange: String?,
        val acceptRanges: String?,
        val setCookies: List<String>,
        val body: InputStream?,
        val finalUrl: String,
        val via: String,
        val close: () -> Unit,
    )

    /**
     * Shared ECH client for proxy upstream fetches. Shared (not
     * per-request) so video Range requests reuse connections instead of
     * re-handshaking every segment. Built only after [EchH3Router.route]
     * has already verified Conscrypt is up for an ECH host, so the
     * newOkHttpClient plain-fallback trap (Conscrypt unavailable ->
     * silent plain client) cannot fire here: route() returns Ech only
     * when ensureProvider() is true. No call timeout (see fetchViaEch).
     */
    private val echClient: OkHttpClient by lazy {
        EchH3Router.newOkHttpClient(
            connectSec = 15,
            readSec = 30,
            callSec = null,
            followRedirects = true,
            retryOnConnectionFailure = true,
        )
    }

    /**
     * 產生給外部播放器用的本地 URL
     * @param realUrl  真實的 CDN 影片網址
     * @param referer  需要帶的 Referer（例如 https://missav.ai/）
     * @param cookies  從 WebView 讀到的 Cookie 字串（可空）
     */
    fun buildProxyUrl(realUrl: String, referer: String, cookies: String?): String {
        val id = UUID.randomUUID().toString()
        proxyTargets[id] = ProxyTarget(realUrl, referer, cookies ?: "")
        val proxyPath = if (isM3u8Url(realUrl)) "/proxy.m3u8" else "/proxy"
        return "http://127.0.0.1:$listeningPort$proxyPath?id=$id"
    }

    fun healthUrl(): String {
        return "http://127.0.0.1:$listeningPort/health"
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method == Method.OPTIONS) {
                return withCors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK"))
            }

            if (session.uri == "/health") {
                return withCors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK"))
            }

            if (session.uri != "/proxy" && session.uri != "/proxy.m3u8") {
                return withCors(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"))
            }

            val params = session.parameters
            val target = params["id"]?.firstOrNull()
                ?.let { proxyTargets[it] }
                ?: legacyTargetFromQuery(params)
                ?: return withCors(newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing proxy target"))

            val rangeHeader = session.headers["range"]

            withCors(proxyRequest(target.realUrl, target.referer, target.cookies, rangeHeader))
        } catch (e: Exception) {
            withCors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy error: ${e.message}"))
        }
    }

    private fun withCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Origin, Range, Accept, Content-Type, X-Requested-With")
        response.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, Content-Type")
        response.addHeader("Access-Control-Max-Age", "86400")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun legacyTargetFromQuery(params: Map<String, List<String>>): ProxyTarget? {
        val realUrl = params["url"]?.firstOrNull()
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: return null
        val referer = params["referer"]?.firstOrNull()
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
        val cookies = params["cookies"]?.firstOrNull()
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
        return ProxyTarget(realUrl, referer, cookies)
    }

    /**
     * Picks the upstream transport. HTTPS + an ECH config for the host
     * ([EchH3Router.route] == Ech) goes through the ECH client; anything
     * else (plain HTTP, no config, DoH failure) keeps the old
     * HttpURLConnection path byte-for-byte. The gate itself never
     * breaks playback: if DoH fails we cannot know the config, and the
     * ECH client could not have fetched a config either, so plain is
     * the only possible transport anyway.
     */
    private fun fetchUpstream(
        realUrl: String,
        referer: String,
        outboundCookies: String,
        rangeHeader: String?,
        isRouStream: Boolean,
    ): Upstream {
        val host = hostForUrl(realUrl)
        val useEch = host != null &&
            realUrl.startsWith("https://", ignoreCase = true) &&
            runCatching { EchH3Router.route(host) == EchH3Router.Route.Ech }.getOrDefault(false)
        return if (useEch) {
            fetchViaEch(realUrl, referer, outboundCookies, rangeHeader, isRouStream)
        } else {
            fetchViaHttpUrlConnection(realUrl, referer, outboundCookies, rangeHeader, isRouStream)
        }
    }

    /** The pre-ECH transport, unchanged: plain system TLS, visible SNI. */
    private fun fetchViaHttpUrlConnection(
        realUrl: String,
        referer: String,
        outboundCookies: String,
        rangeHeader: String?,
        isRouStream: Boolean,
    ): Upstream {
        val connection = URL(realUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true

            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (referer.isNotEmpty()) connection.setRequestProperty("Referer", referer)
            if (outboundCookies.isNotEmpty()) connection.setRequestProperty("Cookie", outboundCookies)
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "identity;q=1, *;q=0")
            connection.setRequestProperty("Sec-Fetch-Dest", "video")
            connection.setRequestProperty("Sec-Fetch-Mode", "no-cors")
            connection.setRequestProperty("Sec-Fetch-Site", "same-site")
            if (realUrl.contains("doppiocdn", ignoreCase = true) ||
                referer.contains("stripchat", ignoreCase = true)
            ) {
                originForUrl(referer)?.let { connection.setRequestProperty("Origin", it) }
                connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
            }
            // ROU 的 PNG 包裝必須取得完整檔案才能找到 roUd chunk；不要把播放器的
            // Range 要求傳給上游，解包後再一次回傳完整片段。
            if (!rangeHeader.isNullOrEmpty() && !isRouStream) {
                connection.setRequestProperty("Range", rangeHeader)
            }

            val code = connection.responseCode
            return Upstream(
                code = code,
                contentType = connection.contentType ?: "",
                contentEncoding = connection.getHeaderField("Content-Encoding").orEmpty(),
                contentLength = connection.contentLengthLong,
                contentRange = connection.getHeaderField("Content-Range"),
                acceptRanges = connection.getHeaderField("Accept-Ranges"),
                setCookies = connection.headerFields["Set-Cookie"] ?: emptyList(),
                body = if (code >= 400) connection.errorStream else connection.inputStream,
                finalUrl = connection.url.toString(),
                via = "PLAIN",
                close = { connection.disconnect() },
            )
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }

    /**
     * ECH upstream fetch for hosts that publish an ECH config. Same
     * request headers as the plain path, same response shape. NO call
     * timeout: a video stream lasts far longer than any call bound, and
     * the search client's 15s call timeout would kill playback
     * mid-stream — connect/read timeouts mirror the plain path
     * (15s/30s, read is per-read idle, so a flowing stream never trips
     * it). Fail-closed: any transport failure throws to serve()'s 500;
     * the caller never retries this host over plaintext. (The policy's
     * per-host DISABLED for config-less redirect targets is
     * Conscrypt's own design, not a fallback added here: a redirect
     * hop to a host without a config goes plain exactly as before.)
     */
    private fun fetchViaEch(
        realUrl: String,
        referer: String,
        outboundCookies: String,
        rangeHeader: String?,
        isRouStream: Boolean,
    ): Upstream {
        val requestBuilder = Request.Builder().url(realUrl)
            .header("User-Agent", USER_AGENT)
        if (referer.isNotEmpty()) requestBuilder.header("Referer", referer)
        if (outboundCookies.isNotEmpty()) requestBuilder.header("Cookie", outboundCookies)
        requestBuilder.header("Accept", "*/*")
        requestBuilder.header("Accept-Encoding", "identity;q=1, *;q=0")
        requestBuilder.header("Sec-Fetch-Dest", "video")
        requestBuilder.header("Sec-Fetch-Mode", "no-cors")
        requestBuilder.header("Sec-Fetch-Site", "same-site")
        if (realUrl.contains("doppiocdn", ignoreCase = true) ||
            referer.contains("stripchat", ignoreCase = true)
        ) {
            originForUrl(referer)?.let { requestBuilder.header("Origin", it) }
            requestBuilder.header("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
        }
        // ROU 的 PNG 包裝必須取得完整檔案才能找到 roUd chunk；不要把播放器的
        // Range 要求傳給上游，解包後再一次回傳完整片段。
        if (!rangeHeader.isNullOrEmpty() && !isRouStream) {
            requestBuilder.header("Range", rangeHeader)
        }
        val response = echClient.newCall(requestBuilder.build()).execute()
        val body = response.body
        return Upstream(
            code = response.code,
            contentType = response.header("Content-Type") ?: "",
            contentEncoding = response.header("Content-Encoding").orEmpty(),
            contentLength = body.contentLength(),
            contentRange = response.header("Content-Range"),
            acceptRanges = response.header("Accept-Ranges"),
            setCookies = response.headers("Set-Cookie"),
            body = body.byteStream(),
            finalUrl = response.request.url.toString(),
            via = "ECH",
            close = { response.close() },
        )
    }

    private fun proxyRequest(
        requestedUrl: String,
        referer: String,
        cookies: String,
        rangeHeader: String?
    ): Response {
        val realUrl = authorizeUrl(requestedUrl)
        val isRouStream = RouVideoDomains.isUrl(referer)
        val outboundCookies = mergeCookiesForUrl(realUrl, referer, cookies)
        val upstream = fetchUpstream(realUrl, referer, outboundCookies, rangeHeader, isRouStream)
        try {
            val responseCode = upstream.code
            val contentType = upstream.contentType
            val contentEncoding = upstream.contentEncoding
            val contentLength = upstream.contentLength
            val contentRange = upstream.contentRange
            val acceptRanges = upstream.acceptRanges
            rememberSetCookies(realUrl, upstream.setCookies)

            android.util.Log.d(
                "VideoProxy",
                "CDN response=$responseCode type=$contentType length=$contentLength range=${rangeHeader ?: "-"} url=${redactUrlForLog(realUrl)} referer=$referer cookieLen=${outboundCookies.length} via=${upstream.via}"
            )

            val inputStream: InputStream = if (responseCode >= 400) {
                upstream.body ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "CDN error $responseCode"
                )
            } else {
                upstream.body ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Empty upstream body"
                )
            }

            if (responseCode >= 400) {
                val status = Response.Status.lookup(responseCode) ?: Response.Status.INTERNAL_ERROR
                return newChunkedResponse(
                    status,
                    contentType.ifEmpty { MIME_PLAINTEXT },
                    inputStream
                )
            }

            if (isRouStream) {
                val wrappedBytes = inputStream.readBytes()
                val mediaBytes = unwrapRouPngPayload(wrappedBytes) ?: wrappedBytes
                val finalUrl = upstream.finalUrl
                upstream.close()
                val textHead = mediaBytes.take(16).toByteArray().toString(Charsets.UTF_8)
                if (textHead.startsWith("#EXTM3U")) {
                    val playlist = mediaBytes.toString(Charsets.UTF_8)
                    val rewritten = rewriteM3u8(playlist, finalUrl, referer, cookies)
                    val bytes = rewritten.toByteArray(Charsets.UTF_8)
                    android.util.Log.i(
                        "ROU_PLAYER",
                        "decoded playlist wrapped=${mediaBytes !== wrappedBytes} bytes=${bytes.size} url=$finalUrl"
                    )
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "application/x-mpegURL",
                        ByteArrayInputStream(bytes),
                        bytes.size.toLong(),
                    )
                }

                val decodedMime = when {
                    mediaBytes.size >= 12 &&
                        mediaBytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" -> "video/mp4"
                    mediaBytes.firstOrNull() == 0x47.toByte() -> "video/mp2t"
                    else -> "application/octet-stream"
                }
                android.util.Log.d(
                    "ROU_PLAYER",
                    "decoded segment wrapped=${mediaBytes !== wrappedBytes} type=$decodedMime bytes=${mediaBytes.size}"
                )
                return newFixedLengthResponse(
                    Response.Status.OK,
                    decodedMime,
                    ByteArrayInputStream(mediaBytes),
                    mediaBytes.size.toLong(),
                )
            }

            // 如果是 m3u8，重寫內部片段網址，讓後續請求也走本機 proxy
            if (isM3u8Url(realUrl) || isM3u8ContentType(contentType)) {
                val isStripchatStream = realUrl.contains("doppiocdn", ignoreCase = true) ||
                    referer.contains("stripchat", ignoreCase = true)
                val playlistBytes = inputStream.readBytes()
                val isGzipPayload = playlistBytes.size >= 3 &&
                    playlistBytes[0] == 0x1f.toByte() &&
                    playlistBytes[1] == 0x8b.toByte() &&
                    playlistBytes[2] == 0x08.toByte()
                val shouldAttemptGunzip = isStripchatStream &&
                    (contentEncoding.contains("gzip", ignoreCase = true) || isGzipPayload)
                val decompressedContent = if (shouldAttemptGunzip) {
                    runCatching {
                        GZIPInputStream(ByteArrayInputStream(playlistBytes))
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    }.getOrNull()
                } else null
                val rawContent = decompressedContent ?: playlistBytes.toString(Charsets.UTF_8)
                val wasGunzipDecompressed = decompressedContent != null
                upstream.close()
                if (isStripchatStream) {
                    val head = rawContent.take(220).replace('\n', ' ').replace('\r', ' ')
                    android.util.Log.w(
                        "STRIPCHAT_HLS",
                        "proxy m3u8 status=$responseCode type=$contentType encoding=${contentEncoding.ifEmpty { "-" }} " +
                            "gzipMagic=$isGzipPayload decompressed=$wasGunzipDecompressed url=$realUrl head=$head"
                    )
                    if (!rawContent.trimStart().startsWith("#EXTM3U")) {
                        android.util.Log.w("STRIPCHAT_HLS", "proxy response is not a valid m3u8 playlist")
                    }
                    if (rawContent.contains("#EXT-X-MOUFLON", ignoreCase = true)) {
                        android.util.Log.w("STRIPCHAT_HLS", "mouflon playlist detected; playback may require pdkey/pkey handling")
                    }
                }
                val rewritten = rewriteM3u8(rawContent, realUrl, referer, cookies)
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                val m3u8MimeType = "application/x-mpegURL"
                val response = newFixedLengthResponse(
                    Response.Status.OK, m3u8MimeType, ByteArrayInputStream(bytes), bytes.size.toLong()
                )
                return response
            }

            val status = when (responseCode) {
                206 -> Response.Status.PARTIAL_CONTENT
                200 -> Response.Status.OK
                else -> Response.Status.lookup(responseCode) ?: Response.Status.INTERNAL_ERROR
            }

            val response = if (contentLength > 0) {
                newFixedLengthResponse(status, contentType, inputStream, contentLength)
            } else {
                newChunkedResponse(status, contentType, inputStream)
            }

            if (!contentRange.isNullOrEmpty()) response.addHeader("Content-Range", contentRange)
            if (!acceptRanges.isNullOrEmpty()) response.addHeader("Accept-Ranges", acceptRanges)

            return response
        } catch (e: Exception) {
            upstream.close()
            throw e
        }
    }

    // 判斷是否為 m3u8 URL
    private fun isM3u8Url(url: String): Boolean {
        return MediaStreamFormats.isHls(url)
    }

    // 判斷是否為 m3u8 Content-Type
    private fun isM3u8ContentType(contentType: String): Boolean {
        val lower = contentType.lowercase()
        return lower.contains("mpegurl") || lower.contains("x-mpegurl")
    }

    /**
     * ROU 把 HLS manifest 與媒體片段放在 PNG 的自訂 roUd chunk 裡。
     * chunk 第一個 byte 是旗標；bit 0 代表其餘 payload 使用 zlib/deflate 壓縮。
     */
    private fun unwrapRouPngPayload(bytes: ByteArray): ByteArray? {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        )
        if (bytes.size < signature.size || !bytes.copyOfRange(0, signature.size).contentEquals(signature)) {
            return null
        }

        var offset = signature.size
        while (offset + 12 <= bytes.size) {
            val length = ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
            if (length < 1) {
                offset += 12 + length.coerceAtLeast(0)
                continue
            }
            val dataStart = offset + 8
            val dataEnd = dataStart + length
            if (dataEnd + 4 > bytes.size) return null
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (type == "roUd") {
                val flags = bytes[dataStart].toInt() and 0xff
                val payload = bytes.copyOfRange(dataStart + 1, dataEnd)
                return if ((flags and 1) != 0) {
                    runCatching {
                        InflaterInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
                    }.onFailure {
                        android.util.Log.w("ROU_PLAYER", "roUd inflate failed", it)
                    }.getOrNull()
                } else {
                    payload
                }
            }
            offset = dataEnd + 4
        }
        return null
    }

    /**
     * 重寫 m3u8 內容：
     * - 所有非 # 開頭的行（片段 URL / 子播放清單 URL）→ 換成 proxy URL
     * - EXT-X-KEY 和 EXT-X-MAP 裡的 URI="" 也一併替換（加密金鑰 / init segment）
     * - Stripchat Low-Latency HLS 的 EXT-X-PART / PRELOAD-HINT 等 URI 也必須走 proxy
     */
    private fun rewriteM3u8(content: String, baseUrl: String, referer: String, cookies: String): String {
        val lines = content.lines()
        val result = StringBuilder()
        val isStripchatPlaylist = baseUrl.contains("doppiocdn", ignoreCase = true) ||
            referer.contains("stripchat", ignoreCase = true)

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> result.appendLine()

                // Stripchat 的 LL-HLS 片段 URI 位於 tag 屬性內，而不是獨立 URL 行。
                trimmed.startsWith("#EXT-X-KEY") ||
                    trimmed.startsWith("#EXT-X-MAP") ||
                    (isStripchatPlaylist &&
                        trimmed.startsWith("#EXT-X-") &&
                        trimmed.contains("URI=\"")) -> {
                    result.appendLine(rewriteTagUri(trimmed, baseUrl, referer, cookies))
                }

                // 一般 # 開頭的 tag，直接保留
                trimmed.startsWith("#") -> result.appendLine(line)

                // URL 行（相對或絕對），換成 proxy URL
                else -> {
                    val absoluteUrl = resolveUrl(baseUrl, trimmed)
                    result.appendLine(buildProxyUrl(absoluteUrl, referer, cookies))
                }
            }
        }

        return result.toString()
    }

    // 把 #EXT-X-KEY URI="xxx" 裡的 URI 換成 proxy URL
    private fun rewriteTagUri(tag: String, baseUrl: String, referer: String, cookies: String): String {
        return tag.replace(Regex("""URI="([^"]+)"""")) { matchResult ->
            val originalUri = matchResult.groupValues[1]
            val absoluteUri = resolveUrl(baseUrl, originalUri)
            val proxyUri = buildProxyUrl(absoluteUri, referer, cookies)
            """URI="$proxyUri""""
        }
    }

    // 將相對 URL 解析為絕對 URL
    private fun resolveUrl(baseUrl: String, relative: String): String {
        if (JavrateStreams.isSigned(baseUrl)) return JavrateStreams.resolve(baseUrl, relative)
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return try {
            URI(baseUrl).resolve(relative).toString()
        } catch (e: Exception) {
            val base = baseUrl.substringBeforeLast("/") + "/"
            base + relative
        }
    }

    private fun mergeCookiesForUrl(realUrl: String, referer: String, pageCookies: String): String {
        val host = hostForUrl(realUrl) ?: return pageCookies
        val refererHost = hostForUrl(referer)
        val jarCookies = upstreamCookies[host]
            ?.values
            ?.filter { it.isNotBlank() }
            ?.joinToString("; ")
            .orEmpty()

        // Do not leak page cookies cross-domain. A browser visiting missav.ai would not send
        // missav cookies to surrit.com, and Cloudflare may reject that abnormal request.
        val sameHostPageCookies = if (refererHost == host) pageCookies else ""

        return listOf(sameHostPageCookies, jarCookies)
            .filter { it.isNotBlank() }
            .joinToString("; ")
    }

    private fun rememberSetCookies(realUrl: String, setCookies: List<String>?) {
        if (setCookies.isNullOrEmpty()) return
        val host = hostForUrl(realUrl) ?: return
        val hostCookies = upstreamCookies.getOrPut(host) { ConcurrentHashMap() }

        setCookies.forEach { header ->
            val cookiePair = header.substringBefore(";").trim()
            val name = cookiePair.substringBefore("=", "")
            if (name.isNotBlank() && cookiePair.contains("=")) {
                hostCookies[name] = cookiePair
            }
        }
    }

    private fun hostForUrl(url: String): String? {
        return try {
            URI(url).host?.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    private fun originForUrl(url: String): String? {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (e: Exception) {
            null
        }
    }

    private fun redactUrlForLog(url: String): String {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: return url.take(160)
            val host = uri.host ?: return url.take(160)
            val port = if (uri.port != -1) ":${uri.port}" else ""
            val path = uri.path ?: ""
            val queryMarker = if (uri.query != null) "?..." else ""
            "$scheme://$host$port$path$queryMarker"
        } catch (e: Exception) {
            url.take(160)
        }
    }
}
