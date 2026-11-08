package com.example.javbrowser

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 九個來源共用的搜尋與結果頁。 */
class UnifiedSearchActivity : LocalizedActivity(), SearchSnapshotListener {
    companion object {
        const val EXTRA_QUERY = "unified_search_query"
        const val EXTRA_FORCE_CODE = "unified_search_force_code"
        /** Browse mode: open one site as a native list (standard-app style). */
        const val EXTRA_BROWSE_SITE = "unified_search_browse_site"
        const val EXTRA_BROWSE_URL = "unified_search_browse_url"

        /** Embed page per extracted javhd file URL (file URL -> embed
         * URL). The player sends the embed page as Referer — the video
         * CDN rejects the detail page as referer (HTTP 403), while the
         * embed page is the real player referer. Static so
         * VideoChoiceActivity can launch the player itself (keeping
         * the picker alive) and look the referer up. Keyed by the
         * unique file URL (sign is per-extraction), so entries never
         * collide across videos; not cleared per-sniff so a picker
         * opened earlier still finds its referer. */
        val javhdEmbedRefererByUrl = mutableMapOf<String, String>()
    }

    private lateinit var queryEdit: EditText
    private lateinit var searchButton: Button
    private lateinit var stopButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var siteChooser: LinearLayout
    private lateinit var siteStatuses: LinearLayout
    private lateinit var sourceSpinner: Spinner
    private lateinit var resultsView: RecyclerView
    private lateinit var adapter: UnifiedSearchAdapter
    private lateinit var repository: UnifiedSearchRepository
    private lateinit var sites: List<SearchSite>
    private val siteChecks = LinkedHashMap<String, CheckBox>()
    private var session: UnifiedSearchRepository.UnifiedSearchSession? = null
    private var lastSnapshot: SearchSnapshot? = null
    private var forceCodeForNextSearch = false
    private var selectedSource = ""
    private var activeRequestId: String? = null
    private var restoredListState: android.os.Parcelable? = null
    private var panelState = SearchPanelState()
    private lateinit var savedSearchStore: SavedSearchStore
    private var savedSearches = emptyList<SavedSearchInfo>()
    private var savedSearchesReady = false
    private var savingSearch = false
    private var savedReadSerial = 0
    private var openedSavedAt: Long? = null
    private var openedSavedKey: String? = null
    private var runMode = SearchRunMode.BROWSE
    private var queryMode = SearchInputMode.AUTO
    private var sortMode = SearchSortMode.RELEVANCE
    private var preserveSessionOnStop = false
    private var renderedSiteStates: List<SiteSearchState>? = null
    private var readAnchor: SearchReadAnchor? = null
    private var restoredAnchor: SearchReadAnchor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unified_search)

        repository = UnifiedSearchRepository(this)
        sites = SearchSiteRegistry.sites(this)
        savedSearchStore = SavedSearchStore(java.io.File(noBackupFilesDir, "saved_searches"))
        openedSavedAt = savedInstanceState?.getLong("opened_saved_at", 0L)?.takeIf { it > 0 }
        openedSavedKey = savedInstanceState?.getString("opened_saved_key")
        queryEdit = findViewById(R.id.et_unified_query)
        searchButton = findViewById(R.id.btn_unified_search)
        stopButton = findViewById(R.id.btn_unified_stop)
        progress = findViewById(R.id.progress_unified)
        statusText = findViewById(R.id.tv_unified_status)
        siteChooser = findViewById(R.id.unified_site_chooser)
        siteStatuses = findViewById(R.id.unified_site_statuses)
        sourceSpinner = findViewById(R.id.spinner_unified_source)
        panelState = SearchPanelState(
            savedInstanceState?.getBoolean("controls_collapsed") ?: false,
            savedInstanceState?.getBoolean("controls_collapse_handled") ?: false
        )
        runMode = if (savedInstanceState?.getString("run_mode") == SearchRunMode.DEEP.name)
            SearchRunMode.DEEP else SearchRunMode.BROWSE
        queryMode = runCatching { SearchInputMode.valueOf(savedInstanceState?.getString("query_mode").orEmpty()) }
            .getOrDefault(SearchInputMode.AUTO)
        sortMode = runCatching { SearchSortMode.valueOf(savedInstanceState?.getString("sort_mode").orEmpty()) }
            .getOrDefault(SearchSortMode.RELEVANCE)
        findViewById<Button>(R.id.btn_unified_run_mode).setOnClickListener {
            runMode = if (runMode == SearchRunMode.BROWSE) SearchRunMode.DEEP else SearchRunMode.BROWSE
            renderPanel()
        }
        findViewById<Button>(R.id.btn_unified_query_mode).setOnClickListener {
            queryMode = when (queryMode) {
                SearchInputMode.AUTO -> SearchInputMode.CODE
                SearchInputMode.CODE -> SearchInputMode.KEYWORD
                SearchInputMode.KEYWORD -> SearchInputMode.AUTO
            }
            renderPanel()
        }
        findViewById<Button>(R.id.btn_unified_sort_mode).setOnClickListener {
            sortMode = when (sortMode) {
                SearchSortMode.RELEVANCE -> SearchSortMode.SOURCE_PREFERENCE
                SearchSortMode.SOURCE_PREFERENCE -> SearchSortMode.ORIGINAL
                SearchSortMode.ORIGINAL -> SearchSortMode.RELEVANCE
            }
            renderItems(lastSnapshot)
            renderPanel()
        }
        findViewById<Button>(R.id.btn_unified_toggle_controls).setOnClickListener {
            panelState = panelState.toggle()
            if (panelState.collapsed) hideSearchKeyboard()
            renderPanel()
        }
        renderPanel()

        adapter = UnifiedSearchAdapter(sites.associate { it.id to it.displayName },
            onOpen = { item -> openResult(item) },
            onShowSources = { group ->
                rememberReadAnchor()
                UnifiedSearchSourceSheet.show(this, group, sites.associate { it.id to it.displayName }) { item ->
                    openResult(item)
                }
            })
        resultsView = findViewById<RecyclerView>(R.id.rv_unified_results).apply {
            layoutManager = LinearLayoutManager(this@UnifiedSearchActivity)
            adapter = this@UnifiedSearchActivity.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) this@UnifiedSearchActivity.adapter.setOrderFrozen(true)
                }
            })
        }
        adapter.onListCommitted = { restoreReadAnchorIfNeeded() }

        loadLocalMetadata()

        setupSiteChooser()
        setupSourceFilter()
        findViewById<Button>(R.id.btn_unified_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_unified_select_all).setOnClickListener {
            siteChecks.values.forEach { it.isChecked = true }
        }
        findViewById<Button>(R.id.btn_unified_clear_sites).setOnClickListener {
            siteChecks.values.forEach { it.isChecked = false }
        }
        searchButton.setOnClickListener { startSearch() }
        findViewById<Button>(R.id.btn_unified_save_search).setOnClickListener { saveCurrentSearch() }
        findViewById<Button>(R.id.btn_unified_saved_searches).setOnClickListener { showSavedSearches() }
        stopButton.setOnClickListener { session?.cancel() }
        findViewById<Button>(R.id.btn_unified_copy_diagnostics).setOnClickListener {
            copyDiagnostics(lastSnapshot?.siteStates.orEmpty())
        }
        findViewById<Button>(R.id.btn_unified_view_log).setOnClickListener { openNetworkLog() }
        findViewById<Button>(R.id.btn_unified_view_log_panel).setOnClickListener { openNetworkLog() }
        findViewById<Button>(R.id.btn_unified_view_playback_data_panel).setOnClickListener { openPlaybackData() }
        PlaybackDataStore.init(applicationContext)
        findViewById<Button>(R.id.btn_unified_resume).setOnClickListener { session?.resume() }
        findViewById<Button>(R.id.btn_unified_continue_pages).setOnClickListener {
            session?.continuePages(selectedSource.takeIf { it.isNotBlank() })
        }
        queryEdit.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                startSearch()
                true
            } else false
        }

        queryEdit.setText(savedInstanceState?.getString("query") ?: intent.getStringExtra(EXTRA_QUERY).orEmpty())
        forceCodeForNextSearch = intent.getBooleanExtra(EXTRA_FORCE_CODE, false)
        if (forceCodeForNextSearch) queryMode = SearchInputMode.CODE
        if (savedInstanceState != null) {
            val selected = savedInstanceState.getStringArrayList("sites").orEmpty()
            siteChecks.forEach { (id, check) -> check.isChecked = id in selected }
            sourceSpinner.setSelection(savedInstanceState.getInt("source", 0))
            restoredListState = savedInstanceState.getParcelable("scroll")
            restoredAnchor = savedInstanceState.getString("anchor_group")?.let {
                SearchReadAnchor(it, savedInstanceState.getInt("anchor_offset", 0))
            }
            val previous = SearchSessionStore.get(savedInstanceState.getString("session"))
            if (previous != null) {
                activeRequestId = previous.request.requestId
                session = repository.start(previous.request, this, restored = previous)
            } else {
                panelState = SearchPanelState()
                renderPanel()
                statusText.text = tr("搜尋狀態已清除，請重新搜尋", "Search state was cleared. Search again.")
                openedSavedKey?.let { openSavedSearch(it) }
            }
        }
        val browseSiteId = intent.getStringExtra(EXTRA_BROWSE_SITE)
        val browseSiteUrl = intent.getStringExtra(EXTRA_BROWSE_URL)
        refreshSavedSearches {
            if (savedInstanceState == null && lastSnapshot == null) {
                if (browseSiteId != null && browseSiteUrl != null) startBrowse(browseSiteId, browseSiteUrl)
                else if (queryEdit.text.toString().isNotBlank()) startSearch(false)
            }
        }
    }

    /**
     * Result of the full-page episode list (A方案 — replaces the old
     * "選擇一集" dialog). The result echoes the session and the series
     * item back, so a rotation while the page is open loses nothing;
     * back press returns canceled and nothing happens, exactly like
     * dismissing the old dialog.
     */
    private val episodeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val sessionId = data.getLongExtra(HentaiMamaEpisodeActivity.EXTRA_SESSION_ID, -1L)
        if (sessionId < 0) return@registerForActivityResult
        val detailUrl = data.getStringExtra(HentaiMamaEpisodeActivity.EXTRA_DETAIL_URL).orEmpty()
        when (data.getStringExtra(HentaiMamaEpisodeActivity.EXTRA_RESULT_KIND)) {
            HentaiMamaEpisodeActivity.RESULT_KIND_PICK -> {
                val epUrl = data.getStringExtra(HentaiMamaEpisodeActivity.EXTRA_PICKED_URL).orEmpty()
                if (epUrl.isBlank() || detailUrl.isBlank()) return@registerForActivityResult
                val item = SearchItem(
                    stableId = "", siteId = data.getStringExtra(HentaiMamaEpisodeActivity.EXTRA_SITE_ID).orEmpty(),
                    title = data.getStringExtra(HentaiMamaEpisodeActivity.EXTRA_TITLE).orEmpty(),
                    detailUrl = detailUrl, canonicalUrl = detailUrl)
                resolveHentaiMamaEpisode(item.copy(detailUrl = epUrl), sessionId)
            }
            HentaiMamaEpisodeActivity.RESULT_KIND_BROWSER -> {
                PlaybackDataStore.recordPick(sessionId, null, null)
                if (detailUrl.isNotBlank()) BrowserNavigator.open(this, detailUrl)
            }
        }
    }

    /**
     * Result of the full-page play-address list (A方案 — replaces the
     * old "选择播放地址" dialog). Same echo-back contract as above:
     * pick plays it (recorded in the playback-data session), browser
     * opens WebView, back press does nothing.
     */
    private val choiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val sessionId = data.getLongExtra(VideoChoiceActivity.EXTRA_SESSION_ID, -1L)
        if (sessionId < 0) return@registerForActivityResult
        val detailUrl = data.getStringExtra(VideoChoiceActivity.EXTRA_DETAIL_URL).orEmpty()
        when (data.getStringExtra(VideoChoiceActivity.EXTRA_RESULT_KIND)) {
            VideoChoiceActivity.RESULT_KIND_PICK -> {
                val url = data.getStringExtra(VideoChoiceActivity.EXTRA_PICKED_URL).orEmpty()
                if (url.isBlank() || detailUrl.isBlank()) return@registerForActivityResult
                val item = SearchItem(
                    stableId = "", siteId = data.getStringExtra(VideoChoiceActivity.EXTRA_SITE_ID).orEmpty(),
                    title = data.getStringExtra(VideoChoiceActivity.EXTRA_TITLE).orEmpty(),
                    detailUrl = detailUrl, canonicalUrl = detailUrl)
                PlaybackDataStore.recordPick(sessionId, url,
                    data.getIntExtra(VideoChoiceActivity.EXTRA_PICKED_INDEX, -1).takeIf { it >= 0 })
                playSniffed(item, url, sessionId)
            }
            VideoChoiceActivity.RESULT_KIND_BROWSER -> {
                PlaybackDataStore.recordPick(sessionId, null, null)
                if (detailUrl.isNotBlank()) BrowserNavigator.open(this, detailUrl)
            }
        }
    }

    /**
     * Opens a search result. For hosts that publish an ECH config
     * (the app-layer fetch works while WebView is the blocked leg),
     * sniff the detail page HTML fetched over ECH and list the
     * candidate video URLs for the user to pick (auto-pick proved
     * unreliable: it kept landing on ads or unplayable URLs); the
     * user taps one to play it in the internal player, or chooses
     * the browser to fall back to WebView. No candidates, or a
     * non-ECH host, goes straight to WebView (current behavior).
     */
    private fun openResult(item: SearchItem) {
        rememberReadAnchor()
        preserveSessionOnStop = true
        // One playback-data session per open: every candidate found, the
        // pick, and the real playback outcome are recorded against it, so
        // the analysis can later say which path actually plays.
        val sessionId = PlaybackDataStore.startSession(item.siteId, item.detailUrl)
        // javhdporn: the real file never appears in static HTML (only ads do),
        // so resolve it through a hidden WebView instead of static sniffing.
        if (item.siteId == SearchSiteIds.JAVHD) {
            // Reuse a recently extracted address: the file URL's sign
            // lives ~6h, so a sniff from the last 5h is still good —
            // skip re-extraction. A failed playback invalidates the
            // page's cache, so the next open re-extracts.
            val cached = PlaybackDataStore.cachedCandidates(item.detailUrl, 5L * 60 * 60 * 1000)
            if (cached.isNotEmpty()) {
                PlaybackDataStore.addCandidates(sessionId, cached)
                showSniffPicker(item, cached, sessionId)
                return
            }
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    LanguageManager.text(this, "正在解析真实地址…", "Resolving real video URL…"),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            // Primary: format-agnostic network sniff on the real page.
            // Falls back to the old direct extraction (then the click
            // path) when the sniff catches nothing.
            resolveJavhdViaSniff(item, sessionId) { sniffed ->
                if (isDestroyed || isFinishing) return@resolveJavhdViaSniff
                if (sniffed != null) {
                    val (kept, ads) = sniffed
                    if (kept.isNotEmpty() || ads.isNotEmpty()) {
                        showSniffPicker(item, kept, sessionId, ads)
                        return@resolveJavhdViaSniff
                    }
                }
                tryResolveJavhdRealUrl(item, sessionId) { parts ->
                    if (isDestroyed || isFinishing) return@tryResolveJavhdRealUrl
                    if (parts != null) {
                        showSniffPicker(item, parts.map { (label, url) ->
                            resolverRecord(url, "resolver-javhd",
                                if (label.isEmpty()) "解析直連（跳過廣告）" else "$label 解析直連（跳過廣告）")
                        }, sessionId)
                    } else {
                        BrowserNavigator.open(this, item.detailUrl)
                    }
                }
            }
            return
        }
        // hentaimama: the real file only exists in the dt_embed JWPlayer
        // setup (video domain gdvid.info), never in static HTML — static
        // sniffing only finds snapshot thumbnails. Resolve it over HTTP,
        // skipping the VAST ad entirely; any failure falls back to WebView.
        // NOTE: search results link to series intro pages (/tvshows/),
        // which have no player at all — only an episode list. Resolving
        // the intro page as if it were an episode always fails, so a
        // series goes through its episode list first.
        if (item.siteId == SearchSiteIds.HENTAIMAMA) {
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    LanguageManager.text(this, "正在解析真实地址…", "Resolving real video URL…"),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            if (item.detailUrl.contains("/episodes/")) {
                resolveHentaiMamaEpisode(item, sessionId)
            } else {
                resolveHentaiMamaSeries(item, sessionId)
            }
            return
        }
        SearchScheduler.workers.execute {
            val (candidates, ads) = trySniffVideoCandidates(item, sessionId)
            runOnUiThread {
                if (candidates.isEmpty() && ads.isEmpty()) {
                    BrowserNavigator.open(this, item.detailUrl)
                } else {
                    showSniffPicker(item, candidates, sessionId, ads)
                }
            }
        }
    }

    /** Display-only record for a resolver-produced URL (the store already holds the precise attempt). */
    private fun resolverRecord(url: String, source: String, evidence: String = "解析直連（跳過廣告）") = SniffCandidateRecord(
        url = url, source = source, score = 50,
        evidence = evidence, kept = true, dropReason = null, durationSec = null,
    )

    /** Resolves one episode page to its direct file, then picker or browser fallback. Never throws. */
    private fun resolveHentaiMamaEpisode(item: SearchItem, sessionId: Long) {
        tryResolveHentaiMamaRealUrl(item, sessionId) { realUrl ->
            if (isDestroyed || isFinishing) return@tryResolveHentaiMamaRealUrl
            if (realUrl != null) {
                showSniffPicker(item, listOf(resolverRecord(realUrl, "resolver-hentaimama")), sessionId)
            } else {
                BrowserNavigator.open(this, item.detailUrl)
            }
        }
    }

    /**
     * Series intro page (/tvshows/): fetch its episode list over ECH,
     * then resolve the chosen episode. One episode resolves directly;
     * several show an episode picker. No episodes, or a fetch failure,
     * falls back to WebView. Never throws.
     */
    private fun resolveHentaiMamaSeries(item: SearchItem, sessionId: Long) {
        SearchScheduler.workers.execute {
            val site = sites.firstOrNull { it.id == item.siteId }
            val episodes: List<Pair<String, String>> = try {
                if (site == null) {
                    emptyList()
                } else {
                    val response = SearchHttpFetcher().fetch(item.detailUrl, SearchCancellation(), site)
                    if (response.transportError != null || response.statusCode !in 200..299 || response.body.isBlank()) {
                        emptyList()
                    } else {
                        VideoExtractor.extractHentaiMamaEpisodes(response.body, item.detailUrl)
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                when {
                    episodes.isEmpty() -> {
                        PlaybackDataStore.recordFail(sessionId, "系列介紹頁無劇集列表")
                        BrowserNavigator.open(this, item.detailUrl)
                    }
                    episodes.size == 1 -> {
                        resolveHentaiMamaEpisode(item.copy(detailUrl = episodes[0].first), sessionId)
                    }
                    else -> {
                        episodeLauncher.launch(HentaiMamaEpisodeActivity.intent(this, item, episodes, sessionId))
                    }
                }
            }
        }
    }

    /**
     * Resolves javhdporn's real files through a hidden WebView. The detail
     * page carries no video URL at all — only an encrypted data-mpu token
     * and Server links (Part1/Part2, a.srv[data-type="load-fv"]). Clicking
     * a Server link makes the page's own JS decode the token and load
     * video.javhdporn.net/p/<code> (signed t/s params), whose JWPlayer
     * setup decodes data-config into the direct file
     * https://<region>.vdcdn.xyz/<token>/file.mp4?sign=...&t=... — the
     * preload fetch is what the interceptor catches (verified 2026-11-05
     * by running the embed page's own main.js in Node: the setup it
     * produces has an empty advertising schedule, so the file itself
     * carries no ad). Playing that file directly skips the whole ad
     * stack (VAST pre-roll, popunders, the paid-SMS scam ads), and the
     * hidden WebView also blocks the ad hosts outright.
     * One part at a time: click a.srv[i], wait for its file (the first
     * click is sometimes a no-op — retry once after 8s), then move to
     * the next part; a part that never produces a file is skipped after
     * 20s. Old Streamtape videos (no .srv links) keep the legacy path:
     * generic play click + streamtape/tapecontent interception.
     * The hidden WebView gets the same ECH treatment as the main
     * browser (EchH3Router.fetchWebViewResource for GET fall-through),
     * so the detail page loads here exactly the way it loads there.
     * The file host is geo-selected (us-a2 in the sandbox run), so the
     * interceptor also accepts any /file.mp4 path, not just vdcdn.xyz.
     * On failure the recorded reason carries the step that died
     * (page loaded? Server links? embed seen? media hosts seen?) so
     * the 播放數據 screen says where it stopped instead of just
     * "解析失敗". Callback on the UI thread with every part found
     * (label + URL), or null when none resolves — the caller then
     * falls back to WebView. Never throws.
     */
    /**
     * Fallback javhdporn resolver: direct extraction, no page clicks.
     * (Primary since 2026-11-08 is the network sniff,
     * resolveJavhdViaSniff; this chain only runs when the sniff
     * catches nothing.) Chain (proven 2026-11-05, no WebView
     * browsing, no ads): detail HTML (ECH) -> a.srv tokens -> bundled
     * cast.js computes `sources` -> POST /api/play/ (ECH) -> the
     * handler's own .done() (binddata) decodes the embed URL -> embed
     * HTML (ECH) -> bundled embed main.js decodes the vdcdn file URL.
     * Any step failing falls back to the hidden-WebView click path
     * (resolveJavhdViaClick).
     */
    /** Trace clock for the javhd resolve log: reset at the start of
     * each sniff so every trace line carries a relative timestamp
     * (where it stuck is visible from the gaps). */
    private var javhdTraceT0 = 0L

    /** Ad hosts blocked outright in javhd hidden WebViews (popunders,
     * VAST, the paid-SMS scam creatives). The video file host and the
     * embed host are NOT in this list. Shared by the click fallback
     * and the network-sniff path. */
    private val javhdAdHosts = listOf(
        "whitetrafsa", "content-sync.xyz", "alaphoid.com", "udzpel.com",
        "jads.co", "wpadmngr.com", "xlviirdr.com", "xlirdr.com",
        "javhdporn.live", "javhd-trk.com", "vivodemisrentas.net",
    )

    /** Video hosts whose m3u8/mp4 are ads, never the feature — the
     * javhd sniff must not return them as candidates. Seeded from
     * AdFilterRules' ad-host table (BUILTIN_COMMON_BLOCK_OVERRIDES,
     * AdFilterRules.kt — "streamhls.click" is listed there) and
     * MainActivity.kt:2028 ("javhdporn.net 的廣告 CDN（如
     * streamhls.click）也會出 m3u8，跳過避免誤判"). The feature
     * comes from vdcdn.xyz / tapecontent / streamtape (2026-10-31),
     * never from these hosts. Used only to exclude candidates — the
     * requests themselves are NOT blocked, so the embed page's
     * player flow (ad first, feature after) is not disturbed. */
    private val javhdAdVideoHosts = listOf(
        "streamhls.click",
        "trafficjunky.com", "trafficstars.com", "exoclick.com",
        "juicyads.com", "ero-advertising.com", "plugrush.com",
        "adsterra.com", "propellerads.com", "popads.net",
        "popcash.net", "hilltopads.com",
    )

    /** One trace line for the current javhd sniff, shown in 播放數據
     * under 過程 and copied with it. Never throws. */
    private fun traceJavhd(sessionId: Long, line: String) {
        try {
            val t = if (javhdTraceT0 == 0L) 0.0
                else (android.os.SystemClock.elapsedRealtime() - javhdTraceT0) / 1000.0
            PlaybackDataStore.addTrace(sessionId, "+${"%.1f".format(t)}s $line")
        } catch (e: Exception) {
        }
    }

    private fun tryResolveJavhdRealUrl(
        item: SearchItem,
        sessionId: Long,
        callback: (List<Pair<String, String>>?) -> Unit,
    ) {
        javhdTraceT0 = android.os.SystemClock.elapsedRealtime()
        traceJavhd(sessionId, "嗅探開始：${item.detailUrl}")
        resolveJavhdDirect(item, sessionId, callback = { parts, directFail ->
            if (parts != null) {
                callback(parts)
                return@resolveJavhdDirect
            }
            resolveJavhdViaClick(item, sessionId, directFail, callback)
        })
    }

    /**
     * Primary javhdporn resolver (2026-11-08): network sniff on the
     * embed page. Trigger: the data-mpu token -> cast.js computes
     * `sources` -> POST /api/play/ (ECH) -> embed URL, then the embed
     * page loads in a hidden ECH WebView and whatever video file
     * (m3u8/mp4) it requests is caught via shouldInterceptRequest.
     * The video itself is never parsed out of the embed's
     * data-config/JWPlayer — that is the fragile old path. Clicking
     * .video-player alone never started anything (the player is built
     * by JS and needs /api/play/ first), so it stays only as an
     * auxiliary trigger. Note `sources` is NOT the raw token — cast.js
     * decrypts the 488-char token into a 272-char `sources` (raw
     * token -> /api/play/ 400 "invalid sources"), so the token can
     * only reach /api/play/ through the cast.js sandbox (synthetic
     * .srv anchors — the page ships none anymore).
     * Caught URLs go through the same filter as static sniffing
     * (filterSniffCandidates: ad keywords, <60s m3u8, dedupe, repeat
     * clusters), so the picker's 疑似廣告 folding applies unchanged.
     * Known ad video hosts (javhdAdVideoHosts — streamhls.click et
     * al., per AdFilterRules) are excluded from candidates outright:
     * the embed page plays an ad first and the feature after, so an
     * ad m3u8 must never be returned as the video. If only ads are
     * caught by the timeout, the sniff reports null so the caller
     * falls back to decoding the embed's data-config (vdcdn direct
     * link) instead of handing out an ad.
     * Callback on the UI thread with (kept, ads), or null when nothing
     * was sniffed — the caller then falls back to the old direct
     * extraction. Never throws.
     */
    private fun resolveJavhdViaSniff(
        item: SearchItem,
        sessionId: Long,
        callback: (Pair<List<SniffCandidateRecord>, List<SniffCandidateRecord>>?) -> Unit,
    ) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var hiddenWebView: android.webkit.WebView? = null
        var done = false
        var setupDone = false
        var pageFinished = false
        var pageStarted = false
        var maxProgress = 0
        var lastLoadError: String? = null
        var clickCount = 0
        var finishScheduled = false
        val foundUrls = LinkedHashSet<String>()
        val mediaHosts = LinkedHashSet<String>()
        // Ad-host video URLs seen (excluded from candidates — the
        // embed page plays an ad first, then the feature; the ad
        // must not be returned as the video). Named in the failure
        // log so "只攔到廣告、未見正片" is distinguishable from
        // "nothing was requested at all".
        val adUrlsSeen = LinkedHashSet<String>()
        val adHostsSeen = LinkedHashSet<String>()
        val pageSlug = item.detailUrl.trimEnd('/').substringAfterLast('/')
        var timeoutRunnable: Runnable by kotlin.properties.Delegates.notNull()
        var finishRunnable: Runnable? = null
        var clickRunnable: Runnable? = null
        // Which page the sniff WebView ended up loading: the embed
        // URL from /api/play/, or the detail page (trigger failed).
        // The failure reason names it so "嵌頁里没拦到视频" and
        // "详情页里没拦到视频" are distinguishable.
        var loadedEmbed = false

        /** A real video file: the extension must terminate the path
         * (query/fragment stripped) so "...Episode-1.mp4_snapshot_
         * 05.21_....webp" thumbnails never match as mp4 — same rule
         * as VideoPageSniffer's loose match. */
        fun isVideoFile(url: String): Boolean {
            val clean = url.substringBefore('?').substringBefore('#').lowercase()
            return clean.endsWith(".m3u8") || clean.endsWith(".mp4")
        }

        /** True when the URL's host is a known ad video host (see
         * javhdAdVideoHosts). The request itself is NOT blocked —
         * only the candidate is dropped, so the player flow is
         * undisturbed and a later feature request is still caught. */
        fun isAdVideoHost(url: String): Boolean {
            val host = try { java.net.URL(url).host.lowercase() } catch (e: Exception) { return false }
            return javhdAdVideoHosts.any { host == it || host.endsWith(".$it") }
        }

        fun finish() {
            if (done) return
            done = true
            handler.removeCallbacks(timeoutRunnable)
            finishRunnable?.let { handler.removeCallbacks(it) }
            clickRunnable?.let { handler.removeCallbacks(it) }
            hiddenWebView?.destroy()
            hiddenWebView = null
            if (foundUrls.isEmpty()) {
                val media = if (mediaHosts.isEmpty()) "無" else mediaHosts.take(3).joinToString(",")
                val err = lastLoadError?.let { "；$it" } ?: ""
                val where = if (loadedEmbed) "嵌頁已載、未攔到視頻文件" else "詳情頁已載、未攔到視頻文件"
                // Only ads were caught (e.g. streamhls.click): say so
                // plainly and fall back to the data-config decode
                // (the caller runs the direct extraction) instead of
                // handing the ad out as the feature.
                val reason = if (adUrlsSeen.isNotEmpty()) {
                    "javhd嗅探失敗 [$pageSlug]：只攔到廣告 host（${adHostsSeen.joinToString(",")}，${adUrlsSeen.size} 個），未見正片；回落解 data-config"
                } else {
                    "javhd嗅探失敗 [$pageSlug]：$where；媒體host=$media$err"
                }
                traceJavhd(sessionId, "嗅探失敗：$reason")
                PlaybackDataStore.recordFail(sessionId, reason)
                callback(null)
                return
            }
            traceJavhd(sessionId, "嗅探完成：攔到 ${foundUrls.size} 個視頻地址")
            val site = sites.firstOrNull { it.id == item.siteId }
            if (site == null) {
                callback(null)
                return
            }
            // Filter on a worker thread (m3u8 duration fetches), then
            // report on the UI thread.
            val html = foundUrls.joinToString("\n")
            SearchScheduler.workers.execute {
                val scored = try {
                    VideoPageSniffer.sniffScored(html, item.detailUrl, item.code)
                } catch (e: Exception) {
                    emptyList()
                }
                val result = try {
                    filterSniffCandidates(scored, site, sessionId)
                } catch (e: Exception) {
                    emptyList<SniffCandidateRecord>() to emptyList()
                }
                handler.post {
                    if (isDestroyed || isFinishing) return@post
                    callback(result)
                }
            }
        }

        /** Grace window: let stragglers (other Parts / qualities)
         * arrive before filtering. Scheduled at most once. */
        fun scheduleFinish() {
            if (done || finishScheduled) return
            finishScheduled = true
            finishRunnable = Runnable { finish() }
            handler.postDelayed(finishRunnable!!, 3000)
        }

        /** Generic player-area click: tries the video element and the
         * usual player/play selectors in order. Never looks for a
         * specific a.srv/Server link — the site's own JS decides what
         * a play click loads. */
        fun clickPlayer() {
            if (done) return
            clickCount++
            traceJavhd(sessionId, "嗅探點擊播放器（第 $clickCount 次）")
            hiddenWebView?.evaluateJavascript(
                "(function(){" +
                    "var s=['video','#mainvideo','.video-player','#video-player','[class*=\"video-player\"]','#overlay','[id*=\"play\"]','[class*=\"play\"]'];" +
                    "for(var i=0;i<s.length;i++){" +
                    "var el=document.querySelector(s[i]);" +
                    "if(el){try{if(el.tagName==='VIDEO'){el.play();}else{el.click();}}catch(e){}return 'clicked:'+s[i];}}" +
                    "return 'no element found';})();",
            ) { value ->
                if (!done) {
                    val s = value?.trim()?.removeSurrounding("\"").orEmpty()
                    traceJavhd(sessionId, "嗅探點擊結果：$s")
                }
            }
        }

        timeoutRunnable = Runnable { finish() }
        handler.post {
            val wv = android.webkit.WebView(this)
            hiddenWebView = wv
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                mediaPlaybackRequiresUserGesture = false
                // Never serve this page from cache: a page that once failed
                // sticks dead in the shared WebView cache (known app
                // behaviour — only clearing the app cache revives it).
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            }
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                    if (newProgress > maxProgress) maxProgress = newProgress
                }
            }
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if (javhdAdHosts.any { reqUrl.contains(it) }) {
                        return android.webkit.WebResourceResponse("text/plain", "utf-8",
                            java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    if (reqUrl.contains(".mp4") || reqUrl.contains(".m3u8") || reqUrl.contains(".ts")) {
                        try {
                            val h = java.net.URL(reqUrl).host
                            if (mediaHosts.add(h)) traceJavhd(sessionId, "媒體請求：$h")
                        } catch (e: Exception) { }
                    }
                    if (isVideoFile(reqUrl)) {
                        // Ad-host video (e.g. streamhls.click): let
                        // the request proceed so the player flow is
                        // undisturbed, but never return it as a
                        // candidate — the feature comes from
                        // vdcdn/tapecontent/streamtape, not here.
                        // Keep waiting for a feature URL instead of
                        // finishing on the ad.
                        if (isAdVideoHost(reqUrl)) {
                            handler.post {
                                if (!done && adUrlsSeen.add(reqUrl)) {
                                    val h = try { java.net.URL(reqUrl).host } catch (e: Exception) { "?" }
                                    adHostsSeen.add(h)
                                    traceJavhd(sessionId, "嗅探攔到廣告：$h ${reqUrl.take(80)}")
                                }
                            }
                        } else {
                            handler.post {
                                if (!done && foundUrls.add(reqUrl)) {
                                    traceJavhd(sessionId, "嗅探攔到：${reqUrl.take(120)}")
                                    if (pageFinished) scheduleFinish()
                                }
                            }
                        }
                        // Let the request proceed: the page's own player
                        // needs the bytes, and we only need the URL.
                        // Fall through to ECH/default below.
                    }
                    // ECH: same treatment as the main browser — GET
                    // requests to hosts with an ECH config ride the
                    // app-layer stack instead of WebView's plaintext
                    // TLS. Hosts without a config fall through to
                    // WebView default as before.
                    if (request?.method.equals("GET", ignoreCase = true) == true) {
                        EchH3Router.fetchWebViewResource(
                            reqUrl, request?.requestHeaders, request?.isForMainFrame == true,
                        )?.let { return it }
                    }
                    return null
                }

                override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!pageStarted) traceJavhd(sessionId, "嗅探頁面開始加載：${url?.take(100)}")
                    pageStarted = true
                }

                override fun onReceivedError(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    if (lastLoadError == null && error != null) {
                        lastLoadError = "錯誤 ${request?.url?.host ?: "?"} ${error.errorCode} ${error.description}"
                        traceJavhd(sessionId, "嗅探頁面錯誤：$lastLoadError")
                    }
                }

                override fun onReceivedHttpError(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (lastLoadError == null && errorResponse != null) {
                        lastLoadError = "HTTP ${errorResponse.statusCode} ${request?.url?.host ?: "?"}"
                        traceJavhd(sessionId, "嗅探頁面錯誤：$lastLoadError")
                    }
                }

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (setupDone || done) return
                    setupDone = true
                    pageFinished = true
                    traceJavhd(sessionId, "嗅探頁面完成加載（進度 $maxProgress%）")
                    // If the player auto-loaded, a candidate may already
                    // be in hand; otherwise trigger it generically.
                    handler.postDelayed({
                        if (done) return@postDelayed
                        if (foundUrls.isEmpty()) {
                            clickPlayer()
                            // One retry: the first click is sometimes a no-op.
                            clickRunnable = Runnable {
                                if (!done && foundUrls.isEmpty()) clickPlayer()
                            }
                            handler.postDelayed(clickRunnable!!, 8000)
                        } else {
                            scheduleFinish()
                        }
                    }, 2000)
                }
            }
            // Trigger: token -> /api/play/ -> embed URL, then load
            // the embed page here and sniff the video out of it.
            // Clicking .video-player never started anything — the
            // player is built by JS and needs /api/play/ first, and
            // the token sits unused in the page. The video itself is
            // still caught by sniffing (never by parsing the embed's
            // data-config/JWPlayer), and the player-area click stays
            // as an auxiliary trigger in onPageFinished below.
            javhdTraceT0 = android.os.SystemClock.elapsedRealtime()
            traceJavhd(sessionId, "嗅探開始：${item.detailUrl}")
            traceJavhd(sessionId, "嗅探觸發：取令牌 → /api/play/ → 嵌頁")
            resolveJavhdDirect(
                item, sessionId,
                callback = { _, _ -> },
                onEmbed = { embeds, reason ->
                    if (done) return@resolveJavhdDirect
                    if (embeds.isNullOrEmpty()) {
                        // Say which step died: token / api/play /
                        // embed decode — not just "嗅探失敗".
                        val step = when {
                            reason == null -> "嗅探觸發：未拿到嵌頁地址"
                            reason.contains("無令牌") -> "嗅探觸發：令牌沒取到"
                            reason.contains("sources") -> "嗅探觸發：令牌有、sources 未算出"
                            reason.contains("api/play") -> "嗅探觸發：/api/play/ 沒回嵌頁"
                            reason.contains("嵌頁地址") -> "嗅探觸發：嵌頁地址未解出"
                            reason.contains("超時") -> "嗅探觸發：取嵌頁超時"
                            else -> "嗅探觸發失敗：$reason"
                        }
                        traceJavhd(sessionId, step)
                        traceJavhd(sessionId, "嗅探開始：載入詳情頁（改點播放器） ${item.detailUrl}")
                        wv.loadUrl(item.detailUrl)
                    } else {
                        val embedUrl = embeds[0].second
                        loadedEmbed = true
                        traceJavhd(sessionId, "嗅探觸發：載入嵌頁 ${embedUrl.take(120)}")
                        wv.loadUrl(embedUrl)
                    }
                    handler.postDelayed(timeoutRunnable, 45000)
                },
                timeoutMs = 30000,
            )
        }
    }

    /** ECH form POST via the search ECH client (same client the search
     * fetcher uses for GET). Returns the response body on HTTP 2xx,
     * null otherwise. No plaintext fallback here — the caller falls
     * back to plain postForm when this returns null. */
    private fun postFormEch(url: String, referer: String, fields: Map<String, String>): String? {
        return try {
            val form = FormBody.Builder().apply {
                fields.forEach { (k, v) -> add(k, v) }
            }.build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(form).build()
            EchH3Router.searchEchClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveJavhdDirect(
        item: SearchItem,
        sessionId: Long,
        callback: (List<Pair<String, String>>?, String?) -> Unit,
        // Embed-only mode (sniff trigger): when non-null, Phase 1
        // returns the embed URLs through onEmbed and skips Phase 2
        // (no data-config/JWPlayer decode — the video is caught by
        // sniffing the embed page instead). The second argument is
        // the failure reason when no embed URL was produced.
        // `sources` is NOT the raw token — cast.js decrypts the
        // data-mpu token into it (verified 2026-11-08 in Node:
        // raw token -> /api/play/ 400 "invalid sources"; cast.js
        // click -> 272-char sources -> status true), so the token
        // can only reach /api/play/ through the cast.js sandbox.
        onEmbed: ((List<Pair<String, String>>?, String?) -> Unit)? = null,
        // Overall bound for the chain; the sniff trigger passes a
        // shorter one so the whole sniff stays within its window.
        timeoutMs: Long = 75000,
    ) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var hiddenWebView: android.webkit.WebView? = null
        var done = false
        val foundParts = LinkedHashMap<Int, String>()
        var partLabels: List<String> = emptyList()
        var tokens: List<String> = emptyList()
        var detailHtml: String = ""
        var lastSourcesPreview: String = ""
        var lastDataPreview: String = ""
        var jqueryJs: String? = null
        var driverCastJs: String? = null
        var castJs: String? = null
        var driverEmbedJs: String? = null
        var embedMainJs: String? = null
        val sourcesByPart = LinkedHashMap<Int, String>()
        // Phase 1 collects each Part's embed URL here (on the cast
        // page); Phase 2 decodes them. Split because decoding a Part
        // navigates the WebView to the embed page, which destroys the
        // cast page's __javhdDoneCbs — feeding a later Part's done
        // callback after that always fails ("done回調未註冊").
        val embedUrlByPart = LinkedHashMap<Int, String>()
        // Last per-Part failure, kept so the final message can say
        // which Part failed when nothing at all was decoded.
        var lastPartFailReason: String? = null
        // The picker is launched at most once: surfaceParts() fires
        // it as soon as the first direct link is decoded (the user
        // asked: once an address is in hand, play it — do not wait
        // for the other Parts); finish() must not fire it again.
        var pickerLaunched = false
        var timeoutRunnable: Runnable by kotlin.properties.Delegates.notNull()

        fun finish(parts: List<Pair<String, String>>?, reason: String?) {
            if (done) return
            done = true
            handler.removeCallbacks(timeoutRunnable)
            hiddenWebView?.destroy()
            hiddenWebView = null
            if (onEmbed != null) {
                // Embed-only mode (sniff trigger): the
                // direct-extraction callback and picker are not used.
                // finish is only reached here on failure — success
                // returns earlier from postAndFeed.
                onEmbed(null, reason)
                return
            }
            if (parts != null) {
                traceJavhd(sessionId, "直取完成：${parts.size} 個 Part（${parts.joinToString("，") { it.first }}）")
                // Remember each file's embed page so the player can
                // send it as Referer (see javhdEmbedRefererByUrl).
                for ((i, url) in foundParts) {
                    embedUrlByPart[i]?.let { javhdEmbedRefererByUrl[url] = it }
                }
                PlaybackDataStore.addCandidates(sessionId, parts.map { (label, url) ->
                    resolverRecord(url, "resolver-javhd-direct", "$label 直接提取（跳過廣告）")
                })
            }
            // The picker was already launched by surfaceParts() with
            // the first decoded Part(s); do not launch a second one
            // (and do not fall back to the click path — the user is
            // already choosing).
            if (!pickerLaunched) {
                callback(parts, reason)
            }
        }

        fun fail(reason: String) {
            if (done) return
            traceJavhd(sessionId, "直取失敗：$reason")
            if (foundParts.isNotEmpty()) {
                // A later Part failed, but earlier Parts already
                // yielded direct links — surface those instead of
                // discarding them. (10:03 run: Part1's file URL was
                // extracted and plays, then Part2 failed and the
                // whole sniff was reported as "未找到候選".)
                val parts = foundParts.entries.map { (i, url) ->
                    (partLabels.getOrNull(i).orEmpty().ifEmpty { "Part${i + 1}" }) to url
                }
                traceJavhd(sessionId, "直取部分成功：${parts.size} 個 Part 已解出（$reason）")
                finish(parts, reason)
            } else {
                finish(null, reason)
            }
        }

        /** Surface the Parts decoded so far. Called after every
         * successful decode (Phase 2): the first call launches the
         * picker with the first direct link — the user asked that a
         * decoded address be playable immediately instead of waiting
         * for the remaining Parts. Later calls only refresh the
         * session's candidate list (the picker polls the store and
         * appends new rows live); the picker itself is launched at
         * most once. Never throws. */
        fun surfaceParts() {
            if (done || foundParts.isEmpty()) return
            val parts = foundParts.entries.map { (i, url) ->
                (partLabels.getOrNull(i).orEmpty().ifEmpty { "Part${i + 1}" }) to url
            }
            for ((i, url) in foundParts) {
                embedUrlByPart[i]?.let { javhdEmbedRefererByUrl[url] = it }
            }
            PlaybackDataStore.addCandidates(sessionId, parts.map { (label, url) ->
                resolverRecord(url, "resolver-javhd-direct", "$label 直接提取（跳過廣告）")
            })
            if (!pickerLaunched) {
                pickerLaunched = true
                traceJavhd(sessionId, "直取先出：${parts.size} 個 Part 已解出，先出選擇（其餘後台繼續）")
                callback(parts, null)
            }
        }

        timeoutRunnable = Runnable { fail("直取超時") }
        handler.postDelayed(timeoutRunnable, timeoutMs)

        fun assetText(name: String): String? {
            return try {
                assets.open(name).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                null
            }
        }

        /** One-shot JS eval on the hidden WebView; result is the raw
         * evaluateJavascript string (JSON-quoted). Never throws. */
        fun evalJs(js: String, cb: (String?) -> Unit) {
            if (done) return
            try {
                hiddenWebView?.evaluateJavascript(js) { value ->
                    if (!done) cb(value)
                } ?: cb(null)
            } catch (e: Exception) {
                cb(null)
            }
        }

        /** Unquote an evaluateJavascript string result (JSON string). */
        fun unquoteJs(value: String?): String? {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty() || raw == "null") return null
            return try {
                org.json.JSONTokener(raw).nextValue() as? String
            } catch (e: Exception) {
                raw.removeSurrounding("\"")
            }
        }

        /** Poll a JS expression until it yields a non-empty string. */
        fun pollJs(expr: String, timeoutMs: Long, cb: (String?) -> Unit) {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
            val poll = object : Runnable {
                override fun run() {
                    if (done) return
                    evalJs(expr) { value ->
                        if (done) return@evalJs
                        val s = unquoteJs(value)
                        if (!s.isNullOrEmpty()) {
                            cb(s)
                        } else if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                            cb(null)
                        } else {
                            handler.postDelayed(this, 500)
                        }
                    }
                }
            }
            handler.post(poll)
        }

        // Forward references: a local function can only call functions
        // declared above it, but this chain is cyclic (postAndFeed ->
        // feedDone -> postAndFeed, and decodePart -> fetchEmbed ->
        // setupEmbedPage -> decodePart). These vars break the cycle;
        // each is assigned right after its function is declared,
        // before any of them can run.
        lateinit var feedDoneFn: (Int, String) -> Unit
        lateinit var fetchEmbedFn: (Int, String) -> Unit
        lateinit var setupEmbedFn: (Int, String, String) -> Unit

        /** Phase 2: decode each Part's collected embed URL to a file
         * URL. Runs after Phase 1 fed every Part's done callback on
         * the cast page, so navigating the WebView to the embed page
         * here is safe — the cast page (and its __javhdDoneCbs) is
         * no longer needed. A Part that fails here is skipped; parts
         * already decoded are kept. */
        fun decodePart(partIdx: Int) {
            if (done) return
            if (partIdx >= tokens.size) {
                if (foundParts.isEmpty()) {
                    fail(lastPartFailReason ?: "直取失敗：無嵌頁地址")
                    return
                }
                val parts = foundParts.entries.map { (i, url) ->
                    (partLabels.getOrNull(i).orEmpty().ifEmpty { "Part${i + 1}" }) to url
                }
                finish(parts, null)
                return
            }
            val embedUrl = embedUrlByPart[partIdx].orEmpty()
            if (embedUrl.isEmpty()) {
                decodePart(partIdx + 1)
                return
            }
            fetchEmbedFn(partIdx, embedUrl)
        }

        /** Phase 1: per-Part POST sources and feed the response
         * through the handler's own .done() (binddata), collecting
         * the embed URL. Stays on the cast page the whole time — no
         * navigation here, so __javhdDoneCbs survives for every
         * Part. When every Part is fed, Phase 2 (decodePart) decodes
         * the collected embed URLs. A Part that fails is skipped, not
         * fatal: earlier Parts' links are kept. */
        fun postAndFeed(partIdx: Int) {
            if (done) return
            if (partIdx >= tokens.size) {
                if (onEmbed != null) {
                    // Embed-only mode: Phase 1 is done — hand the
                    // embed URLs back and stop (no Phase 2 decode).
                    val embeds = embedUrlByPart.entries.sortedBy { it.key }.map { (i, url) ->
                        (partLabels.getOrNull(i).orEmpty().ifEmpty { "Part${i + 1}" }) to url
                    }
                    done = true
                    handler.removeCallbacks(timeoutRunnable)
                    hiddenWebView?.destroy()
                    hiddenWebView = null
                    if (embeds.isEmpty()) {
                        onEmbed(null, lastPartFailReason ?: "api/play 未回嵌頁")
                    } else {
                        traceJavhd(sessionId, "嗅探觸發：拿到 ${embeds.size} 個嵌頁地址")
                        onEmbed(embeds, null)
                    }
                    return
                }
                decodePart(0)
                return
            }
            val sources = sourcesByPart[partIdx].orEmpty()
            if (sources.isEmpty()) {
                lastPartFailReason = "直取失敗：Part${partIdx + 1} sources缺失"
                traceJavhd(sessionId, lastPartFailReason!!)
                postAndFeed(partIdx + 1)
                return
            }
            SearchScheduler.workers.execute {
                val echJson = postFormEch(
                    "https://www.javhdporn.net/api/play/", item.detailUrl,
                    mapOf("sources" to sources, "ver" to "2"),
                )
                val postJson = echJson ?: postForm(
                    "https://www.javhdporn.net/api/play/", item.detailUrl,
                    mapOf("sources" to sources, "ver" to "2"),
                )
                val transport = if (echJson != null) "ECH" else "明文"
                if (postJson.isNullOrBlank()) {
                    traceJavhd(sessionId, "Part${partIdx + 1} POST api/play：ECH 與明文皆失敗")
                    handler.post {
                        lastPartFailReason = "直取失敗：Part${partIdx + 1} api/play失敗"
                        postAndFeed(partIdx + 1)
                    }
                    return@execute
                }
                val data = try {
                    val obj = org.json.JSONObject(postJson)
                    if (!obj.optBoolean("status", false)) null
                    else obj.optString("data").orEmpty().ifEmpty { null }
                } catch (e: Exception) {
                    null
                }
                if (data == null) {
                    traceJavhd(sessionId, "Part${partIdx + 1} POST api/play（$transport）：回包無效（前 ${postJson.take(80)}）")
                    handler.post {
                        lastPartFailReason = "直取失敗：Part${partIdx + 1} api/play無效"
                        postAndFeed(partIdx + 1)
                    }
                    return@execute
                }
                lastSourcesPreview = sources.take(24) + "…len=" + sources.length
                lastDataPreview = data.take(24) + "…len=" + data.length
                traceJavhd(sessionId, "Part${partIdx + 1} POST api/play（$transport）：status=true，data長 ${data.length}（前 ${data.take(24)}）")
                handler.post { if (!done) feedDoneFn(partIdx, postJson) }
            }
        }

        /** Feed the /api/play/ response into the click handler's own
         * .done() callback (binddata decodes `data` to the embed URL),
         * then read the embed URL into embedUrlByPart (Phase 1 only
         * — the embed page is fetched later, in Phase 2). */
        fun feedDoneAndReadVideodata(partIdx: Int, postJson: String) {
            if (done) return
            val quoted = org.json.JSONObject.quote(postJson)
            evalJs("window.__javhdDoneCbs && window.__javhdDoneCbs.length > $partIdx ? (window.__javhdDoneCbs[$partIdx](JSON.parse($quoted)), 'fed') : ''") { fed ->
                if (done) return@evalJs
                if (unquoteJs(fed).isNullOrEmpty()) {
                    // Phase 1 runs on the cast page, so this should
                    // not happen — but skip the Part rather than
                    // killing the rest.
                    lastPartFailReason = "直取失敗：Part${partIdx + 1} done回調未註冊"
                    traceJavhd(sessionId, lastPartFailReason!!)
                    postAndFeed(partIdx + 1)
                    return@evalJs
                }
                traceJavhd(sessionId, "Part${partIdx + 1} 餵回調：已餵")
                // Read the embed URL from the binddata wrapper first.
                // The wrapper (installed after the sandbox probe)
                // records the argument the done callback passes to
                // binddata as window.__binddataArg. On the device
                // binddata receives the correct URL but
                // window.videodata stays undefined — the property name
                // binddata decodes differs from jsdom, so the URL lands
                // under another name and polling videodata alone never
                // finds it. The wrapper argument IS the embed URL, so
                // use it directly. Fall back to the videodata poll if
                // the wrapper has nothing (binddata not called, or the
                // wrapper not installed). Accept any http URL: the
                // embed host varies by region (video.javhdporn.net is
                // not the only one), so do not hard-code it here.
                evalJs("typeof window.__binddataArg === 'string' && window.__binddataArg.indexOf('http') === 0 ? window.__binddataArg : ''") { argUrl ->
                    if (done) return@evalJs
                    val url = unquoteJs(argUrl).orEmpty()
                    if (url.isNotEmpty()) {
                        traceJavhd(sessionId, "Part${partIdx + 1} 嵌頁地址（binddata參數）：$url")
                        embedUrlByPart[partIdx] = url
                        postAndFeed(partIdx + 1)
                        return@evalJs
                    }
                    // binddata runs synchronously inside the callback
                    // and sets window.videodata; poll briefly in case
                    // it defers.
                    pollJs("typeof window.videodata === 'string' && window.videodata.indexOf('http') === 0 ? window.videodata : ''", 10000) { videodata ->
                        if (done) return@pollJs
                        if (videodata.isNullOrEmpty()) {
                            // Read back whatever videodata became, plus the
                            // capture counts and what the done callback
                            // passed to binddata (if anything), so the
                            // failure message shows where it actually died:
                            // "binddata未調用" = callback never reached it;
                            // "binddata參數=undefined" = decode produced
                            // nothing; a URL here with videodata still
                            // undefined = binddata itself failed to set it.
                            evalJs("(window.__javhdAjaxCalls ? window.__javhdAjaxCalls.length : -1) + '/' + (window.__javhdDoneCbs ? window.__javhdDoneCbs.length : -1)") { counts ->
                                evalJs("typeof window.videodata === 'string' ? window.videodata : (window.videodata === undefined ? 'undefined' : typeof window.videodata)") { actual ->
                                    evalJs("typeof window.__binddataArg === 'undefined' ? 'binddata未調用' : ('binddata參數=' + window.__binddataArg)") { arg ->
                                        lastPartFailReason = "直取失敗：Part${partIdx + 1} 嵌頁地址未解出（videodata=${unquoteJs(actual).orEmpty().take(80)}；${unquoteJs(arg).orEmpty().take(120)}；ajax/done=${unquoteJs(counts).orEmpty()}；src=${lastSourcesPreview}；data=${lastDataPreview}）"
                                        traceJavhd(sessionId, lastPartFailReason!!)
                                        postAndFeed(partIdx + 1)
                                    }
                                }
                            }
                            return@pollJs
                        }
                        traceJavhd(sessionId, "Part${partIdx + 1} 嵌頁地址：$videodata")
                        embedUrlByPart[partIdx] = videodata
                        postAndFeed(partIdx + 1)
                    }
                }
            }
        }

        feedDoneFn = ::feedDoneAndReadVideodata

        /** Fetch the embed page over ECH, pull data-config, and decode
         * the file URL with the bundled embed main.js. */
        fun fetchEmbedAndDecode(partIdx: Int, embedUrl: String) {
            if (done) return
            SearchScheduler.workers.execute {
                val site = sites.firstOrNull { it.id == item.siteId }
                if (site == null) {
                    handler.post { fail("直取失敗：站點未註冊") }
                    return@execute
                }
                val embed = try {
                    SearchHttpFetcher().fetch(embedUrl, SearchCancellation(), site)
                } catch (e: Exception) {
                    traceJavhd(sessionId, "Part${partIdx + 1} 嵌頁 GET：拋錯 ${e.message}")
                    handler.post {
                        lastPartFailReason = "直取失敗：Part${partIdx + 1} 嵌頁取不到"
                        decodePart(partIdx + 1)
                    }
                    return@execute
                }
                val embedHost = try { java.net.URL(embedUrl).host } catch (e: Exception) { "?" }
                if (embed.transportError != null || embed.statusCode !in 200..299 || embed.body.isBlank()) {
                    traceJavhd(sessionId, "Part${partIdx + 1} 嵌頁 GET $embedHost：失敗（HTTP ${embed.statusCode}，transport=${embed.transportError ?: "無"}）")
                    handler.post {
                        lastPartFailReason = "直取失敗：Part${partIdx + 1} 嵌頁取不到"
                        decodePart(partIdx + 1)
                    }
                    return@execute
                }
                val config = Regex("data-config=\"([^\"]+)\"").find(embed.body)?.groupValues?.getOrNull(1).orEmpty()
                if (config.isEmpty()) {
                    traceJavhd(sessionId, "Part${partIdx + 1} 嵌頁 GET $embedHost：HTTP ${embed.statusCode}，無 data-config（${embed.body.length}字節）")
                    handler.post {
                        lastPartFailReason = "直取失敗：Part${partIdx + 1} 嵌頁無data-config"
                        decodePart(partIdx + 1)
                    }
                    return@execute
                }
                traceJavhd(sessionId, "Part${partIdx + 1} 嵌頁 GET $embedHost：HTTP ${embed.statusCode}，data-config 長 ${config.length}")
                handler.post { if (!done) setupEmbedFn(partIdx, embedUrl, config) }
            }
        }

        fetchEmbedFn = ::fetchEmbedAndDecode

        /** Reuse the hidden WebView for the embed stage: fresh blank
         * page holding only the jwplayer div, then the embed driver
         * and the bundled embed main.js. The cast page is no longer
         * needed (all sources were collected up front). */
        fun setupEmbedPage(partIdx: Int, embedUrl: String, config: String) {
            if (done) return
            val pageHtml = "<html><head></head><body><div id=\"jwplayer\" data-config=\"$config\"></div></body></html>"
            val client = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }

                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): Boolean = true

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (done) return
                    val driver = driverEmbedJs
                    val main = embedMainJs
                    if (driver == null || main == null) {
                        fail("直取失敗：內置腳本缺失")
                        return
                    }
                    evalJs(driver) {
                        evalJs(main) {
                            pollJs("window.__jwSetup ? window.__jwSetup : ''", 10000) { setup ->
                                if (done) return@pollJs
                                // setup is already unquoted by pollJs
                                // (it unquotes the evaluateJavascript
                                // result once). Do NOT unquote it again:
                                // unquoteJs on a JSON object string
                                // returns null (JSONTokener yields a
                                // JSONObject, not a String), so
                                // JSONObject("") throws and fileUrl
                                // comes out empty even though __jwSetup
                                // holds the full config — that is why
                                // the 08:52 run died here with a valid
                                // __jwSetup in the trace.
                                val fileUrl = try {
                                    val obj = org.json.JSONObject(setup.orEmpty())
                                    obj.optJSONArray("sources")?.optJSONObject(0)?.optString("file").orEmpty()
                                } catch (e: Exception) {
                                    ""
                                }
                                if (fileUrl.isEmpty() || !(fileUrl.contains("/file.mp4") || fileUrl.contains(".m3u8"))) {
                                    traceJavhd(sessionId, "Part${partIdx + 1} 解嵌頁失敗：__jwSetup=${setup.orEmpty().take(120)}")
                                    lastPartFailReason = "直取失敗：Part${partIdx + 1} 直鏈未解出"
                                    decodePart(partIdx + 1)
                                    return@pollJs
                                }
                                traceJavhd(sessionId, "Part${partIdx + 1} 直鏈：$fileUrl")
                                foundParts[partIdx] = fileUrl
                                // First address in hand: surface it now
                                // (picker opens) instead of waiting for
                                // the remaining Parts; they are appended
                                // to the same session and the picker
                                // refreshes live.
                                surfaceParts()
                                decodePart(partIdx + 1)
                            }
                        }
                    }
                }
            }
            hiddenWebView?.webViewClient = client
            hiddenWebView?.loadDataWithBaseURL(embedUrl, pageHtml, "text/html", "utf-8", null)
        }

        setupEmbedFn = ::setupEmbedPage

        /** Strip the page's own scripts: the bundled cast.js is the
         * only script the sandbox runs. Theme/ad/analytics scripts are
         * removed so they cannot run, rewrite the DOM, or phone home;
         * the static DOM they would have read stays intact. */
        fun stripScripts(html: String): String {
            return html.replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), "")
                .replace(Regex("(?is)<script\\b[^>]*/>"), "")
        }

        /** Trigger every Part's click on the cast page and collect the
         * `sources` each handler computes (via the overridden
         * jQuery.ajax). The handler runs synchronously on trigger, so
         * the capture is readable right after. */
        fun collectSources(partIdx: Int) {
            if (done) return
            if (partIdx >= tokens.size) {
                postAndFeed(0)
                return
            }
            evalJs("window.__javhdAjaxCalls ? window.__javhdAjaxCalls.length : -1") { before ->
                if (done) return@evalJs
                val n0 = before?.trim()?.toIntOrNull() ?: -1
                if (n0 < 0) {
                    fail("直取失敗：驅動未就緒")
                    return@evalJs
                }
            // Clicking the already-active server is a no-op (the
            // handler skips it), and the first server is active by
            // default — clear the marker before every trigger so each
            // Part's handler actually runs.
            evalJs("jQuery('.box-server .srv').removeClass('default-srv'); 'ok'") {
                evalJs("jQuery('.box-server .srv').eq($partIdx).trigger('click'); 'ok'") {
                    evalJs("JSON.stringify(window.__javhdAjaxCalls.slice($n0))") { value ->
                        if (done) return@evalJs
                        val arrJson = unquoteJs(value).orEmpty()
                        val sources = try {
                            val arr = org.json.JSONArray(arrJson)
                            var found = ""
                            for (i in 0 until arr.length()) {
                                val entry = arr.optJSONObject(i) ?: continue
                                if (entry.optString("url").orEmpty().contains("api/play")) {
                                    found = entry.optJSONObject("data")?.optString("sources").orEmpty()
                                    if (found.isNotEmpty()) break
                                }
                            }
                            found
                        } catch (e: Exception) {
                            ""
                        }
                        if (sources.isEmpty()) {
                            traceJavhd(sessionId, "Part${partIdx + 1} 點擊：ajax新增 ${try { org.json.JSONArray(arrJson).length() } catch (e: Exception) { -1 }} 個，sources 未算出")
                            fail("直取失敗：Part${partIdx + 1} sources未算出")
                            return@evalJs
                        }
                        traceJavhd(sessionId, "Part${partIdx + 1} 點擊：ajax新增 ${try { org.json.JSONArray(arrJson).length() } catch (e: Exception) { -1 }} 個，sources長 ${sources.length}（前 ${sources.take(24)}）")
                        sourcesByPart[partIdx] = sources
                        collectSources(partIdx + 1)
                    }
                }
            }
            }
        }

        /** Cast stage: the real detail page (scripts stripped), then
         * jQuery + driver + cast.js. The page must be real, not a
         * hand-built blank: cast.js reads elements the blank page did
         * not have (video-player-area, video-player, playeriframe) and
         * throws at load when they are missing, so no handler is ever
         * registered. The WebView is a pure JS sandbox — every network
         * request is blocked; the app does all network natively. */
        fun setupCastPage() {
            if (done) return
            val wv = android.webkit.WebView(this)
            hiddenWebView = wv
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            }
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }

                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): Boolean = true

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (done) return
                    val jquery = jqueryJs
                    val driver = driverCastJs
                    val cast = castJs
                    if (jquery == null || driver == null || cast == null) {
                        fail("直取失敗：內置腳本缺失")
                        return
                    }
                    evalJs(jquery) {
                        evalJs(driver) {
                            evalJs(cast) {
                                // Probe what the sandbox actually holds
                                // before triggering: jQuery present?
                                // how many click handlers registered on
                                // the first .srv? are the elements
                                // cast.js reads all there?
                                evalJs("(function(){try{var jq=(typeof jQuery!=='undefined');var srv=document.querySelectorAll('.box-server .srv').length;var h=0;try{var el=document.querySelector('.box-server .srv');var ev=el&&jq?jQuery._data(el,'events'):null;h=ev&&ev.click?ev.click.length:0;}catch(e){}var ids=['video-player-area','video-player','playeriframe'].map(function(id){return id+'='+(!!document.getElementById(id));}).join(',');return 'jq='+jq+' srv='+srv+' handlers='+h+' '+ids;}catch(e){return 'ERR:'+e.message;}})();") { probe ->
                                    traceJavhd(sessionId, "沙盒探針：${unquoteJs(probe).orEmpty()}")
                                    // Wrap window.binddata so the feed
                                    // step can report exactly what (if
                                    // anything) the done callback passes
                                    // to it. The callback calls
                                    // window.binddata (verified in jsdom),
                                    // so this intercepts it without
                                    // changing behaviour: the wrapper
                                    // records the argument, then calls
                                    // the original.
                                    evalJs("(function(){if(window.__binddataWrapped)return 'already';var orig=window.binddata;if(typeof orig!=='function')return 'no-binddata';window.binddata=function(arg){try{window.__binddataArg=(arg===undefined?'undefined':String(arg));}catch(e){window.__binddataArg='ERR:'+e.message;}return orig.apply(this,arguments);};window.__binddataWrapped=true;return 'wrapped';})();") { wrap ->
                                        traceJavhd(sessionId, "binddata 包裝：${unquoteJs(wrap).orEmpty()}")
                                        // jQuery ready callbacks fire on
                                        // injection (document already complete);
                                        // give handler registration a beat.
                                        handler.postDelayed({ if (!done) collectSources(0) }, 500)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            wv.loadDataWithBaseURL(item.detailUrl, detailHtml, "text/html", "utf-8", null)
        }

        // Step 1 (worker): read the bundled site scripts, then fetch
        // the detail page over ECH and pull the Server-link tokens
        // out of the HTML. The page itself is never rendered.
        SearchScheduler.workers.execute {
            jqueryJs = assetText("javhd_jquery.min.js")
            driverCastJs = assetText("javhd_driver_cast.js")
            castJs = assetText("javhd_cast.js")
            driverEmbedJs = assetText("javhd_driver_embed.js")
            embedMainJs = assetText("javhd_embed_main.js")
            if (jqueryJs == null || driverCastJs == null || castJs == null ||
                driverEmbedJs == null || embedMainJs == null
            ) {
                handler.post { fail("直取失敗：內置腳本缺失") }
                return@execute
            }
            val site = sites.firstOrNull { it.id == item.siteId }
            if (site == null) {
                handler.post { fail("直取失敗：站點未註冊") }
                return@execute
            }
            val detail = try {
                SearchHttpFetcher().fetch(item.detailUrl, SearchCancellation(), site)
            } catch (e: Exception) {
                traceJavhd(sessionId, "詳情頁 GET：拋錯 ${e.message}")
                handler.post { fail("直取失敗：詳情頁取不到") }
                return@execute
            }
            if (detail.transportError != null || detail.statusCode !in 200..299 || detail.body.isBlank()) {
                traceJavhd(sessionId, "詳情頁 GET：失敗（HTTP ${detail.statusCode}，transport=${detail.transportError ?: "無"}）")
                handler.post { fail("直取失敗：詳情頁取不到") }
                return@execute
            }
            val detailRoute = try {
                EchH3Router.route(java.net.URL(item.detailUrl).host).toString()
            } catch (e: Exception) {
                "?"
            }
            traceJavhd(sessionId, "詳情頁：HTTP ${detail.statusCode}，${detail.body.length}字節，最終=${detail.finalUrl}，路由=$detailRoute")
            // <a class="srv" ... data-link="TOKEN" ...><i ...></i> Part1</a>
            // The anchor content holds an <i> icon, so the label cannot
            // be captured with ([^<]*); take the token from the opening
            // tag and the label leniently after any nested tags.
            val tokenRe = Regex("<a[^>]*data-link=\"([^\"]+)\"[^>]*>")
            val labelRe = Regex("<a[^>]*data-link=\"[^\"]+\"[^>]*>(?:<[^>]*>)*\\s*([^<]*)</a>")
            val labels = labelRe.findAll(detail.body).map { it.groupValues.getOrNull(1).orEmpty().trim() }.toList()
            val found = tokenRe.findAll(detail.body).mapIndexed { i, m ->
                m.groupValues.getOrNull(1).orEmpty().trim() to labels.getOrNull(i).orEmpty()
            }.filter { it.first.isNotEmpty() }.toList()
            val pairs = if (found.isNotEmpty()) found else run {
                // Fallback: Part1's token is also the data-mpu value.
                val mpu = Regex("data-mpu=\"([^\"]+)\"").find(detail.body)?.groupValues?.getOrNull(1).orEmpty()
                if (mpu.isNotEmpty()) listOf(mpu to "Part1") else emptyList()
            }
            if (pairs.isEmpty()) {
                handler.post { fail("直取失敗：詳情頁無令牌") }
                return@execute
            }
            traceJavhd(
                sessionId,
                "令牌：${pairs.size} 個（${pairs.joinToString("，") { it.second.ifEmpty { "?" } }}），長 ${pairs.joinToString("/") { it.first.length.toString() }}" +
                    if (found.isEmpty()) "（正則 0 個，改用 data-mpu）" else "",
            )
            handler.post {
                if (done) return@post
                tokens = pairs.map { it.first }
                partLabels = pairs.mapIndexed { i, (_, label) ->
                    label.ifEmpty { "Part${i + 1}" }
                }
                detailHtml = stripScripts(detail.body)
                // The site no longer ships .srv anchors (the token
                // moved to data-mpu on #video-player, no Server links
                // at all) — cast.js has nothing to bind to, so no
                // `sources` is ever computed. Build synthetic anchors
                // from the extracted tokens so the handler can run.
                // Both data-link and data-mpu carry the token: cast.js
                // reads whichever it uses. (Verified 2026-11-08 in
                // Node: synthetic anchor -> handler bound -> click ->
                // 272-char sources -> /api/play/ status true.)
                if (!detailHtml.contains("class=\"srv\"") && !detailHtml.contains("class='srv'")) {
                    val anchors = tokens.mapIndexed { i, tok ->
                        val label = partLabels.getOrNull(i).orEmpty().ifEmpty { "Part${i + 1}" }
                        val esc = tok.replace("&", "&amp;").replace("\"", "&quot;")
                        "<a class=\"srv\" data-link=\"$esc\" data-mpu=\"$esc\"><i></i> $label</a>"
                    }.joinToString("")
                    val box = "<div class=\"box-server\">$anchors</div>"
                    val bodyIdx = detailHtml.indexOf("</body>", ignoreCase = true)
                    detailHtml = if (bodyIdx >= 0) {
                        detailHtml.substring(0, bodyIdx) + box + detailHtml.substring(bodyIdx)
                    } else {
                        detailHtml + box
                    }
                    traceJavhd(sessionId, "沙盒頁：無 Server 連結，注入 ${tokens.size} 個合成 .srv 錨點")
                }
                traceJavhd(sessionId, "沙盒頁：去 script 後 ${detailHtml.length}字節")
                setupCastPage()
            }
        }
    }

    /** Hidden-WebView click path (live site JS): fallback when the
     * direct extraction chain fails, e.g. the bundled site JS goes
     * stale. Kept intact; tryResolveJavhdRealUrl is the primary
     * direct chain. */
    private fun resolveJavhdViaClick(
        item: SearchItem,
        sessionId: Long,
        directFailReason: String?,
        callback: (List<Pair<String, String>>?) -> Unit,
    ) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var hiddenWebView: android.webkit.WebView? = null
        var done = false
        var chasedEmbed = false
        var setupDone = false
        var partLabels: List<String> = emptyList()
        var partIdx = 0
        var partClicks = 0
        var partStartedAt = 0L
        val foundParts = LinkedHashMap<Int, String>()
        var checker: Runnable? = null
        var timeoutRunnable: Runnable by kotlin.properties.Delegates.notNull()

        // Diagnostics for the 播放數據 screen: which step the resolve
        // died on. pageFinished = any main frame finished; embedSeen =
        // the video.javhdporn.net/p/ iframe was requested; mediaHosts =
        // every host that served a .mp4/.m3u8/.ts (matched or not — a
        // file from an unexpected host shows up here, not in silence).
        // pageStarted/maxProgress/lastLoadError pin a stalled load:
        // started-but-never-finished with a progress % and the last
        // error is a very different failure from never-started.
        // jQueryLoaded/domAfterClick/lastClickedPart separate "the
        // click handler never ran" (jQuery missing / no iframe after
        // the click) from "the embed request failed".
        var pageFinished = false
        var pageStarted = false
        var maxProgress = 0
        var lastLoadError: String? = null
        var jQueryLoaded: Boolean? = null
        var domAfterClick: String? = null
        var lastClickedPart = 0
        var embedSeen = false
        val mediaHosts = LinkedHashSet<String>()
        val pageSlug = item.detailUrl.trimEnd('/').substringAfterLast('/')

        // Ad hosts (see javhdAdHosts) are blocked outright in this
        // hidden WebView so the resolve never runs any of them.

        /** Step-level failure reason for the 播放數據 screen. Never throws. */
        fun javhdFailReason(): String {
            val media = if (mediaHosts.isEmpty()) "無" else mediaHosts.take(3).joinToString(",")
            val err = lastLoadError?.let { "；$it" } ?: ""
            val jq = when (jQueryLoaded) {
                true -> "；jQuery已載入"
                false -> "；jQuery未載入"
                null -> ""
            }
            val dom = domAfterClick?.let { "；點擊後$it" } ?: ""
            return when {
                !pageStarted -> "javhd解析失敗 [$pageSlug]：頁面未開始加載"
                !pageFinished -> "javhd解析失敗 [$pageSlug]：頁面未完成加載（進度$maxProgress%" +
                    (lastLoadError?.let { "，$it" } ?: "") + ")"
                partLabels.isEmpty() -> "javhd解析失敗 [$pageSlug]：頁面已載、無Server連結；媒體host=$media$err$jq"
                !embedSeen -> "javhd解析失敗 [$pageSlug]：srv=${partLabels.size} " +
                    (if (lastClickedPart > 0) "已點Part$lastClickedPart、" else "") +
                    "嵌頁未載入；媒體host=$media$jq$dom$err"
                else -> "javhd解析失敗 [$pageSlug]：srv=${partLabels.size} 嵌頁已載、未見文件；媒體host=$media$err"
            }
        }

        fun finish() {
            if (done) return
            done = true
            handler.removeCallbacks(timeoutRunnable)
            checker?.let { handler.removeCallbacks(it) }
            hiddenWebView?.destroy()
            hiddenWebView = null
            val result = if (foundParts.isEmpty()) null else foundParts.entries.map { (i, url) ->
                val label = if (i in partLabels.indices) partLabels[i] else ""
                label to url
            }
            if (result != null) {
                traceJavhd(sessionId, "點擊完成：${result.size} 個 Part")
                PlaybackDataStore.addCandidates(sessionId, result.map { (label, url) ->
                    resolverRecord(
                        url, "resolver-javhd",
                        if (label.isEmpty()) "解析直連（跳過廣告）" else "$label 解析直連（跳過廣告）",
                    )
                })
            } else {
                val reason = if (directFailReason != null) {
                    "$directFailReason；點擊亦失敗：${javhdFailReason()}"
                } else {
                    javhdFailReason()
                }
                traceJavhd(sessionId, "點擊失敗：${javhdFailReason()}")
                PlaybackDataStore.recordFail(sessionId, reason)
            }
            callback(result)
        }

        fun clickSrv(i: Int) {
            partClicks++
            lastClickedPart = i + 1
            traceJavhd(sessionId, "點擊 Part${i + 1}（第 $partClicks 次）")
            hiddenWebView?.evaluateJavascript(
                "(function(){var a=document.querySelectorAll('a.srv');" +
                    "if(a.length>" + i + "){a[" + i + "].click();return 'clicked';}" +
                    "return 'no-srv';})();",
                null,
            )
        }

        /** Before giving up, look at the DOM once: an embed iframe in
         * the page with no network request seen means the handler ran
         * but the request died before the network; no iframe at all
         * means the handler never ran. The 75s timeout still calls
         * finish() directly if this probe never returns. */
        fun finishWithDomProbe() {
            hiddenWebView?.evaluateJavascript(
                "(function(){var f=document.querySelectorAll('iframe').length;" +
                    "var e=document.querySelector('iframe[src*=\"video.javhdporn.net\"]');" +
                    "return 'iframes='+f+'|'+(e?'embed':'no-embed');})();",
            ) { value ->
                val s = value?.trim()?.removeSurrounding("\"").orEmpty()
                if (s.isNotEmpty() && s != "null") domAfterClick = s
                traceJavhd(sessionId, "DOM 探針：$domAfterClick")
                if (!done) finish()
            }
        }

        fun clickLegacyPlay() {
            hiddenWebView?.evaluateJavascript(
                "(function(){" +
                    "var s=['#overlay','#mainvideo','video','[id*=\"play\"]','[class*=\"play\"]','[class*=\"btn\"]'];" +
                    "for(var i=0;i<s.length;i++){" +
                    "var el=document.querySelector(s[i]);" +
                    "if(el){el.click();return 'clicked:'+s[i];}}" +
                    "return 'no element found';})();",
                null,
            )
        }

        fun parseSrvLabels(value: String?): List<String> {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty() || raw == "null") return emptyList()
            return try {
                val inner = org.json.JSONTokener(raw).nextValue() as? String ?: return emptyList()
                val arr = org.json.JSONArray(inner)
                List(arr.length()) { i -> arr.optString(i).trim() }.filter { it.isNotEmpty() }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun listSrv(attempt: Int) {
            hiddenWebView?.evaluateJavascript(
                "(function(){var a=document.querySelectorAll('a.srv');var out=[];" +
                    "for(var i=0;i<a.length;i++){out.push((a[i].textContent||'').trim()||('Part'+(i+1)));}" +
                    "return JSON.stringify(out);})();"
            ) { value ->
                if (done) return@evaluateJavascript
                partLabels = parseSrvLabels(value)
                if (partLabels.isEmpty()) {
                    // No Server links yet — the page may still be
                    // settling or redirecting; retry twice before
                    // falling back to the legacy Streamtape path.
                    traceJavhd(sessionId, "Server 連結：0 個（第 ${attempt + 1} 次）")
                    if (attempt < 2) {
                        handler.postDelayed({ if (!done) listSrv(attempt + 1) }, 4000)
                    } else {
                        clickLegacyPlay()
                        handler.postDelayed({ if (!done) finish() }, 20000)
                    }
                } else {
                    traceJavhd(sessionId, "Server 連結：${partLabels.size} 個（${partLabels.joinToString("，")}）")
                    // Probe jQuery before clicking: the theme's click
                    // handler lives in cast.js and needs jQuery (from
                    // ajax.googleapis.com). If jQuery never loaded, the
                    // clicks are dead on arrival — that is a different
                    // failure from a dead embed request.
                    hiddenWebView?.evaluateJavascript(
                        "(function(){return (typeof jQuery!=='undefined')?'jq-yes':'jq-no';})();",
                    ) { value ->
                        val s = value?.trim()?.removeSurrounding("\"").orEmpty()
                        if (s == "jq-yes") jQueryLoaded = true
                        else if (s == "jq-no") jQueryLoaded = false
                        traceJavhd(sessionId, "jQuery：${if (jQueryLoaded == true) "已載入" else "未載入"}")
                        if (!done) checker?.let { handler.post(it) }
                    }
                }
            }
        }

        checker = object : Runnable {
            override fun run() {
                if (done) return
                if (partLabels.isEmpty()) {
                    // Legacy video (no Server links): the single play
                    // click was already sent; the 20s cutoff ends it.
                    handler.postDelayed(this, 3000)
                    return
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (partIdx >= partLabels.size) {
                    finishWithDomProbe()
                    return
                }
                when {
                    foundParts.containsKey(partIdx) -> {
                        partIdx++
                        partClicks = 0
                        if (partIdx >= partLabels.size) {
                            finishWithDomProbe()
                            return
                        }
                        partStartedAt = now
                        clickSrv(partIdx)
                    }
                    partClicks == 0 -> {
                        partStartedAt = now
                        clickSrv(partIdx)
                    }
                    partClicks == 1 && now - partStartedAt > 8000 -> clickSrv(partIdx)
                    now - partStartedAt > 20000 -> {
                        partIdx++
                        partClicks = 0
                        if (partIdx >= partLabels.size) {
                            finishWithDomProbe()
                            return
                        }
                        partStartedAt = now
                        clickSrv(partIdx)
                    }
                }
                handler.postDelayed(this, 3000)
            }
        }

        timeoutRunnable = Runnable { finish() }
        handler.post {
            val wv = android.webkit.WebView(this)
            hiddenWebView = wv
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                mediaPlaybackRequiresUserGesture = false
                // Never serve this page from cache: a page that once failed
                // sticks dead in the shared WebView cache (known app
                // behaviour — only clearing the app cache revives it), and
                // a dead cached page has no Server links to click. The
                // resolver must always see the live page.
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            }
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                    if (newProgress > maxProgress) maxProgress = newProgress
                }
            }
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if (javhdAdHosts.any { reqUrl.contains(it) }) {
                        return android.webkit.WebResourceResponse("text/plain", "utf-8",
                            java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    if (reqUrl.contains("video.javhdporn.net/p/")) {
                        if (!embedSeen) {
                            embedSeen = true
                            traceJavhd(sessionId, "嵌頁請求：${reqUrl.take(100)}")
                        } else {
                            embedSeen = true
                        }
                    }
                    if (reqUrl.contains(".mp4") || reqUrl.contains(".m3u8") || reqUrl.contains(".ts")) {
                        try {
                            val h = java.net.URL(reqUrl).host
                            if (mediaHosts.add(h)) traceJavhd(sessionId, "媒體請求：$h")
                        } catch (e: Exception) { }
                    }
                    val isReal = (reqUrl.contains("streamtape") && reqUrl.contains("get_video")) ||
                        (reqUrl.contains("tapecontent") &&
                            (reqUrl.contains(".mp4") || reqUrl.contains(".ts") || reqUrl.contains("m3u8"))) ||
                        (reqUrl.contains("vdcdn.xyz") &&
                            (reqUrl.contains(".mp4") || reqUrl.contains("m3u8"))) ||
                        // The file host is geo-selected (us-a2 in the
                        // sandbox run); any /file.mp4 is the JWPlayer
                        // file, whatever region host serves it.
                        reqUrl.contains("/file.mp4")
                    if (isReal) {
                        handler.post {
                            if (!done) {
                                val key = if (partLabels.isEmpty()) -1 else partIdx
                                if (!foundParts.containsKey(key)) {
                                    foundParts[key] = reqUrl
                                    traceJavhd(sessionId, "直鏈請求：${reqUrl.take(120)}")
                                }
                            }
                        }
                        return android.webkit.WebResourceResponse("text/plain", "utf-8",
                            java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    if (!chasedEmbed && reqUrl.contains("streamtape") && reqUrl.contains("/e/")) {
                        chasedEmbed = true
                        traceJavhd(sessionId, "跳轉 streamtape：${reqUrl.take(100)}")
                        handler.post { hiddenWebView?.loadUrl(reqUrl) }
                        return android.webkit.WebResourceResponse("text/plain", "utf-8",
                            java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    // ECH: same treatment as the main browser — GET
                    // requests to hosts with an ECH config ride the
                    // app-layer stack instead of WebView's plaintext
                    // TLS. Without this the hidden WebView can hang on
                    // page load while the main browser opens the same
                    // URL fine; hosts without a config fall through to
                    // WebView default as before.
                    if (request?.method.equals("GET", ignoreCase = true) == true) {
                        EchH3Router.fetchWebViewResource(
                            reqUrl, request?.requestHeaders, request?.isForMainFrame == true,
                        )?.let { return it }
                    }
                    return null
                }

                override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!pageStarted) traceJavhd(sessionId, "頁面開始加載：${url?.take(100)}")
                    pageStarted = true
                }

                override fun onReceivedError(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    if (lastLoadError == null && error != null) {
                        lastLoadError = "錯誤 ${request?.url?.host ?: "?"} ${error.errorCode} ${error.description}"
                        traceJavhd(sessionId, "頁面錯誤：$lastLoadError")
                    }
                }

                override fun onReceivedHttpError(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (lastLoadError == null && errorResponse != null) {
                        lastLoadError = "HTTP ${errorResponse.statusCode} ${request?.url?.host ?: "?"}"
                        traceJavhd(sessionId, "頁面錯誤：$lastLoadError")
                    }
                }

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (setupDone || done) return
                    setupDone = true
                    pageFinished = true
                    traceJavhd(sessionId, "頁面完成加載（進度 $maxProgress%）")
                    handler.postDelayed({
                        if (!done) listSrv(0)
                    }, 2000)
                }
            }
            wv.loadUrl(item.detailUrl)
            traceJavhd(sessionId, "點擊開始：載入 ${item.detailUrl}")
            handler.postDelayed(timeoutRunnable, 75000)
        }
    }

    private fun playSniffed(item: SearchItem, videoUrl: String, sessionId: Long) {
        // HentaiMama and javhdporn direct files play in the native player:
        // the WebView player stalls on them (plays ~1s, then jumps back),
        // while the same URL plays fine in a browser. Every other site
        // keeps the existing internal player.
        val target = if (item.siteId == SearchSiteIds.HENTAIMAMA || item.siteId == SearchSiteIds.JAVHD) {
            NativePlayerActivity::class.java
        } else {
            FullscreenInternalPlayerActivity::class.java
        }
        // javhd: the video CDN rejects the detail page as referer
        // (HTTP 403, InvalidResponseCodeException — the 13:09 run).
        // Send the embed page instead: it is the real player referer,
        // the one JWPlayer itself sends. Fall back to no referer (a
        // bare link also plays) when the embed URL wasn't recorded.
        val referer = if (item.siteId == SearchSiteIds.JAVHD) {
            javhdEmbedRefererByUrl[videoUrl].orEmpty()
        } else {
            item.detailUrl
        }
        startActivity(android.content.Intent(this, target).apply {
            putExtra(FullscreenInternalPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_REFERER, referer)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_PAGE_URL, item.detailUrl)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_DOWNLOAD_NAME, item.title)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_SESSION_ID, sessionId)
        })
    }

    /** Lists sniffed candidates with their m3u8 duration, score and source — now a full page, not a dialog. Never throws. */
    private fun showSniffPicker(item: SearchItem, candidates: List<SniffCandidateRecord>, sessionId: Long, ads: List<SniffCandidateRecord> = emptyList()) {
        if (isDestroyed || isFinishing) return
        choiceLauncher.launch(VideoChoiceActivity.intent(this, item, candidates, sessionId, ads))
    }

    /** Returns sniffed candidates (proven <60s m3u8 ads excluded), or empty to fall back to WebView. Never throws. */
    /**
     * Resolves HentaiMama's real file without ever running the player's
     * VAST ad: episode page (ECH fetch) -> post id -> admin-ajax POST
     * get_player_contents -> dt_embed iframe -> JWPlayer sources file
     * (gdvid.info mp4). The ad domains (twinrdengine/marzaent/sacdnssedge)
     * only appear in the advertising block, which this path never executes.
     * Callback on the UI thread with the direct URL, or null on any failure
     * — the caller then falls back to WebView. Never throws.
     */
    private fun tryResolveHentaiMamaRealUrl(item: SearchItem, sessionId: Long, callback: (String?) -> Unit) {
        SearchScheduler.workers.execute {
            val fastUrl = try { resolveHentaiMamaRealUrl(item) } catch (e: Exception) { null }
            if (fastUrl != null) {
                PlaybackDataStore.addCandidates(sessionId, listOf(resolverRecord(fastUrl, "resolver-hentaimama-fast")))
                runOnUiThread { callback(fastUrl) }
                return@execute
            }
            // Fast path failed — usually the admin-ajax POST is CF-challenged
            // on-device (the sandbox POST works because it comes from a
            // foreign IP). Retry through a hidden WebView, which passes CF
            // challenges exactly like a browser.
            runOnUiThread { tryResolveHentaiMamaViaWebView(item, sessionId, callback) }
        }
    }

    /**
     * Resolves HentaiMama's real file through a hidden WebView: load the
     * episode page (CF clearance happens like a browser), click the mirror
     * tab so the page itself runs get_player_contents, read the injected
     * dt_embed iframe src, then fetch the embed page over ECH and parse
     * the JWPlayer file. Callback on the UI thread with the URL, or null
     * on timeout (15s) / failure — the caller then falls back to WebView.
     * Never throws.
     */
    private fun tryResolveHentaiMamaViaWebView(item: SearchItem, sessionId: Long, callback: (String?) -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var hiddenWebView: android.webkit.WebView? = null
        var done = false
        var timeoutRunnable: Runnable by kotlin.properties.Delegates.notNull()
        // No-op default: finish() may run before onPageFinished assigns the real poll.
        var pollRunnable: Runnable = Runnable {}
        fun finish(url: String?) {
            if (done) return
            done = true
            handler.removeCallbacks(timeoutRunnable)
            handler.removeCallbacks(pollRunnable)
            hiddenWebView?.destroy()
            hiddenWebView = null
            if (url != null) {
                PlaybackDataStore.addCandidates(sessionId, listOf(resolverRecord(url, "resolver-hentaimama-webview")))
            } else {
                PlaybackDataStore.recordFail(sessionId, "hentaimama解析失敗（ajax與WebView皆失敗）")
            }
            callback(url)
        }
        timeoutRunnable = Runnable { finish(null) }
        handler.post {
            val wv = android.webkit.WebView(this)
            hiddenWebView = wv
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                mediaPlaybackRequiresUserGesture = false
            }
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Let the page settle, then click the mirror tab so the
                    // page runs get_player_contents itself.
                    handler.postDelayed({
                        if (done) return@postDelayed
                        view?.evaluateJavascript(
                            "(function(){var t=document.querySelector('.dt-mi-tabs li a.selected')||document.querySelector('.dt-mi-tabs li a');if(t){t.click();return 'clicked';}return 'no-tab';})();",
                            null
                        )
                    }, 2000)
                    // Poll for the injected dt_embed iframe: the page
                    // auto-loads its first mirror, but the AJAX can be
                    // slow behind a CF challenge — a one-shot read at 5s
                    // misses it. Poll every second until the 15s timeout.
                    pollRunnable = Runnable {
                        if (done) return@Runnable
                        view?.evaluateJavascript(
                            "(function(){var f=document.querySelector('iframe[src*=\"dt_embed\"]');return f?f.src:'';})();"
                        ) { value ->
                            if (done) return@evaluateJavascript
                            val embedUrl = value?.removeSurrounding("\"").orEmpty()
                            if (embedUrl.isBlank() || !embedUrl.contains("dt_embed")) {
                                handler.postDelayed(pollRunnable, 1000)
                                return@evaluateJavascript
                            }
                            SearchScheduler.workers.execute {
                                val fileUrl = try {
                                    val site = sites.firstOrNull { it.id == item.siteId }
                                    if (site == null) {
                                        null
                                    } else {
                                        val embed = SearchHttpFetcher().fetch(
                                            embedUrl, SearchCancellation(), site
                                        )
                                        if (embed.transportError != null || embed.statusCode !in 200..299 || embed.body.isBlank()) {
                                            null
                                        } else {
                                            VideoExtractor.extractHentaiMamaVideoFile(embed.body)
                                        }
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                                handler.post { finish(fileUrl) }
                            }
                        }
                    }
                    handler.postDelayed(pollRunnable, 3000)
                }
            }
            wv.loadUrl(item.detailUrl)
            handler.postDelayed(timeoutRunnable, 15000)
        }
    }

    private fun resolveHentaiMamaRealUrl(item: SearchItem): String? {
        val site = sites.firstOrNull { it.id == item.siteId } ?: return null
        val episode = SearchHttpFetcher().fetch(item.detailUrl, SearchCancellation(), site)
        if (episode.transportError != null || episode.statusCode !in 200..299 || episode.body.isBlank()) return null
        val postId = VideoExtractor.extractHentaiMamaPostId(episode.body) ?: return null
        val host = try { java.net.URL(item.detailUrl).host } catch (e: Exception) { return null }
        val ajaxJson = postForm(
            "https://$host/wp-admin/admin-ajax.php", item.detailUrl,
            mapOf("action" to "get_player_contents", "a" to postId, "i" to "0")
        ) ?: return null
        val embedUrl = VideoExtractor.extractHentaiMamaEmbedUrl(ajaxJson) ?: return null
        val embed = SearchHttpFetcher().fetch(embedUrl, SearchCancellation(), site)
        if (embed.transportError != null || embed.statusCode !in 200..299 || embed.body.isBlank()) return null
        return VideoExtractor.extractHentaiMamaVideoFile(embed.body)
    }

    /** Small form-POST helper for the HentaiMama player AJAX (SearchHttpFetcher is GET-only). */
    private fun postForm(url: String, referer: String, fields: Map<String, String>): String? {
        return try {
            val form = FormBody.Builder().apply {
                fields.forEach { (k, v) -> add(k, v) }
            }.build()
            val requestBuilder = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Referer", referer)
            // CF clearance: the app's WebView may already hold __cf_bm for
            // this host (from browsing the episode page); send it so the
            // admin-ajax POST is not challenged the way a bare request is.
            val cookies = android.webkit.CookieManager.getInstance().getCookie(referer)
                ?.takeIf { it.isNotEmpty() }
            if (!cookies.isNullOrEmpty()) requestBuilder.header("Cookie", cookies)
            val request = requestBuilder.post(form).build()
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns kept sniffed candidates (proven <60s m3u8 ads excluded), or
     * empty to fall back to WebView. Every candidate — kept and dropped —
     * is recorded against the session with its score, source and drop
     * reason, so the analysis can see which signal actually predicted a
     * real play. Never throws. The second list is the folded ads: dropped
     * as ads (ad keywords, <60s m3u8, or a repeated low-confidence
     * cluster). The picker shows them collapsed under 「疑似廣告」 so a
     * wrong call can be undone by expanding the group.
     */
    private fun trySniffVideoCandidates(item: SearchItem, sessionId: Long): Pair<List<SniffCandidateRecord>, List<SniffCandidateRecord>> {
        return try {
            val host = try { java.net.URL(item.detailUrl).host } catch (e: Exception) {
                PlaybackDataStore.recordFail(sessionId, "詳情頁地址無效")
                return emptyList<SniffCandidateRecord>() to emptyList()
            }
            if (EchDoh.configFor(host) == null) {
                PlaybackDataStore.recordFail(sessionId, "無ECH設定，不嗅探")
                return emptyList<SniffCandidateRecord>() to emptyList()
            }
            val site = sites.firstOrNull { it.id == item.siteId } ?: run {
                PlaybackDataStore.recordFail(sessionId, "站點未註冊")
                return emptyList<SniffCandidateRecord>() to emptyList()
            }
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    LanguageManager.text(this, "正在嗅探播放地址…", "Sniffing video URL…"),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            val response = SearchHttpFetcher().fetch(item.detailUrl, SearchCancellation(), site)
            if (response.transportError != null) {
                PlaybackDataStore.recordFail(sessionId, "取詳情頁失敗：${response.transportError}")
                return emptyList<SniffCandidateRecord>() to emptyList()
            }
            if (response.statusCode !in 200..299) {
                PlaybackDataStore.recordFail(sessionId, "詳情頁HTTP ${response.statusCode}")
                return emptyList<SniffCandidateRecord>() to emptyList()
            }
            if (response.body.isBlank()) {
                PlaybackDataStore.recordFail(sessionId, "詳情頁為空")
                return emptyList<SniffCandidateRecord>() to emptyList()
            }
            filterSniffCandidates(
                VideoPageSniffer.sniffScored(response.body, item.detailUrl, item.code),
                site, sessionId,
            )
        } catch (e: Exception) {
            PlaybackDataStore.recordFail(sessionId, "嗅探異常：${e.message}")
            emptyList<SniffCandidateRecord>() to emptyList()
        }
    }

    /**
     * Shared candidate filter: m3u8 duration, URL dedupe, repeat-cluster
     * ad folding. Identical for static HTML sniffing and live network
     * sniffing, so both paths produce the same (kept, ads) and the
     * picker's 疑似廣告 folding applies unchanged. Runs on a worker
     * thread (m3u8Duration fetches). Never throws.
     */
    private fun filterSniffCandidates(
        scored: List<VideoPageSniffer.ScoredCandidate>,
        site: SearchSite,
        sessionId: Long,
    ): Pair<List<SniffCandidateRecord>, List<SniffCandidateRecord>> {
        val records = mutableListOf<SniffCandidateRecord>()
        for (sc in scored) {
            val duration = if (sc.kept) m3u8Duration(sc.url, site) else null
            val (kept, dropReason, evidence) = when {
                !sc.kept -> Triple(false, sc.dropReason, sc.evidence)
                duration != null && duration < 60.0 -> Triple(
                    false, "m3u8時長<60s疑似廣告",
                    "${sc.evidence} · m3u8 ${duration.toInt()}秒",
                )
                duration != null -> Triple(
                    true, null,
                    "${sc.evidence} · m3u8 ${(duration / 60).toInt()}:${(duration % 60).toInt().toString().padStart(2, '0')}",
                )
                else -> Triple(true, null, sc.evidence)
            }
            val score = sc.score + if (duration != null && duration >= 60.0) 20 else 0
            records += SniffCandidateRecord(sc.url, sc.source, score, evidence, kept, dropReason, duration)
        }
        // 自動分析廣告：同一詳情頁裡反覆出現的候選（同 host + 同類型
        // + 同來源，如 12 個一模一樣的 10 分 mp4）幾乎總是廣告/預告
        // 片，不是正片。先按 URL 去重，再把數量 ≥3 的簇整組標為疑似
        // 廣告，折疊進選擇器的「疑似廣告」分組（點開可取回），避免
        // 選擇器被同一條廣告刷屏。
        val seenUrls = mutableSetOf<String>()
        val deduped = records.map { r ->
            if (r.kept && !seenUrls.add(r.url)) r.copy(kept = false, dropReason = "重複地址")
            else r
        }
        // Master/variant 併流：一個 master.m3u8 和同目錄下的分
        // variant（如 index-f1-v1-a1.m3u8）是同一條流，不是兩個
        // 視頻（2026-11-08 javhd：streamhls.click …/76285967/
        // master.m3u8 與 …/76285967/index-f1-v1-a1.m3u8 被重複列出
        // 兩次）。同目錄已有保留的 master 時，分 variant 併入
        // master，只留一條候選；master 本身沒被保留時不動分
        // variant（它可能是唯一可播的副本）。
        val fileName = { url: String ->
            url.substringAfterLast('/').substringBefore('?').substringBefore('#').lowercase()
        }
        val variantOfMaster = deduped.filter { it.kept && it.url.lowercase().contains(".m3u8") }
            .groupBy { it.url.substringBeforeLast('/') }
            .flatMap { (_, group) ->
                val masterKept = group.any { fileName(it.url) == "master.m3u8" }
                if (!masterKept) emptyList()
                else group.filter { fileName(it.url) != "master.m3u8" }.map { it.url }
            }.toSet()
        val collapsed = deduped.map { r ->
            if (r.kept && r.url in variantOfMaster) r.copy(kept = false, dropReason = "同流分 variant，已併入 master")
            else r
        }
        val clusterKey = { r: SniffCandidateRecord ->
            val host = try { java.net.URL(r.url).host } catch (e: Exception) { "" }
            val type = if (r.url.lowercase().contains(".m3u8")) "m3u8" else "mp4"
            "$host|$type|${r.source}"
        }
        val clusterCounts = collapsed.filter { it.kept }.groupingBy(clusterKey).eachCount()
        val filtered = collapsed.map { r ->
            if (!r.kept) return@map r
            val key = clusterKey(r)
            val count = clusterCounts[key] ?: 0
            if (count >= 3) r.copy(
                kept = false, dropReason = "重複疑似廣告",
                evidence = "${r.evidence} · 疑似廣告×$count",
            ) else r
        }
        PlaybackDataStore.addCandidates(sessionId, filtered)
        val kept = filtered.filter { it.kept }
        // Folded ads: dropped as ads (ad keywords, <60s m3u8, or a
        // repeated low-confidence cluster). The picker shows them
        // collapsed under 「疑似廣告」 so a wrong call can be undone.
        val ads = filtered.filter { !it.kept && (it.dropReason?.contains("廣告") == true) }
        if (kept.isEmpty() && ads.isEmpty()) PlaybackDataStore.recordFail(sessionId, "找到 ${records.size} 個候選，全部被篩掉")
        return kept to ads
    }

    /**
     * Summed #EXTINF duration of an m3u8 media playlist, or null when
     * it cannot be determined (not m3u8, master playlist with no
     * EXTINF, fetch failure). Null never excludes a candidate — only
     * a proven <60s total marks an ad.
     */
    private fun m3u8Duration(candidate: String, site: SearchSite): Double? {
        if (!candidate.lowercase().contains(".m3u8")) return null
        return try {
            val playlist = SearchHttpFetcher().fetch(candidate, SearchCancellation(), site)
            if (playlist.transportError != null) return null
            if (playlist.statusCode !in 200..299) return null
            var total = 0.0
            var sawExtinf = false
            for (line in playlist.body.lines()) {
                if (line.startsWith("#EXTINF:")) {
                    val duration = line.removePrefix("#EXTINF:").substringBefore(",").toDoubleOrNull()
                    if (duration != null) {
                        total += duration
                        sawExtinf = true
                    }
                }
            }
            if (sawExtinf) total else null
        } catch (e: Exception) {
            null
        }
    }

    private fun setupSiteChooser() {
        sites.forEach { site ->
            val check = CheckBox(this).apply {
                text = site.displayName
                tag = site.id
                // avjoy 自建服务器且三条路全 RST，默认不选（用户仍可手动勾选）。
                isChecked = site.id != SearchSiteIds.AVJOY
                textSize = 11f
                setTextColor(android.graphics.Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.rgb(187, 134, 252)
                )
                setPadding(0, 0, dp(6), 0)
            }
            siteChecks[site.id] = check
            siteChooser.addView(check, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)
            ))
        }
    }

    private fun loadLocalMetadata() {
        SavedSearchStore.io.execute {
            val index = runCatching {
                SearchLocalMetadataIndex.from(FavoritesManager(this).getFavorites(), DownloadRepository.list(this))
            }.getOrDefault(SearchLocalMetadataIndex.EMPTY)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) adapter.setLocalMetadata(index)
            }
        }
    }

    private fun setupSourceFilter() {
        val labels = listOf(tr("全部來源", "All sources")) + sites.map { it.displayName }
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedSource = if (position == 0) "" else sites[position - 1].id
                renderItems(lastSnapshot)
                renderPanel()
            }
        }
    }

    private fun startSearch(bypassCache: Boolean = true, offerSaved: Boolean = true) {
        if (!savedSearchesReady) {
            Toast.makeText(this, tr("正在讀取已存搜尋，請稍候", "Loading saved searches; please wait"), Toast.LENGTH_SHORT).show()
            return
        }
        savedReadSerial++
        val raw = queryEdit.text.toString().trim()
        if (raw.isBlank()) {
            Toast.makeText(this, tr("請輸入番號或關鍵字", "Enter a code or keyword"), Toast.LENGTH_SHORT).show()
            return
        }
        val selected = siteChecks.filterValues { it.isChecked }.keys.toList()
        if (selected.isEmpty()) {
            Toast.makeText(this, tr("至少選擇一個網站", "Select at least one site"), Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = SearchQueryNormalizer.normalize(raw, queryMode)
        val key = SavedSearchCodec.key(normalized.normalized, normalized.mode)
        val existing = savedSearches.firstOrNull { it.key == key }
        if (offerSaved && existing != null && openedSavedKey != key) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(tr("找到已存搜尋", "Saved search found"))
                .setMessage(savedSearchLabel(existing) + "\n" + tr("可直接開啟上次結果，或重新搜尋最新資料。", "Open previous results or run a fresh search."))
                .setPositiveButton(tr("開啟已存結果", "Open saved")) { _, _ -> openSavedSearch(existing.key) }
                .setNeutralButton(tr("重新搜尋", "Search again")) { _, _ -> startSearch(bypassCache, false) }
                .setNegativeButton(tr("取消", "Cancel"), null).show()
            return
        }

        session?.cancel()
        SearchSessionStore.remove(activeRequestId)
        repository.close()
        repository = UnifiedSearchRepository(this)
        lastSnapshot = null
        renderedSiteStates = null
        adapter.setOrderFrozen(false)
        readAnchor = null
        restoredAnchor = null
        openedSavedAt = null
        openedSavedKey = null
        panelState = SearchPanelState()
        renderPanel()
        adapter.submitItems(emptyList())
        siteStatuses.removeAllViews()
        val request = repository.createRequest(raw, queryMode == SearchInputMode.CODE, selected, runMode, queryMode)
        forceCodeForNextSearch = false
        activeRequestId = request.requestId
        session = repository.start(request, this, bypassCache)
        hideSearchKeyboard()
    }

    /**
     * Browse mode — the standard-app site screen. Lists one site's own
     * latest/home page as native items, reusing that site's search
     * provider (the browse URL is the first page token) and the native
     * result list + tap-to-play flow (openResult). The search box and
     * site chooser are hidden: this screen is one site, browsed.
     */
    private fun startBrowse(siteId: String, browseUrl: String) {
        val site = sites.firstOrNull { it.id == siteId } ?: return
        session?.cancel()
        SearchSessionStore.remove(activeRequestId)
        repository.close()
        repository = UnifiedSearchRepository(this)
        lastSnapshot = null
        renderedSiteStates = null
        adapter.setOrderFrozen(false)
        readAnchor = null
        restoredAnchor = null
        openedSavedAt = null
        openedSavedKey = null
        panelState = SearchPanelState()
        renderPanel()
        adapter.submitItems(emptyList())
        siteStatuses.removeAllViews()
        findViewById<android.view.View>(R.id.unified_query_controls)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.unified_site_chooser_scroll)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.unified_selection_controls)?.visibility = android.view.View.GONE
        statusText.text = LanguageManager.text(this, "正在載入 ${site.displayName}…", "Loading ${site.displayName}…")
        val request = SearchRequest(
            java.util.UUID.randomUUID().toString(), "", "", SearchMode.KEYWORD,
            listOf(site.id), site.configurationKey,
            runMode = SearchRunMode.BROWSE, browseUrl = browseUrl
        )
        activeRequestId = request.requestId
        session = repository.start(request, this, true)
        hideSearchKeyboard()
    }

    override fun onSnapshot(snapshot: SearchSnapshot) {
        if (snapshot.request.requestId != activeRequestId || isDestroyed) return
        lastSnapshot = snapshot
        SearchSessionStore.put(snapshot)
        val running = snapshot.isRunning
        progress.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        stopButton.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        searchButton.isEnabled = true
        searchButton.text = tr("重新搜尋", "Search again")
        val wasCollapsed = panelState.collapsed
        panelState = panelState.onResults(snapshot.items.isNotEmpty())
        if (!wasCollapsed && panelState.collapsed) hideSearchKeyboard()
        renderPanel()
        renderItems(snapshot)
        if (renderedSiteStates != snapshot.siteStates) {
            renderSiteStatuses(snapshot)
            renderedSiteStates = snapshot.siteStates
        }
    }

    private fun hideSearchKeyboard() {
        queryEdit.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(queryEdit.windowToken, 0)
    }

    private fun renderPanel() {
        val currentKey = lastSnapshot?.request?.let { SavedSearchCodec.key(it.normalizedQuery, it.mode) }
        findViewById<Button>(R.id.btn_unified_save_search).apply {
            isEnabled = savedSearchesReady && !savingSearch && lastSnapshot?.items?.isNotEmpty() == true
            text = if (savedSearches.any { it.key == currentKey }) tr("更新儲存", "Update saved") else tr("儲存搜尋", "Save search")
        }
        findViewById<Button>(R.id.btn_unified_saved_searches).apply {
            isEnabled = savedSearchesReady && !savingSearch
            text = tr("已存搜尋（${savedSearches.size}）", "Saved (${savedSearches.size})")
        }
        findViewById<Button>(R.id.btn_unified_run_mode).apply {
            text = if (runMode == SearchRunMode.BROWSE)
                tr("流暢瀏覽 · 首頁優先", "Smooth browse · first page first")
            else tr("深入搜尋 · 自動續頁", "Deep search · auto next pages")
            contentDescription = text
        }
        findViewById<Button>(R.id.btn_unified_query_mode).apply {
            text = when (queryMode) {
                SearchInputMode.AUTO -> tr("查詢模式：自動辨識", "Query mode: Auto")
                SearchInputMode.CODE -> tr("查詢模式：精確番號", "Query mode: Exact code")
                SearchInputMode.KEYWORD -> tr("查詢模式：關鍵字", "Query mode: Keyword")
            }
            contentDescription = text
        }
        findViewById<Button>(R.id.btn_unified_sort_mode).apply {
            text = when (sortMode) {
                SearchSortMode.RELEVANCE -> tr("排序：相關性", "Sort: Relevance")
                SearchSortMode.SOURCE_PREFERENCE -> tr("排序：來源偏好", "Sort: Source preference")
                SearchSortMode.ORIGINAL -> tr("排序：原站順序", "Sort: Original order")
            }
            contentDescription = text
        }
        findViewById<TextView>(R.id.tv_unified_saved_notice).apply {
            visibility = if (openedSavedAt != null) android.view.View.VISIBLE else android.view.View.GONE
            text = openedSavedAt?.let { tr("載入儲存時間：", "Loaded save from: ") + savedTime(it) +
                tr(" · 含非即時資料；更新後請再按儲存", " · Includes saved data; save again after updating") }.orEmpty()
        }
        val resumable = lastSnapshot?.siteStates.orEmpty().filter { selectedSource.isBlank() || it.siteId == selectedSource }
            .filter { SearchPagingPolicy.canContinue(it) || it.status in setOf(SearchStatus.CANCELLED, SearchStatus.TIMEOUT) }
        findViewById<Button>(R.id.btn_unified_continue_pages).apply {
            visibility = if (resumable.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            isEnabled = lastSnapshot?.isRunning != true
            text = tr("繼續抓取後續頁（${resumable.size} 站）", "Continue next pages (${resumable.size} sites)")
        }
        val visibility = if (panelState.collapsed) android.view.View.GONE else android.view.View.VISIBLE
        listOf(
            R.id.btn_unified_run_mode,
            R.id.unified_query_controls,
            R.id.btn_unified_query_mode,
            R.id.btn_unified_sort_mode,
            R.id.unified_selection_controls,
            R.id.unified_site_chooser_scroll,
            R.id.btn_unified_save_search,
            R.id.btn_unified_saved_searches,
            R.id.tv_unified_saved_notice,
            R.id.btn_unified_copy_diagnostics,
            R.id.btn_unified_view_log_panel,
            R.id.btn_unified_view_playback_data_panel,
            R.id.unified_site_status_scroll
        ).forEach { findViewById<android.view.View>(it).visibility = visibility }
        findViewById<Button>(R.id.btn_unified_resume).visibility =
            if (!panelState.collapsed && lastSnapshot?.siteStates?.any { it.status == SearchStatus.CANCELLED } == true)
                android.view.View.VISIBLE else android.view.View.GONE
        findViewById<Button>(R.id.btn_unified_toggle_controls).apply {
            text = if (panelState.collapsed) tr("展開設定與站點 ▾", "Show controls ▾")
                else tr("收合設定與站點 ▴", "Hide controls ▴")
            contentDescription = text
        }
        statusText.text = lastSnapshot?.let {
            val visibleItems = it.items.filter { item -> selectedSource.isBlank() || item.siteId == selectedSource }
            if (panelState.collapsed) SearchStatusPresentation.compactSummary(it, LanguageManager.isEnglish(this), visibleItems)
            else SearchStatusPresentation.summary(it, LanguageManager.isEnglish(this), visibleItems)
        } ?: tr("輸入內容後按搜尋", "Enter a query and search")
    }

    private fun renderItems(snapshot: SearchSnapshot?) {
        val items = snapshot?.let {
            SearchResultRanker.sort(it.items, it.request, sites.map(SearchSite::id), sortMode)
        }.orEmpty().filter { selectedSource.isBlank() || it.siteId == selectedSource }
        adapter.submitItems(items)
        findViewById<TextView>(R.id.tv_unified_empty).apply {
            text = SearchStatusPresentation.emptyMessage(snapshot, selectedSource, LanguageManager.isEnglish(this@UnifiedSearchActivity))
            visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
        // The log button rides with the empty state: when a search fails
        // (0 results), the user is already on this page and needs one tap
        // to the transport log — not a hunt through collapsed settings.
        findViewById<Button>(R.id.btn_unified_view_log).visibility =
            if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        if (items.isNotEmpty()) restoredListState?.let {
            resultsView.layoutManager?.onRestoreInstanceState(it)
            restoredListState = null
        }
    }

    private fun renderSiteStatuses(snapshot: SearchSnapshot) {
        siteStatuses.removeAllViews()
        val english = LanguageManager.isEnglish(this)
        snapshot.siteStates.forEach { state ->
            val site = sites.firstOrNull { it.id == state.siteId } ?: return@forEach
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(android.graphics.Color.rgb(32, 32, 32))
            }
            val title = site.displayName + " · " + SearchStatusPresentation.label(state, english)
            val detail = listOf(SearchStatusPresentation.description(state, english), SearchStatusPresentation.pagination(state, english))
                .filter { it.isNotBlank() }.joinToString("\n")
            card.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(when (state.status) {
                    SearchStatus.SUCCESS -> android.graphics.Color.rgb(129, 199, 132)
                    SearchStatus.NEEDS_USER_ACTION -> android.graphics.Color.rgb(255, 204, 128)
                    else -> android.graphics.Color.WHITE
                })
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@UnifiedSearchActivity)
                        .setTitle(site.displayName)
                        .setMessage(title + "\n" + detail + "\n\n" + state.errorCode.orEmpty() + "\n" + state.diagnostic)
                        .setPositiveButton(tr("複製診斷", "Copy diagnostics")) { _, _ -> copyDiagnostics(listOf(state)) }
                        .setNegativeButton(tr("返回", "Back"), null).show()
                }
            })
            if (detail.isNotBlank()) card.addView(TextView(this).apply {
                text = detail
                textSize = 12f
                setTextColor(android.graphics.Color.LTGRAY)
                setPadding(0, dp(4), 0, dp(4))
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun action(label: String, click: () -> Unit) {
                actions.addView(Button(this).apply {
                    text = label
                    textSize = 12f
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener { click() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            action(if (state.status == SearchStatus.NEEDS_USER_ACTION &&
                state.errorCode in setOf("CF_CHALLENGE", "HUMAN_VERIFICATION", "CF_BLOCKED"))
                tr("去驗證", "Verify") else tr("原始搜尋頁", "Original page")) {
                val url = site.buildSearchUrl(snapshot.request.normalizedQuery, state.pendingPageToken)
                preserveSessionOnStop = true
                startActivityForResult(android.content.Intent(this, SearchSourceActivity::class.java)
                    .putExtra(SearchSourceActivity.EXTRA_SITE, site.id)
                    .putExtra(SearchSourceActivity.EXTRA_URL, url)
                    .putExtra(SearchSourceActivity.EXTRA_REQUEST, snapshot.request.requestId), 410)
            }
            if (state.status.isTerminal) action(tr("重試此站", "Retry site")) { retrySite(state.siteId) }
            if (SearchPagingPolicy.canContinue(state)) {
                action(tr("載入更多", "Load more")) { session?.loadMore(state.siteId) }
            }
            card.addView(actions)
            siteStatuses.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun savedTime(time: Long): String = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))

    private fun savedSearchLabel(info: SavedSearchInfo): String = info.query + "\n" + savedTime(info.savedAt) +
        tr(" · ${info.groups} 組／${info.links} 連結", " · ${info.groups} groups / ${info.links} links")

    private fun refreshSavedSearches(after: () -> Unit = {}) {
        SavedSearchStore.io.execute {
            val loaded = runCatching { savedSearchStore.list() }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loaded.onSuccess { savedSearches = it }.onFailure {
                    Toast.makeText(this, tr("無法讀取已存搜尋清單", "Unable to read saved searches"), Toast.LENGTH_SHORT).show()
                }
                savedSearchesReady = true
                renderPanel()
                after()
            }
        }
    }

    private fun saveCurrentSearch() {
        val snapshot = lastSnapshot?.takeIf { it.items.isNotEmpty() } ?: return
        val key = SavedSearchCodec.key(snapshot.request.normalizedQuery, snapshot.request.mode)
        val existing = savedSearches.firstOrNull { it.key == key }
        val message = snapshot.request.rawQuery + "\n" + tr(
            "儲存目前已取得的結果到本機。未完成的頁面不會被當作已完成；封面圖片仍可能需要網路。",
            "Save currently loaded results on this device. Unfinished pages stay unfinished; cover images may still need a network connection.") +
            if (existing != null) "\n\n" + tr("將更新這個關鍵字原有的儲存紀錄。", "This replaces the existing save for this query.") else ""
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tr(if (existing != null) "更新已存搜尋" else "儲存搜尋", if (existing != null) "Update saved search" else "Save search"))
            .setMessage(message)
            .setPositiveButton(tr("儲存", "Save")) { _, _ ->
                val toSave = lastSnapshot?.takeIf { it.request.requestId == snapshot.request.requestId } ?: snapshot
                savingSearch = true
                renderPanel()
                SavedSearchStore.io.execute {
                    val result = runCatching { savedSearchStore.save(toSave) }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        savingSearch = false
                        val notice = if (result.isSuccess) tr("已儲存搜尋結果", "Search results saved")
                            else if (result.exceptionOrNull()?.message == "SAVED_SEARCH_LIMIT")
                                tr("最多儲存 20 筆搜尋，請先刪除不需要的紀錄", "Up to 20 saved searches; delete an unused entry first")
                            else tr("儲存失敗，原有紀錄保留；請確認可用空間或縮小搜尋範圍", "Save failed; the previous record is retained. Check storage or narrow the search.")
                        Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
                        refreshSavedSearches()
                    }
                }
            }.setNegativeButton(tr("取消", "Cancel"), null).show()
    }

    private fun showSavedSearches() {
        if (savedSearches.isEmpty()) {
            Toast.makeText(this, tr("尚未儲存搜尋；有結果後按「儲存搜尋」", "No saved searches yet. Save one after results load."), Toast.LENGTH_LONG).show()
            return
        }
        val entries = savedSearches.toList()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tr("已存搜尋（本機）", "Saved searches (on this device)"))
            .setItems(entries.map(::savedSearchLabel).toTypedArray()) { _, position ->
                val entry = entries[position]
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(entry.query)
                    .setItems(arrayOf(tr("開啟已存結果", "Open saved results"), tr("重新搜尋最新資料", "Search for fresh results"), tr("刪除這筆搜尋", "Delete this save"))) { _, action ->
                        when (action) {
                            0 -> openSavedSearch(entry.key)
                            1 -> openSavedSearch(entry.key, refresh = true)
                            2 -> confirmDeleteSavedSearch(entry)
                        }
                    }.setNegativeButton(tr("取消", "Cancel"), null).show()
            }.setNegativeButton(tr("返回", "Back"), null).show()
    }

    private fun confirmDeleteSavedSearch(info: SavedSearchInfo) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tr("刪除已存搜尋？", "Delete saved search?"))
            .setMessage(info.query + "\n" + tr("只刪除這筆本機搜尋紀錄，不影響原有書籤。刪除後無法復原。", "Only this local search save is deleted; bookmarks are unaffected. This cannot be undone."))
            .setPositiveButton(tr("刪除", "Delete")) { _, _ ->
                SavedSearchStore.io.execute {
                    val result = runCatching { savedSearchStore.delete(info.key) }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        Toast.makeText(this, if (result.isSuccess) tr("已刪除這筆搜尋紀錄", "Saved search deleted") else tr("刪除失敗，請重試", "Delete failed; try again"), Toast.LENGTH_SHORT).show()
                        refreshSavedSearches()
                    }
                }
            }.setNegativeButton(tr("取消", "Cancel"), null).show()
    }

    private fun openSavedSearch(key: String, refresh: Boolean = false) {
        val readSerial = ++savedReadSerial
        SavedSearchStore.io.execute {
            val result = runCatching { savedSearchStore.load(key) }
            runOnUiThread {
                if (isDestroyed || isFinishing || readSerial != savedReadSerial) return@runOnUiThread
                val record = result.getOrNull()
                if (record == null) {
                    Toast.makeText(this, tr("無法讀取這筆搜尋；可從已存清單刪除後重新儲存", "Unable to read this save; delete it from the saved list and save again"), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val ids = record.snapshot.request.siteIds.filter { id -> sites.any { it.id == id } }
                if (ids.isEmpty()) return@runOnUiThread
                queryEdit.setText(if (record.info.mode == SearchMode.CODE) record.snapshot.request.normalizedQuery else record.info.query)
                siteChecks.forEach { (id, check) -> check.isChecked = id in ids }
                queryMode = if (record.info.mode == SearchMode.CODE) SearchInputMode.CODE else SearchInputMode.KEYWORD
                forceCodeForNextSearch = false
                selectedSource = ""
                sourceSpinner.setSelection(0)
                if (refresh) { startSearch(true, false); return@runOnUiThread }
                session?.cancel()
                SearchSessionStore.remove(activeRequestId)
                repository.close()
                repository = UnifiedSearchRepository(this)
                val selected = sites.filter { it.id in ids }
                val request = record.snapshot.request.copy(
                    configKey = selected.joinToString("|") { it.configurationKey },
                    siteIds = ids,
                    runMode = runMode
                )
                val states = record.snapshot.siteStates.filter { it.siteId in ids }.map { state ->
                    val site = selected.first { it.id == state.siteId }
                    val next = state.nextPageToken?.takeIf { SearchResultNormalizer.validUrl(it, site) }
                    val pending = state.pendingPageToken?.takeIf { SearchResultNormalizer.validUrl(it, site) }
                    val stalePaging = next != state.nextPageToken || pending != state.pendingPageToken
                    state.copy(nextPageToken = next, pendingPageToken = pending,
                        pageState = if (stalePaging) SearchPageState.UNKNOWN else state.pageState)
                }
                val snapshot = record.snapshot.copy(request = request, siteStates = states,
                    items = SearchResultNormalizer.merge(states.flatMap { it.items }), completedSites = ids.size, selectedSites = ids.size, isRunning = false)
                activeRequestId = request.requestId
                openedSavedKey = record.info.key
                openedSavedAt = record.info.savedAt
                panelState = SearchPanelState(collapsed = snapshot.items.isNotEmpty(), automaticCollapseHandled = true)
                restoredListState = null
                lastSnapshot = snapshot
                session = repository.start(request, this, restored = snapshot)
                forceCodeForNextSearch = false
                hideSearchKeyboard()
                findViewById<RecyclerView>(R.id.rv_unified_results).scrollToPosition(0)
            }
        }
    }

    private fun retrySite(siteId: String) {
        val state = lastSnapshot?.siteStates?.firstOrNull { it.siteId == siteId } ?: return
        val remaining = state.retryAfterUntil - System.currentTimeMillis()
        if (remaining > 0) {
            val seconds = remaining / 1000 + 1
            Toast.makeText(this, tr("請於 " + seconds + " 秒後重試", "Retry in " + seconds + " seconds"), Toast.LENGTH_SHORT).show()
        } else session?.retry(siteId)
    }

    private fun rememberReadAnchor() {
        val manager = resultsView.layoutManager as? LinearLayoutManager ?: return
        val position = manager.findFirstVisibleItemPosition()
        val group = adapter.groupAt(position) ?: return
        readAnchor = SearchReadAnchor(group.stableId, resultsView.getChildAt(0)?.top ?: 0)
    }

    private fun restoreReadAnchorIfNeeded() {
        val anchor = restoredAnchor ?: return
        val position = adapter.positionOf(anchor.groupId)
        if (position < 0) return
        resultsView.post {
            (resultsView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, anchor.offsetPx)
            readAnchor = anchor
            restoredAnchor = null
        }
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 410 && resultCode == RESULT_OK && data != null &&
            data.getStringExtra(SearchSourceActivity.EXTRA_REQUEST) == activeRequestId &&
            data.getBooleanExtra(SearchSourceActivity.EXTRA_RETRY, false)) {
            data.getStringExtra(SearchSourceActivity.EXTRA_SITE)?.let(::retrySite)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        savedReadSerial++
        if (::adapter.isInitialized) adapter.close()
        session?.cancel()
        repository.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Returning from an internal detail/original-page Activity is not background cancellation.
        preserveSessionOnStop = false
    }
    private fun tr(zh: String, en: String) = LanguageManager.text(this, zh, en)

    private fun openNetworkLog() {
        startActivity(android.content.Intent(this, NetworkLogActivity::class.java))
    }

    private fun openPlaybackData() {
        startActivity(android.content.Intent(this, PlaybackDataActivity::class.java))
    }

    private fun copyDiagnostics(states: List<SiteSearchState>) {
        val report = buildString {
            append("UnifiedSearch diagnostic v6\n")
            append("time=").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())).append('\n')
            append("android=").append(android.os.Build.VERSION.SDK_INT).append('\n')
            append("Query, URLs, page content and cookies are excluded.\n")
            states.forEach { state ->
                append("site=").append(state.siteId).append(" status=").append(state.status)
                append(" code=").append(state.errorCode ?: "NONE")
                append(" items=").append(state.items.size).append(" cache=").append(state.fromCache)
                append(" pages=").append(state.loadedPages).append(" paging=").append(state.pageState)
                append(" hasNext=").append(state.nextPageToken != null)
                append(' ').append(state.diagnostic).append('\n')
            }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Search diagnostics", report))
        Toast.makeText(this, tr("已複製診斷資訊（不含搜尋內容）", "Diagnostics copied (query excluded)"), Toast.LENGTH_SHORT).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        session?.snapshot()?.let(SearchSessionStore::put)
        outState.putString("session", activeRequestId)
        outState.putString("query", queryEdit.text.toString())
        outState.putString("opened_saved_key", openedSavedKey)
        outState.putLong("opened_saved_at", openedSavedAt ?: 0L)
        outState.putBoolean("controls_collapsed", panelState.collapsed)
        outState.putBoolean("controls_collapse_handled", panelState.automaticCollapseHandled)
        outState.putStringArrayList("sites", ArrayList(siteChecks.filterValues { it.isChecked }.keys))
        outState.putInt("source", sourceSpinner.selectedItemPosition)
        outState.putString("run_mode", runMode.name)
        outState.putString("query_mode", queryMode.name)
        outState.putString("sort_mode", sortMode.name)
        outState.putParcelable("scroll", findViewById<RecyclerView>(R.id.rv_unified_results).layoutManager?.onSaveInstanceState())
        rememberReadAnchor()
        outState.putString("anchor_group", readAnchor?.groupId)
        outState.putInt("anchor_offset", readAnchor?.offsetPx ?: 0)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (!isChangingConfigurations && !preserveSessionOnStop) session?.cancel()
        session?.snapshot()?.let(SearchSessionStore::put)
        super.onStop()
    }
}
