package com.example.javbrowser

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.collections.ArrayDeque

/**
 * Real-playback data: the ground truth the sniffer learns from.
 *
 * Order of work (user's decision 2026-11-05): collect real playback
 * first, derive the sniffing strategy from it second. v1 only collects
 * and analyzes — it does NOT change what gets picked yet.
 *
 * One [SniffSession] per openResult: which candidates each source
 * found (kept ones and dropped ones, with the reason), which one the
 * user picked, and what really happened when it played (started?
 * seconds played? stalled at ~1s? error?). The player activities
 * report back into the same session, so a sniff that "succeeded" but
 * never actually played is visible as a failure, not a success.
 *
 * Analysis is computed from the records, never hand-written:
 * per-site / per-host / per-source success rates over recent plays,
 * plus one plain-language conclusion line per site. When a site has
 * enough plays, the conclusion names the path that works; that is
 * the seed of the learned strategy.
 *
 * Ring buffer (latest 100 sessions), persisted as JSON in the app's
 * files dir so data survives process death and accumulates across
 * days. URLs are the user's own playback history, kept on-device
 * only; nothing is uploaded. Never throws.
 */
data class SniffCandidateRecord(
    val url: String,
    val source: String, // 站內解析 / video標籤 / 寬鬆匹配 / resolver-javhd / resolver-hentaimama-fast / resolver-hentaimama-webview
    val score: Int,
    val evidence: String, // e.g. "含番號 · m3u8 12:34"
    val kept: Boolean,
    val dropReason: String?, // null when kept
    val durationSec: Double?,
)

data class PlaybackOutcome(
    val player: String, // 原生 / 內部
    val started: Boolean,
    val secondsPlayed: Double,
    val wallSeconds: Double,
    val stalled: Boolean, // in player >15s but position <2s (the 1s jump-back signature)
    val error: String?,
) {
    /** Played for real: started, ran at least 5s, no stall, no error. */
    fun playedOk(): Boolean = started && !stalled && error == null && secondsPlayed >= 5.0
}

data class SniffSession(
    val id: Long,
    val timeMs: Long,
    val siteId: String,
    val pageUrl: String,
    val candidates: List<SniffCandidateRecord> = emptyList(),
    val pickedUrl: String? = null,
    val pickedRank: Int? = null,
    /** 待選 / 已選 / 瀏覽器 / 無候選 */
    val pickOutcome: String = "待選",
    /** Why the sniff produced nothing usable (resolver failed, no candidates, …). */
    val failStage: String? = null,
    val playback: PlaybackOutcome? = null,
    /** Step-by-step trace of the resolve, one line per step with a
     * relative timestamp (e.g. "+1.2s 详情页：HTTP 200 …"). Written
     * live while the resolve runs, so one test run on the device
     * shows exactly where it died instead of needing a guess. */
    val trace: List<String> = emptyList(),
)

object PlaybackDataStore {
    const val MAX_SESSIONS = 100
    private const val MAX_TRACE_LINES = 200
    private const val FILE_NAME = "playback_data.json"

    private val lock = Any()
    private val sessions = ArrayDeque<SniffSession>()
    private var appContext: Context? = null
    private var loaded = false
    private var nextId = 1L

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext == null) appContext = context.applicationContext
            if (!loaded) {
                loaded = true
                loadLocked()
            }
        }
    }

    fun startSession(siteId: String, pageUrl: String): Long {
        synchronized(lock) {
            val id = nextId++
            val session = SniffSession(id = id, timeMs = System.currentTimeMillis(), siteId = siteId, pageUrl = pageUrl)
            sessions.addLast(session)
            while (sessions.size > MAX_SESSIONS) sessions.removeFirst()
            saveLocked()
            return id
        }
    }

    fun addCandidates(sessionId: Long, candidates: List<SniffCandidateRecord>) {
        update(sessionId) { it.copy(candidates = candidates) }
    }

    fun recordFail(sessionId: Long, stage: String) {
        update(sessionId) { it.copy(failStage = stage, pickOutcome = "無候選") }
    }

    /** Append one trace line (max 200 per session, then ignored). */
    fun addTrace(sessionId: Long, line: String) {
        update(sessionId) { s ->
            if (s.trace.size >= MAX_TRACE_LINES) s
            else s.copy(trace = s.trace + line)
        }
    }

    /** url null = user chose the browser fallback; rank null likewise. */
    fun recordPick(sessionId: Long, url: String?, rank: Int?) {
        update(sessionId) {
            it.copy(
                pickedUrl = url,
                pickedRank = rank,
                pickOutcome = if (url == null) "瀏覽器" else "已選",
            )
        }
    }

    /** Last write wins: rotation records an interim sample, exit overwrites with the final state. */
    fun recordPlayback(sessionId: Long, outcome: PlaybackOutcome) {
        update(sessionId) { it.copy(playback = outcome) }
    }

    fun snapshot(): List<SniffSession> = synchronized(lock) { sessions.reversed() }

    fun clear() {
        synchronized(lock) {
            sessions.clear()
            saveLocked()
        }
    }

    private fun update(sessionId: Long, change: (SniffSession) -> SniffSession) {
        synchronized(lock) {
            val idx = sessions.indexOfFirst { it.id == sessionId }
            if (idx < 0) return
            sessions[idx] = change(sessions[idx])
            saveLocked()
        }
    }

    // ---- Analysis: computed from the records, never hand-written. ----

    data class RateStats(val key: String, val picks: Int, val playedOk: Int, val avgSeconds: Double) {
        val rate: Double get() = if (picks == 0) 0.0 else playedOk.toDouble() / picks
    }

    data class Analysis(
        val totalSessions: Int,
        val totalPicks: Int,
        val totalPlayedOk: Int,
        val bySite: List<RateStats>,
        val byHost: List<RateStats>,
        val bySource: List<RateStats>,
        /** One plain-language conclusion line per site. */
        val conclusions: List<String>,
    )

    fun analyze(): Analysis {
        val all = synchronized(lock) { sessions.toList() }
        val picked = all.filter { it.pickedUrl != null }
        fun statsOf(keyOf: (SniffSession) -> String?): List<RateStats> {
            return picked.groupBy { keyOf(it) ?: "?" }
                .map { (key, list) ->
                    val ok = list.count { it.playback?.playedOk() == true }
                    val avg = list.mapNotNull { it.playback?.secondsPlayed }.average()
                    RateStats(key, list.size, ok, if (avg.isNaN()) 0.0 else avg)
                }.sortedByDescending { it.picks }
        }
        val bySite = statsOf { it.siteId }
        val byHost = statsOf { hostOf(it.pickedUrl) }
        val bySource = statsOf { session ->
            session.candidates.firstOrNull { it.url == session.pickedUrl }?.source
        }
        val conclusions = bySite.map { site ->
            val bestHost = byHost.filter { h ->
                picked.any { it.siteId == site.key && hostOf(it.pickedUrl) == h.key }
            }.maxByOrNull { it.rate }
            when {
                site.picks < 3 -> "${site.key}：數據不足（${site.picks} 次播放），先繼續攢"
                site.rate >= 0.8 -> "${site.key}：優先 ${bestHost?.key ?: "?"}（${site.playedOk}/${site.picks} 播出，平均 ${"%.0f".format(site.avgSeconds)} 秒）"
                site.rate == 0.0 -> "${site.key}：避開——${site.picks} 次播放全部失敗，現有路子不通"
                else -> "${site.key}：不穩定（${site.playedOk}/${site.picks} 播出），還沒有穩定的路"
            }
        }
        return Analysis(
            totalSessions = all.size,
            totalPicks = picked.size,
            totalPlayedOk = picked.count { it.playback?.playedOk() == true },
            bySite = bySite,
            byHost = byHost,
            bySource = bySource,
            conclusions = conclusions,
        )
    }

    // ---- Display: summary (analysis) + session detail, both copyable. ----

    fun formatSummary(): String {
        val a = analyze()
        val sb = StringBuilder()
        sb.append("播放數據（最近 ${a.totalSessions} 次嗅探 · ${a.totalPicks} 次播放 · 播出 ${a.totalPlayedOk} 次）\n")
        if (a.conclusions.isEmpty()) {
            sb.append("尚無播放記錄：點一個搜索結果、播一次，數據就開始攢")
        } else {
            sb.append("結論（按站）：\n")
            a.conclusions.forEach { sb.append("· $it\n") }
            if (a.byHost.isNotEmpty()) {
                sb.append("按域名：")
                sb.append(a.byHost.joinToString(" · ") { "${it.key} ${it.playedOk}/${it.picks}" })
                sb.append("\n")
            }
            if (a.bySource.isNotEmpty()) {
                sb.append("按來源：")
                sb.append(a.bySource.joinToString(" · ") { "${it.key} ${it.playedOk}/${it.picks}" })
            }
        }
        return sb.toString().trimEnd()
    }

    fun formatSessions(): String {
        val list = snapshot()
        if (list.isEmpty()) return "尚無嗅探記錄"
        return list.joinToString("\n\n") { formatSession(it) }
    }

    fun formatAll(): String = formatSummary() + "\n\n" + formatSessions()

    private fun formatSession(s: SniffSession): String {
        val sb = StringBuilder()
        sb.append("${formatTime(s.timeMs)} ${s.siteId} ${hostOf(s.pageUrl)}\n")
        if (s.candidates.isEmpty()) {
            sb.append("  未找到候選")
            s.failStage?.let { sb.append("：$it") }
        } else {
            s.candidates.forEachIndexed { i, c ->
                val host = hostOf(c.url)
                val dur = c.durationSec?.let { " · ${(it / 60).toInt()}:${(it % 60).toInt().toString().padStart(2, '0')}" } ?: ""
                val state = if (c.kept) "保留" else "篩掉（${c.dropReason ?: "?" }）"
                sb.append("  ${i + 1}. [$state ${c.score}分] ${c.source}$dur · $host\n")
                sb.append("     ${c.evidence}\n")
            }
        }
        sb.append("  選擇：${s.pickOutcome}")
        s.pickedRank?.let { sb.append("（第 ${it + 1} 個）") }
        s.pickedUrl?.let { sb.append(" ${hostOf(it)}") }
        sb.append("\n")
        val p = s.playback
        if (p == null) {
            sb.append("  播放：無記錄（未進播放器或未回填）")
        } else {
            val verdict = when {
                p.error != null -> "失敗：${p.error}"
                p.stalled -> "卡住：進播放器 ${"%.0f".format(p.wallSeconds)} 秒只播了 ${"%.1f".format(p.secondsPlayed)} 秒"
                p.playedOk() -> "播出：${"%.0f".format(p.secondsPlayed)} 秒"
                p.started -> "播過 ${"%.0f".format(p.secondsPlayed)} 秒（未滿 5 秒）"
                else -> "未開始播放"
            }
            sb.append("  播放（${p.player}）：$verdict")
        }
        if (s.trace.isNotEmpty()) {
            sb.append("\n  過程：\n")
            s.trace.forEach { sb.append("    $it\n") }
        }
        return sb.toString().trimEnd()
    }

    fun formatTime(timeMs: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(timeMs))

    private fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return "?"
        return try { java.net.URL(url).host } catch (e: Exception) { "?" }
    }

    // ---- Persistence: one JSON file, written on every change (small data). ----

    private fun file(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }

    private fun saveLocked() {
        try {
            val f = file() ?: return
            val arr = org.json.JSONArray()
            sessions.forEach { s -> arr.put(sessionToJson(s)) }
            f.writeText(arr.toString())
        } catch (e: Exception) {
            // Disk failure must never break playback; data stays memory-only.
        }
    }

    private fun loadLocked() {
        try {
            val f = file() ?: return
            if (!f.exists()) return
            val arr = org.json.JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val s = jsonToSession(arr.optJSONObject(i)) ?: continue
                sessions.addLast(s)
                if (s.id >= nextId) nextId = s.id + 1
            }
            while (sessions.size > MAX_SESSIONS) sessions.removeFirst()
        } catch (e: Exception) {
            sessions.clear()
        }
    }

    private fun sessionToJson(s: SniffSession): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("id", s.id)
        o.put("timeMs", s.timeMs)
        o.put("siteId", s.siteId)
        o.put("pageUrl", s.pageUrl)
        o.put("pickedUrl", s.pickedUrl)
        o.put("pickedRank", s.pickedRank ?: -1)
        o.put("pickOutcome", s.pickOutcome)
        o.put("failStage", s.failStage)
        val cands = org.json.JSONArray()
        s.candidates.forEach { c ->
            val co = org.json.JSONObject()
            co.put("url", c.url)
            co.put("source", c.source)
            co.put("score", c.score)
            co.put("evidence", c.evidence)
            co.put("kept", c.kept)
            co.put("dropReason", c.dropReason)
            co.put("durationSec", c.durationSec ?: -1.0)
            cands.put(co)
        }
        o.put("candidates", cands)
        s.playback?.let { p ->
            val po = org.json.JSONObject()
            po.put("player", p.player)
            po.put("started", p.started)
            po.put("secondsPlayed", p.secondsPlayed)
            po.put("wallSeconds", p.wallSeconds)
            po.put("stalled", p.stalled)
            po.put("error", p.error)
            o.put("playback", po)
        }
        if (s.trace.isNotEmpty()) {
            val tr = org.json.JSONArray()
            s.trace.forEach { tr.put(it) }
            o.put("trace", tr)
        }
        return o
    }

    private fun jsonToSession(o: org.json.JSONObject?): SniffSession? {
        if (o == null) return null
        return try {
            val cands = mutableListOf<SniffCandidateRecord>()
            val arr = o.optJSONArray("candidates")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val co = arr.optJSONObject(i) ?: continue
                    cands += SniffCandidateRecord(
                        url = co.optString("url"),
                        source = co.optString("source"),
                        score = co.optInt("score"),
                        evidence = co.optString("evidence"),
                        kept = co.optBoolean("kept"),
                        dropReason = co.optString("dropReason").takeIf { it.isNotEmpty() },
                        durationSec = co.optDouble("durationSec", -1.0).takeIf { it >= 0 },
                    )
                }
            }
            val po = o.optJSONObject("playback")
            val playback = po?.let {
                PlaybackOutcome(
                    player = it.optString("player"),
                    started = it.optBoolean("started"),
                    secondsPlayed = it.optDouble("secondsPlayed"),
                    wallSeconds = it.optDouble("wallSeconds"),
                    stalled = it.optBoolean("stalled"),
                    error = it.optString("error").takeIf { e -> e.isNotEmpty() },
                )
            }
            val trace = mutableListOf<String>()
            val tr = o.optJSONArray("trace")
            if (tr != null) {
                for (i in 0 until tr.length()) trace += tr.optString(i)
            }
            SniffSession(
                id = o.optLong("id"),
                timeMs = o.optLong("timeMs"),
                siteId = o.optString("siteId"),
                pageUrl = o.optString("pageUrl"),
                candidates = cands,
                pickedUrl = o.optString("pickedUrl").takeIf { it.isNotEmpty() },
                pickedRank = o.optInt("pickedRank", -1).takeIf { it >= 0 },
                pickOutcome = o.optString("pickOutcome", "待選"),
                failStage = o.optString("failStage").takeIf { it.isNotEmpty() },
                playback = playback,
                trace = trace,
            )
        } catch (e: Exception) {
            null
        }
    }
}
