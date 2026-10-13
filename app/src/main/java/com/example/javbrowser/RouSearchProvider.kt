package com.example.javbrowser

import android.content.Context

class RouSearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(
    site, context, if (site.parserKind == SearchParserKind.ROU_SERIES) SERIES_RULES else VIDEO_RULES
) {
    companion object {
        val VIDEO_RULES = SearchParserRules("main a.group[href^=/v/]", "a.group",
            Regex("/v/[A-Za-z0-9_-]+/?"), "main h2", true)
        val SERIES_RULES = SearchParserRules("main a.group[href^=/s/]", "a.group",
            Regex("/s/[A-Za-z0-9_-]+/?"), "main h2", true)
    }
}
