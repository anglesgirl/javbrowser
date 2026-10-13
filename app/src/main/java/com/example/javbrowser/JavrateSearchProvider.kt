package com.example.javbrowser

import android.content.Context

class JavrateSearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        val RULES = SearchParserRules(".mgn-item a.movie-card-link[href]", ".mgn-item",
            Regex("/movie/detail/[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\\.html", RegexOption.IGNORE_CASE),
            ".search-page .search-stats-detail", true)
    }
}
