package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryEntriesTest {
    @Test fun repeatedPagesMoveToTheTopWithoutDuplicates() {
        val initial = listOf(PlaybackHistoryEntry("https://javday.app/videos/one/", "First", 1),
            PlaybackHistoryEntry("https://rouvb1.xyz/v/episode02", "Second", 2))
        val result = PlaybackHistoryEntries.updated(initial, "https://rouvb1.xyz/v/episode02", "Second - 第 2 集", 3)
        assertEquals(2, result.size)
        assertEquals("Second - 第 2 集", result.first().title)
        assertEquals(3L, result.first().playedAt)
    }

    @Test fun localFilesAndEphemeralProxyUrlsAreNotSavedAsWebPages() {
        listOf("content://media/video/1", "file:///tmp/video.mp4", "http://127.0.0.1:1234/proxy?id=1",
            "https://javbrowser.app/", "https://user@example.org/page").forEach {
            assertTrue(it, PlaybackHistoryEntries.updated(emptyList(), it, "Title", 1).isEmpty())
        }
    }

    @Test fun preservesPageQueryAndChineseTitleAndLimitsHistory() {
        val result = PlaybackHistoryEntries.updated(emptyList(), "https://javday.app/videos/test/?line=2#player", " 中文名稱 ", 1)
        assertEquals("中文名稱", result.single().title)
        assertEquals("https://javday.app/videos/test/?line=2", result.single().url)
        val many = (0 until 500).map { PlaybackHistoryEntry("https://example.org/$it", "$it", 1) }
        assertEquals(200, PlaybackHistoryEntries.updated(many, "https://example.org/new", "", 2).size)
    }
}
