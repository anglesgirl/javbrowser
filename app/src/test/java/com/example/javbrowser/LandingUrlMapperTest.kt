package com.example.javbrowser

import com.example.javbrowser.gecko.LandingUrlMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LandingUrlMapperTest {
    private val mapper = LandingUrlMapper(
        landingAssetUrl = "resource://android/assets/landing.html",
        publicLandingUrl = "https://javbrowser.app/"
    )

    @Test fun transientAboutBlankDoesNotClearPendingLandingIdentity() {
        mapper.beginLandingLoad()

        assertEquals("about:blank", mapper.map("about:blank"))
        assertEquals(
            "https://javbrowser.app/",
            mapper.map("resource://android/assets/landing.html#data=%7B%7D")
        )
    }

    @Test fun realNavigationAwayClearsLandingIdentity() {
        mapper.beginLandingLoad()

        assertEquals("https://example.com/", mapper.map("https://example.com/"))
        assertEquals(
            "resource://android/assets/landing.html",
            mapper.map("resource://android/assets/landing.html")
        )
    }

    @Test fun explicitNavigationCanCancelPendingLandingIdentity() {
        mapper.beginLandingLoad()
        mapper.clearLandingLoad()

        assertEquals(
            "resource://android/assets/landing.html",
            mapper.map("resource://android/assets/landing.html")
        )
        assertNull(mapper.map(null))
    }

    @Test fun extensionLandingIdentityMatchesOnlyTheExactDocument() {
        val url = "moz-extension://example-id/landing.html"
        mapper.beginLandingLoad(url)
        assertEquals("about:blank", mapper.map("about:blank"))
        assertEquals("https://javbrowser.app/", mapper.map("$url#data=%7B%7D"))
        assertEquals("$url.evil", mapper.map("$url.evil"))
        assertEquals(url, mapper.map(url))
    }
}
