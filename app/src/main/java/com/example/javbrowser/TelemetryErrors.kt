package com.example.javbrowser

import java.util.IdentityHashMap

/** Preserve code locations and exception types, never exception messages or arbitrary metadata. */
internal object TelemetryErrors {
    fun sanitized(error: Throwable): Throwable = copy(error, IdentityHashMap(), 0)

    private fun copy(error: Throwable, seen: IdentityHashMap<Throwable, Boolean>, depth: Int): Throwable {
        if (depth >= 16 || seen.put(error, true) != null) {
            return RuntimeException("Exception chain truncated").apply { stackTrace = emptyArray() }
        }
        return RuntimeException(error.javaClass.name).apply {
            stackTrace = error.stackTrace
            error.cause?.let { initCause(copy(it, seen, depth + 1)) }
            error.suppressed.take(8).forEach { addSuppressed(copy(it, seen, depth + 1)) }
        }
    }
}
