package com.example.javbrowser

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale

/**
 * In-app transport event log: what the user needs to see whether ECH/H3
 * actually worked, without adb. Search fetch paths (SearchHttpFetcher and
 * EchH3Router's Cronet path) append one entry per transport attempt.
 *
 * - route "ECH" + no error = ECH handshake succeeded (ECH effective).
 * - route "ECH" + source "共享" = the official shared Cloudflare
 *   config was used (rescue attempt), not the host's own config;
 *   source "Gateway+共享" = resolve-time merge (addresses from
 *   that endpoint, ech shared).
 * - route "Cronet" + negotiated "h3" = HTTP/3 negotiated (H3 effective).
 * - route "Cronet" with no ECH config = the site publishes no ECH config.
 * - any entry with an error = that attempt failed; the error text is the
 *   real transport reason, not the generic UI wording.
 *
 * Memory-only ring buffer (latest 200 entries); nothing is written to
 * disk and no query, URL path, or cookie is ever stored — host only.
 */
data class NetworkLogEntry(
    val timeMs: Long,
    val host: String,
    val route: String, // "ECH" / "Cronet" / "OkHttp"
    val hasEchConfig: Boolean,
    val negotiated: String?, // Cronet negotiated protocol ("h3"/"h2"/"http/1.1"), null otherwise
    val error: String?, // null = success
    /** DoH endpoint that provided the record ("Gateway"), "" when unknown. */
    val dohSource: String = "",
)

object NetworkLogStore {
    const val MAX_ENTRIES = 200

    private val lock = Any()
    private val entries = ArrayDeque<NetworkLogEntry>()

    fun add(entry: NetworkLogEntry) {
        // Single-line, truncated; a failure entry keeps a non-null error so
        // the failure count stays truthful even when the message was blank.
        val cleanError = entry.error
            ?.replace('\n', ' ')?.replace('\r', ' ')?.take(150)?.takeIf { it.isNotBlank() }
            ?: entry.error?.let { "未知錯誤" }
        val normalized = entry.copy(error = cleanError)
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(normalized)
        }
    }

    /** Newest first, for display. */
    fun snapshot(): List<NetworkLogEntry> = synchronized(lock) { entries.reversed() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    data class Summary(val echSuccess: Int, val cronetH3: Int, val failures: Int, val total: Int)

    fun summary(): Summary = synchronized(lock) {
        Summary(
            echSuccess = entries.count { it.route == "ECH" && it.error == null },
            cronetH3 = entries.count { it.route == "Cronet" && it.negotiated == "h3" && it.error == null },
            failures = entries.count { it.error != null },
            total = entries.size,
        )
    }

    fun formatTime(timeMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(timeMs))

    fun formatEntry(e: NetworkLogEntry): String {
        val configPart = if (e.hasEchConfig) "有 ECH 設定" else "無 ECH 設定"
        val sourcePart = e.dohSource.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        val base = "${formatTime(e.timeMs)} ${e.host} ${e.route}" +
            (e.negotiated?.let { " $it" } ?: "") +
            "（$configPart$sourcePart）"
        return if (e.error == null) "$base 成功" else "$base 失敗：${e.error}"
    }

    fun formatAll(): String {
        val s = summary()
        val header = "傳輸事件（最近 ${s.total} 筆）\n" +
            "ECH 成功 ${s.echSuccess} 次 · Cronet h3 ${s.cronetH3} 次 · 失敗 ${s.failures} 次\n"
        val list = snapshot()
        return if (list.isEmpty()) header + "尚無傳輸事件"
        else header + list.joinToString("\n") { formatEntry(it) }
    }
}
