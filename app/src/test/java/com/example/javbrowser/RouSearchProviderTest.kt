package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class RouSearchProviderTest {
    private val sites = SearchSiteRegistry.rouSites("rouvb1.xyz")
    private fun parse(series: Boolean, html: String, query: String = "出差", mode: SearchMode = SearchMode.KEYWORD): SiteSearchResult {
        val site = sites[if (series) 1 else 0]
        val rules = if (series) RouSearchProvider.SERIES_RULES else RouSearchProvider.VIDEO_RULES
        return SearchHtmlParser(site, rules).parse(
            SearchRequest("request", query, query, mode, listOf(site.id), "test"), "attempt",
            SearchHttpResponse(200, site.buildSearchUrl(query, null), html, "text/html"))
    }

    @Test fun sourcesHaveSeparateUrlsAndPreserveChineseQueries() {
        assertEquals("https://rouvb1.xyz/search?q=%E5%87%BA%E5%B7%AE", sites[0].buildSearchUrl("出差", null))
        assertEquals("https://rouvb1.xyz/search?q=%E5%87%BA%E5%B7%AE&tab=series", sites[1].buildSearchUrl("出差", null))
        assertNotEquals(sites[0].id, sites[1].id)
    }

    @Test fun videosReadTheSeparateCodeBadgeAndIgnoreOtherCategoriesAndNavigation() {
        val result = parse(false, """
            <main><a class="group" href="/v/video01"><img src="/cover.jpg" alt=""/>
                <span class="truncate">TEST-001</span><h3>出差 Sample</h3></a>
                <a class="group" href="/s/series01"><h3>Other category</h3></a>
                <nav><a class="group" href="/v/nav01"><h3>Navigation</h3></a></nav></main>
        """, "TEST-001", SearchMode.CODE)
        assertEquals(SearchStatus.SUCCESS, result.status)
        assertEquals("TEST-001", result.items.single().code)
        assertEquals("https://rouvb1.xyz/cover.jpg", result.items.single().coverUrl)
    }

    @Test fun seriesKeepTheirCategoryDuringPagination() {
        val result = parse(true, """
            <main><a class="group" href="/s/series01"><h3>出差影集</h3></a>
                <a class="group" href="/v/video01"><h3>Other category</h3></a>
                <nav><a rel="next" href="/search?q=%E5%87%BA%E5%B7%AE&amp;tab=series&amp;page=2">下一頁</a></nav></main>
        """)
        assertEquals(SearchStatus.SUCCESS, result.status)
        assertEquals(1, result.items.size)
        assertEquals("https://rouvb1.xyz/search?q=%E5%87%BA%E5%B7%AE&tab=series&page=2", result.nextPageToken)
    }

    @Test fun explicitEmptySeriesAndChallengesAreReportedCorrectly() {
        assertEquals(SearchStatus.EMPTY, parse(true, "<main><h2>沒有找到符合的影集</h2></main>").status)
        assertEquals(SearchStatus.NEEDS_USER_ACTION, parse(false, "<title>Just a moment</title>").status)
    }
}
