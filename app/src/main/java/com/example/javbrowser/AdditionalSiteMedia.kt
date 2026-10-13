package com.example.javbrowser

import java.net.URI

/** Keep advertising streams and serialized episode lists out of the native player. */
object AdditionalSiteMedia {
    fun hostMatches(url: String, domain: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase().orEmpty()
        host == domain || host.endsWith(".$domain")
    }.getOrDefault(false)

    fun isPage(url: String): Boolean = hostMatches(url, "javday.app") || hostMatches(url, "supjav.com") || hostMatches(url, "javrate.com")

    fun isMedia(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme in setOf("http", "https") && !url.contains('$') &&
            Regex("\\.(m3u8|mp4)$", RegexOption.IGNORE_CASE).containsMatchIn(uri.path.orEmpty())
    }.getOrDefault(false)

    fun accepts(page: String, media: String, referer: String): Boolean {
        if (!isMedia(media)) return false
        return when {
            hostMatches(page, "javday.app") -> hostMatches(media, "javday.homes") || hostMatches(media, "javday.app")
            hostMatches(page, "supjav.com") -> hostMatches(referer, "fc2stream.tv") ||
                hostMatches(referer, "supremejav.com")
            hostMatches(page, "javrate.com") -> JavrateStreams.isSigned(media) &&
                hostMatches(referer, "javrate.com")
            else -> false
        }
    }
}
