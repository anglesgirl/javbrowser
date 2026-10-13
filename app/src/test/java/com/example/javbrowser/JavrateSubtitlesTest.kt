package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class JavrateSubtitlesTest {
    @Test fun onlyAcceptsWebsiteSubtitleFiles() {
        assertTrue(JavrateSubtitles.isAllowed("https://subtitle.avking.xyz/TEST-001/sample.vtt"))
        assertFalse(JavrateSubtitles.isAllowed("https://subtitle.avking.xyz.evil.invalid/a.vtt"))
        assertFalse(JavrateSubtitles.isAllowed("https://user@subtitle.avking.xyz/a.vtt"))
        assertFalse(JavrateSubtitles.isAllowed("http://subtitle.avking.xyz/a.vtt"))
        assertFalse(JavrateSubtitles.isAllowed("https://subtitle.avking.xyz/ad.html"))
    }
}
