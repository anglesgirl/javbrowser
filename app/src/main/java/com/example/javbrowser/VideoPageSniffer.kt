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
    // The extension must terminate the path (end of URL, or a ?/#
    // query/fragment follows). Without this, video-snapshot thumbnails
    // such as "...Episode-1.mp4_snapshot_05.21_....webp" match as "mp4"
    // candidates and get fed to the internal player, which then cannot
    // load (it is an image, not a video). Real stream URLs end in
    // .mp4/.m3u8 or carry ?/# parameters, so they still match.
    private val looseM3u8 =
        Regex("""https?://[^"'\s<>]+\.m3u8(?=[?#]|["'\s<>]|$)(?:[?#][^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
    private val looseMp4 =
        Regex("""https?://[^"'\s<>]+\.mp4(?=[?#]|["'\s<>]|$)(?:[?#][^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
    // Matched as whole URL segments only, so "load" or "download"
    // never false-positive on the "ad" keyword.
    private val adSegment =
        Regex("""(?<![a-z0-9])(ad|ads|preview|trailer|thumb|sample|snapshot)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

    /**
     * One candidate with its score and the reason it was kept or dropped.
     * Scores are informational in v1 (the picker still lists in the old
     * order) — their real job is the playback-data record, so a later
     * analysis can see which signal actually predicted a real play.
     */
    data class ScoredCandidate(
        val url: String,
        val source: String, // 站內解析 / video標籤 / 寬鬆匹配
        val score: Int,
        val evidence: String,
        val kept: Boolean,
        val dropReason: String?,
    )

    fun sniffCandidates(html: String, pageUrl: String, code: String?): List<String> =
        sniffScored(html, pageUrl, code).filter { it.kept }.map { it.url }

    fun sniffScored(html: String, pageUrl: String, code: String?): List<ScoredCandidate> {
        if (html.isBlank()) return emptyList()
        // Same normalization the pigav extractor uses: unescape \/ and &amp;
        // so URLs captured from JS strings are directly playable.
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        val out = mutableListOf<ScoredCandidate>()
        val seen = mutableSetOf<String>()
        fun add(url: String?, source: String, score: Int, evidence: String, kept: Boolean, dropReason: String?) {
            if (url.isNullOrBlank()) return
            val resolved = resolve(pageUrl, url)
            if (resolved in seen) return
            seen += resolved
            out += ScoredCandidate(resolved, source, score, evidence, kept, dropReason)
        }
        val cleanCode = code?.trim().orEmpty()
        fun boost(score: Int, url: String, evidence: String): Pair<Int, String> =
            if (cleanCode.isNotEmpty() && cleanCode in url) score + 15 to "$evidence · 含番號"
            else score to evidence
        // 1. Per-site extractor (trusted as-is).
        val host = hostOf(pageUrl)
        val extracted: String? = when {
            "missav" in host -> VideoExtractor.extractMissAV(html)
            "pigav" in host -> VideoExtractor.extractPigAV(html)
            "rouvb1" in host -> VideoExtractor.extractRouVideo(html, pageUrl)
            "jable" in host -> VideoExtractor.extractJable(html)
            "avjoy" in host -> VideoExtractor.extractAvJoy(html)
            else -> null
        }
        extracted?.let {
            val (score, evidence) = boost(30, resolve(pageUrl, it), "站內解析")
            add(it, "站內解析", score, evidence, true, null)
        }
        // 2. The page's own player sources (playable only, ad-filtered).
        mediaTagSrc.findAll(normalized).forEach { match ->
            val url = resolve(pageUrl, match.groupValues.getOrNull(1).orEmpty())
            val (score, evidence) = boost(20, url, "video標籤")
            when {
                !looksPlayable(url) -> add(url, "video標籤", score, evidence, false, "不是mp4/m3u8")
                looksAd(url) -> add(url, "video標籤", score, evidence, false, "含廣告詞")
                else -> add(url, "video標籤", score, evidence, true, null)
            }
        }
        // 3. Loose stream URLs (ad-filtered).
        looseM3u8.findAll(normalized).forEach {
            val (score, evidence) = boost(10, it.value, "寬鬆匹配")
            if (looksAd(it.value)) add(it.value, "寬鬆匹配", score, evidence, false, "含廣告詞")
            else add(it.value, "寬鬆匹配", score, evidence, true, null)
        }
        looseMp4.findAll(normalized).forEach {
            val (score, evidence) = boost(10, it.value, "寬鬆匹配")
            if (looksAd(it.value)) add(it.value, "寬鬆匹配", score, evidence, false, "含廣告詞")
            else add(it.value, "寬鬆匹配", score, evidence, true, null)
        }
        // 4. Code boost: candidates carrying the video code go first.
        // (The +15 above already applied; this keeps the old ordering.)
        if (cleanCode.isEmpty()) return out
        val (withCode, rest) = out.partition { cleanCode in it.url }
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
