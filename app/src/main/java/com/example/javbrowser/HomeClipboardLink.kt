package com.example.javbrowser

import java.net.URI

internal object HomeClipboardLink {
    data class Match(val url: String, val site: String)

    private val sites = mapOf(
        "missav.ai" to "MissAV", "missav.ws" to "MissAV", "missav.com" to "MissAV",
        "jable.tv" to "Jable", "avjoy.me" to "AvJoy",
        "pigav.ws" to "PigAV", "pigav.com" to "PigAV", "avtoday.io" to "AVToday",
        "javhdporn.net" to "JavHDPorn", "7mmtv.sx" to "7MMTV", "7mmtv.tv" to "7MMTV",
        "avple.tv" to "Avple", "whos.tv" to "Whos.tv", "javday.app" to "JAVDAY",
        "supjav.com" to "SUPJAV", "javrate.com" to "JavRate", "javdb.com" to "JavDB",
        "javtrailers.com" to "JavTrailers", "stripchat.com" to "Stripchat",
        "pornhub.com" to "Pornhub", "xvideos.com" to "XVideos"
    )

    fun find(text: String, configuredSites: Map<String, String> = emptyMap()): Match? {
        val allowed = sites + configuredSites.mapKeys { it.key.lowercase().trimEnd('.') }
        return Regex("https?://[^\\s<>\"'\\[\\]「」『』]+", RegexOption.IGNORE_CASE)
            .findAll(text.take(16000)).mapNotNull { candidate ->
                val url = candidate.value.trimEnd('.', ',', ';', '!', ')', '，', '。', '；', '！', '）')
                val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
                val host = uri.host?.lowercase()?.trimEnd('.') ?: return@mapNotNull null
                if (uri.userInfo != null || uri.scheme.lowercase() !in setOf("http", "https")) return@mapNotNull null
                val site = allowed.entries.firstOrNull { (domain, _) ->
                    domain.isNotBlank() && (host == domain || host.endsWith(".$domain"))
                }?.value ?: if (RouVideoDomains.isHost(host)) "Rou.Video" else return@mapNotNull null
                Match(url, site)
            }.firstOrNull()
    }
}
