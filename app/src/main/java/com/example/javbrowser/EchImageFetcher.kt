package com.example.javbrowser

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cover-art / image bytes through the same ECH → Cronet → plain
 * routing the search HTML fetcher uses.
 *
 * Why: Glide's default fetcher is plain HttpURLConnection (TCP).
 * On the user's network plain TCP to image CDNs gets RST
 * (Whos.tv covers live on v.hersav.me — grey boxes in search
 * results while the video itself plays, because the player goes
 * through ECH/Cronet). Routing image bytes through ECH (when the
 * host has an ECH config) or Cronet H3/QUIC (otherwise) makes
 * covers load on the same transports that already work for video.
 *
 * Routing mirrors SearchHttpFetcher exactly:
 * - ECH route: ECH OkHttp client, fail-closed — a failed ECH
 *   handshake returns null, never a plaintext fallback (same
 *   SNI-leak rule as search). A first-attempt failure retries
 *   once after 700ms (DoH/ECH config may not have been ready).
 * - Cronet route: Cronet H3 bytes; on Cronet failure fall back
 *   to plain OkHttp (same as search).
 * - Plain fallback client is a plain OkHttpClient (no ECH),
 *   used ONLY for the Cronet route's fallback.
 *
 * Returns the image bytes, or null on any failure (caller shows
 * the placeholder — same as the current grey box, no worse).
 * Body cap 2MB, same as the search fetcher.
 */
object EchImageFetcher {
    private const val TAG = "EchImage"
    private const val MAX_IMAGE_BYTES = 2L * 1024 * 1024

    private val plainClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Blocking. Call from a background thread (Glide's DataFetcher does). */
    fun fetchBytes(url: String): ByteArray? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        return when (EchH3Router.route(host)) {
            EchH3Router.Route.Ech -> fetchEchBytes(url, host)
            EchH3Router.Route.Cronet -> fetchCronetBytes(url)
        }
    }

    private fun fetchEchBytes(url: String, host: String): ByteArray? {
        val client = try {
            EchH3Router.searchEchClient()
        } catch (e: IOException) {
            Log.w(TAG, "ECH client unavailable for $host (fail-closed): ${e.message}")
            return null
        }
        return try {
            fetchOkHttpBytes(client, url)
        } catch (e: IOException) {
            Log.d(TAG, "ECH image attempt failed for $host, retrying after 700ms: ${e.message}")
            try {
                Thread.sleep(700)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            EchDoh.invalidate(host)
            try {
                fetchOkHttpBytes(client, url)
            } catch (e2: IOException) {
                Log.w(TAG, "ECH image failed for $host (fail-closed): ${e2.message}")
                null
            }
        }
    }

    private fun fetchCronetBytes(url: String): ByteArray? {
        val cronetBytes = try {
            EchH3Router.fetchBytesViaCronet(url)
        } catch (e: SearchTransportException) {
            // Body-cap overflow: hard failure, never fall back.
            Log.w(TAG, "Cronet image too large: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.d(TAG, "Cronet image failed, falling back to plain OkHttp: ${e.message}")
            null
        }
        if (cronetBytes != null) return cronetBytes
        // Cronet failed or returned null: plain fallback.
        return try {
            fetchOkHttpBytes(plainClient, url)
        } catch (e2: IOException) {
            Log.w(TAG, "Plain image fallback failed: ${e2.message}")
            null
        }
    }

    @Throws(IOException::class)
    private fun fetchOkHttpBytes(client: OkHttpClient, url: String): ByteArray {
        val request = Request.Builder().url(url)
            .header("User-Agent", SearchHttpFetcher.USER_AGENT)
            .header("Accept", "image/*,*/*;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            val source = body.source()
            source.request(MAX_IMAGE_BYTES + 1)
            if (source.buffer.size > MAX_IMAGE_BYTES) throw IOException("image too large")
            return source.readByteArray()
        }
    }
}
