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
        refreshSavedSearches {
            if (savedInstanceState == null && queryEdit.text.toString().isNotBlank() && lastSnapshot == null) startSearch(false)
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
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    LanguageManager.text(this, "正在解析真实地址…", "Resolving real video URL…"),
                    android.widget.Toast.LENGTH_SHORT).show()
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
            val candidates = trySniffVideoCandidates(item, sessionId)
            runOnUiThread {
                if (candidates.isEmpty()) {
                    BrowserNavigator.open(this, item.detailUrl)
                } else {
                    showSniffPicker(item, candidates, sessionId)
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
     * Callback on the UI thread with every part found (label + URL),
     * or null when none resolves — the caller then falls back to
     * WebView. Never throws.
     */
    private fun tryResolveJavhdRealUrl(
        item: SearchItem,
        sessionId: Long,
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

        // Ad hosts seen on the detail + embed pages (popunders, VAST,
        // the paid-SMS scam creatives). Blocked outright in this hidden
        // WebView so the resolve never runs any of them. The video file
        // host (vdcdn.xyz) and the embed host are NOT in this list.
        val adHosts = listOf(
            "whitetrafsa", "content-sync.xyz", "alaphoid.com", "udzpel.com",
            "jads.co", "wpadmngr.com", "xlviirdr.com", "xlirdr.com",
            "javhdporn.live", "javhd-trk.com", "vivodemisrentas.net",
        )

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
                PlaybackDataStore.addCandidates(sessionId, result.map { (label, url) ->
                    resolverRecord(
                        url, "resolver-javhd",
                        if (label.isEmpty()) "解析直連（跳過廣告）" else "$label 解析直連（跳過廣告）",
                    )
                })
            } else {
                PlaybackDataStore.recordFail(sessionId, "javhd隱藏WebView解析失敗")
            }
            callback(result)
        }

        fun clickSrv(i: Int) {
            partClicks++
            hiddenWebView?.evaluateJavascript(
                "(function(){var a=document.querySelectorAll('a.srv');" +
                    "if(a.length>" + i + "){a[" + i + "].click();return 'clicked';}" +
                    "return 'no-srv';})();",
                null,
            )
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
                    finish()
                    return
                }
                when {
                    foundParts.containsKey(partIdx) -> {
                        partIdx++
                        partClicks = 0
                        if (partIdx >= partLabels.size) {
                            finish()
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
                            finish()
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
            }
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if (adHosts.any { reqUrl.contains(it) }) {
                        return android.webkit.WebResourceResponse("text/plain", "utf-8",
                            java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    val isReal = (reqUrl.contains("streamtape") && reqUrl.contains("get_video")) ||
                        (reqUrl.contains("tapecontent") &&
                            (reqUrl.contains(".mp4") || reqUrl.contains(".ts") || reqUrl.contains("m3u8"))) ||
                        (reqUrl.contains("vdcdn.xyz") &&
                            (reqUrl.contains(".mp4") || reqUrl.contains("m3u8")))
                    if (isReal) {
                        handler.post {
                            if (!done) {
                                val key = if (partLabels.isEmpty()) -1 else partIdx
                                if (!foundParts.containsKey(key)) foundParts[key] = reqUrl
                            }
                        }
                        return android.webkit.WebResourceResponse("text/plain", "utf-8",
                            java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    if (!chasedEmbed && reqUrl.contains("streamtape") && reqUrl.contains("/e/")) {
                        chasedEmbed = true
                        handler.post { hiddenWebView?.loadUrl(reqUrl) }
                        return android.webkit.WebResourceResponse("text/plain", "utf-8",
                            java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (setupDone || done) return
                    setupDone = true
                    handler.postDelayed({
                        if (done) return@postDelayed
                        view?.evaluateJavascript(
                            "(function(){var a=document.querySelectorAll('a.srv');var out=[];" +
                                "for(var i=0;i<a.length;i++){out.push((a[i].textContent||'').trim()||('Part'+(i+1)));}" +
                                "return JSON.stringify(out);})();"
                        ) { value ->
                            if (done) return@evaluateJavascript
                            partLabels = parseSrvLabels(value)
                            if (partLabels.isEmpty()) {
                                // No Server links: legacy Streamtape video.
                                clickLegacyPlay()
                                handler.postDelayed({ if (!done) finish() }, 20000)
                            } else {
                                checker?.let { handler.post(it) }
                            }
                        }
                    }, 2000)
                }
            }
            wv.loadUrl(item.detailUrl)
            handler.postDelayed(timeoutRunnable, 75000)
        }
    }

    private fun playSniffed(item: SearchItem, videoUrl: String, sessionId: Long) {
        // HentaiMama and javhdporn direct files play in the native player:
        // the WebView player stalls on them (plays ~1s, then jumps back),
        // while the same URL plays fine in a browser. javhdporn's file host
        // also ignores Range requests (200 with the full body), which the
        // native player tolerates better. Every other site keeps the
        // existing internal player.
        val target = if (item.siteId == SearchSiteIds.HENTAIMAMA || item.siteId == SearchSiteIds.JAVHD) {
            NativePlayerActivity::class.java
        } else {
            FullscreenInternalPlayerActivity::class.java
        }
        startActivity(android.content.Intent(this, target).apply {
            putExtra(FullscreenInternalPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_REFERER, item.detailUrl)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_PAGE_URL, item.detailUrl)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_DOWNLOAD_NAME, item.title)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_SESSION_ID, sessionId)
        })
    }

    /** Lists sniffed candidates with their m3u8 duration, score and source — now a full page, not a dialog. Never throws. */
    private fun showSniffPicker(item: SearchItem, candidates: List<SniffCandidateRecord>, sessionId: Long) {
        if (isDestroyed || isFinishing) return
        choiceLauncher.launch(VideoChoiceActivity.intent(this, item, candidates, sessionId))
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
     * real play. Never throws.
     */
    private fun trySniffVideoCandidates(item: SearchItem, sessionId: Long): List<SniffCandidateRecord> {
        return try {
            val host = try { java.net.URL(item.detailUrl).host } catch (e: Exception) {
                PlaybackDataStore.recordFail(sessionId, "詳情頁地址無效")
                return emptyList()
            }
            if (EchDoh.configFor(host) == null) {
                PlaybackDataStore.recordFail(sessionId, "無ECH設定，不嗅探")
                return emptyList()
            }
            val site = sites.firstOrNull { it.id == item.siteId } ?: run {
                PlaybackDataStore.recordFail(sessionId, "站點未註冊")
                return emptyList()
            }
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    LanguageManager.text(this, "正在嗅探播放地址…", "Sniffing video URL…"),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            val response = SearchHttpFetcher().fetch(item.detailUrl, SearchCancellation(), site)
            if (response.transportError != null) {
                PlaybackDataStore.recordFail(sessionId, "取詳情頁失敗：${response.transportError}")
                return emptyList()
            }
            if (response.statusCode !in 200..299) {
                PlaybackDataStore.recordFail(sessionId, "詳情頁HTTP ${response.statusCode}")
                return emptyList()
            }
            if (response.body.isBlank()) {
                PlaybackDataStore.recordFail(sessionId, "詳情頁為空")
                return emptyList()
            }
            val records = mutableListOf<SniffCandidateRecord>()
            for (sc in VideoPageSniffer.sniffScored(response.body, item.detailUrl, item.code)) {
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
            PlaybackDataStore.addCandidates(sessionId, records)
            val kept = records.filter { it.kept }
            if (kept.isEmpty()) PlaybackDataStore.recordFail(sessionId, "找到 ${records.size} 個候選，全部被篩掉")
            kept
        } catch (e: Exception) {
            PlaybackDataStore.recordFail(sessionId, "嗅探異常：${e.message}")
            emptyList()
        }
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
            action(tr("原始搜尋頁", "Original page")) {
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
