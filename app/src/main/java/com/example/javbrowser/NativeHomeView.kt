package com.example.javbrowser

import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.net.URLEncoder

/**
 * 原生首页（1.2.17-gecko）：替代 landing.html + GeckoSession 加载。
 *
 * 内容与 app/src/main/assets/landing.html 对齐（站点入口、搜索入口、常用
 * 操作），文案按 [isEnglish] 取中英两套。所有点击直接调 [Callbacks] 走
 * MainActivity 现有流程（统一搜索 / 站点浏览 / 外站 GeckoBrowserActivity /
 * 今日新片 / 销量排行 / 播放记录 / 帮助 / 存番号 / 剪贴板），不动搜索解析、
 * 播放地址提取、选集、/api/play/ 桌面 UA、VAST 放行、嗅探、Cookie 两仓。
 *
 * 首页不再经过 GeckoSession 加载，也就不再白屏；GeckoView 只在进入外站时
 * 才由 MainActivity 显示。
 */
class NativeHomeView(
    private val root: View,
    private val callbacks: Callbacks,
    private val isEnglish: Boolean,
    private val domainConfig: DomainConfig,
) {

    interface Callbacks {
        fun onUnifiedSearch(query: String)
        fun onSiteBrowse(siteId: String, url: String)
        fun onNavigateToUrl(url: String)
        fun onLoadGeckoUrl(url: String)
        fun onTodayNew()
        fun onSalesRankings()
        fun onPlaybackHistory()
        fun onHelp()
        fun onSaveJavCodes(codes: List<String>)
        fun onClipboardOpen()
        fun onClipboardDismiss()
        fun extractJavCodes(text: String): List<String>
    }

    // ── 文案（与 loadLandingPage() 传给 landing.html 的 JSON 一致） ──
    private val tSearchPlaceholder = if (isEnglish) "Search keywords or video code..." else "輸入搜尋關鍵字 / 番號..."
    private val tUnifiedSearch = if (isEnglish) "Search all sites · Unified results" else "統一跨站搜尋 · 彙整結果"
    private val tFallbackSummary = if (isEnglish) "Fallback: search on an original site" else "備用：到個別原站搜尋"
    private val tSearchHintPrefix = if (isEnglish) "Search: " else "搜尋內容："
    private val tDirectLinks = if (isEnglish) "Go directly to" else "或直接前往"
    private val tRecentReleases = if (isEnglish) "📅 Releases from the last 3 days" else "📅 近3日新着リスト"
    private val tSalesRankings = if (isEnglish) "📊 Sales rankings" else "📊 銷售排行"
    private val tPlaybackHistory = if (isEnglish) "📜 Playback history" else "📜 播放紀錄"
    private val tClipboardFrom = if (isEnglish) "Copied link from " else "剪貼簿網址："
    private val tClipboardAsk = if (isEnglish) "Open this page?" else "要前往這個頁面嗎？"
    private val tClipboardOpen = if (isEnglish) "Open page" else "前往此頁面"
    private val tClipboardDismiss = if (isEnglish) "Not now" else "暫時不要"
    private val tRouVideos = if (isEnglish) "ROU · Videos" else "ROU · 影片"
    private val tRouSeries = if (isEnglish) "ROU · Series" else "ROU · 影集"
    private val tBookmarkSinglePrefix = if (isEnglish) "Add bookmark: " else "存入書籤："
    private val tBookmarkBatchPrefix = if (isEnglish) "Add bookmarks (" else "批量存入書籤 ("
    private val tBookmarkCountSuffix = if (isEnglish) "): " else "個)："
    private val tSavingLabel = if (isEnglish) "Saving... " else "存入中... "
    private val tSavedPrefix = if (isEnglish) "✅ Saved " else "✅ 已存入 "
    private val tSavedSuffix = if (isEnglish) " items" else " 個"
    private val tAdHeadline = if (isEnglish) "🐱 Lingmao Games — discounts, bonuses and member perks" else "🐱 靈貓遊戲 - 超低折扣 | 海量福利 | 專屬特權"
    private val tAdAction = if (isEnglish) "Tap to download the app" else "點擊下載 APP"
    private val tAdUrl = "https://www.277sy.com/index.php/Rmiddle/down_ra/?appid=401&tgid=da0003500&type=1"

    private val context get() = root.context

    private val searchInput: EditText = root.findViewById(R.id.native_home_search_input)
    private val searchButton: Button = root.findViewById(R.id.native_home_search_button)
    private val javHintButton: Button = root.findViewById(R.id.native_home_jav_hint)
    private val searchResults: LinearLayout = root.findViewById(R.id.native_home_search_results)
    private val searchResultsHeader: TextView = root.findViewById(R.id.native_home_search_results_header)
    private val searchHint: TextView = root.findViewById(R.id.native_home_search_hint)
    private val searchGrid: GridLayout = root.findViewById(R.id.native_home_search_grid)
    private val quickGrid: GridLayout = root.findViewById(R.id.native_home_quick_grid)
    private val divider: TextView = root.findViewById(R.id.native_home_divider)
    private val siteGrid: GridLayout = root.findViewById(R.id.native_home_site_grid)
    private val historyButton: Button = root.findViewById(R.id.native_home_history)
    private val clipboardCard: LinearLayout = root.findViewById(R.id.native_home_clipboard_card)
    private val clipboardTitle: TextView = root.findViewById(R.id.native_home_clipboard_title)
    private val clipboardAsk: TextView = root.findViewById(R.id.native_home_clipboard_ask)
    private val clipboardUrl: TextView = root.findViewById(R.id.native_home_clipboard_url)
    private val clipboardOpen: Button = root.findViewById(R.id.native_home_clipboard_open)
    private val clipboardDismiss: Button = root.findViewById(R.id.native_home_clipboard_dismiss)

    /** 当前搜索框里检测到的番号（供保存按钮用）。 */
    private var currentJavCodes: List<String> = emptyList()
    /** 当前关键词对应的 12 个原站搜索地址（输入变化时重建）。 */
    private var currentSearchUrls: List<String> = emptyList()
    /** 搜索结果区是否已展开（点标题切换）。 */
    private var searchResultsExpanded = false
    /** 存番号进行中（按钮禁用中，防重复点）。 */
    private var javSaving = false

    init {
        searchInput.hint = tSearchPlaceholder
        searchButton.text = tUnifiedSearch
        divider.text = tDirectLinks
        historyButton.text = tPlaybackHistory
        root.findViewById<TextView>(R.id.native_home_ad_headline).text = tAdHeadline
        root.findViewById<TextView>(R.id.native_home_ad_action).text = tAdAction
        clipboardAsk.text = tClipboardAsk
        clipboardOpen.text = tClipboardOpen
        clipboardDismiss.text = tClipboardDismiss

        root.findViewById<View>(R.id.native_home_help).setOnClickListener { callbacks.onHelp() }
        searchButton.setOnClickListener { submitSearch() }
        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submitSearch()
                true
            } else {
                false
            }
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onSearchTextChanged(s?.toString().orEmpty())
        })
        javHintButton.setOnClickListener {
            if (!javSaving && currentJavCodes.isNotEmpty()) {
                javSaving = true
                javHintButton.isEnabled = false
                javHintButton.text = tSavingLabel + "0 / " + currentJavCodes.size
                callbacks.onSaveJavCodes(currentJavCodes)
            }
        }
        searchResultsHeader.setOnClickListener {
            searchResultsExpanded = !searchResultsExpanded
            updateSearchResultsHeader()
            searchGrid.visibility = if (searchResultsExpanded) View.VISIBLE else View.GONE
        }
        historyButton.setOnClickListener { callbacks.onPlaybackHistory() }
        clipboardOpen.setOnClickListener { callbacks.onClipboardOpen() }
        clipboardDismiss.setOnClickListener { callbacks.onClipboardDismiss() }
        root.findViewById<View>(R.id.native_home_ad_card).setOnClickListener {
            callbacks.onNavigateToUrl(tAdUrl)
        }

        buildQuickGrid()
        buildSiteGrid()
        buildSearchGrid()
    }

    private fun submitSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isNotEmpty()) callbacks.onUnifiedSearch(query)
    }

    /** 搜索框内容变化：番号提示 + 搜索结果区 + 12 个原站搜索地址。 */
    private fun onSearchTextChanged(text: String) {
        val keyword = text.trim()
        currentJavCodes = callbacks.extractJavCodes(keyword)
        updateJavHint()
        if (keyword.isEmpty()) {
            searchResults.visibility = View.GONE
            searchResultsExpanded = false
            currentSearchUrls = emptyList()
            return
        }
        searchHint.text = tSearchHintPrefix + keyword
        currentSearchUrls = buildSearchUrls(keyword)
        searchResults.visibility = View.VISIBLE
        updateSearchResultsHeader()
        // 关键词变化时保持收合（与 landing.html 的 <details> 默认收合一致）。
        searchGrid.visibility = if (searchResultsExpanded) View.VISIBLE else View.GONE
    }

    private fun updateSearchResultsHeader() {
        searchResultsHeader.text = tFallbackSummary + if (searchResultsExpanded) " −" else " +"
    }

    private fun updateJavHint() {
        val codes = currentJavCodes
        if (codes.isEmpty() || javSaving) {
            if (!javSaving) javHintButton.visibility = View.GONE
            return
        }
        javHintButton.visibility = View.VISIBLE
        javHintButton.isEnabled = true
        javHintButton.text = if (codes.size == 1) {
            tBookmarkSinglePrefix + codes[0]
        } else {
            tBookmarkBatchPrefix + codes.size + tBookmarkCountSuffix +
                codes.take(3).joinToString(", ") + if (codes.size > 3) "…" else ""
        }
    }

    /**
     * 存番号进度（processJavCodeQueue / processFc2CodeQueue 每存完一个调一次，
     * 替代原来调 landing.html 里 updateBatchProgress 的 GeckoEval）。
     */
    fun updateJavSaveProgress(done: Int, total: Int) {
        if (total <= 0) return
        javHintButton.text = if (done >= total) {
            tSavedPrefix + total + tSavedSuffix
        } else {
            tSavingLabel + done + " / " + total
        }
        if (done >= total) {
            javHintButton.postDelayed({
                javSaving = false
                updateJavHint()
            }, 1800)
        }
    }

    /** 剪贴板检测到站内链接：显示卡片。无链接时隐藏卡片。 */
    fun showClipboardResult(linkSite: String?, linkUrl: String?) {
        if (linkSite == null || linkUrl == null) {
            clipboardCard.visibility = View.GONE
            return
        }
        clipboardTitle.text = tClipboardFrom + linkSite
        clipboardUrl.text = linkUrl
        clipboardCard.visibility = View.VISIBLE
    }

    /** 剪贴板无链接但有番号：填进搜索框（与 landing.html onClipboardResult 一致）。 */
    fun prefillSearchFromClipboard(codes: List<String>) {
        if (codes.isEmpty()) return
        searchInput.setText(codes.joinToString(" "))
        searchInput.setSelection(searchInput.text.length)
    }

    fun clearSearch() {
        searchInput.setText("")
    }

    // ── 网格搭建 ────────────────────────────────────────────────────

    private fun gridButton(
        label: String,
        backgroundRes: Int,
        textColor: Int,
        minHeightDp: Int = 48,
        textSizeSp: Float = 14f,
        bold: Boolean = true,
    ): Button {
        val dp = context.resources.displayMetrics.density
        return Button(context).apply {
            text = label
            setBackgroundResource(backgroundRes)
            setTextColor(textColor)
            textSize = textSizeSp
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            minHeight = (minHeightDp * dp).toInt()
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }
    }

    private fun addToGrid(grid: GridLayout, view: View, column: Int, row: Int, marginDp: Int = 4) {
        val dp = context.resources.displayMetrics.density
        val m = (marginDp * dp).toInt()
        val params = GridLayout.LayoutParams(
            GridLayout.spec(row, 1f),
            GridLayout.spec(column, 1f),
        ).apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            setMargins(m, m, m, m)
        }
        grid.addView(view, params)
    }

    private fun buildQuickGrid() {
        val todayNew = gridButton(
            tRecentReleases, R.drawable.rounded_button_bg,
            ContextCompat.getColor(context, android.R.color.white), 54,
        ).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())
            setOnClickListener { callbacks.onTodayNew() }
        }
        val sales = gridButton(
            tSalesRankings, R.drawable.rounded_button_bg,
            ContextCompat.getColor(context, android.R.color.white), 54,
        ).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00897B.toInt())
            setOnClickListener { callbacks.onSalesRankings() }
        }
        addToGrid(quickGrid, todayNew, 0, 0)
        addToGrid(quickGrid, sales, 1, 0)
    }

    /** 站点入口（与 landing.html .site-grid 一致：13 个走原生浏览，5 个走外站）。 */
    private fun buildSiteGrid() {
        data class Site(val label: String, val browse: Pair<String, () -> String>? = null, val externalUrl: String? = null)
        val sites = listOf(
            Site("MissAV", "missav" to { domainConfig.getMissAvBaseUrl() }),
            Site("Jable", "jable" to { "https://" + domainConfig.getJableDomain() + "/" }),
            Site("Rou.Video", "rou_video" to { "https://" + domainConfig.getRouVideoDomain() + "/home" }),
            Site("AvJoy", "avjoy" to { "https://" + domainConfig.getAvJoyDomain() + "/" }),
            Site("PigAV", "pigav" to { "https://pigav.ws/" }),
            Site("AVToday", "avtoday" to { "https://avtoday.io/cht/index.html" }),
            Site("JavHDPorn", "javhd" to { "https://www.javhdporn.net/" }),
            Site("7MMTV", "7mmtv" to { domainConfig.get7MmTvBaseUrl() }),
            Site("Avple", "avple" to { domainConfig.getAvpleBaseUrl() }),
            Site("Whos.tv", "whos" to { domainConfig.getWhosBaseUrl() }),
            Site("JAVDAY", "javday" to { "https://javday.app/" }),
            Site("SUPJAV", "supjav" to { "https://supjav.com/" }),
            Site("JavRate", "javrate" to { "https://www.javrate.com/" }),
            Site("JavDB", externalUrl = "https://javdb.com/"),
            Site("JavTrailers", externalUrl = "https://javtrailers.com/ja/videos"),
            Site("Stripchat", externalUrl = "https://zt.stripchat.com/"),
            Site("Pornhub CN", externalUrl = "https://cn.pornhub.com/"),
            Site("XVideos", externalUrl = "https://www.xvideos.com/"),
        )
        sites.forEachIndexed { index, site ->
            val button = gridButton(site.label, R.drawable.site_button_bg, 0xFF24152B.toInt()).apply {
                setOnClickListener {
                    val browse = site.browse
                    if (browse != null) {
                        callbacks.onSiteBrowse(browse.first, browse.second())
                    } else if (site.externalUrl != null) {
                        callbacks.onNavigateToUrl(site.externalUrl)
                    }
                }
            }
            addToGrid(siteGrid, button, index % 2, index / 2)
        }
    }

    /** 原站搜索入口（与 landing.html .search-site-grid 一致，12 个）。 */
    private fun buildSearchGrid() {
        val labels = listOf(
            "MissAV", "Jable.TV", "AvJoy", "PigAV", "AVToday", "JavHDPorn",
            "7MMTV", "Avple", "Whos.tv", "JAVDAY", tRouVideos, tRouSeries,
        )
        labels.forEachIndexed { index, label ->
            val button = gridButton(label, R.drawable.site_button_bg, 0xFF24152B.toInt()).apply {
                setOnClickListener {
                    val url = currentSearchUrls.getOrNull(index) ?: return@setOnClickListener
                    callbacks.onLoadGeckoUrl(url)
                }
            }
            addToGrid(searchGrid, button, index % 2, index / 2)
        }
    }

    /** 12 个原站搜索地址（与 landing.html 的 input 监听里拼的 URL 一致）。 */
    private fun buildSearchUrls(keyword: String): List<String> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        return listOf(
            "https://" + domainConfig.getMissAvDomain() + "/search/" + q,
            "https://jable.tv/search/" + q + "/",
            "https://" + domainConfig.getAvJoyDomain() + "/search/videos/" + q,
            "https://pigav.ws/search?search=" + q + "&searchTarget=local",
            "https://avtoday.io/search?s=" + q,
            "https://www.javhdporn.net/?s=" + q,
            "https://" + domainConfig.get7MmTvDomain() + "/zh/searchall_search/all/" + q + "/1.html",
            "https://" + domainConfig.getAvpleDomain() + "/search?key=" + q,
            "https://" + domainConfig.getWhosDomain() + "/result?search=" + q,
            "https://javday.app/search/?wd=" + q,
            "https://" + domainConfig.getRouVideoDomain() + "/search?q=" + q,
            "https://" + domainConfig.getRouVideoDomain() + "/search?q=" + q + "&tab=series",
        )
    }
}
