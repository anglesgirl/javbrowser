package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class HomeClipboardLinkTest {
    @Test fun detectsSharedLinksAndKeepsTheFullAddress() {
        assertEquals(HomeClipboardLink.Match("https://missav.ws/dm339", "MissAV"), HomeClipboardLink.find("https://missav.ws/dm339"))
        val url = "https://missav.ai/zh/ABP-123?from=share&v=2#player"
        assertEquals(HomeClipboardLink.Match(url, "MissAV"), HomeClipboardLink.find("分享「$url」"))
        assertEquals("JAVDAY", HomeClipboardLink.find("https://javday.app/videos/2694mXrM/")?.site)
        assertEquals("Rou.Video", HomeClipboardLink.find("https://rouvb1.xyz/v/episode01")?.site)
    }

    @Test fun acceptsHomeSubdomainsAndConfiguredMirrors() {
        assertEquals("Stripchat", HomeClipboardLink.find("https://zt.stripchat.com/model")?.site)
        assertEquals("MissAV", HomeClipboardLink.find("https://new-domain.invalid/video", mapOf("new-domain.invalid" to "MissAV"))?.site)
    }

    @Test fun doesNotRecognizeLookalikesCredentialsOrDomainsInQueryStrings() {
        listOf("https://missav.ai.evil.invalid/video", "https://evilmissav.ai/video",
            "https://missav.ai@evil.invalid/video", "https://user@missav.ai/video",
            "https://example.org/?site=missav.ai", "javascript:alert(1)", "ABP-123").forEach {
            assertNull(it, HomeClipboardLink.find(it))
        }
    }

    @Test fun findsTheSupportedLinkAfterAnUnrelatedOne() {
        assertEquals("Jable", HomeClipboardLink.find("https://example.org/ https://jable.tv/videos/example/")?.site)
        assertEquals("https://javday.app/videos/2694mXrM/", HomeClipboardLink.find("(https://javday.app/videos/2694mXrM/)。")?.url)
    }
}
