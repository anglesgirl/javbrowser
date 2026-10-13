package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class AdditionalSitesTest {
    private fun parse(kind: SearchParserKind, rules: SearchParserRules, html: String): SiteSearchResult {
        val site = SearchSite("test", "Test", 0, kind, "test",
            { _, _ -> "https://test.invalid/search" }, setOf("test.invalid"))
        return SearchHtmlParser(site, rules).parse(
            SearchRequest("request", "TEST-001", "TEST-001", SearchMode.CODE, listOf("test"), "test"),
            "attempt", SearchHttpResponse(200, "https://test.invalid/search", html, "text/html"))
    }

    @Test fun javdayExtractsBackgroundCoverAndExactCode() {
        val result = parse(SearchParserKind.JAVDAY, JavdaySearchProvider.RULES, """
            <a class="videoBox" href="/videos/TEST001/">
              <div class="videoBox-cover" style="background-image:url('/cover.webp')"></div>
              <span class="title">TEST-001 Sample</span>
            </a>
            <a class="videoBox" href="/videos/TEST0012/"><span class="title">TEST-0012 Other</span></a>
        """)
        assertEquals(SearchStatus.SUCCESS, result.status)
        assertEquals(1, result.items.size)
        assertEquals("https://test.invalid/cover.webp", result.items.single().coverUrl)
    }

    @Test fun supjavDeduplicatesImageAndTitleLinksAndRejectsAds() {
        val result = parse(SearchParserKind.SUPJAV, SupjavSearchProvider.RULES, """
            <div class="post"><a class="img" href="/123.html"><img src="/cover.jpg"></a>
              <div class="con"><h3><a href="/123.html">TEST-001 Sample</a></h3></div></div>
            <div class="post"><h3><a href="https://ads.invalid/456.html">TEST-001 Advertisement</a></h3></div>
        """)
        assertEquals(SearchStatus.SUCCESS, result.status)
        assertEquals(1, result.items.size)
        assertEquals("https://test.invalid/123.html", result.items.single().detailUrl)
        assertEquals("https://test.invalid/cover.jpg", result.items.single().coverUrl)
    }

    @Test fun verificationIsNotReportedAsEmpty() {
        for ((kind, rules) in listOf(SearchParserKind.JAVDAY to JavdaySearchProvider.RULES,
            SearchParserKind.SUPJAV to SupjavSearchProvider.RULES)) {
            assertEquals(SearchStatus.NEEDS_USER_ACTION, parse(kind, rules, "<title>Just a moment</title>").status)
        }
    }

    @Test fun javdayFollowsObservedPagingAndPreservesKeyword() {
        val site = SearchSite("javday", "JAVDAY", 0, SearchParserKind.JAVDAY, "test",
            { _, _ -> "https://javday.app/search/wd/TEST/" }, setOf("javday.app"))
        val url = "https://javday.app/search/wd/TEST/"
        val document = org.jsoup.Jsoup.parse("""
            <div class="layui-laypage"><a href="/search/page/2/wd/TEST/">下一頁</a></div>
        """, url)
        assertEquals("https://javday.app/search/page/2/wd/TEST/", SearchPagination.detect(document, url, site).next)
        assertFalse(SearchPagination.advances(okhttp3.HttpUrl.Companion.run { url.toHttpUrl() },
            "https://javday.app/search/page/2/wd/OTHER/", site, true))
    }

    @Test fun mediaRequiresActualSourceAndTrustedPlayerReferer() {
        assertTrue(AdditionalSiteMedia.accepts("https://javday.app/videos/TEST001/",
            "https://javday.homes/videos/abc/index.m3u8?line=1", "https://javday.app/"))
        assertFalse(AdditionalSiteMedia.isMedia("Ep1${'$'}https://javday.homes/a.m3u8#Ep2${'$'}https://javday.homes/b.m3u8"))
        assertTrue(AdditionalSiteMedia.accepts("https://supjav.com/123.html",
            "https://cdn.invalid/main.m3u8", "https://fc2stream.tv/player/abc"))
        assertFalse(AdditionalSiteMedia.accepts("https://supjav.com/123.html",
            "https://cdn.invalid/ad.m3u8", "https://advertiser.invalid/"))
        assertFalse(AdditionalSiteMedia.isPage("https://supjav.com.evil.invalid/123.html"))
        assertFalse(AdditionalSiteMedia.accepts("https://javday.app/", "https://javday.homes.evil.invalid/a.mp4", ""))
    }
}
