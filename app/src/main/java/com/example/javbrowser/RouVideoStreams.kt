package com.example.javbrowser

import java.net.URI
import java.net.URLDecoder

/** ROU uses the same endpoint for HLS and thumbnail VTT; only the default kind is video. */
internal object RouVideoStreams {
    private val pagePath = Regex("^/v/([A-Za-z0-9_-]{6,64})/?$")
    private val playlistPath = Regex("^/api/hls/[A-Za-z0-9_-]{6,64}$")

    fun playlistForPage(pageUrl: String): String? = runCatching {
        val page = URI(pageUrl)
        if (!isRouHttpUrl(page)) return null
        val id = pagePath.matchEntire(page.path.orEmpty())?.groupValues?.get(1) ?: return null
        page.resolve("/api/hls/$id").toASCIIString()
    }.getOrNull()

    fun isPlaylist(url: String): Boolean = runCatching {
        val uri = URI(url)
        isRouHttpUrl(uri) && playlistPath.matches(uri.path.orEmpty()) &&
            uri.rawQuery.orEmpty().split('&').none { part ->
                URLDecoder.decode(part.substringBefore('='), "UTF-8").equals("kind", ignoreCase = true)
            }
    }.getOrDefault(false)

    fun accepts(pageUrl: String, candidate: String): Boolean = runCatching {
        val expected = URI(playlistForPage(pageUrl) ?: return false)
        val actual = URI(candidate)
        isPlaylist(candidate) && actual.scheme.equals(expected.scheme, ignoreCase = true) &&
            actual.host.equals(expected.host, ignoreCase = true) && actual.port == expected.port &&
            actual.path == expected.path
    }.getOrDefault(false)

    private fun isRouHttpUrl(uri: URI): Boolean =
        (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
            uri.userInfo == null && RouVideoDomains.isHost(uri.host.orEmpty())
}
