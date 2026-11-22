package com.example.javbrowser

/**
 * AvJoy 原生直取：详情页 HTML -> <video> 的 <source> 直链 MP4。
 *
 * 规则（2026-11-23，用户抓包日志 ROYD-368 + 沙箱实测详情页 HTML，
 * 见 sniff-rules-avjoy.md）：
 * 1. 正片是直链 MP4，不是 HLS。详情页 HTML 里两个 <source>：
 *      https://media-cdn3.avjoy.me/av1/<token>/<ts>/<id>_720p.mp4   # AV1，排第一
 *      https://media-cdn3.avjoy.me/video/<token>/<ts>/<id>_720p.mp4 # H.264，排第二
 *    优先用 /video/（H.264，兼容性最好），取不到再用 /av1/。
 * 2. growcdnssedge.com 的两条 m3u8 不是正片：id 与本页完全无关，
 *    是站内其他视频的预览/广告。只看“有 m3u8”会抓错——本解析器
 *    根本不找 m3u8，只读 <source> 的 MP4。
 * 3. token 与过期时间戳每次都变，必须现取现用，不可硬编码。
 * 4. 不用 Referer（全部请求 Referer 为空，直链直接播）。
 *
 * 全程走原生 OkHttp（与搜寻同一条原生网络栈），不经 WebView。
 * AvJoy 不在 CF 上，搜寻走明文即可；解析复用 SearchHttpFetcher
 * 同栈，顺带走现有路由。纯函数 + 网络取数；永不抛异常，
 * 失败返回空列表。
 */
object AvJoyResolver {

    // <source ... src="...">（单/双引号都认，大小写不敏感）。
    private val sourceTag =
        Regex("""<source[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** AvJoy MP4 优先级：0=/video/（H.264），1=/av1/（AV1），其余 avjoy.me MP4=2。 */
    fun mp4Priority(url: String): Int {
        val path = runCatching { java.net.URL(url).path.lowercase() }.getOrDefault("")
        return when {
            path.contains("/video/") -> 0
            path.contains("/av1/") -> 1
            else -> 2
        }
    }

    /** 只认 avjoy.me 域名下的 .mp4（query 之前算）。 */
    private fun isAvJoyMp4(url: String): Boolean {
        val host = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
        if (host != "avjoy.me" && !host.endsWith(".avjoy.me")) return false
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".mp4")
    }

    /**
     * 从详情页 HTML 取 <source> 直链 MP4，按 /video/ > /av1/ 排好序。
     * 调用方在 worker 线程调。失败返回空列表。
     */
    fun resolve(detailHtml: String, site: SearchSite): List<VideoPageSniffer.ScoredCandidate> {
        return try {
            val seen = mutableSetOf<String>()
            val urls = mutableListOf<String>()
            sourceTag.findAll(detailHtml).forEach { m ->
                val raw = m.groupValues.getOrNull(1).orEmpty().trim()
                val url = when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    else -> return@forEach
                }
                if (isAvJoyMp4(url) && seen.add(url)) urls += url
            }
            urls.sortedBy { mp4Priority(it) }.map { url ->
                val (score, evidence) = when (mp4Priority(url)) {
                    0 -> 30 to "AvJoy直取 · H.264"
                    1 -> 20 to "AvJoy直取 · AV1"
                    else -> 10 to "AvJoy直取 · MP4"
                }
                VideoPageSniffer.ScoredCandidate(url, "AvJoy直取", score, evidence, true, null)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
