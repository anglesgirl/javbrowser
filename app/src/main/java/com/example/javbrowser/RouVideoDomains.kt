package com.example.javbrowser

import java.net.URI

/** One identity shared by navigation, bookmarks and the playback proxy. */
object RouVideoDomains {
    const val DEFAULT_HOST = "rouvb1.xyz"

    fun isHost(host: String, configured: String? = null): Boolean {
        val value = host.lowercase().trimEnd('.')
        return value == "rou.video" || value.endsWith(".rou.video") ||
            Regex("^(?:www\\.)?rouv[a-z][0-9]+\\.xyz$").matches(value) ||
            (!configured.isNullOrBlank() && (value == configured.lowercase() || value.endsWith(".${configured.lowercase()}")))
    }

    fun isUrl(url: String, configured: String? = null): Boolean = runCatching {
        isHost(URI(url).host.orEmpty(), configured)
    }.getOrDefault(false)

    fun redirectedHost(url: String): String? = runCatching {
        val uri = URI(url)
        uri.host?.lowercase()?.takeIf { uri.scheme == "https" && isHost(it) && uri.userInfo == null && uri.port == -1 }
    }.getOrNull()
}
