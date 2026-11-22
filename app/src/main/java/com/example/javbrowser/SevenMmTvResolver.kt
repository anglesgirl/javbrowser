package com.example.javbrowser

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 7mmtv 原生直取：详情页 HTML -> play.php id -> play.php -> 正片 m3u8。
 *
 * 链路（2026-11-22 沙箱实测打通，HUNTC-649）：
 * 1. 详情页 HTML 里 mvarr['42_1'] 的 token 是混淆过的 play.php id：
 *    按分隔符切分、每段当 base-radix 数、异或 xor、拼成 base64，
 *    再 AES-128-CBC 解密（key/iv 在页内 inline script 里，动态取，
 *    取不到用当前站方值兜底）。分隔符、radix、xor、key、iv 全部从
 *    页里现取，不写死（站方换值也不怕；同名干扰变量用精确名排除）。
 * 2. GET https://<host>/assets/js/play/play.php?id=<id>，HTML 里直接有
 *    streamsuperpro 的 .m3u8（/32/1/ 与 /32/2/，同片 115 分钟）。
 * 3. growcdnssedge 的 .m3u8 不在 play.php 里；有就优先，没有就用
 *    streamsuperpro（已验证的 7mmtv 规则：media-hls 优先、长时长）。
 *
 * 全程走原生 OkHttp（与搜寻同一条原生网络栈），不经 WebView、
 * 不经 Go 代理、不带 Referer（7mmtv 拉流本来就不带 Referer）。
 * 纯函数 + 网络取数；永不抛异常，失败返回空列表。
 */
object SevenMmTvResolver {

    // mvarr 条目：mvarr['KEY']=[['iframeId','token','prefix','baseUrl',...],];
    private val mvarrEntry = Regex("""mvarr\['([^']+)'\]=\[\['[^']*','([^']*)'""")
    private val playPhpBase = Regex("""'([^']*play\.php\?id=[^']*)'""")
    private val radixRe = Regex("""hcdeedg252\s*=\s*(\d+)""")
    private val xorRe = Regex("""hadeedg252\s*=\s*(\d+)""")
    private val keyRe = Regex("""argdeqweqweqwe\s*=\s*'([^']+)'""")
    private val ivRe = Regex("""hdddedg252\s*=\s*'([^']+)'""")
    private val looseM3u8 =
        Regex("""https?://[^"'\s<>]+\.m3u8(?=[?#]|["'\s<>]|$)(?:[?#][^"'\s<>]*)?""", RegexOption.IGNORE_CASE)

    // 当前站方值（2026-11-22 实测）；动态提取优先，取不到才用兜底。
    private const val DEFAULT_RADIX = 21
    private const val DEFAULT_XOR = 22
    private const val DEFAULT_KEY = "7c777d550ccc654e"
    private const val DEFAULT_IV = "d94f3d176115d0e5"

    private const val MIN_FEATURE_SECONDS = 60.0
    private const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024

    data class DecodeParams(val radix: Int, val xor: Int, val key: String, val iv: String)

    fun extractParams(html: String): DecodeParams {
        val radix = radixRe.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: DEFAULT_RADIX
        val xor = xorRe.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: DEFAULT_XOR
        val key = keyRe.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: DEFAULT_KEY
        val iv = ivRe.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: DEFAULT_IV
        return DecodeParams(radix, xor, key, iv)
    }

    /** JS parseInt(s, radix) 语义：取最长合法前缀，无合法前缀返回 0。 */
    private fun parseIntJsPrefix(s: String, radix: Int): Int {
        var value = 0
        var any = false
        for (ch in s) {
            val d = Character.digit(ch, radix)
            if (d < 0) break
            value = value * radix + d
            any = true
        }
        return if (any) value else 0
    }

    /** token -> base64（分隔符切分、base-radix、异或），再 AES-128-CBC 解密出 play.php id。失败返回 null。 */
    fun decodeToken(token: String, params: DecodeParams): String? {
        return try {
            val radix = if (params.radix <= 25) params.radix else params.radix % 25
            val splitChar = (radix + 97).toChar()
            val b64 = token.split(splitChar).joinToString("") { part ->
                val v = parseIntJsPrefix(part, radix)
                (v xor params.xor).toChar().toString()
            }
            if (b64.isBlank()) return null
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(params.key.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(params.iv.toByteArray(Charsets.UTF_8))
            )
            val plain = cipher.doFinal(Base64.decode(b64, Base64.DEFAULT))
            String(plain, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** 从详情页 HTML 取 play.php id（token 解码）。失败返回 null。 */
    fun extractPlayId(detailHtml: String): String? {
        val baseMatch = playPhpBase.find(detailHtml) ?: return null
        val basePos = baseMatch.range.first
        // play.php 条目是 base URL 之前最近的那个 mvarr 条目
        // （token 在同一条目里、base URL 之前）。
        val entry = mvarrEntry.findAll(detailHtml)
            .filter { it.range.first < basePos }
            .lastOrNull() ?: return null
        val token = entry.groupValues.getOrNull(2).orEmpty()
        if (token.isBlank()) return null
        return decodeToken(token, extractParams(detailHtml))
    }

    /** play.php 的 base URL（// 开头补 https:）。 */
    fun extractPlayBase(detailHtml: String): String? {
        val raw = playPhpBase.find(detailHtml)?.groupValues?.getOrNull(1) ?: return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> "https://$raw"
        }
    }

    /** 7mmtv .m3u8 优先级：0=media-hls.growcdnssedge.com，1=streamsuperpro，其余=2。 */
    fun m3u8Priority(url: String): Int {
        val host = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
        return when {
            host == "media-hls.growcdnssedge.com" || host.endsWith(".media-hls.growcdnssedge.com") -> 0
            host.contains("streamsuperpro.com") -> 1
            else -> 2
        }
    }

    /**  summed #EXTINF 时长；取不到返回 null（null 永不排除候选）。 */
    private fun measureM3u8Duration(url: String): Double? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = Request.Builder().url(url)
                .header("User-Agent", SearchHttpFetcher.USER_AGENT)
                .header("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val source = response.body?.source() ?: return null
                source.request(MAX_PLAYLIST_BYTES + 1)
                if (source.buffer.size > MAX_PLAYLIST_BYTES) return null
                val body = source.readByteArray().toString(Charsets.UTF_8)
                var total = 0.0
                var sawExtinf = false
                for (line in body.lines()) {
                    if (line.startsWith("#EXTINF:")) {
                        val duration = line.removePrefix("#EXTINF:").substringBefore(",").toDoubleOrNull()
                        if (duration != null) {
                            total += duration
                            sawExtinf = true
                        }
                    }
                }
                if (sawExtinf) total else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 全链路：详情页 HTML -> play.php -> 排好序的 m3u8 候选。
     * growcdnssedge 优先，其次 streamsuperpro；<60 秒的短 m3u8 按
     * 既有规则排除（时长取不到时保留）。失败返回空列表。
     * 调用方在 worker 线程调（内部有网络请求）。
     */
    fun resolve(detailHtml: String, site: SearchSite): List<VideoPageSniffer.ScoredCandidate> {
        return try {
            val playId = extractPlayId(detailHtml) ?: return emptyList()
            val base = extractPlayBase(detailHtml) ?: return emptyList()
            val playUrl = base + playId
            val response = SearchHttpFetcher().fetch(playUrl, SearchCancellation(), site)
            if (response.transportError != null || response.statusCode !in 200..299 || response.body.isBlank()) {
                return emptyList()
            }
            val normalized = response.body.replace("\\/", "/").replace("&amp;", "&")
            val seen = mutableSetOf<String>()
            val urls = mutableListOf<String>()
            looseM3u8.findAll(normalized).forEach { m ->
                if (seen.add(m.value)) urls += m.value
            }
            urls.sortedBy { m3u8Priority(it) }.mapNotNull { url ->
                val duration = measureM3u8Duration(url)
                if (duration != null && duration < MIN_FEATURE_SECONDS) return@mapNotNull null
                val (score, evidence) = when (m3u8Priority(url)) {
                    0 -> 30 to "7mmtv直取 · growcdnssedge"
                    1 -> 20 to "7mmtv直取 · streamsuperpro"
                    else -> 10 to "7mmtv直取 · play.php"
                }
                val fullEvidence = if (duration != null) {
                    "$evidence · m3u8 ${(duration / 60).toInt()}:${(duration % 60).toInt().toString().padStart(2, '0')}"
                } else evidence
                VideoPageSniffer.ScoredCandidate(url, "7mmtv直取", score, fullEvidence, true, null)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
