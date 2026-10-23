package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TelemetryErrorsTest {
    @Test fun stripsMessagesFromCausesAndSuppressedErrorsButKeepsCodeLocations() {
        val cause = IOException("https://example.test/private?token=secret")
        val error = IllegalStateException("Private video title", cause)
        error.addSuppressed(SecurityException("Cookie: secret"))
        val clean = TelemetryErrors.sanitized(error)
        val text = clean.stackTraceToString()
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("Private video title"))
        assertFalse(text.contains("example.test"))
        assertTrue(text.contains("java.io.IOException"))
        assertTrue(text.contains("java.lang.SecurityException"))
        assertArrayEquals(error.stackTrace, clean.stackTrace)
        assertEquals("Private video title", error.message)
    }

    @Test fun boundsCyclicCauseChains() {
        val first = RuntimeException("first secret")
        val second = RuntimeException("second secret", first)
        first.initCause(second)
        val text = TelemetryErrors.sanitized(first).stackTraceToString()
        assertTrue(text.contains("Exception chain truncated"))
        assertFalse(text.contains("secret"))
    }
}
