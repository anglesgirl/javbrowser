package com.example.javbrowser

import java.net.URI

object JavrateSubtitles {
    fun isAllowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            uri.host.equals("subtitle.avking.xyz", true) && uri.path.endsWith(".vtt", true)
    }.getOrDefault(false)
}
