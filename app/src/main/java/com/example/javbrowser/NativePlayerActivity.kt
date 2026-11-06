package com.example.javbrowser

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Native fullscreen player (Media3/ExoPlayer) for direct video files.
 *
 * Unlike [FullscreenInternalPlayerActivity], which plays through a WebView
 * video element behind the localhost proxy and ECH interception, this
 * player fetches the file directly with ExoPlayer's own network stack —
 * the same path a browser takes. Used for sources whose direct file is
 * already known (HentaiMama's gdvid.info file), where the WebView player
 * stalls (plays ~1s, then jumps back).
 *
 * Extras reuse [FullscreenInternalPlayerActivity]'s keys so callers can
 * switch players without changing their putExtra calls.
 */
class NativePlayerActivity : LocalizedActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    // Real-playback data: the outcome of this play reports back into the
    // sniff session it came from, so the analysis learns which candidate
    // actually plays (started? seconds? stalled at ~1s? error?).
    private var sessionId: Long = -1
    private var enteredAtMs: Long = 0
    private var started: Boolean = false
    private var errorMsg: String? = null
    private var outcomeRecorded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaybackDataStore.init(applicationContext)
        sessionId = intent.getLongExtra(FullscreenInternalPlayerActivity.EXTRA_SESSION_ID, -1)
        enteredAtMs = System.currentTimeMillis()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = PlayerView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
            keepScreenOn = true
        }
        setContentView(playerView)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val videoUrl =
            intent.getStringExtra(FullscreenInternalPlayerActivity.EXTRA_VIDEO_URL).orEmpty()
        val referer =
            intent.getStringExtra(FullscreenInternalPlayerActivity.EXTRA_REFERER).orEmpty()
        intent.getStringExtra(FullscreenInternalPlayerActivity.EXTRA_DOWNLOAD_NAME)
            ?.takeIf { it.isNotBlank() }?.let { title = it }
        if (videoUrl.isBlank()) {
            finish()
            return
        }

        val headers = mutableMapOf<String, String>()
        if (referer.isNotEmpty()) headers["Referer"] = referer
        // Cookies only for the video host itself (or the referer when
        // it is the SAME host). The old code fell back to the
        // referer's cookies unconditionally, which sent another
        // site's cookies to the video CDN — e.g. a javhdporn.net
        // cf_clearance to vdcdn.xyz, whose Cloudflare edge can reject
        // a clearance token that belongs to a different zone. The
        // player then failed with a generic "Source error" while the
        // same link played fine with no cookies at all.
        val cookieManager = android.webkit.CookieManager.getInstance()
        val videoHost = try { java.net.URL(videoUrl).host } catch (e: Exception) { "" }
        val refererHost = try { java.net.URL(referer).host } catch (e: Exception) { "" }
        val cookies = cookieManager.getCookie(videoUrl)?.takeIf { it.isNotEmpty() }
            ?: if (refererHost.isNotEmpty() && refererHost == videoHost) {
                cookieManager.getCookie(referer)?.takeIf { it.isNotEmpty() }
            } else null
        if (!cookies.isNullOrEmpty()) headers["Cookie"] = cookies

        // Fetch the video through the app-layer ECH client, not plain
        // HTTPS: the plain request exposes the SNI (us-a2.vdcdn.xyz)
        // and the carrier answers it with HTTP 403, while the same
        // URL over ECH (and in Muse) plays. OkHttpDataSource takes
        // the ECH OkHttpClient directly; videoEchClient has no call
        // timeout so a long stream is not killed mid-playback.
        val dataSourceFactory = OkHttpDataSource.Factory(EchH3Router.videoEchClient())
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(headers)
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && exoPlayer.playWhenReady) {
                    started = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Record the full cause chain, not just the generic
                // message: "Source error" alone cannot tell a 403
                // from a timeout from a bad container, and the
                // playback-data page shows this string. The Toast
                // keeps the short message.
                val causeChain = generateSequence(error.cause) { it.cause }
                    .joinToString(" <- ") { c ->
                        c.javaClass.simpleName + (c.message?.let { ": $it" } ?: "")
                    }.takeIf { it.isNotEmpty() } ?: "無 cause"
                val detail = "code=${error.errorCode} msg=${error.message ?: "?"} | $causeChain"
                android.util.Log.w(
                    "NATIVE_PLAYER",
                    "playback failed url=$videoUrl $detail",
                    error
                )
                errorMsg = detail
                // Record immediately: an error may be followed by a fast
                // exit, and onPause must not overwrite it with a blank.
                recordOutcome()
                Toast.makeText(
                    this@NativePlayerActivity,
                    "播放失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        playerView.player = exoPlayer
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl))
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
        player = exoPlayer
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        recordOutcome()
    }

    /**
     * Reports this play into its sniff session. Last write wins, so a
     * rotation records an interim sample and the exit overwrites it with
     * the final state. Never throws.
     */
    private fun recordOutcome() {
        if (sessionId < 0 || outcomeRecorded) return
        outcomeRecorded = true
        try {
            val positionMs = player?.currentPosition ?: 0L
            val wallSeconds = (System.currentTimeMillis() - enteredAtMs) / 1000.0
            val secondsPlayed = positionMs / 1000.0
            if (positionMs > 500L) started = true
            val stalled = errorMsg == null && wallSeconds > 15.0 && secondsPlayed < 2.0
            PlaybackDataStore.recordPlayback(
                sessionId,
                PlaybackOutcome(
                    player = "原生",
                    started = started,
                    secondsPlayed = secondsPlayed,
                    wallSeconds = wallSeconds,
                    stalled = stalled,
                    error = errorMsg,
                ),
            )
            // A failed play means the cached direct link is dead
            // (sign expired or revoked): drop the page's cached
            // candidates so the next open re-extracts instead of
            // replaying the dead link.
            if (errorMsg != null) {
                val pageUrl = PlaybackDataStore.snapshot().firstOrNull { it.id == sessionId }?.pageUrl.orEmpty()
                if (pageUrl.isNotEmpty()) PlaybackDataStore.invalidatePage(pageUrl)
            }
        } catch (e: Exception) {
            // Recording must never break playback.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
