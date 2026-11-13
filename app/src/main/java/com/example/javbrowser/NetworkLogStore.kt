package com.example.javbrowser

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale

/**
 * In-app transport event log: what the user needs to see whether a fetch
 * actually worked, without adb. Search fetch paths (SearchHttpFetcher,
 * plain OkHttp) append one entry per transport attempt. Browser loads
 * (GeckoView, native ECH/DoH) are not logged here.
 *
 * - route "OkHttp" + no error = plain-OkHttp fetch succeeded.
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
    fun snapshot(): List<NetworkLogEntry> = synchronized(lock) { entries.toList().reversed() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    data class Summary(val okHttpSuccess: Int, val failures: Int, val total: Int)

    fun summary(): Summary = synchronized(lock) {
        Summary(
            okHttpSuccess = entries.count { it.route == "OkHttp" && it.error == null },
            failures = entries.count { it.error != null },
            total = entries.size,
        )
    }

    fun formatTime(timeMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(timeMs))

    fun formatEntry(e: NetworkLogEntry): String {
        val base = "${formatTime(e.timeMs)} ${e.host} ${e.route}" +
            (e.negotiated?.let { " $it" } ?: "")
        return if (e.error == null) "$base 成功" else "$base 失敗：${e.error}"
    }

    fun formatAll(): String {
        val s = summary()
        val header = "傳輸事件（最近 ${s.total} 筆）\n" +
            "OkHttp 成功 ${s.okHttpSuccess} 次 · 失敗 ${s.failures} 次\n"
        val list = snapshot()
        return if (list.isEmpty()) header + "尚無傳輸事件"
        else header + list.joinToString("\n") { formatEntry(it) }
    }
}
