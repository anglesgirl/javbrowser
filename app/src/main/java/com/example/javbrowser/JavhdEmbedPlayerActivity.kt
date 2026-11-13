package com.example.javbrowser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import com.example.javbrowser.gecko.GeckoRuntimeProvider
import com.example.javbrowser.gecko.JavhdGeckoExtension
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * javhd 嵌頁直播播放器（GeckoView，2026-11-14 遷移）。
 *
 * 嗅探取直鏈 + 原生播放器走不通，改走網頁本身：把 /api/play/ 拿到的嵌頁
 * （video.javhdporn.net）直接裝進全螢幕 GeckoView，用站方自己的 JWPlayer
 * 原頁播出。Gecko 原生自帶 DoH + ECH，不再 ECH 預載、也不再走
 * shouldInterceptRequest；廣告攔截、VAST 跳過、video 狀態讀取全部改走
 * 內置 WebExtension（assets/javhd-gecko/，見 JavhdGeckoExtension）：
 * 廣告請求由擴展的 webRequest 攔掉，VAST 貼片（tsyndicate.com）放行
 * （攔了它跳過按鈕不出現、正片不播，vc140 教訓），content script
 * 輪詢自動點跳過，並定期把 <video> 狀態回傳給 Activity 記播放數據。
 *
 * 廣告域名表現在 assets/javhd-gecko/background.js 裡，
 * 須與 UnifiedSearchActivity.javhdAdHosts 同步；tsyndicate.com
 * （VAST 貼片）絕不能加進來。
 * 目前只播第一個 Part（Part1）；多 Part 的選集後續再加。
 */
class JavhdEmbedPlayerActivity : LocalizedActivity() {

    companion object {
        const val EXTRA_EMBED_URL = "embed_url"
        const val EXTRA_SESSION_ID = "playback_session_id"
        const val EXTRA_DETAIL_URL = "detail_url"
        const val EXTRA_TITLE = "title"

        private const val EMBED_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        fun intent(context: Context, embedUrl: String, sessionId: Long, detailUrl: String, title: String): Intent =
            Intent(context, JavhdEmbedPlayerActivity::class.java).apply {
                putExtra(EXTRA_EMBED_URL, embedUrl)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_DETAIL_URL, detailUrl)
                putExtra(EXTRA_TITLE, title)
            }
    }

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private var sessionId: Long = -1
    private var enteredAtMs: Long = 0
    private var outcomeRecorded: Boolean = false
    /** 擴展最近一次回傳的 <video> 狀態（content script 每 2 秒報一次）。 */
    @Volatile
    private var latestVideoState: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaybackDataStore.init(applicationContext)
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1)
        enteredAtMs = System.currentTimeMillis()
        val embedUrl = intent.getStringExtra(EXTRA_EMBED_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isNotBlank()) this.title = title
        if (embedUrl.isBlank()) {
            Toast.makeText(this, "嵌頁地址為空", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        geckoView = GeckoView(this)
        setContentView(geckoView)

        val runtime = GeckoRuntimeProvider.get(this)
        geckoSession = GeckoSession()
        geckoSession.settings.apply {
            userAgentOverride = EMBED_UA
            allowJavascript = true
            // 關掉 Gecko 內建追蹤防護：它用 Disconnect 清單，可能誤攔
            // VAST（tsyndicate）或視頻 CDN。廣告攔截由擴展精確控制。
            useTrackingProtection = false
        }
        // 頁面載入失敗時記一筆（onPageStop 的 success=false）。
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) {
                    PlaybackDataStore.addTrace(
                        sessionId,
                        "嵌頁播放器錯誤：頁面載入失敗"
                    )
                }
            }
        }
        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)

        // 裝擴展（冪等），掛上本 session 的 videoState 回調。
        JavhdGeckoExtension.ensureInstalled(this)
        JavhdGeckoExtension.attachToSession(geckoSession) { state ->
            latestVideoState = state
        }

        PlaybackDataStore.addTrace(sessionId, "嵌頁播放器：載入 ${embedUrl.take(100)}")
        // Gecko 原生 ECH/DoH，嵌頁直接載入，不再 ECH 預載。
        geckoSession.loadUri(embedUrl)
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onPause() {
        super.onPause()
        recordGeckoPlayback()
    }

    /** 用擴展最近回傳的 video 狀態，回填進播放數據（與內建播放器同一寫法）。 */
    private fun recordGeckoPlayback() {
        if (sessionId < 0 || outcomeRecorded) return
        outcomeRecorded = true
        try {
            val wallSeconds = (System.currentTimeMillis() - enteredAtMs) / 1000.0
            val state = latestVideoState
            if (state == null) {
                PlaybackDataStore.recordPlayback(
                    sessionId,
                    PlaybackOutcome("嵌頁", false, 0.0, wallSeconds, false, "無video元素"),
                )
                return
            }
            val secondsPlayed = state.optDouble("currentTime", 0.0)
            val errorCode = state.optInt("errorCode", 0)
            val error = if (errorCode != 0) "video error $errorCode" else null
            val started = secondsPlayed > 0.5
            val stalled = error == null && wallSeconds > 15.0 && secondsPlayed < 2.0
            PlaybackDataStore.recordPlayback(
                sessionId,
                PlaybackOutcome("嵌頁", started, secondsPlayed, wallSeconds, stalled, error),
            )
        } catch (e: Exception) {
            // 讀狀態失敗不影響退出。
        }
    }

    override fun onDestroy() {
        if (::geckoSession.isInitialized) {
            JavhdGeckoExtension.detachFromSession(geckoSession)
            geckoSession.close()
        }
        if (::geckoView.isInitialized) {
            geckoView.setSession(null)
        }
        super.onDestroy()
    }
}
