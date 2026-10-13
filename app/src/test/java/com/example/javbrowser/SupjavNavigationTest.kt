package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class SupjavNavigationTest {
    private val page = "https://supjav.com/123.html"

    @Test fun blocksBlankPagesAndExternalTopLevelAdvertising() {
        listOf("about:blank", "https://advertiser.invalid/", "https://stripchat.com/",
            "https://supjav.com.evil.invalid/", "intent://advertisement").forEach {
            assertTrue(it, SupjavNavigation.blocks(page, it, true))
        }
    }

    @Test fun preservesSiteNavigationAndCrossOriginPlayerFrames() {
        assertFalse(SupjavNavigation.blocks(page, "https://supjav.com/456.html", true))
        assertFalse(SupjavNavigation.blocks(page, "https://supjav.com/?s=TEST", true))
        assertFalse(SupjavNavigation.blocks(page, "https://lk1.supremejav.com/supjav.php", false))
        assertFalse(SupjavNavigation.blocks(page, "about:blank", false))
        assertFalse(SupjavNavigation.blocks("https://javday.app/", "https://example.org/", true))
    }
}
