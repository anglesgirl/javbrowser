package com.example.javbrowser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** One line per candidate: type, duration (longest is usually the main video), host, score. */
internal fun candidateLabel(c: SniffCandidateRecord): String {
    val host = try { java.net.URL(c.url).host } catch (e: Exception) { "" }
    val type = if (c.url.lowercase().contains(".m3u8")) "m3u8" else "mp4"
    val dur = c.durationSec?.let {
        "${(it / 60).toInt()}:${(it % 60).toInt().toString().padStart(2, '0')}"
    } ?: "—"
    // Resolver parts (javhdporn Part1/Part2) share one host and no
    // duration, so the part tag in the evidence is the only thing that
    // tells the rows apart.
    val part = Regex("^Part\\d+").find(c.evidence)?.value
    val prefix = if (part != null) "$part · " else ""
    val adMark = if (c.evidence.contains("疑似廣告")) "⚠疑似廣告 · " else ""
    return "$adMark$prefix$type · $dur · $host · ${c.score}分"
}

/**
 * Full-page replacement for the old "选择播放地址" dialog (A方案:
 * 薄列表 + 分站详情 — the list stays a thin common card, and the
 * choice that used to squeeze into a popup is a real page now).
 *
 * One row per candidate; tap to play, or "用浏览器打开". The result
 * echoes back everything the caller needs (session, site, page URL,
 * picked URL/index), so a rotation while this page is open loses
 * nothing. Back press just closes the page, exactly like dismissing
 * the old dialog. Never throws.
 */
class VideoChoiceActivity : LocalizedActivity() {

    companion object {
        const val EXTRA_TITLE = "video_choice_title"
        const val EXTRA_SITE_ID = "video_choice_site_id"
        const val EXTRA_DETAIL_URL = "video_choice_detail_url"
        const val EXTRA_SESSION_ID = "video_choice_session_id"
        const val EXTRA_CANDIDATES_JSON = "video_choice_candidates_json"
        const val EXTRA_AD_CANDIDATES_JSON = "video_choice_ad_candidates_json"
        const val EXTRA_RESULT_KIND = "video_choice_result_kind"
        const val EXTRA_PICKED_INDEX = "video_choice_picked_index"
        const val EXTRA_PICKED_URL = "video_choice_picked_url"
        const val RESULT_KIND_PICK = "pick"
        const val RESULT_KIND_BROWSER = "browser"

        fun intent(context: Context, item: SearchItem, candidates: List<SniffCandidateRecord>, sessionId: Long, ads: List<SniffCandidateRecord> = emptyList()): Intent =
            Intent(context, VideoChoiceActivity::class.java).apply {
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_SITE_ID, item.siteId)
                putExtra(EXTRA_DETAIL_URL, item.detailUrl)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CANDIDATES_JSON, candidatesToJson(candidates))
                putExtra(EXTRA_AD_CANDIDATES_JSON, candidatesToJson(ads))
            }

        fun candidatesToJson(candidates: List<SniffCandidateRecord>): String {
            val arr = org.json.JSONArray()
            for (c in candidates) {
                arr.put(org.json.JSONObject().apply {
                    put("url", c.url)
                    put("source", c.source)
                    put("score", c.score)
                    put("evidence", c.evidence)
                    put("kept", c.kept)
                    put("dropReason", c.dropReason ?: org.json.JSONObject.NULL)
                    put("durationSec", if (c.durationSec == null) org.json.JSONObject.NULL else c.durationSec)
                })
            }
            return arr.toString()
        }

        fun candidatesFromJson(json: String): List<SniffCandidateRecord> {
            val out = mutableListOf<SniffCandidateRecord>()
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out += SniffCandidateRecord(
                        url = o.optString("url"),
                        source = o.optString("source"),
                        score = o.optInt("score"),
                        evidence = o.optString("evidence"),
                        kept = o.optBoolean("kept", true),
                        dropReason = if (o.isNull("dropReason")) null else o.optString("dropReason"),
                        durationSec = if (o.isNull("durationSec")) null else o.optDouble("durationSec"),
                    )
                }
            } catch (e: Exception) {
                // Malformed extra: the caller guards the empty list.
            }
            return out
        }
    }

    private var sessionId = -1L
    private var siteId = ""
    private var detailUrl = ""
    private var title = ""
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private val candidates = mutableListOf<SniffCandidateRecord>()
    private val adCandidates = mutableListOf<SniffCandidateRecord>()
    private var adsExpanded = false
    private var adapter: ChoiceAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_choice)
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        siteId = intent.getStringExtra(EXTRA_SITE_ID).orEmpty()
        detailUrl = intent.getStringExtra(EXTRA_DETAIL_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val initial = candidatesFromJson(intent.getStringExtra(EXTRA_CANDIDATES_JSON).orEmpty())
        val initialAds = candidatesFromJson(intent.getStringExtra(EXTRA_AD_CANDIDATES_JSON).orEmpty())
        if (initial.isEmpty() && initialAds.isEmpty()) {
            finish()
            return
        }
        candidates.addAll(initial)
        adCandidates.addAll(initialAds)
        // No kept candidate at all: open the folded ads so the user
        // still sees something playable instead of an empty list.
        adsExpanded = candidates.isEmpty() && adCandidates.isNotEmpty()
        findViewById<TextView>(R.id.tv_video_choice_title).text = title
        findViewById<TextView>(R.id.tv_video_choice_subtitle).text =
            tr("选择播放地址", "Choose video URL")
        adapter = ChoiceAdapter(candidates, adCandidates, adsExpanded, { record, rank ->
            val url = record.url
            // Record the pick here: the picker no longer finishes on
            // pick (it stays alive so back from the player returns to
            // the list), so the choiceLauncher in UnifiedSearchActivity
            // never sees this pick.
            try {
                PlaybackDataStore.recordPick(sessionId, url, rank)
                val target = if (siteId == SearchSiteIds.JAVHD || siteId == SearchSiteIds.HENTAIMAMA) {
                    NativePlayerActivity::class.java
                } else {
                    FullscreenInternalPlayerActivity::class.java
                }
                val referer = if (siteId == SearchSiteIds.JAVHD) {
                    UnifiedSearchActivity.javhdEmbedRefererByUrl[url].orEmpty().ifEmpty { detailUrl }
                } else {
                    detailUrl
                }
                PlaybackDataStore.addTrace(sessionId, "點候選：啟動播放器 ${target.simpleName} url=${url.take(80)} referer=${referer.take(60)}")
                android.widget.Toast.makeText(this, "正在打開播放器…", android.widget.Toast.LENGTH_SHORT).show()
                startActivity(android.content.Intent(this, target).apply {
                    putExtra(FullscreenInternalPlayerActivity.EXTRA_VIDEO_URL, url)
                    putExtra(FullscreenInternalPlayerActivity.EXTRA_REFERER, referer)
                    putExtra(FullscreenInternalPlayerActivity.EXTRA_PAGE_URL, detailUrl)
                    putExtra(FullscreenInternalPlayerActivity.EXTRA_DOWNLOAD_NAME, title)
                    putExtra(FullscreenInternalPlayerActivity.EXTRA_SESSION_ID, sessionId)
                })
                PlaybackDataStore.addTrace(sessionId, "點候選：startActivity 已調用")
            } catch (e: Throwable) {
                PlaybackDataStore.addTrace(sessionId, "點候選失敗：${e.javaClass.simpleName} ${e.message}")
                android.widget.Toast.makeText(this, "打開播放器失敗：${e.javaClass.simpleName} ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                android.util.Log.e("VIDEO_CHOICE", "pick failed url=$url", e)
            }
            // Do NOT finish: the list stays, and back from the player
            // returns here instead of losing the list and forcing a
            // re-tap plus a full re-extraction.
        }) {
            adsExpanded = !adsExpanded
            adapter?.setAdsExpanded(adsExpanded)
        }
        findViewById<RecyclerView>(R.id.rv_video_choice).apply {
            layoutManager = LinearLayoutManager(this@VideoChoiceActivity)
            adapter = this@VideoChoiceActivity.adapter
        }
        findViewById<Button>(R.id.btn_video_choice_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_video_choice_browser).setOnClickListener {
            finishWith(RESULT_KIND_BROWSER)
        }
        // The resolver surfaces the first decoded Part immediately
        // and decodes the rest in the background; refresh this list
        // from the session store so later Parts appear live instead
        // of needing a re-tap. Stops in onDestroy.
        val poll = object : Runnable {
            override fun run() {
                val latest = PlaybackDataStore.snapshot().firstOrNull { it.id == sessionId }?.candidates.orEmpty()
                val known = (candidates + adCandidates).map { it.url }.toSet()
                val fresh = latest.filter { it.url !in known }
                if (fresh.isNotEmpty()) {
                    val freshMain = fresh.filter { it.kept }
                    val freshAds = fresh.filter { !it.kept && (it.dropReason?.contains("廣告") == true) }
                    var changed = false
                    if (freshMain.isNotEmpty()) {
                        candidates.addAll(freshMain)
                        changed = true
                    }
                    if (freshAds.isNotEmpty()) {
                        adCandidates.addAll(freshAds)
                        changed = true
                    }
                    // The ad-group header shifts when main grows, so a
                    // full refresh is the safe notify here.
                    if (changed) adapter?.notifyDataSetChanged()
                }
                handler.postDelayed(this, 1000)
            }
        }
        refreshRunnable = poll
        handler.postDelayed(poll, 1000)
    }

    override fun onDestroy() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun finishWith(kind: String, index: Int = -1, url: String? = null) {
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(EXTRA_RESULT_KIND, kind)
            putExtra(EXTRA_PICKED_INDEX, index)
            putExtra(EXTRA_PICKED_URL, url)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_SITE_ID, siteId)
            putExtra(EXTRA_DETAIL_URL, detailUrl)
            putExtra(EXTRA_TITLE, title)
        })
        finish()
    }

    private fun tr(zh: String, en: String) = LanguageManager.text(this, zh, en)

    /**
     * Main candidates, then one collapsible 「疑似廣告」 header row, then
     * the folded ad candidates when expanded. Tapping the header folds /
     * unfolds the group, so a wrong ad call can be undone by picking one
     * of the folded rows. The ad rows reuse the same row layout and the
     * same play path as the main rows.
     */
    private class ChoiceAdapter(
        private val main: MutableList<SniffCandidateRecord>,
        private val ads: MutableList<SniffCandidateRecord>,
        private var adsExpanded: Boolean,
        private val onPick: (SniffCandidateRecord, Int) -> Unit,
        private val onToggleAds: () -> Unit,
    ) : RecyclerView.Adapter<ChoiceAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.tv_choice_row)
        }

        fun setAdsExpanded(expanded: Boolean) {
            adsExpanded = expanded
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int {
            if (ads.isEmpty()) return main.size
            return main.size + 1 + if (adsExpanded) ads.size else 0
        }

        override fun getItemViewType(position: Int): Int {
            return when {
                position < main.size -> 0
                position == main.size -> 1
                else -> 2
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_choice_row, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            when (getItemViewType(position)) {
                0 -> {
                    val c = main[position]
                    holder.text.text = candidateLabel(c)
                    holder.itemView.setOnClickListener { onPick(c, position) }
                }
                1 -> {
                    holder.text.text = if (adsExpanded) "⚠疑似廣告 (${ads.size}) — 點擊收起"
                    else "⚠疑似廣告 (${ads.size}) — 點擊展開"
                    holder.itemView.setOnClickListener { onToggleAds() }
                }
                else -> {
                    val adIndex = position - main.size - 1
                    val c = ads[adIndex]
                    holder.text.text = candidateLabel(c)
                    holder.itemView.setOnClickListener { onPick(c, main.size + adIndex) }
                }
            }
        }
    }
}
