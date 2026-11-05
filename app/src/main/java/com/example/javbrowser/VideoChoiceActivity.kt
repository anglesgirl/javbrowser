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
    return "$prefix$type · $dur · $host · ${c.score}分"
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
        const val EXTRA_RESULT_KIND = "video_choice_result_kind"
        const val EXTRA_PICKED_INDEX = "video_choice_picked_index"
        const val EXTRA_PICKED_URL = "video_choice_picked_url"
        const val RESULT_KIND_PICK = "pick"
        const val RESULT_KIND_BROWSER = "browser"

        fun intent(context: Context, item: SearchItem, candidates: List<SniffCandidateRecord>, sessionId: Long): Intent =
            Intent(context, VideoChoiceActivity::class.java).apply {
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_SITE_ID, item.siteId)
                putExtra(EXTRA_DETAIL_URL, item.detailUrl)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CANDIDATES_JSON, candidatesToJson(candidates))
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_choice)
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        siteId = intent.getStringExtra(EXTRA_SITE_ID).orEmpty()
        detailUrl = intent.getStringExtra(EXTRA_DETAIL_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val candidates = candidatesFromJson(intent.getStringExtra(EXTRA_CANDIDATES_JSON).orEmpty())
        if (candidates.isEmpty()) {
            finish()
            return
        }
        findViewById<TextView>(R.id.tv_video_choice_title).text = title
        findViewById<TextView>(R.id.tv_video_choice_subtitle).text =
            tr("选择播放地址", "Choose video URL")
        findViewById<RecyclerView>(R.id.rv_video_choice).apply {
            layoutManager = LinearLayoutManager(this@VideoChoiceActivity)
            adapter = ChoiceAdapter(candidates) { index ->
                finishWith(RESULT_KIND_PICK, index, candidates[index].url)
            }
        }
        findViewById<Button>(R.id.btn_video_choice_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_video_choice_browser).setOnClickListener {
            finishWith(RESULT_KIND_BROWSER)
        }
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

    private class ChoiceAdapter(
        private val candidates: List<SniffCandidateRecord>,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<ChoiceAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.tv_choice_row)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_choice_row, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.text.text = candidateLabel(candidates[position])
            holder.itemView.setOnClickListener { onPick(position) }
        }

        override fun getItemCount(): Int = candidates.size
    }
}
