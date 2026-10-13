package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class RouVideoStreamsTest {
    private val page = "https://rouvb1.xyz/v/cmu4yd6dn0004s6wxm5495pmy"
    private val playlist = "https://rouvb1.xyz/api/hls/cmu4yd6dn0004s6wxm5495pmy"

    @Test fun aFreshVideoPageDoesNotNeedAnInitializedPagePlayer() {
        assertEquals(playlist, VideoExtractor.extractRouVideo("<video></video>", page))
        assertEquals(playlist, RouVideoStreams.playlistForPage("$page?from=home#player"))
        assertEquals(playlist, RouVideoStreams.playlistForPage("$page/"))
    }

    @Test fun thumbnailRequestsCannotBecomeVideoSources() {
        listOf("kind=thumbs", "kind=thumbs&v=6", "v=6&kind=thumbs", "%6bind=thumbs", "KIND=thumbs").forEach {
            assertFalse(it, RouVideoStreams.isPlaylist("$playlist?$it"))
            assertFalse(it, RouVideoStreams.accepts(page, "$playlist?$it"))
            assertFalse(it, MediaStreamFormats.isHls("$playlist?$it"))
        }
    }

    @Test fun theExtensionlessVideoEndpointIsHls() {
        assertTrue(MediaStreamFormats.isHls(playlist))
        assertTrue(MediaStreamFormats.isHls("$playlist?v=6"))
        assertTrue(RouVideoStreams.accepts(page, "$playlist?v=6"))
        assertFalse(MediaStreamFormats.isHls("https://example.org/api/hls/cmu4yd6dn0004s6wxm5495pmy"))
    }

    @Test fun oldEpisodesAndOtherOriginsCannotReplaceTheCurrentVideo() {
        assertFalse(RouVideoStreams.accepts(page, playlist.replace("cmu4yd6dn0004s6wxm5495pmy", "cmu507xlc0007s6wx4qws4sk9")))
        assertFalse(RouVideoStreams.accepts(page, playlist.replace("rouvb1.xyz", "rouvb2.xyz")))
        assertFalse(RouVideoStreams.accepts(page, playlist.replace("https:", "http:")))
        assertFalse(RouVideoStreams.isPlaylist(playlist.replace("rouvb1.xyz", "rouvb1.xyz.evil.invalid")))
    }

    @Test fun unrelatedAndInvalidPagesDoNotInventPlaylistUrls() {
        listOf("https://rouvb1.xyz/home", "https://example.org/v/episode01", "https://rouvb1.xyz/v/x",
            "https://user@rouvb1.xyz/v/episode01", "not a URL").forEach {
            assertNull(it, RouVideoStreams.playlistForPage(it))
        }
    }
}
