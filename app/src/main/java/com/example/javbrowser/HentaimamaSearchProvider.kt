package com.example.javbrowser

import android.content.Context

/** Structural contract; live-site acceptance status is recorded separately. */
class HentaimamaSearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        val RULES = SearchParserRules(
            "article.series-card a.sc-poster[href], article.series-card h3.sc-title a[href], .dt-series-cards article a.sc-poster[href]",
            "article.series-card, .dt-series-cards article",
            Regex("/(?:episodes|tvshows)/[^/]+/?", RegexOption.IGNORE_CASE),
            ".no-results, .search-no-results, .no-result",
            true)
    }
}
