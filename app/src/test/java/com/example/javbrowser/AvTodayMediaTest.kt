package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class AvTodayMediaTest {
    private val page = "https://avtoday.io/cht/video/MIDA-807.html"
    private val player = "https://avtoday.io/player?s=selected"
    private val mp4 = "https://video.xdmm.workers.dev/2026/10/movie.mp4?exp=123&ua=abc&sig=xyz"

    @Test fun acceptsSignedMp4AndLegacyHlsFromSelectedPlayer() {
        assertTrue(AvTodayMedia.accepts(page, player, mp4))
        assertTrue(AvTodayMedia.accepts(page, player, "https://cdn.invalid/main.m3u8?token=abc"))
        assertFalse(AvTodayMedia.accepts(page, player, "blob:https://avtoday.io/video"))
        assertFalse(AvTodayMedia.accepts(page, player, "https://cdn.invalid/html?next=movie.mp4"))
    }

    @Test fun rejectsHomePreviewsAndUnrelatedPlayerPages() {
        assertFalse(AvTodayMedia.accepts("https://avtoday.io/cht/index.html", player, mp4))
        assertFalse(AvTodayMedia.accepts(page, "https://ads.invalid/player?s=selected", mp4))
        assertFalse(AvTodayMedia.accepts(page, "https://avtoday.io/preview?s=selected", mp4))
        assertFalse(AvTodayMedia.accepts(page, "https://avtoday.io/player", mp4))
        assertFalse(AvTodayMedia.accepts(page, player, "https://avtoday.io@evil.invalid/movie.mp4"))
    }
}
