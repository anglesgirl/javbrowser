package com.example.javbrowser

import java.net.URI

internal object MediaStreamFormats {
    fun isHls(url: String): Boolean = runCatching {
        val path = URI(url).path.orEmpty().lowercase()
        path.endsWith(".m3u8") || path.endsWith(".m3u") ||
            RouVideoStreams.isPlaylist(url) ||
            (Regex("/hls\\d*/").containsMatchIn(path) && Regex("/(master|index|playlist|iframes)(?:-[a-z0-9]+)*\\.txt$").containsMatchIn(path))
    }.getOrDefault(false)

    fun isStreamtapeVideo(url: String): Boolean = runCatching {
        val uri = URI(url)
        (AdditionalSiteMedia.hostMatches(url, "streamtape.com") || AdditionalSiteMedia.hostMatches(url, "streamtape.to")) &&
            uri.path == "/get_video" && !uri.rawQuery.isNullOrBlank()
    }.getOrDefault(false)
}
