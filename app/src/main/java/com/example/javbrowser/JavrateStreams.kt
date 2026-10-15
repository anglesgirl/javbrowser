package com.example.javbrowser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** JavRate's player supplies short-lived CDN credentials separately from playlist paths. */
object JavrateStreams {
    private fun tokenPath(url: String): String? {
        val raw = url.toHttpUrlOrNull()?.queryParameter("token_path") ?: return null
        val path = if (raw.startsWith("%2f", ignoreCase = true)) runCatching {
            java.net.URLDecoder.decode(raw, "UTF-8")
        }.getOrNull() ?: return null else raw
        return path.takeIf { Regex("^/(?:[A-Za-z0-9_-]+/)+$").matches(it) }
    }

    private fun assetRoot(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val scope = tokenPath(url) ?: return null
        if (parsed.encodedPath.startsWith(scope)) return scope
        // Newer CDN objects add a date directory that is absent from token_path.
        val date = Regex("^/[0-9]{8}(?=/)").find(parsed.encodedPath)?.value ?: return null
        return (date + scope).takeIf { parsed.encodedPath.startsWith(it) }
    }

    fun sameAsset(first: String, second: String): Boolean {
        val a = first.toHttpUrlOrNull() ?: return false
        val b = second.toHttpUrlOrNull() ?: return false
        return isSigned(first) && isSigned(second) && a.host == b.host &&
            tokenPath(first) != null && tokenPath(first) == tokenPath(second) &&
            assetRoot(first) != null && assetRoot(first) == assetRoot(second)
    }

    fun belongsToAsset(source: String, target: String): Boolean {
        val a = source.toHttpUrlOrNull() ?: return false
        val b = target.toHttpUrlOrNull() ?: return false
        val path = assetRoot(source) ?: return false
        return isSigned(source) && b.scheme == "https" && a.host == b.host &&
            path.startsWith('/') && path.endsWith('/') && b.encodedPath.startsWith(path)
    }

    fun authorize(source: String, target: String): String {
        if (!belongsToAsset(source, target)) return target
        val a = source.toHttpUrlOrNull()!!
        return target.toHttpUrlOrNull()!!.newBuilder().apply {
            for (key in listOf("token", "expires")) setQueryParameter(key, a.queryParameter(key))
            setQueryParameter("token_path", tokenPath(source))
        }.build().toString()
    }

    fun isPlayer(url: String): Boolean = url.toHttpUrlOrNull()?.let {
        it.scheme == "https" && AdditionalSiteMedia.hostMatches(url, "javrate.com") &&
            it.encodedPath == "/player/v2" && !it.queryParameter("payload").isNullOrBlank()
    } == true

    fun isSigned(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.port == 443 && AdditionalSiteMedia.hostMatches(url, "avking.xyz") &&
            listOf("token", "expires", "token_path").all { !parsed.queryParameter(it).isNullOrBlank() }
    }

    /** Normal-storage sources do not use the short-lived Bunny token protocol. */
    fun isDirect(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.port == 443 && AdditionalSiteMedia.hostMatches(url, "avking.xyz") &&
            AdditionalSiteMedia.isMedia(url) &&
            listOf("token", "expires", "token_path").none { parsed.queryParameter(it) != null }
    }

    fun resolve(base: String, child: String): String {
        val parent = base.toHttpUrlOrNull() ?: return child
        val resolved = parent.resolve(child) ?: return child
        if (!isSigned(base) || resolved.host != parent.host ||
            !belongsToAsset(base, resolved.toString())) return resolved.toString()
        return resolved.newBuilder().apply {
            for (key in listOf("token", "expires", "token_path")) {
                if (resolved.queryParameter(key) == null) addQueryParameter(key,
                    if (key == "token_path") tokenPath(base) else parent.queryParameter(key))
            }
        }.build().toString()
    }
}
