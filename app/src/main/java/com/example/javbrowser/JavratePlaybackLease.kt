package com.example.javbrowser

import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Downloads retain their authorization/proxy after the player Activity closes. */
class JavratePlaybackLease private constructor(
    val id: String, private val session: JavratePlaybackSession, private val proxy: VideoProxyServer
) {
    companion object {
        private val leases = mutableMapOf<String, JavratePlaybackLease>()
        @Synchronized fun create(session: JavratePlaybackSession, proxy: VideoProxyServer): JavratePlaybackLease =
            JavratePlaybackLease(UUID.randomUUID().toString(), session, proxy).also { leases[it.id] = it }
        @Synchronized fun acquire(id: String?): JavratePlaybackLease? = leases[id]?.also { it.references++ }
        @Synchronized private fun release(lease: JavratePlaybackLease) {
            if (--lease.references != 0) return
            leases.remove(lease.id)
            Handler(Looper.getMainLooper()).post { lease.proxy.stop(); lease.session.close() }
        }
    }
    private var references = 1
    fun release() = Companion.release(this)
}
