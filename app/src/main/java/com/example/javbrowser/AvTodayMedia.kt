package com.example.javbrowser

import java.net.URI

internal object AvTodayMedia {
    fun isPage(url: String) = AdditionalSiteMedia.hostMatches(url, "avtoday.io")

    fun accepts(page: String, player: String, media: String): Boolean = runCatching {
        val pageUri = URI(page)
        val playerUri = URI(player)
        val mediaUri = URI(media)
        isPage(page) && Regex("^/(?:[a-zA-Z-]+/)?video/[^/]+\\.html$").matches(pageUri.path.orEmpty()) &&
            isPage(player) && playerUri.path == "/player" &&
            playerUri.rawQuery.orEmpty().split('&').any { it.startsWith("s=") && it.length > 2 } &&
            SupjavSourceSession.origin(media) != null && mediaUri.userInfo == null &&
            (mediaUri.path.orEmpty().endsWith(".mp4", true) || MediaStreamFormats.isHls(media))
    }.getOrDefault(false)
}
