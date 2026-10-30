package com.example.javbrowser

/**
 * Sniffs a playable video URL from a video detail page's HTML.
 *
 * Generic first, because the page's own player must reference its
 * stream somewhere: <video>/<source> tags, then per-site extractors
 * for packed/obfuscated pages (missav's packer, rou's SSR payload),
 * then loose .m3u8/.mp4 URLs as a last resort. Returns null when
 * nothing playable is found — the caller falls back to WebView.
 * Pure string work, no network, never throws. Per-site extractor
 * output is trusted as-is (it returns null when it finds nothing);
 * generic matches must look like a stream (.m3u8/.mp4) so a
 * subtitle, image, or audio tag is never mistaken for video.
 */
object VideoPageSniffer {

    private val mediaTagSrc =
        Regex("""<(?:video|source)[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val looseM3u8 =
        Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""", RegexOption.IGNORE_CASE)
    private val looseMp4 =
        Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""", RegexOption.IGNORE_CASE)

    fun sniff(html: String, pageUrl: String): String? {
        if (html.isBlank()) return null
        // Same normalization the pigav extractor uses: unescape \/ and &amp;
        // so URLs captured from JS strings are directly playable.
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        // 1. The page's own player source — most reliable.
        mediaTagSrc.findAll(normalized).forEach { match ->
            val raw = match.groupValues.getOrNull(1).orEmpty()
            val url = resolve(pageUrl, raw)
            if (looksPlayable(url)) return url
        }
        // 2. Per-site extractors for packed/obfuscated pages. They get
        // the original HTML (they do their own normalization as
        // needed — normalizing first could break packer unpacking).
        val host = hostOf(pageUrl)
        val extracted = when {
            "missav" in host -> VideoExtractor.extractMissAV(html)
            "pigav" in host -> VideoExtractor.extractPigAV(html)
            "rouvb1" in host -> VideoExtractor.extractRouVideo(html, pageUrl)
            "jable" in host -> VideoExtractor.extractJable(html)
            "avjoy" in host -> VideoExtractor.extractAvJoy(html)
            else -> null
        }
        if (extracted != null) return resolve(pageUrl, extracted)
        // 3. Loose stream URLs anywhere in the page.
        looseM3u8.find(normalized)?.groupValues?.getOrNull(0)?.let { return it }
        looseMp4.find(normalized)?.groupValues?.getOrNull(0)?.let { return it }
        return null
    }

    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }

    private fun hostOf(pageUrl: String): String =
        try { java.net.URL(pageUrl).host.lowercase() } catch (e: Exception) { "" }

    private fun resolve(pageUrl: String, raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return try { java.net.URL(java.net.URL(pageUrl), raw).toString() } catch (e: Exception) { raw }
    }
}
