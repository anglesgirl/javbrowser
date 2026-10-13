package com.example.javbrowser

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Player trust belongs to the selected Server's frame chain, not a fixed hosting-company list. */
internal class SupjavSourceSession(val page: String, val embed: String, private val blocked: List<String> = emptyList()) {
    private val playerOrigins = ConcurrentHashMap.newKeySet<String>()
    init { registerPlayer(embed) }

    fun registerPlayer(url: String): Boolean {
        val origin = origin(url) ?: return false
        if (isBlocked(url)) return false
        playerOrigins.add(origin)
        return true
    }

    fun trustsPlayer(url: String): Boolean = origin(url)?.let { it in playerOrigins } == true

    fun accepts(media: String, referer: String): Boolean {
        val refererOrigin = origin(referer) ?: return false
        return (AdditionalSiteMedia.isMedia(media) || MediaStreamFormats.isHls(media) || MediaStreamFormats.isStreamtapeVideo(media)) &&
            !runCatching { Regex("^/(e|embed)/").containsMatchIn(URI(media).path.orEmpty()) }.getOrDefault(true) &&
            origin(media) != null && !isBlocked(media) && refererOrigin in playerOrigins
    }

    fun isBlocked(url: String): Boolean = blocked.any { it.isNotBlank() && url.contains(it, true) } ||
        runCatching { Regex("/(?:ads?|preroll|vast)(?:/|[._-]|$)", RegexOption.IGNORE_CASE).containsMatchIn(URI(url).path.orEmpty()) }.getOrDefault(true)

    companion object {
        fun origin(url: String): String? = runCatching {
            val uri = URI(url)
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) null
            else uri.scheme.lowercase() + "://" + uri.host.lowercase() + ":" +
                (if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80)
        }.getOrNull()
    }
}
