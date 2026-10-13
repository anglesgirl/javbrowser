package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class RouVideoDomainsTest {
    @Test fun recognisesDomainGenerationsAndConfiguredDomains() {
        listOf("https://rou.video/home", "https://rouva8.xyz/video/1",
            "https://rouvb1.xyz/video/1", "https://rouvc12.xyz/video/1").forEach {
            assertTrue(it, RouVideoDomains.isUrl(it))
        }
        assertTrue(RouVideoDomains.isUrl("https://new-domain.invalid/watch/1", "new-domain.invalid"))
    }

    @Test fun redirectsCannotLearnAnAdHostOrDowngradeHttps() {
        assertEquals("rouvb2.xyz", RouVideoDomains.redirectedHost("https://rouvb2.xyz/home"))
        listOf("http://rouvb2.xyz/home", "https://rouvb2.xyz.evil.invalid/",
            "https://advertiser.invalid/", "https://user@rouvb2.xyz/", "https://rouvb2.xyz:8443/").forEach {
            assertNull(it, RouVideoDomains.redirectedHost(it))
        }
        assertFalse(RouVideoDomains.isUrl("https://example.org/?next=https://rouvb1.xyz"))
    }
}
