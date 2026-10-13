package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class JavrateTest {
    private val site = SearchSite("javrate", "JavRate", 11, SearchParserKind.JAVRATE, "test",
        { _, _ -> "https://www.javrate.com/search/TEST-001" }, setOf("javrate.com"))

    @Test fun extractsCardsAndDeduplicatesWithoutRecommendationsOrAdvertisements() {
        val path = "/movie/detail/28db0514-0dde-4662-86c4-5184ea485d8b.html"
        val html = """
            <section class="search-page"><div class="mgn-item">
              <div class="mgn-picture"><a class="movie-card-link" href="$path" title="TEST-001 Sample">
                <img src="https://picture.avking.xyz/cover.webp" alt="TEST-001"></a></div>
              <div class="mgn-title"><a class="movie-card-link" href="$path"><h3>TEST-001 Sample</h3></a></div>
            </div></section>
            <a class="movie-carousel-link" href="/movie/detail/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.html">TEST-001 Recommendation</a>
            <div class="mgn-item"><a class="movie-card-link" href="https://ads.invalid$path" title="TEST-001 Ad"></a></div>
        """
        val result = SearchHtmlParser(site, JavrateSearchProvider.RULES).parse(
            SearchRequest("request", "TEST-001", "TEST-001", SearchMode.CODE, listOf("javrate"), "test"),
            "attempt", SearchHttpResponse(200, site.buildSearchUrl("TEST-001", null), html, "text/html"))
        assertEquals(SearchStatus.SUCCESS, result.status)
        assertEquals(1, result.items.size)
        assertEquals("https://www.javrate.com$path", result.items.single().detailUrl)
        assertEquals("https://picture.avking.xyz/cover.webp", result.items.single().coverUrl)
    }

    @Test fun acceptsTrustedMediaAndKeepsAdsOutOfMainPage() {
        val page = "https://www.javrate.com/movie/detail/example.html"
        assertTrue(AdditionalSiteMedia.accepts(page, "https://videocdn.avking.xyz/main/playlist.m3u8?token=sample&expires=9999999999&token_path=%2Fmain%2F", "https://www.javrate.com/player/v2"))
        assertFalse(AdditionalSiteMedia.accepts(page, "https://ads.invalid/ad.m3u8", page))
        assertFalse(AdditionalSiteMedia.accepts(page, "https://avking.xyz.evil.invalid/ad.mp4", page))
        assertFalse(AdditionalSiteMedia.accepts(page, "https://videocdn.avking.xyz/main.m3u8", "https://ads.invalid"))
        assertTrue(SupjavNavigation.blocks(page, "about:blank", true))
        assertFalse(SupjavNavigation.blocks(page, "https://www.javrate.com/search/TEST", true))
        assertFalse(SupjavNavigation.blocks(page, "https://videocdn.avking.xyz/main.m3u8", false))
    }

    @Test fun followsObservedCategoryPaginationWithoutChangingKeyword() {
        val url = "https://www.javrate.com/search/a%E7%89%87/test/?tab=movie"
        val document = org.jsoup.Jsoup.parse("""
            <div class="pagination-wrapper"><a class="pagination-btn" href="/search/A%E7%89%87/test/?tab=movie&page=2">2</a></div>
        """, url)
        assertEquals("https://www.javrate.com/search/A%E7%89%87/test/?tab=movie&page=2",
            SearchPagination.detect(document, url, site).next)
        val current = okhttp3.HttpUrl.Companion.run { url.toHttpUrl() }
        assertFalse(SearchPagination.advances(current,
            "https://www.javrate.com/search/A%E7%89%87/other/?tab=movie&page=2", site, true))
    }
}
