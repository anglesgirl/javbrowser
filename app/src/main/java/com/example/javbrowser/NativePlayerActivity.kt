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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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
        PlaybackDataStore.addTrace(sessionId, "播放器 onCreate：url=${videoUrl.take(80)} referer=${referer.take(60)}")
        if (videoUrl.isBlank()) {
            PlaybackDataStore.addTrace(sessionId, "播放器：地址為空，直接退出")
            Toast.makeText(this, "播放器：地址為空", Toast.LENGTH_LONG).show()
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

        // GeckoView 遷移後：ECH 基建已刪，直鏈改走 plain OkHttp。
        // 明文 SNI 在用戶網絡上可能被視頻 CDN 回 403（原先走 ECH 才通），
        // javhd 主路已是嵌頁播放器（GeckoView），不受影響。
        // 保留無 call timeout：長串流不能因 15 秒到就被掐斷。
        val videoClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        val exoPlayer = try {
            val dataSourceFactory = OkHttpDataSource.Factory(videoClient)
                .setUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .setDefaultRequestProperties(headers)
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
        } catch (e: Exception) {
            Toast.makeText(this, "播放器啟動失敗：${e.javaClass.simpleName} ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("NATIVE_PLAYER", "player setup failed url=$videoUrl", e)
            finish()
            return
        }
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> playbackState.toString()
                }
                PlaybackDataStore.addTrace(sessionId, "播放器狀態：$name")
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
                PlaybackDataStore.addTrace(sessionId, "播放器錯誤：$detail")
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
        try {
            exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl))
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
            PlaybackDataStore.addTrace(sessionId, "播放器：已 prepare，等待首幀")
        } catch (e: Throwable) {
            PlaybackDataStore.addTrace(sessionId, "播放器 prepare 失敗：${e.javaClass.simpleName} ${e.message}")
            Toast.makeText(this, "播放器啟動失敗：${e.javaClass.simpleName} ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        player = exoPlayer
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        PlaybackDataStore.addTrace(sessionId, "播放器 onPause：started=$started positionMs=${player?.currentPosition ?: 0}")
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
        PlaybackDataStore.addTrace(sessionId, "播放器 onDestroy")
        player?.release()
        player = null
    }
}
