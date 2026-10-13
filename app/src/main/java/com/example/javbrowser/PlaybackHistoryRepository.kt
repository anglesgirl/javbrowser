package com.example.javbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class PlaybackHistoryEntry(val url: String, val title: String, val playedAt: Long,
    val coverUrl: String? = null, val iconUrl: String? = null, val actresses: List<String> = emptyList())

internal object PlaybackHistoryEntries {
    const val MAX_ENTRIES = 200
    fun updated(entries: List<PlaybackHistoryEntry>, url: String, title: String, now: Long,
                coverUrl: String? = null, iconUrl: String? = null): List<PlaybackHistoryEntry> {
        val cleanUrl = url.trim().substringBefore('#')
        val uri = runCatching { URI(cleanUrl) }.getOrNull() ?: return entries
        val host = uri.host?.lowercase() ?: return entries
        if (uri.scheme !in setOf("http", "https") || uri.userInfo != null ||
            host in setOf("javbrowser.app", "localhost", "127.0.0.1", "::1", "[::1]")
        ) return entries
        val previous = entries.firstOrNull { it.url == cleanUrl }
        val item = PlaybackHistoryEntry(cleanUrl, title.trim().take(300).ifBlank { host }, now,
            imageUrl(coverUrl) ?: previous?.coverUrl, imageUrl(iconUrl) ?: previous?.iconUrl,
            previous?.actresses.orEmpty())
        return (listOf(item) + entries.filterNot { it.url == cleanUrl }).take(MAX_ENTRIES)
    }

    fun imageUrl(value: String?): String? = value?.takeIf {
        runCatching { val uri = URI(it); uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null }.getOrDefault(false)
    }
}

object PlaybackHistoryRepository {
    private const val PREFS = "playback_history"
    private const val KEY = "pages"

    @Synchronized fun list(context: Context): List<PlaybackHistoryEntry> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        val entries = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            val title = item.optString("title")
            val time = item.optLong("playedAt")
            val names = item.optJSONArray("actresses")
            val actresses = if (names == null) emptyList() else (0 until names.length())
                .map { names.optString(it).trim().take(100) }.filter { it.isNotEmpty() }.distinct().take(50)
            PlaybackHistoryEntries.updated(emptyList(), url, title, time, item.optString("coverUrl"), item.optString("iconUrl"))
                .firstOrNull()?.copy(actresses = actresses)
        }.distinctBy { it.url }.sortedByDescending { it.playedAt }.take(PlaybackHistoryEntries.MAX_ENTRIES)
        if (array.length() > PlaybackHistoryEntries.MAX_ENTRIES) save(context, entries)
        entries
    }.getOrDefault(emptyList())

    @Synchronized fun record(context: Context, url: String, title: String) {
        val entries = list(context)
        val updated = PlaybackHistoryEntries.updated(entries, url, title, System.currentTimeMillis())
        if (updated != entries) save(context, updated)
    }

    @Synchronized fun remove(context: Context, url: String) = save(context, list(context).filterNot { it.url == url })
    @Synchronized fun clear(context: Context) = save(context, emptyList())

    @Synchronized fun updateArtwork(context: Context, url: String, coverUrl: String?, iconUrl: String?,
                                    actresses: List<String> = emptyList()) {
        val page = url.trim().substringBefore('#')
        val entries = list(context)
        val updated = entries.map { entry ->
            if (entry.url != page) entry else entry.copy(
                coverUrl = PlaybackHistoryEntries.imageUrl(coverUrl) ?: entry.coverUrl,
                iconUrl = PlaybackHistoryEntries.imageUrl(iconUrl) ?: entry.iconUrl,
                actresses = actresses.map { it.trim().take(100) }.filter { it.isNotEmpty() }.distinct().take(50)
                    .ifEmpty { entry.actresses })
        }
        if (updated != entries) save(context, updated)
    }

    private fun save(context: Context, entries: List<PlaybackHistoryEntry>) {
        val array = JSONArray()
        entries.take(PlaybackHistoryEntries.MAX_ENTRIES).forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("playedAt", it.playedAt)
            .put("coverUrl", it.coverUrl).put("iconUrl", it.iconUrl).put("actresses", JSONArray(it.actresses))) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
