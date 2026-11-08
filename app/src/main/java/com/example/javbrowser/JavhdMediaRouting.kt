package com.example.javbrowser

import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/** Small, deterministic routing policy for JavHD embed/file URLs. */
internal object JavhdMediaRouting {
    val adVideoHosts: Set<String> = setOf(
        "streamhls.click",
        "trafficjunky.com", "trafficstars.com", "exoclick.com",
        "juicyads.com", "ero-advertising.com", "plugrush.com",
        "adsterra.com", "propellerads.com", "popads.net",
        "popcash.net", "hilltopads.com",
    )

    fun isAdVideoUrl(rawUrl: String): Boolean {
        val host = try {
            URL(rawUrl).host.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            return false
        }
        return adVideoHosts.any { host == it || host.endsWith(".$it") }
    }

    /** Parse an AJAX URL-encoded data string without silently dropping fields. */
    fun parseUrlEncodedData(rawData: String): Map<String, String>? {
        if (rawData.isEmpty()) return null
        val fields = LinkedHashMap<String, String>()
        return try {
            for (component in rawData.split('&')) {
                val separator = component.indexOf('=')
                if (separator <= 0) return null
                val key = URLDecoder.decode(component.substring(0, separator), "UTF-8")
                val value = URLDecoder.decode(component.substring(separator + 1), "UTF-8")
                if (key.isEmpty() || fields.containsKey(key)) return null
                fields[key] = value
            }
            fields.takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Accept only absolute HTTP(S) media URLs whose path ends in a supported format. */
    fun isVideoMediaUrl(rawUrl: String): Boolean {
        val parsed = try {
            URL(rawUrl)
        } catch (_: Exception) {
            return false
        }
        if (!parsed.protocol.equals("http", ignoreCase = true) &&
            !parsed.protocol.equals("https", ignoreCase = true)
        ) {
            return false
        }
        val path = parsed.path.lowercase(Locale.ROOT)
        return path.endsWith(".mp4") || path.endsWith(".m3u8")
    }

    /** Select the first supported media URL that is not on a known ad host. */
    fun firstNonAdVideoUrl(rawUrls: Iterable<String>): String? =
        rawUrls.firstOrNull { isVideoMediaUrl(it) && !isAdVideoUrl(it) }
}
