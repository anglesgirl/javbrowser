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
import androidx.media3.datasource.DefaultHttpDataSource
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookies = cookieManager.getCookie(videoUrl)?.takeIf { it.isNotEmpty() }
            ?: cookieManager.getCookie(referer)?.takeIf { it.isNotEmpty() }
        if (!cookies.isNullOrEmpty()) headers["Cookie"] = cookies

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(headers)
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w(
                    "NATIVE_PLAYER",
                    "playback failed url=$videoUrl cause=${error.cause}",
                    error
                )
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
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
