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

/**
 * Full-page replacement for the old "選擇一集" dialog (A方案:
 * 薄列表 + 分站详情 — HentaiMama series get their own episode
 * page instead of a popup). One row per episode ("第 N 集"); tap
 * to resolve that episode, or "用浏览器打开". The result echoes
 * back everything the caller needs (session, site, series URL,
 * picked episode URL), so a rotation while this page is open loses
 * nothing. Back press just closes the page, exactly like dismissing
 * the old dialog. Never throws.
 */
class HentaiMamaEpisodeActivity : LocalizedActivity() {

    companion object {
        const val EXTRA_TITLE = "hm_episode_title"
        const val EXTRA_SITE_ID = "hm_episode_site_id"
        const val EXTRA_DETAIL_URL = "hm_episode_detail_url"
        const val EXTRA_SESSION_ID = "hm_episode_session_id"
        const val EXTRA_EPISODES_JSON = "hm_episode_episodes_json"
        const val EXTRA_RESULT_KIND = "hm_episode_result_kind"
        const val EXTRA_PICKED_INDEX = "hm_episode_picked_index"
        const val EXTRA_PICKED_URL = "hm_episode_picked_url"
        const val RESULT_KIND_PICK = "pick"
        const val RESULT_KIND_BROWSER = "browser"

        fun intent(context: Context, item: SearchItem, episodes: List<Pair<String, String>>, sessionId: Long): Intent =
            Intent(context, HentaiMamaEpisodeActivity::class.java).apply {
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_SITE_ID, item.siteId)
                putExtra(EXTRA_DETAIL_URL, item.detailUrl)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_EPISODES_JSON, episodesToJson(episodes))
            }

        fun episodesToJson(episodes: List<Pair<String, String>>): String {
            val arr = org.json.JSONArray()
            for ((url, label) in episodes) {
                arr.put(org.json.JSONObject().apply {
                    put("url", url)
                    put("label", label)
                })
            }
            return arr.toString()
        }

        fun episodesFromJson(json: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url")
                    if (url.isBlank()) continue
                    out += url to o.optString("label")
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
        setContentView(R.layout.activity_hentaimama_episodes)
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        siteId = intent.getStringExtra(EXTRA_SITE_ID).orEmpty()
        detailUrl = intent.getStringExtra(EXTRA_DETAIL_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val episodes = episodesFromJson(intent.getStringExtra(EXTRA_EPISODES_JSON).orEmpty())
        if (episodes.isEmpty()) {
            finish()
            return
        }
        findViewById<TextView>(R.id.tv_episode_title).text = title
        findViewById<TextView>(R.id.tv_episode_subtitle).text =
            tr("選擇一集", "Choose an episode")
        findViewById<RecyclerView>(R.id.rv_episodes).apply {
            layoutManager = LinearLayoutManager(this@HentaiMamaEpisodeActivity)
            adapter = EpisodeAdapter(episodes) { index ->
                finishWith(RESULT_KIND_PICK, index, episodes[index].first)
            }
        }
        findViewById<Button>(R.id.btn_episode_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_episode_browser).setOnClickListener {
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

    private class EpisodeAdapter(
        private val episodes: List<Pair<String, String>>,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.tv_choice_row)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_choice_row, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.text.text = episodes[position].second
            holder.itemView.setOnClickListener { onPick(position) }
        }

        override fun getItemCount(): Int = episodes.size
    }
}
