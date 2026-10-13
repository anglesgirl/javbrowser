package com.example.javbrowser

import android.content.Context

class SupjavSearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        val RULES = SearchParserRules(".post a.img[href], .post h3 a[href]", ".post",
            Regex("/(?:zh/|en/|ja/)?[0-9]+\\.html", RegexOption.IGNORE_CASE),
            ".no-results", true)
    }
}
