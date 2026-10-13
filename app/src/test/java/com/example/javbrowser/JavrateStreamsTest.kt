package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class JavrateStreamsTest {
    private val master = "https://videocdn.avking.xyz/movie/playlist.m3u8?token=sample&expires=9999999999&token_path=%2Fmovie%2F"

    @Test fun carriesAuthorizationAcrossMasterVariantAndSegment() {
        val variant = JavrateStreams.resolve(master, "720p/video.m3u8")
        assertTrue(variant.contains("token=sample"))
        assertTrue(JavrateStreams.resolve(variant, "segment.ts").contains("token_path=%2Fmovie%2F"))
        assertTrue(JavrateStreams.resolve(master, "/movie/key.bin").contains("expires=9999999999"))
    }

    @Test fun neverLeaksCredentialsOutsideHostOrAuthorizedPath() {
        assertEquals("https://other.invalid/segment.ts", JavrateStreams.resolve(master, "https://other.invalid/segment.ts"))
        assertEquals("https://videocdn.avking.xyz/other/segment.ts", JavrateStreams.resolve(master, "/other/segment.ts"))
        assertFalse(JavrateStreams.isSigned("https://videocdn.avking.xyz/movie/playlist.m3u8"))
        assertFalse(JavrateStreams.isSigned("https://avking.xyz.evil.invalid/main.m3u8?token=x&expires=1&token_path=/"))
    }

    @Test fun preservesExplicitChildCredentials() {
        val url = JavrateStreams.resolve(master, "720p/video.m3u8?token=new&expires=123&token_path=%2Fmovie%2F")
        assertTrue(url.contains("token=new"))
        assertFalse(url.contains("token=sample"))
    }

    @Test fun refreshReplacesExpiredCredentialsForEveryChildRequest() {
        val oldChild = JavrateStreams.resolve(master, "720p/segment.ts")
        val renewed = master.replace("token=sample", "token=renewed")
            .replace("expires=9999999999", "expires=10000000000")
        val result = JavrateStreams.authorize(renewed, oldChild)
        assertTrue(result.contains("token=renewed"))
        assertTrue(result.contains("expires=10000000000"))
        assertFalse(result.contains("token=sample"))
        assertTrue(JavrateStreams.sameAsset(master, renewed))
        assertFalse(JavrateStreams.sameAsset(master, renewed.replace("%2Fmovie%2F", "%2Fother%2F")))
        assertEquals("https://other.invalid/movie/a.ts", JavrateStreams.authorize(renewed, "https://other.invalid/movie/a.ts"))
        assertFalse(JavrateStreams.belongsToAsset(master, "http://videocdn.avking.xyz/movie/a.ts"))
        assertFalse(JavrateStreams.belongsToAsset(master, "https://videocdn.avking.xyz/movie-other/a.ts"))
    }

    @Test fun acceptsEncodedApiPathWithoutDoubleEncodingCredentials() {
        val apiSource = master.replace("%2Fmovie%2F", "%252Fmovie%252F")
        assertTrue(JavrateStreams.sameAsset(master, apiSource))
        val child = JavrateStreams.resolve(apiSource, "720p/video.m3u8")
        assertTrue(child.contains("token_path=%2Fmovie%2F"))
        assertFalse(child.contains("%252F"))
        val segment = JavrateStreams.authorize(apiSource, "https://videocdn.avking.xyz/movie/a.ts")
        assertTrue(segment.contains("token_path=%2Fmovie%2F"))
    }
}
