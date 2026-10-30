package com.example.javbrowser

/**
 * Sniffs playable video URL candidates from a video detail page's
 * HTML, in priority order. The caller picks the first candidate that
 * is not an ad (see duration filtering in UnifiedSearchActivity).
 *
 * Order: per-site extractor first (it knows where the site puts its
 * main stream — missav's packer, rou's SSR payload), then the page's
 * own <video>/<source> tags, then loose .m3u8/.mp4 URLs. Generic
 * candidates whose URL carries an ad/preview/trailer/thumb/sample
 * segment are dropped; per-site extractor output is trusted as-is.
 * Candidates carrying the video's code (if known) are moved to the
 * front — the main stream usually carries its ID, while CDN hashed
 * URLs do not, so a miss is only a missed boost, never a rejection.
 * Pure string work, no network, never throws.
 */
object VideoPageSniffer {

    private val mediaTagSrc =
        Regex("""<(?:video|source)[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val looseM3u8 =
        Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""", RegexOption.IGNORE_CASE)
    private val looseMp4 =
        Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""", RegexOption.IGNORE_CASE)
    // Matched as whole URL segments only, so "load" or "download"
    // never false-positive on the "ad" keyword.
    private val adSegment =
        Regex("""(?<![a-z0-9])(ad|ads|preview|trailer|thumb|sample)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

    fun sniffCandidates(html: String, pageUrl: String, code: String?): List<String> {
        if (html.isBlank()) return emptyList()
        // Same normalization the pigav extractor uses: unescape \/ and &amp;
        // so URLs captured from JS strings are directly playable.
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        val out = mutableListOf<String>()
        fun add(url: String?) {
            if (url.isNullOrBlank()) return
            val resolved = resolve(pageUrl, url)
            if (resolved !in out) out += resolved
        }
        // 1. Per-site extractor (trusted as-is).
        val host = hostOf(pageUrl)
        when {
            "missav" in host -> add(VideoExtractor.extractMissAV(html))
            "pigav" in host -> add(VideoExtractor.extractPigAV(html))
            "rouvb1" in host -> add(VideoExtractor.extractRouVideo(html, pageUrl))
            "jable" in host -> add(VideoExtractor.extractJable(html))
            "avjoy" in host -> add(VideoExtractor.extractAvJoy(html))
        }
        // 2. The page's own player sources (playable only, ad-filtered).
        mediaTagSrc.findAll(normalized).forEach { match ->
            val url = resolve(pageUrl, match.groupValues.getOrNull(1).orEmpty())
            if (looksPlayable(url) && !looksAd(url)) add(url)
        }
        // 3. Loose stream URLs (ad-filtered).
        looseM3u8.findAll(normalized).forEach { if (!looksAd(it.value)) add(it.value) }
        looseMp4.findAll(normalized).forEach { if (!looksAd(it.value)) add(it.value) }
        // 4. Code boost: candidates carrying the video code go first.
        val cleanCode = code?.trim().orEmpty()
        if (cleanCode.isEmpty()) return out
        val (withCode, rest) = out.partition { cleanCode in it }
        return withCode + rest
    }

    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }

    private fun looksAd(url: String): Boolean = adSegment.containsMatchIn(url)

    private fun hostOf(pageUrl: String): String =
        try { java.net.URL(pageUrl).host.lowercase() } catch (e: Exception) { "" }

    private fun resolve(pageUrl: String, raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return try { java.net.URL(java.net.URL(pageUrl), raw).toString() } catch (e: Exception) { raw }
    }
}
