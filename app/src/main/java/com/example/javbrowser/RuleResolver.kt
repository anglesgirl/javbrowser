package com.example.javbrowser

/**
 * 规则解析（1.2.27）：按规则中心给的 SiteRule 从详情页 HTML 提正片地址。
 *
 * regex 类（regex-mp4 / regex-m3u8）：按 patterns 顺序取第一个匹配
 * 地址（patterns 本身就是优先级，如 AvJoy 先 /video/ H.264 再
 * /av1/）。归一化与 VideoPageSniffer 同款（\/ 与 &amp;），免得
 * JS 字符串里的转义地址匹配不上。只返回候选；过滤（m3u8 时长、
 * 去重、广告聚类）复用 UnifiedSearchActivity.filterSniffCandidates，
 * 与 7mmtv 同一条路。
 *
 * native 类：转给内置解析器；未知解析器名返回空（回浏览器）。
 */
object RuleResolver {

    fun resolveRegex(html: String, rule: SiteRule): List<VideoPageSniffer.ScoredCandidate> {
        if (html.isBlank() || rule.patterns.isEmpty()) return emptyList()
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        for (pattern in rule.patterns) {
            val regex = runCatching { Regex(pattern) }.getOrNull() ?: continue
            val match = regex.find(normalized)?.value?.trim().orEmpty()
            if (match.isBlank()) continue
            return listOf(
                VideoPageSniffer.ScoredCandidate(
                    url = match,
                    source = "rule-${rule.host}",
                    score = 50,
                    evidence = "规则 ${rule.type}",
                    kept = true,
                    dropReason = null,
                )
            )
        }
        return emptyList()
    }

    fun resolveNative(html: String, site: SearchSite, rule: SiteRule): List<VideoPageSniffer.ScoredCandidate> {
        return when (rule.resolver) {
            "SevenMmTvResolver" -> SevenMmTvResolver.resolve(html, site)
            else -> emptyList()
        }
    }
}
