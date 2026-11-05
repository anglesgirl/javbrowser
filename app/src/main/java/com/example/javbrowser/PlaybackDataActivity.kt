package com.example.javbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Real-playback data, visible and copyable here — no adb needed.
 *
 * Top: analysis computed from the records (per-site / per-host /
 * per-source success rates plus one conclusion line per site — the
 * seed of the learned sniffing strategy). Below: every sniff session
 * with its candidates (score, source, drop reason), the pick, and the
 * real playback outcome (played? seconds? stalled? error?).
 *
 * Data comes from [PlaybackDataStore], written by UnifiedSearchActivity
 * (sniff + pick) and both player activities (playback outcome), and
 * persisted as JSON in the app's files dir so it accumulates across
 * days. Nothing leaves the device except when the user copies it.
 */
class PlaybackDataActivity : LocalizedActivity() {

    private lateinit var summaryText: TextView
    private lateinit var entriesText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaybackDataStore.init(applicationContext)
        setContentView(R.layout.activity_playback_data)
        summaryText = findViewById(R.id.tv_playback_data_summary)
        entriesText = findViewById(R.id.tv_playback_data_entries)
        findViewById<Button>(R.id.btn_playback_data_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_playback_data_copy).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Playback data", PlaybackDataStore.formatAll()))
            Toast.makeText(this, tr("已複製播放數據", "Playback data copied"), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_playback_data_clear).setOnClickListener {
            PlaybackDataStore.clear()
            refresh()
            Toast.makeText(this, tr("已清除播放數據", "Playback data cleared"), Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        summaryText.text = PlaybackDataStore.formatSummary()
        entriesText.text = PlaybackDataStore.formatSessions()
    }

    private fun tr(zh: String, en: String) = LanguageManager.text(this, zh, en)
}
