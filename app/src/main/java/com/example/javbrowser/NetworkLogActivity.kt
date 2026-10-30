package com.example.javbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * In-app transport event log: whether ECH/H3 worked and why a fetch failed,
 * visible and copyable here — no adb needed. Events come from
 * [NetworkLogStore], written by SearchHttpFetcher and EchH3Router's
 * Cronet path; the buffer is memory-only and holds no query, URL path,
 * or cookie (host only).
 */
class NetworkLogActivity : LocalizedActivity() {

    private lateinit var summaryText: TextView
    private lateinit var entriesText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_log)
        summaryText = findViewById(R.id.tv_network_log_summary)
        entriesText = findViewById(R.id.tv_network_log_entries)
        findViewById<Button>(R.id.btn_network_log_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_network_log_copy).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Network log", NetworkLogStore.formatAll()))
            Toast.makeText(this, tr("已複製傳輸事件", "Network log copied"), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_network_log_clear).setOnClickListener {
            NetworkLogStore.clear()
            refresh()
            Toast.makeText(this, tr("已清除傳輸事件", "Network log cleared"), Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val summary = NetworkLogStore.summary()
        summaryText.text = tr(
            "傳輸事件（最近 ${summary.total} 筆）\n" +
                "ECH 成功 ${summary.echSuccess} 次 · Cronet h3 ${summary.cronetH3} 次 · 失敗 ${summary.failures} 次",
            "Transport events (latest ${summary.total})\n" +
                "ECH success ${summary.echSuccess} · Cronet h3 ${summary.cronetH3} · Failures ${summary.failures}",
        )
        val entries = NetworkLogStore.snapshot()
        entriesText.text = if (entries.isEmpty()) {
            tr("尚無傳輸事件", "No transport events yet")
        } else {
            entries.joinToString("\n") { NetworkLogStore.formatEntry(it) }
        }
    }

    private fun tr(zh: String, en: String) = LanguageManager.text(this, zh, en)
}
