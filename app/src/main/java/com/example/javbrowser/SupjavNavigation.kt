package com.example.javbrowser

/** Embedded players may load other hosts; advertisements cannot replace the main page. */
object SupjavNavigation {
    fun blocks(page: String, destination: String, mainFrame: Boolean): Boolean {
        if (!mainFrame) return false
        val domain = listOf("supjav.com", "javrate.com").firstOrNull {
            AdditionalSiteMedia.hostMatches(page, it)
        } ?: return false
        return !AdditionalSiteMedia.hostMatches(destination, domain)
    }
}
