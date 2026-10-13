package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class SupjavSourceSessionTest {
    @Test fun followsNewPlayerDomainsButRejectsAdvertisingAndForeignFrames() {
        val session = SupjavSourceSession("https://supjav.com/1.html", "https://lk1.supremejav.com/supjav.php?l=a", listOf("ads.invalid"))
        assertTrue(session.registerPlayer("https://evsishere.xyz/embed/a"))
        assertTrue(session.accepts("https://cdn.invalid/hls3/01/a/master.txt", "https://evsishere.xyz/embed/a"))
        assertFalse(session.accepts("https://cdn.invalid/hls3/01/a/master.txt", "https://advertiser.invalid/"))
        assertFalse(session.registerPlayer("https://ads.invalid/player"))
        assertTrue(session.trustsPlayer("https://lk1.supremejav.com/supjav.php?l=a"))
        assertFalse(session.trustsPlayer("https://supjav.com/1.html"))
        assertFalse(session.trustsPlayer("https://advertiser.invalid/e/player"))
        assertFalse(session.registerPlayer("https://evsishere.xyz/ad?type=player"))
        assertFalse(session.accepts("https://cdn.invalid/ads/master.m3u8", "https://evsishere.xyz/embed/a"))
        assertFalse(session.accepts("https://ads.invalid/video.mp4", "https://evsishere.xyz/embed/a"))
        assertFalse(session.accepts("https://cdn.invalid/video.mp4", ""))
        assertFalse(session.accepts("https://cdn.invalid/video.mp4", "blob:https://evsishere.xyz/a"))
        assertFalse(session.registerPlayer("javascript:alert(1)"))
        assertFalse(session.registerPlayer("https://supjav.com@evil.invalid/player"))
    }

    @Test fun changingServerDoesNotInheritPreviousPlayerTrust() {
        val first = SupjavSourceSession("https://supjav.com/1.html", "https://lk1.supremejav.com/supjav.php?l=a")
        first.registerPlayer("https://evsishere.xyz/embed/a")
        val second = SupjavSourceSession("https://supjav.com/1.html", "https://lk1.supremejav.com/supjav.php?l=b")
        second.registerPlayer("https://luminscdn.xyz/e/b")
        assertFalse(second.accepts("https://cdn.invalid/video.m3u8", "https://evsishere.xyz/embed/a"))
        assertTrue(second.accepts("https://cdn.invalid/video.m3u8", "https://luminscdn.xyz/e/b"))
    }

    @Test fun handlesObservedHlsTextPlaylistAndStreamtapeVideoEndpoint() {
        assertTrue(MediaStreamFormats.isHls("https://cdn.invalid/hls3/01/a/master.txt?token=x"))
        assertTrue(MediaStreamFormats.isHls("https://cdn.invalid/hls3/01/a/index-f3-v1-a1.txt?token=x"))
        assertTrue(MediaStreamFormats.isHls("https://cdn.invalid/hls3/01/a/iframes-f1-v1-a1.txt"))
        assertFalse(MediaStreamFormats.isHls("https://cdn.invalid/hls3/01/a/segment-001.txt"))
        assertFalse(MediaStreamFormats.isHls("https://cdn.invalid/documents/master.txt"))
        assertFalse(MediaStreamFormats.isHls("https://cdn.invalid/video.mp4?next=master.m3u8"))
        val session = SupjavSourceSession("https://supjav.com/1.html", "https://streamtape.com/e/a/name.mp4")
        assertFalse(session.accepts("https://streamtape.com/e/a/name.mp4", "https://streamtape.com/e/a/name.mp4"))
        assertTrue(session.accepts("https://streamtape.com/get_video?id=a&token=x", "https://streamtape.com/e/a/name.mp4"))
        assertFalse(session.accepts("https://evil.invalid/get_video?id=a", "https://streamtape.com/e/a/name.mp4"))
    }
}
