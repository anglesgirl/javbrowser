package com.example.javbrowser

import android.content.Context

class JavdaySearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        val RULES = SearchParserRules("a.videoBox[href]", ".videoBox",
            Regex("/videos/[^/]+/?", RegexOption.IGNORE_CASE),
            ".search-results .no-results", true)
    }
}
