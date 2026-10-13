package com.example.javbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouEpisodesTest {
    @Test fun episodesAreOrderedAndDuplicateLinksAreRemoved() {
        val html = """
            <a href="/v/episode03">第 3 集</a>
            <a href='/v/episode01'><span>第&nbsp;1 集</span></a>
            <a href="/v/episode02?source=list"><!-- marker -->第 2 集</a>
            <a href="/v/episode01">第 1 集</a>
            <a href="/v/unrelated">推薦影片</a>
        """.trimIndent()
        assertEquals(
            listOf(
                VideoExtractor.RouVideoEpisode("episode01", 1),
                VideoExtractor.RouVideoEpisode("episode02", 2),
                VideoExtractor.RouVideoEpisode("episode03", 3)
            ),
            VideoExtractor.extractRouEpisodes(html)
        )
    }

    @Test fun unrelatedPagesAndInvalidEpisodeNumbersDoNotCreateAPlaylist() {
        assertTrue(VideoExtractor.extractRouEpisodes("""
            <a href="/v/episode00">第 0 集</a>
            <a href="/v/overflow">第 999999999999999999999 集</a>
            <a href="https://example.org/v/external">第 1 集</a>
            <a href="/other/episode02">第 2 集</a>
        """.trimIndent()).isEmpty())
    }
}
