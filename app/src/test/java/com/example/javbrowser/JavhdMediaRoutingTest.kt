package com.example.javbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JavhdMediaRoutingTest {
    @Test
    fun rejectsKnownAdVideoHostsAndTheirSubdomains() {
        assertTrue(JavhdMediaRouting.isAdVideoUrl("https://streamhls.click/ad/master.m3u8"))
        assertTrue(JavhdMediaRouting.isAdVideoUrl("https://edge.trafficstars.com/ad.mp4"))
        assertFalse(JavhdMediaRouting.isAdVideoUrl("https://s2.maxstream.org/master.m3u8"))
        assertFalse(JavhdMediaRouting.isAdVideoUrl("https://streamhls.click.attacker.invalid/ad.m3u8"))
    }

    @Test
    fun parsesEveryUrlEncodedFieldAndPreservesDecodedValues() {
        assertEquals(
            linkedMapOf("sources" to "a+b/c=", "ver" to "2", "extra" to "x y"),
            JavhdMediaRouting.parseUrlEncodedData("sources=a%2Bb%2Fc%3D&ver=2&extra=x+y"),
        )
    }

    @Test
    fun rejectsMalformedOrDuplicateUrlEncodedFieldsInsteadOfDroppingThem() {
        assertNull(JavhdMediaRouting.parseUrlEncodedData("sources=a&broken"))
        assertNull(JavhdMediaRouting.parseUrlEncodedData("sources=a&sources=b"))
        assertNull(JavhdMediaRouting.parseUrlEncodedData("=value"))
        assertNull(JavhdMediaRouting.parseUrlEncodedData(""))
    }

    @Test
    fun recognizesOnlyAbsoluteSupportedMediaPaths() {
        assertTrue(JavhdMediaRouting.isVideoMediaUrl("https://cdn.example/file.mp4?sig=abc"))
        assertTrue(JavhdMediaRouting.isVideoMediaUrl("https://cdn.example/master.m3u8?sig=abc"))
        assertFalse(JavhdMediaRouting.isVideoMediaUrl("https://cdn.example/image.mp4_snapshot.webp"))
        assertFalse(JavhdMediaRouting.isVideoMediaUrl("blob:https://cdn.example/id"))
    }

    @Test
    fun skipsAdSourceAndSelectsNextSupportedMediaUrl() {
        val feature = "https://s2.maxstream.org/master.m3u8?sig=abc"
        assertEquals(
            feature,
            JavhdMediaRouting.firstNonAdVideoUrl(
                listOf("https://streamhls.click/ad/master.m3u8", "https://cdn.example/poster.webp", feature),
            ),
        )
        assertNull(JavhdMediaRouting.firstNonAdVideoUrl(listOf("https://streamhls.click/ad/master.m3u8")))
    }
}
