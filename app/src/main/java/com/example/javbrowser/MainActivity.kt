package com.example.javbrowser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import com.example.javbrowser.gecko.GeckoBridge
import com.example.javbrowser.gecko.GeckoBridgeExtension
import com.example.javbrowser.gecko.GeckoCookies
import com.example.javbrowser.gecko.GeckoEval
import com.example.javbrowser.gecko.GeckoRuntimeProvider
import com.example.javbrowser.gecko.JavhdGeckoExtension
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.net.URLEncoder
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : LocalizedActivity() {

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private var geckoCurrentUrl: String? = null
    private var geckoPageTitle: String = ""
    private var geckoCanGoBack: Boolean = false
    private var landingDataShown: Boolean = false
    private val geckoMainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // GeckoView 151 没有 onShowCustomView 容器：改用一个布林追踪全屏状态。
    private var isGeckoFullscreen: Boolean = false

    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var fileChooserInFlight = false

    // Gecko 版：把选到的 Uri 交给待处理的 FilePrompt（GeckoResult），
    // 而不是旧 WebChromeClient 的 ValueCallback。只接受 content: scheme，
    // 与旧 FileChooserParams.parseResult 之后的过滤一致。
    private val chooseWebFiles = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        fileChooserInFlight = false
        val prompt = pendingFilePrompt
        val pending = pendingFileResult
        pendingFilePrompt = null
        pendingFileResult = null
        if (prompt != null && pending != null) {
            val uris = mutableListOf<Uri>()
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.takeIf { it.scheme == "content" }?.let { uris.add(it) }
                    }
                } ?: data.data?.takeIf { it.scheme == "content" }?.let { uris.add(it) }
            }
            runCatching {
                if (uris.isEmpty()) pending.complete(prompt.dismiss())
                else pending.complete(prompt.confirm(this, uris.toTypedArray()))
            }
        }
    }

    /**
     * 取消等待中的檔案選擇提示（例如頁面跳轉、onDestroy）。
     * 与旧 cancelPendingFileChooser() 同义：只是把 PromptDelegate 的
     * deferred GeckoResult 用 dismiss 补完，避免回调永远不回。
     */
    private fun cancelPendingFilePrompt() {
        val prompt = pendingFilePrompt
        val result = pendingFileResult
        pendingFilePrompt = null
        pendingFileResult = null
        if (prompt != null && result != null) {
            runCatching { result.complete(prompt.dismiss()) }
        }
    }

    private lateinit var adFilterRules: AdFilterRules
    private lateinit var domainConfig: DomainConfig
    private lateinit var btnPlay: Button
    private lateinit var btnHome: Button
    private lateinit var btnAddFavorite: Button
    private lateinit var btnViewFavorites: Button
    private lateinit var btnSettings: Button
    private lateinit var btnDownloads: Button
    private lateinit var btnCrossSiteSearch: Button
    private lateinit var btnCopyPageUrl: Button
    private lateinit var btnBlockElement: Button
    private var domBlockPickerActive = false
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var favoritesManager: FavoritesManager
    private lateinit var privacySettings: PrivacySettings
    private lateinit var biometricHelper: BiometricHelper
    private var currentVideoUrl: String? = null
    private val supjavMediaResolver by lazy { SupjavMediaResolver(this) { MAIN_UA } }
    private val supjavPlayerHook by lazy { assets.open("supjav-player-hook.js").bufferedReader(Charsets.UTF_8).use { it.readText() } }
    private val javrateAdLayoutHook by lazy { assets.open("javrate-ad-layout.js").bufferedReader(Charsets.UTF_8).use { it.readText() } }
    private var supjavSelectionGeneration = 0L

    private fun clearSupjavSource() {
        supjavSelectionGeneration++
        supjavMediaResolver.close()
        currentVideoUrl = null
        currentVideoReferer = null
        btnPlay.visibility = View.GONE
        btnPlay.contentDescription = null
        btnPlay.isEnabled = true
        btnPlay.text = "▶"
    }

    private fun resolveSupjavSource(embed: String, label: String) {
        val page = geckoCurrentUrl ?: currentPageUrl
        if (!AdditionalSiteMedia.hostMatches(page, "supjav.com")) return
        clearSupjavSource()
        val generation = supjavSelectionGeneration
        supjavMediaResolver.resolve(embed, page, { media, referer ->
            if (generation == supjavSelectionGeneration && currentPageUrl == page) {
                currentVideoUrl = media
                currentVideoReferer = referer
                btnPlay.contentDescription = LanguageManager.text(this, "播放：$label", "Play: $label")
                showPlayButtonIfAllowed()
            }
        }, {
            if (generation == supjavSelectionGeneration && currentPageUrl == page) {
                Toast.makeText(this, LanguageManager.text(this,
                    "$label 尚未取得影片，請先於網頁播放或完成驗證，也可切換其他 Server。",
                    "$label has no detected video. Play or complete verification on the webpage, or choose another Server."), Toast.LENGTH_LONG).show()
            }
        })
    }
    private var currentVideoReferer: String? = null
    private var currentRouEpisodes: List<VideoExtractor.RouVideoEpisode> = emptyList()
    private var currentJavdayEpisodes: List<VideoExtractor.AdditionalVideoEpisode> = emptyList()
    private var pendingHomeClipboardLink: HomeClipboardLink.Match? = null
    private var lastHomeClipboardText = ""
    private var dismissedHomeClipboardUrl: String? = null
    private val homeClipboardListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        if (::geckoSession.isInitialized) geckoMainHandler.post { refreshHomeClipboard() }
    }
    private var currentMissAvThumbnailConfig: MissAvThumbnailConfig? = null
    private var currentJableVttUrl: String? = null
    private var isOnJavDbVideoPage = false
    private var isOnJavTrailersVideoPage = false
    private var crossSiteCode: String? = null
    @Volatile private var currentPageUrl: String = ""   // safe to read from background thread
    private var lastStripchatModelUrl: String? = null
    private var videoFoundToastShown = false
    // 全螢幕播放支援
    private var isGeckoFullscreen: Boolean = false
    private lateinit var fullscreenContainer: android.widget.FrameLayout
    private var isStripchatOverlayActive = false
    private var videoProxyServer: VideoProxyServer? = null
    private var cachedBlockList: Set<String> = emptySet()
    @Volatile private var cachedSiteRules: Map<String, SiteAdRuleSet> = emptyMap()
    private var isUnlocked = false
    private var isFreshStart = true
    private val REQUEST_CODE_FAVORITES = 1001
    private val REQUEST_CODE_LOCK = 1002
    private val REQUEST_CODE_STRIPCHAT_RECORDING = 1003
    private var screenSecureClearedForRecording = false
    private val stripchatStreamRecordingStore by lazy { StripchatStreamRecordingStore(applicationContext) }
    private var stripchatPrivacyDialog: android.app.Dialog? = null
    private val stripchatPrivacyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stripchatPrivacyHidePinRunnable: Runnable? = null
    private val stripchatRecordingStatusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stripchatRecordingStatusExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val stripchatRecordingStatusGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val stripchatRecordingStatusCheckInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val stripchatRecordingStatusClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var stripchatRecordingStatusRunnable: Runnable? = null
    private var stripchatRecordingStatusUrl: String = ""
    @Volatile private var stripchatRecordingModelId: Long = 0L
    private var stripchatOfflineConfirmations = 0
    private var stripchatLastOfflineConfirmationAt = 0L
    @Volatile private var stripchatWatchSessionActive = false
    @Volatile private var stripchatWatchSessionRecording = false
    @Volatile private var stripchatWatchSessionStarting = false
    @Volatile private var stripchatWatchLiveState: Boolean? = null
    @Volatile private var stripchatWatchStatusChecked = false
    private val stripchatPlaybackProbeJs by lazy {
        assets.open("stripchat-playback-probe.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    private val stripchatWatchControlsJs by lazy {
        assets.open("stripchat-watch-controls.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    private var stripchatWatchSessionUrl: String = ""
    private var stripchatWatchSessionModelKey: String = ""
    private var stripchatWatchStartRequestedAt = 0L
    private var stripchatLastErrorToastAt = 0L
    // 只有使用者主動按 Home 鍵（真正離開 app）時才設為 true
    private var userLeftApp = false
    // 從銷售排行開啟網頁時，沒有 WebView 上一頁後返回排行頁。
    private var returnToSalesRanking = false

    companion object {
        // 書籤頁設定 URL，MainActivity.onResume 讀取後清空
        @JvmStatic
        var pendingFavoriteUrl: String? = null
        // LocalBroadcast action: 書籤頁傳送 URL 給 MainActivity
        const val ACTION_LOAD_URL = "com.example.javbrowser.LOAD_URL"
        const val EXTRA_URL = "url"
        const val EXTRA_CLEAR_HISTORY = "clear_history"
        const val EXTRA_RETURN_TO_SALES = "return_to_sales_ranking"
        // GeckoView 151：主会话沿用旧 WebView 的 UA；隐藏会话用 Pixel 7 UA。
        const val MAIN_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val HIDDEN_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val LANDING_URL = "https://javbrowser.app/"
    }

    // 接收書籤頁傳來的 URL
    private val favUrlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val url = intent?.getStringExtra(EXTRA_URL) ?: return
            val clearHistory = intent.getBooleanExtra(EXTRA_CLEAR_HISTORY, false)
            android.util.Log.d("MAIN", "BroadcastReceiver: 收到書籤 URL = $url clearHistory=$clearHistory")
            loadIncomingUrl(url, clearHistory, "broadcast")
        }
    }

    private val stripchatRecordingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val active = intent?.getBooleanExtra(StripchatRecordingService.EXTRA_ACTIVE, false) == true
            val message = intent?.getStringExtra(StripchatRecordingService.EXTRA_MESSAGE).orEmpty()
            if (!active) {
                restoreSecureWindowAfterRecording()
                stripchatWatchSessionRecording = false
                if (stripchatWatchSessionActive) {
                    startStripchatRecordingStatusMonitor(stripchatWatchSessionUrl)
                } else {
                    stopStripchatRecordingStatusMonitor()
                }
                notifyStripchatWatchState()
            }
            if (::geckoSession.isInitialized) {
                GeckoEval.runJavascript(geckoSession, 
                    "if(window.__javSetStripchatRecordingState) window.__javSetStripchatRecordingState($active);")
            }
            if (message.isNotBlank()) Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadIncomingUrl(url: String, clearHistory: Boolean, source: String) {
        val updatedUrl = domainConfig.updateUrlIfNeeded(url)
        rememberStripchatModelUrl(updatedUrl)
        android.util.Log.d("NAV_DEBUG", "loadIncomingUrl source=$source clearHistory=$clearHistory url=$updatedUrl canGoBackBefore=${geckoCanGoBack}")
        geckoMainHandler.post {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 10
            startLoadTimeout()
            if (clearHistory) {
                geckoSession.stop()
                geckoSession.purgeHistory()
            }
            loadGeckoUrl(updatedUrl)
        }
    }

    private fun rememberStripchatModelUrl(url: String?) {
        if (url.isNullOrBlank() || !url.contains("stripchat.com", ignoreCase = true)) return
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val host = parsed.host.orEmpty()
        if (!host.contains("stripchat.com", ignoreCase = true)) return
        val path = parsed.path.orEmpty().trim('/')
        if (path.isBlank() || path.contains('/')) return
        if (path.equals("login", true) ||
            path.equals("signup", true) ||
            path.equals("favorites", true) ||
            path.equals("models", true) ||
            path.equals("search", true) ||
            path.equals("category", true) ||
            path.equals("tags", true)) return

        lastStripchatModelUrl = parsed.buildUpon().clearQuery().fragment(null).build().toString().trimEnd('/')
        android.util.Log.d("STRIPCHAT_HLS", "remember model url=$lastStripchatModelUrl")
    }
    
    // Loading Timeout & Progress
    private var loadStartTime: Long = 0
    private var timeoutHandler: android.os.Handler? = null
    private var timeoutRunnable: Runnable? = null
    private val TIMEOUT_DURATION = 30000L // 30 seconds
    private var backPressedTime: Long = 0
    // 儲存每個 URL 對應的滾動位置，格式為 url -> Pair(scrollX, scrollY)
    private val scrollPositionMap = HashMap<String, Pair<Int, Int>>()
    // 標記下一次 onPageFinished 是否需要恢復滾動位置（因為是 goBack 觸發的）
    private var pendingScrollRestoreUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        returnToSalesRanking = intent?.getBooleanExtra(EXTRA_RETURN_TO_SALES, false) == true
        android.util.Log.d("NAV_DEBUG", "onCreate intentUrl=${intent?.getStringExtra("url")} action=${intent?.action}")
        // Prevent screenshots and hide content in recent apps
        if (PrivacySettings(this).isScreenSecure) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(R.layout.activity_main)

        // 预热 Gecko 内核：让 GeckoRuntime 在首页停留期间就开始初始化，
        // 首页点网站进 GeckoBrowserActivity 时 runtime 已就绪
        // （PROFILE_READY），避免首个 session 的加载卡在等内核就绪。
        // 第一帧后再跑，不拖首页启动；失败也不影响首页。
        window.decorView.post {
            runCatching { com.example.javbrowser.gecko.GeckoRuntimeProvider.get(this) }
        }

        favoritesManager = FavoritesManager(this)

        // Start local proxy for CDN-protected video (e.g. avjoy.me)
        startVideoProxyServer()

        adFilterRules = AdFilterRules(this)
        domainConfig = DomainConfig(adFilterRules)

        // 載入初始封鎖清單（從 SharedPreferences 快取，含使用者長按屏蔽）
        refreshBlockList()
        cachedSiteRules = adFilterRules.getSiteRules()
        adFilterRules.refreshRouVideoDomain()

        adFilterRules.updateRulesFromCloud(AdFilterRules.DEFAULT_CLOUD_URL) { success, msg ->
            if (success) {
                // 雲端規則更新成功，刷新快取
                refreshBlockList()
                cachedSiteRules = adFilterRules.getSiteRules()
                adFilterRules.refreshRouVideoDomain()
                android.util.Log.d(
                    "AdBlock",
                    "Rules updated: $msg, common=${cachedBlockList.size}, sites=${cachedSiteRules.size}"
                )
            } else {
                android.util.Log.e("AdBlock", "Rules update failed: $msg")
            }
        }

        privacySettings = PrivacySettings(this)
        // biometricHelper = BiometricHelper(this) // Moved to LockActivity
        
        btnPlay = findViewById(R.id.btn_play)
        btnHome = findViewById(R.id.btn_home)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        btnAddFavorite = findViewById(R.id.btn_add_favorite)
        btnViewFavorites = findViewById(R.id.btn_view_favorites)
        btnSettings = findViewById(R.id.btn_settings)
        btnDownloads = findViewById(R.id.btn_downloads)
        btnCrossSiteSearch = findViewById(R.id.btn_cross_site_search)
        btnCopyPageUrl = findViewById(R.id.btn_copy_page_url)
        btnCopyPageUrl.setOnClickListener { copyCurrentPageUrl() }
        btnBlockElement = findViewById(R.id.btn_block_element)
        btnBlockElement.setOnClickListener { enterDomBlockPicker() }
        btnCopyPageUrl.setOnLongClickListener {
            Toast.makeText(this, LanguageManager.text(this, "複製目前網頁網址", "Copy current page URL"), Toast.LENGTH_SHORT).show()
            true
        }
        progressBar = findViewById(R.id.progressBar)

        // 在 onCreate 就 register，確保 FavoritesActivity 發廣播時（MainActivity 已 pause）也能收到
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(favUrlReceiver, android.content.IntentFilter(ACTION_LOAD_URL))

        val recordingFilter = android.content.IntentFilter(StripchatRecordingService.ACTION_STATE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stripchatRecordingReceiver, recordingFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stripchatRecordingReceiver, recordingFilter)
        }

        initializeApp()
    }
    
    private fun initializeApp() {
        android.util.Log.d("NAV_DEBUG", "initializeApp start intentUrl=${intent?.getStringExtra("url")}")
        setupGeckoSession()
        FooterHelper.setup(this)
        setupPlayButton()
        setupHomeButton()
        setupFavoritesButtons()
        setupSettingsButton()
        setupDownloadsButton()
        setupCrossSiteSearchButton()

        // 優先：若有其他頁面（TodayNewActivity / FavoritesActivity）傳來的 URL
        // 就直接載入該 URL，跳過 loadLandingPage()
        val navPrefs = getSharedPreferences("nav_state", android.content.Context.MODE_PRIVATE)
        val pendingNavUrl = navPrefs.getString("pending_fav_url", null)
        if (pendingNavUrl != null) {
            navPrefs.edit().remove("pending_fav_url").apply()
            val updatedUrl = domainConfig.updateUrlIfNeeded(pendingNavUrl)
            android.util.Log.d("NAV_DEBUG", "initializeApp pendingNavUrl -> loadUrl=$updatedUrl")
            loadGeckoUrl(updatedUrl)
            return
        }

        if (handleIncomingIntent(intent)) {
            android.util.Log.d("NAV_DEBUG", "initializeApp handled incoming intent")
            return
        }

        android.util.Log.d("NAV_DEBUG", "initializeApp fallback -> loadLandingPage")
        loadLandingPage()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        android.util.Log.d("NAV_DEBUG", "onNewIntent intentUrl=${intent?.getStringExtra("url")} action=${intent?.action}")
        setIntent(intent)
        returnToSalesRanking = intent?.getBooleanExtra(EXTRA_RETURN_TO_SALES, false) == true
        if (::btnHome.isInitialized) updateHomeButtonForNavigationContext()
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        android.util.Log.d("NAV_DEBUG", "handleIncomingIntent start intentUrl=${intent.getStringExtra("url")} action=${intent.action}")

        var urlToLoad: String? = null

        // 從書籤頁點選進來
        val favUrl = intent.getStringExtra("url")
        if (favUrl != null) {
            val clearHistory = intent.getBooleanExtra(EXTRA_CLEAR_HISTORY, false)
            android.util.Log.d("NAV_DEBUG", "handleIncomingIntent direct url clearHistory=$clearHistory")
            loadIncomingUrl(favUrl, clearHistory, "intent")
            return true
        }

        if (intent.action == Intent.ACTION_VIEW) {
            val data: Uri? = intent.data
            if (data != null) {
                urlToLoad = data.toString()
            }
        } else if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                urlToLoad = extractUrl(sharedText)
                if (urlToLoad == null) {
                    val javCodes = extractJavCodesFromText(sharedText)
                    if (javCodes.isNotEmpty()) {
                        pendingJavCodesFromShare = javCodes
                        // 確保首頁已載入（若目前在外站頁面則跳回首頁）
                        android.util.Log.d("NAV_DEBUG", "handleIncomingIntent share codes -> loadLandingPage")
                        geckoMainHandler.post { loadLandingPage() }
                        return true
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "無法從分享的內容中找到網址", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        if (urlToLoad != null) {
            val updatedUrl = domainConfig.updateUrlIfNeeded(urlToLoad)
            // Use post to ensure webview is fully initialized
            android.util.Log.d("NAV_DEBUG", "handleIncomingIntent parsed url -> loadUrl=$updatedUrl")
            geckoMainHandler.post {
                loadGeckoUrl(updatedUrl)
            }
            return true
        }

        android.util.Log.d("NAV_DEBUG", "handleIncomingIntent no-op")
        return false
    }

    private fun extractUrl(text: String): String? {
        val urlRegex = "(https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|])".toRegex()
        val matchResult = urlRegex.find(text)
        return matchResult?.value
    }

    // ... (rest of the file)

    override fun onResume() {
        super.onResume()
        // 設定裡刪掉的使用者屏蔽回來即生效。
        if (::adFilterRules.isInitialized) refreshBlockList()
        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .addPrimaryClipChangedListener(homeClipboardListener)
        android.util.Log.d("MAIN", "onResume: isUnlocked=$isUnlocked, isFreshStart=$isFreshStart")
        android.util.Log.d("NAV_DEBUG", "onResume currentUrl=${geckoCurrentUrl} intentUrl=${intent?.getStringExtra("url")}")

        // 最高優先：從書籤頁傳來的 URL（用 SharedPreferences，跨進程有效）
        val prefs = getSharedPreferences("nav_state", android.content.Context.MODE_PRIVATE)
        val favUrl = prefs.getString("pending_fav_url", null)
        if (favUrl != null) {
            prefs.edit().remove("pending_fav_url").apply()
            android.util.Log.d("MAIN", "onResume: SharedPrefs 載入書籤 URL = $favUrl")
            val updatedUrl = domainConfig.updateUrlIfNeeded(favUrl)
            geckoMainHandler.post { loadGeckoUrl(updatedUrl) }
            userLeftApp = false
            isFreshStart = false
            return
        }

        // 重設「使用者離開 app」的旗標
        userLeftApp = false
        // Check if lock is needed when returning from background or fresh start
        if (privacySettings.isLockEnabled && !isUnlocked) {
            if (isFreshStart || privacySettings.shouldLock()) {
                android.util.Log.d("MAIN", "onResume: 啟動鎖定畫面")
                val intent = Intent(this, LockActivity::class.java)
                startActivityForResult(intent, REQUEST_CODE_LOCK)
            }
        }
        isFreshStart = false

        geckoMainHandler.post { refreshHomeClipboard() }
        // Gecko 会话在 setup 时已激活；每次回到前台再确认一次
        // （旧 WebView 从不暂停，这里也不在 onPause 里停会话，
        //  否则页内 Stripchat 录制会在后台断掉）。
        if (::geckoSession.isInitialized && geckoSession.isOpen) activateSession()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::geckoSession.isInitialized) geckoMainHandler.post { refreshHomeClipboard() }
    }

    private fun refreshHomeClipboard() {
        if (!::geckoSession.isInitialized || !::privacySettings.isInitialized || !::domainConfig.isInitialized) return
        android.util.Log.d("HOME_CLIPBOARD", "homeUrl=${geckoCurrentUrl} focused=${hasWindowFocus()} locked=${privacySettings.isLockEnabled && !isUnlocked}")
        if (!hasWindowFocus() || (privacySettings.isLockEnabled && !isUnlocked) ||
            geckoCurrentUrl != "https://javbrowser.app/"
        ) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val raw = (item?.text?.toString() ?: item?.uri?.takeIf {
            it.scheme == "https" || it.scheme == "http"
        }?.toString()).orEmpty().take(16000)
        if (raw != lastHomeClipboardText) {
            lastHomeClipboardText = raw
            dismissedHomeClipboardUrl = null
        }
        val configuredSites = mapOf(
            domainConfig.getMissAvDomain() to "MissAV", domainConfig.getJableDomain() to "Jable",
            domainConfig.getRouVideoDomain() to "Rou.Video", domainConfig.getAvJoyDomain() to "AvJoy",
            domainConfig.getPigAvDomain() to "PigAV", domainConfig.get7MmTvDomain() to "7MMTV",
            domainConfig.getAvpleDomain() to "Avple", domainConfig.getWhosDomain() to "Whos.tv"
        )
        val detectedLink = HomeClipboardLink.find(raw, configuredSites)
        android.util.Log.d("HOME_CLIPBOARD", "items=${clip?.itemCount ?: 0} supported=${detectedLink != null}")
        pendingHomeClipboardLink = detectedLink?.takeUnless { it.url == dismissedHomeClipboardUrl }
        val linkJson = pendingHomeClipboardLink?.let {
            org.json.JSONObject().put("url", it.url).put("site", it.site).toString()
        } ?: "null"
        GeckoEval.runJavascript(geckoSession, 
            "if(typeof onClipboardResult==='function') onClipboardResult(${org.json.JSONObject.quote(if (detectedLink == null) raw else "")}, $linkJson)")
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .removePrimaryClipChangedListener(homeClipboardListener)
        // 注意：favUrlReceiver 在 onDestroy 才 unregister，
        // 這樣 FavoritesActivity 發廣播時（此時 MainActivity 已 pause）也能收到
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 使用者主動按 Home 鍵或切到其他 app，才標記為「真正離開」
        // 注意：進入書籤頁等內部 Activity 時「不會」觸發此方法
        userLeftApp = true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_STRIPCHAT_RECORDING) {
            if (resultCode == RESULT_OK && data != null) {
                val sourceUrl = geckoCurrentUrl ?: currentPageUrl
                val serviceIntent = Intent(this, StripchatRecordingService::class.java).apply {
                    action = StripchatRecordingService.ACTION_START
                    putExtra(StripchatRecordingService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(StripchatRecordingService.EXTRA_RESULT_DATA, data)
                    putExtra(StripchatRecordingService.EXTRA_SOURCE_URL, sourceUrl)
                }
                androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
                startStripchatRecordingStatusMonitor(sourceUrl)
            } else {
                restoreSecureWindowAfterRecording()
                Toast.makeText(this, "已取消直播錄製", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_CODE_FAVORITES && resultCode == RESULT_OK) {
            val url = data?.getStringExtra("url")
            if (url != null) {
                val updatedUrl = domainConfig.updateUrlIfNeeded(url)
                loadGeckoUrl(updatedUrl)
            }
        } else if (requestCode == REQUEST_CODE_LOCK) {
            if (resultCode == RESULT_OK) {
                isUnlocked = true
                privacySettings.updateUnlockTime()
            } else {
                // Lock failed or cancelled, finish app
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 只有在使用者真正離開 app（按 Home 鍵）時才重設解鎖狀態
        // 若只是切換到書籤頁等內部 Activity，userLeftApp=false，不重設
        if (privacySettings.isLockEnabled && userLeftApp) {
            isUnlocked = false
        }
    }

    private fun siteRulesForPage(pageUrl: String?): SiteAdRuleSet? {
        val host = try {
            Uri.parse(pageUrl.orEmpty()).host.orEmpty().lowercase()
        } catch (e: Exception) {
            ""
        }
        if (host.isEmpty()) return null
        return cachedSiteRules.entries.firstOrNull { (ruleHost, _) ->
            host == ruleHost || host.endsWith(".$ruleHost")
        }?.value ?: if (isRouVideoUrl(pageUrl.orEmpty())) {
            cachedSiteRules.entries.firstOrNull { RouVideoDomains.isHost(it.key) }?.value
        } else null
    }

    private fun applySiteDomRules(pageUrl: String?) {
        // Avple 的影片元素本身可能使用 growcdnssedge.com。舊版雲端規則曾把
        // `video[src*=growcdnssedge.com]` 當成廣告，這會直接移除主播放器。
        // 影片元素不套用這類 Avple DOM 規則，串流請求仍由下方的精確條件處理。
        val isAvplePage = pageUrl?.contains("avple.tv", ignoreCase = true) == true
        val rules = siteRulesForPage(pageUrl)?.domRemove.orEmpty()
            .filterNot { isAvplePage && it.selector.contains("video", ignoreCase = true) }
            .toMutableList()
        if (pageUrl?.let(::isRouVideoUrl) == true) {
            // ROU 新版把站內廣告統一導向 /api/hop/；只移除這些明確的
            // 廣告連結與其純廣告容器，不碰 data-slot=card 的影片卡片。
            rules.add(
                0,
                SiteDomRemoveRule("div:has(> a[href*='/api/hop/'])")
            )
            rules.add(SiteDomRemoveRule("a[href*='/api/hop/']"))
        }
        // 使用者點選屏蔽的元素規則：域名匹配（含子域）即併入，與站點規則走同一套
        // 移除 + MutationObserver，動態後插的廣告框也會被清掉。
        val pageHost = try {
            Uri.parse(pageUrl.orEmpty()).host.orEmpty().lowercase()
        } catch (e: Exception) {
            ""
        }
        if (pageHost.isNotEmpty()) {
            adFilterRules.getUserDomRemoveRules()
                .filter { pageHost == it.domain || pageHost.endsWith(".${it.domain}") }
                .forEach { rules.add(SiteDomRemoveRule(it.selector)) }
        }
        if (rules.isEmpty()) return
        val payload = org.json.JSONArray()
        rules.forEach { rule ->
            payload.put(org.json.JSONObject().apply {
                put("selector", rule.selector)
                rule.closest?.let { put("closest", it) }
            })
        }
        val js = """
            (function(rules) {
                try {
                    if (window.__javBrowserSiteAdObserver) {
                        window.__javBrowserSiteAdObserver.disconnect();
                    }
                } catch (e) {}

                function removeMatchedAds(root) {
                    var scope = root && root.querySelectorAll ? root : document;
                    rules.forEach(function(rule) {
                        try {
                            var nodes = [];
                            if (scope.matches && scope.matches(rule.selector)) nodes.push(scope);
                            scope.querySelectorAll(rule.selector).forEach(function(node) { nodes.push(node); });
                            nodes.forEach(function(node) {
                                var target = node;
                                if (rule.closest) target = node.closest(rule.closest) || node;
                                if (!target || !target.tagName) return;
                                var tag = target.tagName.toLowerCase();
                                if (tag === 'html' || tag === 'body') return;
                                target.remove();
                            });
                        } catch (e) {}
                    });
                }

                removeMatchedAds(document);
                try {
                    window.__javBrowserSiteAdObserver = new MutationObserver(function(mutations) {
                        mutations.forEach(function(mutation) {
                            mutation.addedNodes.forEach(function(node) {
                                if (node && node.nodeType === 1) removeMatchedAds(node);
                            });
                        });
                    });
                    window.__javBrowserSiteAdObserver.observe(
                        document.documentElement,
                        { childList: true, subtree: true }
                    );
                } catch (e) {}
            })($payload);
        """.trimIndent()
        GeckoEval.runJavascript(geckoSession, js)
    }

    private fun isRouVideoUrl(url: String): Boolean {
        return RouVideoDomains.isUrl(url, domainConfig.getRouVideoDomain())
    }

    /**
     * 在影片網站頁面插入跨站搜尋按鈕。番號由目前頁面的 URL、標題與主要標題自動辨識，
     * 只在已知影片網站出現，避免在一般網站或 App 首頁誤插入。
     */
    private fun injectLegacyCrossSiteSearchButtons(pageUrl: String?) {
        if (pageUrl.isNullOrBlank()) return

        val english = LanguageManager.isEnglish(this)
        val sites = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("label", "MissAV")
                put("template", "${domainConfig.getMissAvBaseUrl().trimEnd('/')}/search/{code}")
            })
            put(org.json.JSONObject().apply {
                put("label", "Jable.TV")
                put("template", "https://jable.tv/search/{code}/")
            })
            put(org.json.JSONObject().apply {
                put("label", "AvJoy")
                put("template", "https://${domainConfig.getAvJoyDomain()}/search/videos/{code}")
            })
            put(org.json.JSONObject().apply {
                put("label", "PigAV")
                put("template", "https://pigav.ws/search?search={code}&searchTarget=local")
            })
            put(org.json.JSONObject().apply {
                put("label", "AVToday")
                put("template", "https://avtoday.io/search?s={code}")
            })
            put(org.json.JSONObject().apply {
                put("label", "JavHDPorn")
                put("template", "https://www.javhdporn.net/?s={code}")
            })
            put(org.json.JSONObject().apply {
                put("label", "7MMTV")
                put("template", "https://${domainConfig.get7MmTvDomain()}/zh/searchall_search/all/{code}/1.html")
            })
            put(org.json.JSONObject().apply {
                put("label", "Avple")
                put("template", "https://${domainConfig.getAvpleDomain()}/search?key={code}")
            })
            put(org.json.JSONObject().apply {
                put("label", "Whos.tv")
                put("template", "https://${domainConfig.getWhosDomain()}/result?search={code}")
            })
            put(org.json.JSONObject().apply {
                put("label", "JAVDAY")
                put("template", "https://javday.app/search/wd/{code}/")
            })
            put(org.json.JSONObject().apply {
                put("label", "SUPJAV")
                put("template", "https://supjav.com/?s={code}")
            })
            put(org.json.JSONObject().apply {
                put("label", "JavRate")
                put("template", "https://www.javrate.com/search/{code}")
            })
        }
        val panelTitle = org.json.JSONObject.quote(if (english) "Cross-site search" else "跨站搜尋")
        val searchPrefix = org.json.JSONObject.quote(if (english) "Search " else "在 ")
        val searchSuffix = org.json.JSONObject.quote(if (english) " for: " else " 搜尋：")

        val js = """
            (function(sites, panelTitle, searchPrefix, searchSuffix) {
                'use strict';

                var host = (window.location.hostname || '').toLowerCase();
                var supported = /(^|\.)javdb\.com$/.test(host) ||
                    /(^|\.)javtrailers\.com$/.test(host) ||
                    /(^|\.)javhdporn\.net$/.test(host) ||
                    /(^|\.)missav\./.test(host) ||
                    /(^|\.)jable\.tv$/.test(host) ||
                    /(^|\.)avjoy\.me$/.test(host) ||
                    /(^|\.)pigav\.ws$/.test(host) ||
                    /(^|\.)avtoday\.io$/.test(host) ||
                    /(^|\.)7mmtv\.sx$/.test(host) ||
                    /(^|\.)7tv\d*\.com$/.test(host) ||
                    /(^|\.)avple\.tv$/.test(host) ||
                    /(^|\.)whos\.tv$/.test(host) ||
                    /(^|\.)javday\.app$/.test(host) || /(^|\.)supjav\.com$/.test(host) || /(^|\.)javrate\.com$/.test(host);
                var panelId = 'jav-cross-site-search';

                if (!supported) {
                    var stale = document.getElementById(panelId);
                    if (stale) stale.remove();
                    return;
                }

                function normalizeCode(value) {
                    var text = String(value || '').toUpperCase();
                    var fc2 = text.match(/FC2(?:[-_\s]?PPV)?[-_\s]?\d{5,10}/i);
                    if (fc2) {
                        var digits = fc2[0].match(/\d{5,10}/);
                        return digits ? 'FC2-PPV-' + digits[0] : null;
                    }

                    var dashed = text.match(/(?:^|[^A-Z0-9])([A-Z0-9]{2,10})[-_\s](\d{1,5}(?:[-_\s]\d{2,5})?)(?=$|[^A-Z0-9])/i);
                    if (dashed && /[A-Z]/.test(dashed[1])) {
                        return dashed[1].replace(/[-_\s]+/g, '-') + '-' + dashed[2].replace(/[-_\s]+/g, '');
                    }

                    // Compact codes (e.g. ABC123) must not start in the middle of
                    // an opaque numeric JavDB /v/ identifier such as 0eER10.
                    var compact = text.match(/(?:^|[^A-Z0-9])([A-Z]{2,8})(\d{2,7})(?=$|[^A-Z0-9])/i);
                    if (compact) return compact[1] + '-' + compact[2];
                    return null;
                }

                function extractCode() {
                    var metaTitle = document.querySelector('meta[property="og:title"], meta[name="twitter:title"]');
                    // Prefer human-readable page metadata. JavDB /v/<id> is an
                    // internal identifier and must be the final fallback.
                    var sources = [
                        document.title,
                        metaTitle ? metaTitle.content : '',
                        document.querySelector('h1') ? document.querySelector('h1').innerText : '',
                        document.querySelector('.video-title, .entry-title, .title, [class*="video-code"], [class*="video-code"]')?.innerText || '',
                        window.location.pathname,
                        window.location.search,
                        window.location.hash
                    ];
                    for (var i = 0; i < sources.length; i++) {
                        var code = normalizeCode(sources[i]);
                        if (code) return code;
                    }
                    return null;
                }

                function buildUrl(template, code) {
                    return String(template || '').replace('{code}', encodeURIComponent(code));
                }

                function ensurePanel() {
                    var panel = document.getElementById(panelId);
                    if (panel || !document.body) return panel;

                    panel = document.createElement('section');
                    panel.id = panelId;
                    panel.setAttribute('aria-label', panelTitle);
                    panel.innerHTML =
                        '<div data-role="head">' +
                            '<span data-role="title"></span>' +
                            '<button type="button" data-role="toggle" aria-label="toggle">−</button>' +
                        '</div>' +
                        '<div data-role="grid"></div>';

                    var style = document.createElement('style');
                    style.id = '__javCrossSiteSearchStyle';
                    style.textContent =
                        '#jav-cross-site-search {' +
                            'position:sticky!important;top:0!important;z-index:2147483000!important;' +
                            'display:block!important;box-sizing:border-box!important;width:100%!important;' +
                            'max-width:760px!important;margin:0 auto 10px!important;padding:8px!important;' +
                            'background:rgba(25,25,25,.97)!important;color:#fff!important;' +
                            'border:1px solid #8b00ff!important;border-radius:0 0 10px 10px!important;' +
                            'box-shadow:0 3px 12px rgba(0,0,0,.35)!important;font:14px sans-serif!important;' +
                        '}' +
                        '#jav-cross-site-search [data-role="head"] {' +
                            'display:flex!important;align-items:center!important;justify-content:space-between!important;' +
                            'gap:8px!important;margin:0 0 7px!important;font-weight:700!important;' +
                        '}' +
                        '#jav-cross-site-search [data-role="toggle"] {' +
                            'width:28px!important;height:26px!important;padding:0!important;border:0!important;' +
                            'border-radius:6px!important;background:#6a1b9a!important;color:#fff!important;' +
                            'font-size:20px!important;line-height:22px!important;cursor:pointer!important;' +
                        '}' +
                        '#jav-cross-site-search [data-role="grid"] {' +
                            'display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:6px!important;' +
                        '}' +
                        '#jav-cross-site-search [data-role="grid"] button {' +
                            'display:block!important;width:100%!important;min-height:34px!important;padding:7px 5px!important;' +
                            'border:0!important;border-radius:6px!important;background:#bb86fc!important;color:#17121e!important;' +
                            'font:700 13px/1.2 sans-serif!important;text-align:center!important;white-space:normal!important;' +
                            'cursor:pointer!important;overflow-wrap:anywhere!important;' +
                        '}' +
                        '#jav-cross-site-search [data-role="grid"] button:active {' +
                            'background:#cf6fff!important;transform:scale(.99)!important;' +
                        '}';
                    (document.head || document.documentElement).appendChild(style);
                    document.body.insertBefore(panel, document.body.firstElementChild || null);

                    panel.querySelector('[data-role="toggle"]').onclick = function(event) {
                        event.preventDefault();
                        event.stopPropagation();
                        var grid = panel.querySelector('[data-role="grid"]');
                        var toggle = panel.querySelector('[data-role="toggle"]');
                        var collapsed = grid.style.getPropertyValue('display') === 'none';
                        grid.style.setProperty('display', collapsed ? 'grid' : 'none', 'important');
                        toggle.textContent = collapsed ? '−' : '+';
                    };
                    return panel;
                }

                function render() {
                    var code = extractCode();
                    var panel = document.getElementById(panelId);
                    if (!code) {
                        if (panel) panel.style.display = 'none';
                        return;
                    }
                    panel = ensurePanel();
                    if (!panel) return;
                    panel.style.display = 'block';
                    panel.querySelector('[data-role="title"]').textContent = panelTitle + ': ' + code;
                    var grid = panel.querySelector('[data-role="grid"]');
                    var collapsed = grid.style.getPropertyValue('display') === 'none';
                    grid.innerHTML = '';
                    sites.forEach(function(site) {
                        var button = document.createElement('button');
                        button.type = 'button';
                        button.textContent = searchPrefix + site.label + searchSuffix + code;
                        button.setAttribute('data-search-url', buildUrl(site.template, code));
                        button.onclick = function(event) {
                            event.preventDefault();
                            event.stopPropagation();
                            var target = button.getAttribute('data-search-url');
                            if (window.Android && Android.navigateToUrl) Android.navigateToUrl(target);
                            else window.location.href = target;
                        };
                        grid.appendChild(button);
                    });
                    grid.style.setProperty('display', collapsed ? 'none' : 'grid', 'important');
                    panel.querySelector('[data-role="toggle"]').textContent = collapsed ? '+' : '−';
                }

                render();
                if (window.__javCrossSiteSearchTimer) clearInterval(window.__javCrossSiteSearchTimer);
                window.__javCrossSiteSearchTimer = setInterval(render, 1500);
            })($sites, $panelTitle, $searchPrefix, $searchSuffix);
        """.trimIndent()
        GeckoEval.runJavascript(geckoSession, js)
    }

    /**
     * 只在網頁內辨識番號並回傳給 Android，不再把跨站按鈕插入網站 DOM。
     * 網頁內容會自己重繪，因此使用 MutationObserver 觸發防抖偵測，避免固定輪詢與版面佔高。
     */
    private fun injectCrossSiteSearchButtons(pageUrl: String?) {
        if (pageUrl.isNullOrBlank()) return

        val js = """
            (function() {
                'use strict';

                var host = (window.location.hostname || '').toLowerCase();
                var supported = /(^|\.)javdb\.com$/.test(host) ||
                    /(^|\.)javtrailers\.com$/.test(host) ||
                    /(^|\.)javhdporn\.net$/.test(host) ||
                    /(^|\.)missav\./.test(host) ||
                    /(^|\.)jable\.tv$/.test(host) ||
                    /(^|\.)avjoy\.me$/.test(host) ||
                    /(^|\.)pigav\.ws$/.test(host) ||
                    /(^|\.)avtoday\.io$/.test(host) ||
                    /(^|\.)7mmtv\.sx$/.test(host) ||
                    /(^|\.)7tv\d*\.com$/.test(host) ||
                    /(^|\.)avple\.tv$/.test(host) ||
                    /(^|\.)whos\.tv$/.test(host) ||
                    /(^|\.)javday\.app$/.test(host) || /(^|\.)supjav\.com$/.test(host) || /(^|\.)javrate\.com$/.test(host);
                var stateKey = '__javCrossSiteDetectorState';

                function notify(value) {
                    var code = value || '';
                    if (window[stateKey] === code) return;
                    window[stateKey] = code;
                    try {
                        if (window.Android && Android.onCrossSiteCodeDetected) {
                            Android.onCrossSiteCodeDetected(code);
                        }
                    } catch (e) {}
                }

                if (!supported) {
                    notify('');
                    return;
                }

                function normalizeCode(value) {
                    var text = String(value || '').toUpperCase();
                    var fc2 = text.match(/FC2(?:[-_\s]?PPV)?[-_\s]?\d{5,10}/i);
                    if (fc2) {
                        var digits = fc2[0].match(/\d{5,10}/);
                        return digits ? 'FC2-PPV-' + digits[0] : null;
                    }

                    var dashed = text.match(/(?:^|[^A-Z0-9])([A-Z0-9]{2,10})[-_\s](\d{1,5}(?:[-_\s]\d{2,5})?)(?=$|[^A-Z0-9])/i);
                    if (dashed && /[A-Z]/.test(dashed[1])) {
                        return dashed[1].replace(/[-_\s]+/g, '-') + '-' + dashed[2].replace(/[-_\s]+/g, '');
                    }

                    var compact = text.match(/(?:^|[^A-Z0-9])([A-Z]{2,8})(\d{2,7})(?=$|[^A-Z0-9])/i);
                    if (compact) return compact[1] + '-' + compact[2];
                    return null;
                }

                function extractCode() {
                    var metaTitle = document.querySelector('meta[property="og:title"], meta[name="twitter:title"]');
                    var heading = document.querySelector('h1');
                    var titleNode = document.querySelector('.video-title, .entry-title, .title, [class*="video-code"]');
                    var sources = [
                        document.title,
                        metaTitle ? metaTitle.content : '',
                        heading ? heading.innerText : '',
                        titleNode ? titleNode.innerText : '',
                        window.location.pathname,
                        window.location.search,
                        window.location.hash
                    ];
                    for (var i = 0; i < sources.length; i++) {
                        var code = normalizeCode(sources[i]);
                        if (code) return code;
                    }
                    return null;
                }

                if (window.__javCrossSiteSearchObserver) {
                    try { window.__javCrossSiteSearchObserver.disconnect(); } catch (e) {}
                }
                if (window.__javCrossSiteSearchTimer) {
                    clearTimeout(window.__javCrossSiteSearchTimer);
                }

                function scan() {
                    window.__javCrossSiteSearchTimer = null;
                    notify(extractCode() || '');
                }

                function schedule() {
                    if (window.__javCrossSiteSearchTimer) return;
                    window.__javCrossSiteSearchTimer = setTimeout(scan, 250);
                }

                var observer = new MutationObserver(schedule);
                if (document.documentElement) {
                    observer.observe(document.documentElement, {
                        childList: true,
                        subtree: true,
                        characterData: true
                    });
                }
                window.__javCrossSiteSearchObserver = observer;
                window.addEventListener('popstate', schedule);
                window.addEventListener('hashchange', schedule);
                scan();
            })();
        """.trimIndent()
        GeckoEval.runJavascript(geckoSession, js)
    }

    /** 攔截清單 = 雲端/內建通用規則 + 使用者長按屏蔽（後者獨立存放，雲端更新不覆蓋）。 */
    private fun refreshBlockList() {
        cachedBlockList = (adFilterRules.getCommonBlockList() + adFilterRules.getUserBlockList()).toSet()
    }

    /**
     * 長按屏蔽：把長按到的廣告來源加入使用者屏蔽列表。
     * 預設屏蔽完整子域名；來源若不在本站的域名下，也可選整個域名（含子域名）。
     * 本站自己的域名拒絕屏蔽，避免一按就把本站內容全擋掉。寫入後立即刷新
     * 攔截列表並重載頁面，讓屏蔽馬上生效。
     */
    private fun showBlockSourceDialog(targetUrl: String, host: String) {
        val pageHost = runCatching { android.net.Uri.parse(geckoCurrentUrl ?: currentPageUrl).host }.getOrNull()?.lowercase().orEmpty()
        val baseDomain = host.split(".").takeLast(2).joinToString(".")
        // 頁面本身就在該域名下（本站或其子域）時，整個域名不能屏蔽，
        // 否則連主框架導航都會被攔截（攔截不分主框架）。
        val sameBase = pageHost == baseDomain || pageHost.endsWith(".$baseDomain")
        if (host == pageHost) {
            Toast.makeText(this, "這是本站自己的內容，不能屏蔽", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("屏蔽這個來源？")
            .setMessage("來源：$host\n\n屏蔽後，該來源的圖片、腳本與廣告將不再載入。框本身還在的話，點工具欄的 🚫 進點選模式，點一下那個框就能移除。屏錯了可在設定 → 我的屏蔽列表裡刪除。")
        if (sameBase || baseDomain == host) {
            dialog.setPositiveButton("屏蔽此域名") { _, _ -> addUserBlockAndReload(host) }
        } else {
            dialog.setPositiveButton("屏蔽整個域名") { _, _ -> addUserBlockAndReload(baseDomain) }
            dialog.setNeutralButton("僅此子域名") { _, _ -> addUserBlockAndReload(host) }
        }
        dialog.setNegativeButton("取消", null)
        dialog.show()
    }

    private fun addUserBlockAndReload(host: String) {
        val added = adFilterRules.addUserBlock(host)
        refreshBlockList()
        Toast.makeText(
            this,
            if (added) "已屏蔽 $host，正在刷新頁面" else "$host 已在屏蔽列表中，正在刷新頁面",
            Toast.LENGTH_SHORT
        ).show()
        geckoSession.reload()
    }

    /**
     * 點選屏蔽：長按只能攔住廣告圖片/連結的請求，框本身是頁面元素，
     * 網路層攔不掉——要框消失得把元素從 DOM 移除。點工具欄 🚫 進入
     * 點選模式，點一下要屏蔽的元素，確認選擇器後立即移除，並記成
     * 使用者元素規則，以後打開同站頁面自動移除（含動態後插的框）。
     * 按返回鍵取消點選模式；換頁自動退出。
     */
    private fun enterDomBlockPicker() {
        if (domBlockPickerActive) return
        domBlockPickerActive = true
        GeckoEval.runJavascript(geckoSession, 
            """
            (function() {
                if (window.__javDomPicker) return;
                var current = null;
                function describe(el) {
                    var s = (el.tagName || '').toLowerCase();
                    if (el.id) s += '#' + el.id;
                    return s;
                }
                function selectorFor(el) {
                    var parts = [];
                    var node = el;
                    while (node && node.nodeType === 1 && node !== document.documentElement) {
                        var part = (node.tagName || '').toLowerCase();
                        if (node.id) {
                            try {
                                if (document.querySelectorAll('#' + CSS.escape(node.id)).length === 1) {
                                    parts.unshift(part + '#' + CSS.escape(node.id));
                                    break;
                                }
                            } catch (e) {}
                            part += '#' + CSS.escape(node.id);
                            parts.unshift(part);
                            break;
                        }
                        var cls = (node.className && typeof node.className === 'string')
                            ? node.className.trim().split(/\s+/).filter(Boolean).slice(0, 2)
                                .map(function(c) { return '.' + CSS.escape(c); }).join('')
                            : '';
                        part += cls;
                        var sib = node.parentNode
                            ? Array.prototype.filter.call(node.parentNode.children, function(x) { return x.tagName === node.tagName; })
                            : [];
                        if (sib.length > 1) part += ':nth-of-type(' + (sib.indexOf(node) + 1) + ')';
                        parts.unshift(part);
                        node = node.parentNode;
                        if (parts.length > 6) break;
                    }
                    return parts.join(' > ');
                }
                function onOver(e) {
                    var t = e.target;
                    if (current === t) return;
                    if (current) current.style.outline = '';
                    current = t;
                    t.style.outline = '2px solid red';
                }
                function onClick(e) {
                    e.preventDefault();
                    e.stopPropagation();
                    var sel = selectorFor(e.target);
                    var tag = describe(e.target);
                    exitPicker();
                    try { Android.onDomBlockPicked(sel, tag); } catch (err) {}
                }
                function exitPicker() {
                    document.removeEventListener('mouseover', onOver, true);
                    document.removeEventListener('click', onClick, true);
                    if (current) { current.style.outline = ''; current = null; }
                    window.__javDomPicker = false;
                }
                window.__javDomPickerExit = exitPicker;
                window.__javDomPicker = true;
                document.addEventListener('mouseover', onOver, true);
                document.addEventListener('click', onClick, true);
            })();
            """.trimIndent())
        Toast.makeText(this, "點選模式：點一下要屏蔽的元素，按返回鍵取消", Toast.LENGTH_SHORT).show()
    }

    private fun exitDomBlockPicker() {
        if (!domBlockPickerActive) return
        domBlockPickerActive = false
        GeckoEval.runJavascript(geckoSession, 
            "(function() { try { if (window.__javDomPickerExit) window.__javDomPickerExit(); } catch (e) {} })();")
    }

    private fun showDomBlockConfirmDialog(selector: String, tag: String) {
        domBlockPickerActive = false
        val pageHost = runCatching { android.net.Uri.parse(geckoCurrentUrl ?: currentPageUrl).host }.getOrNull()?.lowercase().orEmpty()
        if (pageHost.isEmpty() || selector.isEmpty()) {
            Toast.makeText(this, "這個元素拿不到可用的選擇器", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("屏蔽這個元素？")
            .setMessage("元素：$tag\n選擇器：$selector\n\n確認後立即移除，以後打開 $pageHost 的頁面也會自動移除。屏錯了可在設定 → 我屏蔽的元素裡刪除。")
            .setPositiveButton("屏蔽") { _, _ ->
                val added = adFilterRules.addUserDomRemoveRule(pageHost, selector)
                Toast.makeText(
                    this,
                    if (added) "已屏蔽該元素" else "該元素已在屏蔽列表中",
                    Toast.LENGTH_SHORT
                ).show()
                // 走同一套移除 + MutationObserver，立即清掉現有與動態後插的框。
                applySiteDomRules(geckoCurrentUrl ?: currentPageUrl)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // Gecko 没有 document-start 注入：javhdporn 的 streamtape patch
    // 从 setupWebView() 里的局部变量提到类级，在 onPageStop 注一次
    // （onPageStart 时新文档还没就绪，GeckoEval 注了也会超时；脚本
    // 自带 _javhd_patched 守卫）。
    // 注意：时序弱于旧版 DOCUMENT_START_SCRIPT，页面脚本可能先跑。
    private val stPatchScript = """
(function(){
  if(window._javhd_patched) return;
  window._javhd_patched = true;
  try { Android.onPatchLoaded(location.href); } catch(e) {}
  function reportUrl(u) {
    // 把目前 frame URL 一起傳，讓 Kotlin 側判斷是否為廣告 frame
    try { Android.onStreamtapeEmbedFound(location.href + '|||' + u); } catch(e) {}
  }
  function extractST(text) {
    if(typeof text !== 'string') return;
    // Streamtape embed URL
    var m = text.match(/((?:https?:)?\/\/streamtape\.[a-z]+\/e\/[A-Za-z0-9_-]+)/);
    if(m) { reportUrl(m[1].indexOf('http')===0 ? m[1] : 'https:'+m[1]); return; }
    // tapecontent / streamtape get_video
    var m2 = text.match(/(https?:\/\/[^\s"'<>]*tapecontent[^\s"'<>]*\.(?:mp4|m3u8)[^\s"'<>]*)/);
    if(m2) { reportUrl(m2[1]); return; }
    // 任意 m3u8 URL
    var m3 = text.match(/(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/);
    if(m3) { reportUrl(m3[1]); return; }
    // 任意 mp4 URL（至少含路徑）
    var m4 = text.match(/(https?:\/\/[^\s"'<>]+\/[^\s"'<>]+\.mp4[^\s"'<>]*)/);
    if(m4) { reportUrl(m4[1]); return; }
  }
  // Intercept XHR（player 常用 XHR 取得影片 URL）
  var _xhrOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    if(typeof url === 'string') extractST(url);
    return _xhrOpen.apply(this, arguments);
  };
  var _xhrResp = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText');
  if(_xhrResp && _xhrResp.get) {
    Object.defineProperty(XMLHttpRequest.prototype, 'responseText', {
      get: function() {
        var r = _xhrResp.get.call(this);
        if(typeof r === 'string') extractST(r);
        return r;
      }, configurable: true
    });
  }
  // Intercept fetch（async 取得影片 URL）
  var _fetch = window.fetch;
  if(_fetch) {
    window.fetch = function(input, init) {
      var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
      extractST(url);
      return _fetch.call(this, input, init).then(function(resp) {
        var ct = resp.headers.get('content-type') || '';
        if(ct.indexOf('mpegurl') !== -1 || ct.indexOf('mp4') !== -1) {
          extractST(resp.url);
        }
        return resp;
      });
    };
  }
  // Intercept video/source element src setter
  var _mediaSrcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
  if(_mediaSrcDesc && _mediaSrcDesc.set) {
    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
      set: function(v) { if(typeof v==='string') extractST(v); return _mediaSrcDesc.set.call(this,v); },
      get: _mediaSrcDesc.get, configurable: true
    });
  }
  // Patch document.write / writeln
  var _w = Document.prototype.write;
  Document.prototype.write = function() {
    var c = Array.prototype.join.call(arguments,'');
    if(c.indexOf('streamtape') !== -1) extractST(c);
    return _w.apply(this, arguments);
  };
  var _wl = Document.prototype.writeln;
  Document.prototype.writeln = function() {
    var c = Array.prototype.join.call(arguments,'');
    if(c.indexOf('streamtape') !== -1) extractST(c);
    return _wl.apply(this, arguments);
  };
  // Patch innerHTML setter
  var _d = Object.getOwnPropertyDescriptor(Element.prototype,'innerHTML');
  if(_d && _d.set) {
    Object.defineProperty(Element.prototype,'innerHTML',{
      set: function(v) {
        if(typeof v==='string' && v.indexOf('streamtape')!==-1) extractST(v);
        return _d.set.call(this,v);
      },
      get: _d.get, configurable: true
    });
  }
  // Patch iframe src setter（最常見的動態注入方式）
  var _srcDesc = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype,'src');
  if(_srcDesc && _srcDesc.set) {
    Object.defineProperty(HTMLIFrameElement.prototype,'src',{
      set: function(v) {
        if(typeof v==='string' && v.indexOf('streamtape')!==-1) extractST(v);
        return _srcDesc.set.call(this,v);
      },
      get: _srcDesc.get, configurable: true
    });
  }
  // Patch Element.setAttribute（有時直接 el.setAttribute('src','//streamtape...')）
  var _sa = Element.prototype.setAttribute;
  Element.prototype.setAttribute = function(name, value) {
    if(name==='src' && typeof value==='string' && value.indexOf('streamtape')!==-1) extractST(value);
    return _sa.call(this, name, value);
  };
  // Patch document.createElement 監控 iframe 建立後 src 的變化
  var _ce = document.createElement.bind(document);
  document.createElement = function(tag) {
    var el = _ce(tag);
    if(tag.toLowerCase()==='iframe') {
      var obs = new MutationObserver(function(muts) {
        muts.forEach(function(m) {
          if(m.attributeName==='src') {
            var s = el.getAttribute('src') || '';
            if(s.indexOf('streamtape')!==-1) extractST(s);
          }
        });
      });
      obs.observe(el, {attributes:true, attributeFilter:['src']});
    }
    return el;
  };
  // 攔截 contentDocument getter（主頁呼叫 blobIframe.contentDocument.write(...) 時觸發）
  // blob frame 雖然注入不進去，但主頁 JS 用 contentDocument 存取它時可以在主頁 context 攔截
  var _cdDesc = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'contentDocument');
  if(_cdDesc && _cdDesc.get) {
    Object.defineProperty(HTMLIFrameElement.prototype, 'contentDocument', {
      get: function() {
        var doc = _cdDesc.get.call(this);
        if(doc && !doc._javhd_write_patched) {
          doc._javhd_write_patched = true;
          // 直接在 document 實例上 patch write（不影響 prototype，只影響這個 doc）
          var _dw = doc.write ? doc.write.bind(doc) : null;
          if(_dw) {
            doc.write = function() {
              var c = Array.prototype.join.call(arguments,'');
              if(c.indexOf('streamtape')!==-1) extractST(c);
              return _dw.apply(null, arguments);
            };
          }
          var _dwl = doc.writeln ? doc.writeln.bind(doc) : null;
          if(_dwl) {
            doc.writeln = function() {
              var c = Array.prototype.join.call(arguments,'');
              if(c.indexOf('streamtape')!==-1) extractST(c);
              return _dwl.apply(null, arguments);
            };
          }
        }
        return doc;
      },
      configurable: true
    });
  }
})();
""".trimIndent()

    private fun onGeckoPageStart(url: String?) {

        // 換頁後點選模式的 JS 已隨舊文檔消失，只需復位旗標。
        domBlockPickerActive = false
        cancelPendingFilePrompt()
        supjavSelectionGeneration++
        supjavMediaResolver.close()
        android.util.Log.d("NAV_DEBUG", "onPageStarted url=$url")
        currentPageUrl = url ?: ""   // 在 main thread 更新，供 onGeckoMediaCandidate 安全读取
        if (url != null && isRouVideoUrl(url)) adFilterRules.rememberRouVideoDomain(url)
        rememberStripchatModelUrl(url)
        btnPlay.visibility = View.GONE
        btnPlay.text = "▶"
        clearCrossSiteCode()
        isOnJavDbVideoPage = false
        isOnJavTrailersVideoPage = false
        currentVideoUrl = null
        currentVideoReferer = null
        currentRouEpisodes = emptyList()
        currentJavdayEpisodes = emptyList()
        currentMissAvThumbnailConfig = null
        currentJableVttUrl = null
        videoFoundToastShown = false

        // DMM Cookie bypass: inject region-unlock cookie for all DMM domains
        if (url != null && (url.contains("dmm.co.jp") || url.contains("dmm.com"))) {
            // DMM 地区检测 cookie：走 GeckoCookies.setCookie 写进 Gecko 仓
            // （background 侧写，不需要文档就绪；旧版用
            // android.webkit.CookieManager 写，那是另一个仓）。
            val cookie = "ckcy_remedied_check=ec_mrnhbtk; path=/"
            GeckoCookies.setCookie("https://dmm.co.jp", cookie)
            GeckoCookies.setCookie("https://special.dmm.co.jp", cookie)
            GeckoCookies.setCookie("https://video.dmm.co.jp", cookie)
            GeckoCookies.setCookie("https://www.dmm.co.jp", cookie)
        }

        // javhdporn.net：patch Document.prototype.write 攔截 Streamtape URL
        // 頁面用空 blob iframe + contentDocument.write() 注入播放器，
        // 只需攔截 write() 的內容即可取得 Streamtape embed URL
        // javhdporn.net 的 stPatchScript 不在这里注：GeckoEval 需要已加载好的
            // 文档（token 靠 javascript: URL 注入，onPageStart 时新文档还没就绪，
            // 注了也会超时，还会占住全局串行 eval 队列 10 秒）；
            // 只在 onPageStop 注一次（见 onGeckoPageStop）。

        // Show progress bar and start timeout
        if (!isOnLandingPage()) {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            startLoadTimeout()
        }
    }

    private fun onGeckoPageStop(url: String?) {
        // Gecko 没有 document-start 注入：patch 在页面停止时注
        // （onPageStart 时新文档还没就绪，GeckoEval 注了也会超时；
        //  脚本自带 _javhd_patched 守卫，重复无害）。
        if (url?.contains("javhdporn.net") == true) {
            GeckoEval.runJavascript(geckoSession, stPatchScript)
        }

        if (url != null && AdditionalSiteMedia.isPage(url)) {
            GeckoEval.runJavascript(geckoSession, """
                (function() {
                    var poster = document.getElementById('player-wrap');
                    if (poster) {
                        var match = getComputedStyle(poster).backgroundImage.match(/url\(["']?(.*?)["']?\)/);
                        if (match) window.__javMainPoster = match[1];
                    }
                    if (/(^|\.)javrate\.com${'$'}/i.test(location.hostname)) {
                        var frame = document.querySelector('#v2-player');
                        if (frame && frame.src) Android.onAdditionalPlayerFrame(frame.src);
                        return;
                    }
                    if (/(^|\.)supjav\.com${'$'}/i.test(location.hostname)) {
                        $supjavPlayerHook
                        return;
                    }
                    if (!/(^|\.)javday\.app${'$'}/i.test(location.hostname) || window.__javdayEpisodeHook) return;
                    window.__javdayEpisodeHook = true;
                    var episodePanel = document.getElementById('episodePanel');
                    if (episodePanel) Android.onAdditionalEpisodes(episodePanel.outerHTML, location.href);
                    document.addEventListener('click', function(event) {
                        var episode = event.target.closest('.episode-btn[data-url]');
                        if (episode) Android.onAdditionalEpisode(episode.getAttribute('data-url'));
                    }, true);
                    var first = document.querySelector('.episode-btn[data-url]');
                    var source = document.querySelector('video source[src], source[type="application/x-mpegURL"]');
                    var candidate = first ? first.getAttribute('data-url') : (source ? source.getAttribute('src') : '');
                    if (candidate && candidate.indexOf('${'$'}') < 0) Android.onAdditionalEpisode(candidate);
                })();
            """.trimIndent())
        }
        android.util.Log.d("NAV_DEBUG", "onPageFinished url=$url")
        updateCopyPageUrlVisibility()

        // Hide progress bar and cancel timeout
        progressBar.visibility = View.GONE
        cancelLoadTimeout()

        // 利用網頁自身的 sessionStorage，以「自己的 URL」作為 key 恢復滾動位置
        // shouldOverrideUrlLoading 離開時已儲存；onBackPressed 返回時同樣儲存
        val restoreScript = """
            (function() {
                var key = 'scrollPos__' + window.location.href;
                var savedY = sessionStorage.getItem(key);
                if (savedY) {
                    var sy = parseInt(savedY, 10);
                    if (sy > 0) {
                        setTimeout(function() {
                            window.scrollTo(0, sy);
                            document.documentElement.scrollTop = sy;
                            document.body.scrollTop = sy;
                            sessionStorage.removeItem(key);
                        }, 300);
                    } else {
                        sessionStorage.removeItem(key);
                    }
                }
            })();
        """.trimIndent()
        GeckoEval.runJavascript(geckoSession, restoreScript)
        applySiteDomRules(url)
        injectCrossSiteSearchButtons(url)
        // JavHDPorn 的固定彈窗與頁面內嵌廣告位會在載入後動態重建。
        // 只針對 JavHDPorn 移除已確認的廣告容器，避免影響其他網站的播放器 iframe。
        if (url?.contains("javhdporn.net", ignoreCase = true) == true) {
            val javHdpornPopupCleanupJs = """
                (function() {
                    if (window.__javHdpornPopupCleanup) return;
                    window.__javHdpornPopupCleanup = true;

                    var popupSelector = [
                        'iframe[title="bfh"]',
                        'iframe[src*="go.whitetrafsa.com"]',
                        'iframe[src*="creative.whitetrafsa.com/widgets"]',
                        'iframe[src*="cdn.pornfhd.com/files/banner_300x100.html"]',
                        'iframe[style*="2147483647"]',
                        '.header-ad-mobile',
                        '.under-player-ad-mobile',
                        'a[href*="go.javhdporn.live"]',
                        'a[href*="javhd-trk.com"]'
                    ].join(', ');

                    function removePopupFrames() {
                        try {
                            document.querySelectorAll(popupSelector).forEach(function(frame) {
                                frame.remove();
                            });
                        } catch (e) {}
                    }

                    try {
                        var style = document.createElement('style');
                        style.id = '__javHdpornPopupCleanupStyle';
                        style.textContent = popupSelector + ' { display: none !important; visibility: hidden !important; }';
                        (document.head || document.documentElement).appendChild(style);
                    } catch (e) {}

                    removePopupFrames();
                    try {
                        var observer = new MutationObserver(removePopupFrames);
                        observer.observe(document.documentElement, { childList: true, subtree: true });
                        window.__javHdpornPopupObserver = observer;
                    } catch (e) {}
                })();
            """.trimIndent()
            GeckoEval.runJavascript(geckoSession, javHdpornPopupCleanupJs)
        }

        // Do NOT reset btnPlay or currentVideoUrl here, as video might have been found during load

        // Inject JS to remove specific ad elements
        val removeAdsJs = """
            (function() {
                function removeAds() {
                    // Remove iframes with ID starting with 'container-'
                    var iframes = document.querySelectorAll('iframe[id^="container-"]');
                    iframes.forEach(function(iframe) {
                        iframe.remove();
                    });

                    // Remove elements with high z-index and fixed position (common for overlays)
                    var allElements = document.getElementsByTagName('*');
                    for (var i = 0; i < allElements.length; i++) {
                        var el = allElements[i];
                        var style = window.getComputedStyle(el);
                        if (style.position === 'fixed' && style.zIndex > 2000000000) {
                            el.style.display = 'none';
                            el.remove();
                        }
                    }

                    // Rou.Video specific ad removal - Enhanced
                    var rmpAds = document.querySelectorAll('.rmp-ad-container, .rootContent--OjJEv');
                    rmpAds.forEach(function(ad) { ad.remove(); });

                    // PRIORITY: Remove ALL tscprts.com related elements (all sites)
                    var tscprtsElements = document.querySelectorAll('a[href*="tscprts.com"], a[href*="go.tscprts.com"]');
                    tscprtsElements.forEach(function(link) {
                        // Remove up to 3 levels of parent divs to ensure complete removal
                        var parent = link.parentElement;
                        for (var i = 0; i < 3 && parent; i++) {
                            var nextParent = parent.parentElement;
                            parent.remove();
                            parent = nextParent;
                        }
                    });

                    // Remove bottom-right floating ads by class patterns
                    var bottomRightAds = document.querySelectorAll('[class*="bottomRight"], [class*="slideAnimation"], [class*="root--"]');
                    bottomRightAds.forEach(function(ad) {
                        // Additional check: if it contains tscprts or doppiocdn links
                        if (ad.innerHTML && (ad.innerHTML.includes('tscprts.com') || ad.innerHTML.includes('doppiocdn.com'))) {
                            ad.remove();
                        }
                    });

                    // ENHANCED: Remove close-button ads and their parent containers (up to 2 levels)
                    // BUT exclude video player controls (vjs-*)
                    var closeButtons = document.querySelectorAll('[class*="close-button"]');
                    closeButtons.forEach(function(btn) {
                        // Skip if it's a video player control button
                        if (btn.className.includes('vjs-') || btn.className.includes('video-js')) {
                            return; // Skip video player buttons
                        }

                        // First, try to auto-click the button
                        try { btn.click(); } catch(e) {}

                        // Remove up to 2 levels of parent to get the entire ad container
                        var parent = btn.parentElement;
                        if (parent) {
                            var grandParent = parent.parentElement;
                            if (grandParent) {
                                grandParent.remove();
                            } else {
                                parent.remove();
                            }
                        } else {
                            btn.remove();
                        }
                    });

                    // Remove dialog overlays by ID pattern or role
                    var dialogs = document.querySelectorAll('div[role="dialog"], div[id^="radix-"]');
                    dialogs.forEach(function(dialog) { dialog.remove(); });

                    // Remove specific ad links/images - Enhanced with Safeguard
                    function isSafeToRemove(element) {
                        if (!element) return false;
                        if (element.id === 'player') return false;
                        if (element.classList.contains('video-js')) return false;
                        if (element.classList.contains('vjs-tech')) return false;
                        if (element.querySelector && (element.querySelector('#player') || element.querySelector('.video-js'))) return false;
                        return true;
                    }

                    var adLinks = document.querySelectorAll('a[href*="ra12.xyz"], a[href*="tscprts.com"], a[href*="doppiocdn.com"], img[src*="doppiocdn.com"]');
                    adLinks.forEach(function(link) { 
                        var parent = link.closest('div');
                        if (parent && isSafeToRemove(parent)) {
                            parent.remove();
                        } else {
                            link.remove(); 
                        }
                    });

                    // Generic removal for bottom floating ads (all sites now, not just rou.video)
                    var allDivs = document.getElementsByTagName('div');
                    for (var i = 0; i < allDivs.length; i++) {
                        var el = allDivs[i];
                        var style = window.getComputedStyle(el);
                        // Check for fixed position at bottom or bottom-right
                        if (style.position === 'fixed' && (style.bottom === '0px' || parseInt(style.bottom) < 100)) {
                            // Check if contains ad indicators
                            if (el.innerText.includes('Close') || el.innerHTML.includes('ra12.xyz') || 
                                el.innerHTML.includes('tscprts') || el.innerHTML.includes('go.tscprts') ||
                                el.innerHTML.includes('blob:') || style.zIndex > 100) {
                                el.style.display = 'none';
                                el.remove();
                            }
                        }
                    }


                    // Auto-click "Close ad" buttons (but skip video player controls)
                    var buttons = document.querySelectorAll('button, div[role="button"], a');
                    buttons.forEach(function(btn) {
                        // Skip video player controls
                        if (btn.className.includes('vjs-') || btn.className.includes('video-js')) {
                            return;
                        }

                        var text = btn.innerText || "";
                        if (text.toLowerCase().includes("close ad") || (text.toLowerCase() === "close" && !btn.closest('.video-js')) || text.includes("×")) {
                            // Check if it looks like an ad close button (heuristic)
                            if (btn.className.includes("close") || btn.className.includes("dismiss") || 
                                (btn.style.position === 'absolute' && btn.style.top)) {
                                try { btn.click(); } catch(e) {}
                                btn.remove(); // Remove it after clicking just in case
                            }
                        }
                    });
                }

                // Run immediately and periodically
                removeAds();
                setInterval(removeAds, 1000);
            })();
        """.trimIndent()
        // GeckoEval.runJavascript(geckoSession, removeAdsJs) // DISABLED FOR TESTING

        // New MISSAV Ad Blocking Logic
        if (url?.contains("missav") == true || url?.contains("jable") == true || url?.let(::isRouVideoUrl) == true || url?.contains("avjoy.me") == true || url?.contains("javhdporn.net") == true || url?.contains("7mmtv", ignoreCase = true) == true || url?.contains("7tv", ignoreCase = true) == true || url?.contains("avple.tv", ignoreCase = true) == true || url?.contains("whos.tv", ignoreCase = true) == true) {
            val missavAdBlockJs = """
                (function() {
                    'use strict';

                    // Avple 的播放器與 Whos 的登入/註冊對話框都屬於頁面內容，
                    // 不套用這段舊的通用 DOM 清理器；網路層的精確廣告規則仍會生效。
                    if (/((^|\.)avple\.tv|(^|\.)whos\.tv)$/i.test(window.location.hostname || '')) {
                        return;
                    }

                    // 1. 攔截彈窗與惡意跳轉邏輯
                    var websites = ["missav.com/pop", "tsyndicate.com/api", "${domainConfig.getMissAvDomain()}/pop"];
                    var url = window.location.href;
                    for (var i = 0; i < websites.length; i++) {
                        // 簡單的正則匹配
                        if (url.indexOf(websites[i]) !== -1) {
                            // 在WebView中，window.close() 可能無效，通常需要透過 about:blank 停止加載
                            window.location.href = "about:blank";
                            return; // 停止後續執行
                        }
                    }

                    // 2. 移除廣告 DOM 元素的函數
                    function cleanAds() {
                        // 移除特定的廣告區塊 (class 僅為 mx-auto 的元素)
                        try {
                            const mxauto = document.querySelectorAll('.mx-auto:not([class*=" "])');
                            mxauto.forEach(node => node.remove());
                        } catch (e) {}

                        // 移除特定的 root + bottomRight 廣告區塊 (動態識別)
                        try {
                            // 1. 注入 CSS 強制隱藏
                            var style = document.createElement('style');
                            style.innerHTML = `
                                div[class*="root"][class*="bottomRight"],
                                div[role="dialog"]:not([data-slot="sheet-content"]),
                                div[id^="radix-"]:not([data-slot="sheet-content"]),
                                div[id^="__clb-spot_"],
                                div[id^="ts_ad_"],
                                div[id^="exo-native-widget"],
                                .exo-native-widget,
                                div[data-banner-id],
                                .rmp-ad-container,
                                script[src*="magsrv.com"],
                                ins[data-zoneid],
                                ins {
                                    display: none !important;
                                }
                            `;
                            document.head.appendChild(style);

                            // 2. 使用 MutationObserver 監聽並移除
                            var observer = new MutationObserver(function(mutations) {
                                // Safeguard function
                                function isSafeToRemove(element) {
                                    if (!element || !element.tagName) return false;
                                    var tag = element.tagName.toLowerCase();
                                    if (tag === 'html' || tag === 'body' || tag === 'video' || tag === 'iframe') return false;
                                    if (element.id === 'player') return false;
                                    if (element.classList && (element.classList.contains('video-js') || element.classList.contains('vjs-tech'))) return false;
                                    if (element.querySelector && (element.querySelector('video') || element.querySelector('iframe') || element.querySelector('#player') || element.querySelector('.video-js'))) return false;
                                    return true;
                                }

                                mutations.forEach(function(mutation) {
                                    mutation.addedNodes.forEach(function(node) {
                                        if (node.nodeType === 1) { // Element
                                            // Check if node matches generic ad selectors
                                            if (node.matches && (
                                                node.matches('div[class*="root"][class*="bottomRight"]') ||
                                                (node.matches('div[role="dialog"]') && node.getAttribute('data-slot') !== 'sheet-content') ||
                                                (node.matches('div[id^="radix-"]') && node.getAttribute('data-slot') !== 'sheet-content') ||
                                                node.matches('div[id^="__clb-spot_"]') ||
                                                node.matches('div[id^="ts_ad_"]') ||
                                                node.matches('div[data-banner-id]') ||
                                                node.matches('.rmp-ad-container') ||
                                                node.matches('ins')
                                            )) {
                                                if (isSafeToRemove(node)) {
                                                    node.remove();
                                                }
                                            }

                                            // Check for Rou.Video specific cards (已停用以避免誤刪播放器)
                                            // if (node.matches && node.matches('div[data-slot="card"]')) {
                                            //     if (isSafeToRemove(node) && (node.innerText.includes('通告') || node.innerHTML.includes('ra12.xyz'))) {
                                            //         node.remove();
                                            //     }
                                            // }

                                            // Check for dynamic ad links
                                            if (node.matches && (node.matches('a[href*="ra12.xyz"]') || node.matches('a[href*="rdz1.xyz"]'))) {
                                                // 安全容器查找：最多往上 3 層，且確保容器不含影片內容連結
                                                var safeContainer = null;
                                                var cur = node.parentElement;
                                                for (var _i = 0; _i < 3 && cur; _i++) {
                                                    // 若容器含有影片內容連結，停止往上找（避免誤刪影片列表）
                                                    if (cur.querySelector && cur.querySelector('a[href^="/v/"]')) break;
                                                    safeContainer = cur;
                                                    cur = cur.parentElement;
                                                }
                                                if (safeContainer && isSafeToRemove(safeContainer)) {
                                                    safeContainer.remove();
                                                } else {
                                                    node.remove();
                                                }
                                            }

                                            // Check children of added node for ad links
                                            var dynamicAdLinks = node.querySelectorAll('a[href*="ra12.xyz"], a[href*="rdz1.xyz"]');
                                            dynamicAdLinks.forEach(link => {
                                                // 安全容器查找：最多往上 3 層，且確保容器不含影片內容連結
                                                var safeContainer = null;
                                                var cur = link.parentElement;
                                                for (var _j = 0; _j < 3 && cur; _j++) {
                                                    if (cur.querySelector && cur.querySelector('a[href^="/v/"]')) break;
                                                    safeContainer = cur;
                                                    cur = cur.parentElement;
                                                }
                                                if (safeContainer && isSafeToRemove(safeContainer)) {
                                                    safeContainer.remove();
                                                } else {
                                                    link.remove();
                                                }
                                            });

                                            // Also check children
                                            var ads = node.querySelectorAll('div[class*="root"][class*="bottomRight"], div[role="dialog"]:not([data-slot="sheet-content"]), div[id^="radix-"]:not([data-slot="sheet-content"]), div[id^="__clb-spot_"], div[id^="ts_ad_"], div[data-banner-id], .rmp-ad-container, ins');
                                            ads.forEach(ad => {
                                                if (isSafeToRemove(ad)) {
                                                    ad.remove();
                                                }
                                            });
                                        }
                                    });
                                });
                            });
                            observer.observe(document.body, { childList: true, subtree: true });

                            // 3. 初始移除 (播放器可能還沒載入，避免誤刪)
                            function cleanAdsInitial() {
                                // Safeguard
                                function isSafeToRemove(element) {
                                    if (!element) return false;
                                    if (element.id === 'player') return false;
                                    if (element.classList.contains('video-js')) return false;
                                    if (element.classList.contains('vjs-tech')) return false;
                                    if (element.querySelector && (element.querySelector('#player') || element.querySelector('.video-js'))) return false;
                                    return true;
                                }

                                // Generic selectors (不包括 card，因為播放器可能還沒載入)
                                const selectors = [
                                    'div[class*="root"][class*="bottomRight"]',
                                    'div[role="dialog"]:not([data-slot="sheet-content"])',
                                    'div[id^="radix-"]:not([data-slot="sheet-content"])',
                                    'div[id^="__clb-spot_"]',
                                    'div[id^="ts_ad_"]',
                                    'div[id^="exo-native-widget"]',
                                    '.exo-native-widget',
                                    'div[data-banner-id]',
                                    '.rmp-ad-container',
                                    'ins[data-zoneid]',
                                    'ins'
                                ];

                                selectors.forEach(selector => {
                                    document.querySelectorAll(selector).forEach(node => {
                                        if (isSafeToRemove(node)) {
                                            node.style.display = 'none';
                                            node.remove();
                                        }
                                    });
                                });

                                // Remove magsrv.com ad scripts (jable.tv)
                                document.querySelectorAll('script[src*="magsrv.com"]').forEach(script => {
                                    script.remove();
                                });

                                // Generic ad links and their containers
                                // 注意：不可使用 closest('.grid')，會誤刪整個影片列表！
                                // 改為最多往上 3 層找容器，且確保容器不含影片內容連結
                                document.querySelectorAll('a[href*="ra12.xyz"], a[href*="rdz1.xyz"]').forEach(link => {
                                    var safeContainer = null;
                                    var cur = link.parentElement;
                                    for (var _k = 0; _k < 3 && cur; _k++) {
                                        if (cur.querySelector && cur.querySelector('a[href^="/v/"]')) break;
                                        safeContainer = cur;
                                        cur = cur.parentElement;
                                    }
                                    if (safeContainer && isSafeToRemove(safeContainer)) {
                                        safeContainer.remove();
                                    } else {
                                        link.remove();
                                    }
                                });
                            }


                            cleanAdsInitial();
                        } catch (e) {}

                        // 嘗試點擊各種類型的關閉按鈕
                        // 註：這些 Class 名稱可能是混淆過的，網站更新後可能失效
                        const closeSelectors = [
                            ".close-button--wsOv0",
                            ".absolute.top-1.right-1.p-0.5.bg-black.rounded-lg.opacity-70"
                        ];

                        closeSelectors.forEach(selector => {
                            const btns = document.querySelectorAll(selector);
                            btns.forEach(btn => btn.click());
                        });
                    }

                    // 執行邏輯
                    cleanAds();

                    // 延遲執行 (應對動態加載的廣告)
                    setTimeout(cleanAds, 1000);
                    setTimeout(cleanAds, 2500);
                    setTimeout(cleanAds, 5000);

                })();
            """.trimIndent()
            GeckoEval.runJavascript(geckoSession, missavAdBlockJs)
        }

        checkForVideo()
        updateFavoriteIcon()

        if (url?.contains("stripchat.com", ignoreCase = true) == true) {
            injectStripchatWatchButton(url)
            GeckoEval.runJavascript(geckoSession, 
                """
                (function() {
                    if (window.__javStripchatBookmarkWatcher) return;
                    window.__javStripchatBookmarkWatcher = true;
                    var lastUrl = '';
                    function notifyLocation() {
                        var current = location.href;
                        if (current === lastUrl) return;
                        lastUrl = current;
                        try { Android.onStripchatLocationChanged(current); } catch (e) {}
                    }
                    ['pushState', 'replaceState'].forEach(function(name) {
                        var original = history[name];
                        history[name] = function() {
                            var result = original.apply(this, arguments);
                            setTimeout(notifyLocation, 0);
                            return result;
                        };
                    });
                    window.addEventListener('popstate', notifyLocation);
                    setInterval(notifyLocation, 500);
                    notifyLocation();
                })();
                """.trimIndent())
        }

        // JavDB 影片詳情頁：收藏統一使用左側愛心，避免顯示重複的書籤按鈕。
        if (url?.matches(Regex("https://javdb\\.com/v/[A-Za-z0-9]+.*")) == true) {
            isOnJavDbVideoPage = true
            btnPlay.visibility = View.GONE
        }

        // JavTrailers 影片詳情頁也統一使用左側愛心收藏。
        if (url?.contains("javtrailers.com/ja/video/") == true) {
            isOnJavTrailersVideoPage = true
            btnPlay.visibility = View.GONE
        }

        // javtrailers.com/ja/videos：頁面載入後等 5 秒再點「本日の新着」開關
        if (url?.contains("javtrailers.com/ja/videos") == true) {
            val autoClickNewArrivalJs = """
                setTimeout(function() {
                    var sw = document.getElementById('flexSwitchCheckDefault');
                    if (sw && !sw.checked) {
                        sw.click();
                    }
                }, 5000);
            """.trimIndent()
            GeckoEval.runJavascript(geckoSession, autoClickNewArrivalJs)
        }

        // 分享傳入的番號：landing page 載完後注入
        if (url == "https://javbrowser.app/" && pendingJavCodesFromShare.isNotEmpty()) {
            val codesJson = org.json.JSONArray(pendingJavCodesFromShare).toString()
                .replace("\\", "\\\\").replace("'", "\\'")
            GeckoEval.runJavascript(geckoSession, "prefillJavCodes('$codesJson')")
            pendingJavCodesFromShare = emptyList()
        }
    }

    private fun onGeckoLocationChange(url: String?) {

        // 頁面歷史更新時（包含 goBack），重設恢復目標 URL
        // pendingScrollRestoreUrl 在 onBackPressed 中已設定好，這裡不需要額外處理
        if (url?.contains("stripchat.com", ignoreCase = true) == true && stripchatModelKey(url) != null) {
            currentPageUrl = url
            rememberStripchatModelUrl(url)
            btnAddFavorite.text = "♡"
            updateFavoriteIcon(url)
            injectStripchatWatchButton(url)
        }
        if (url != null && isRouVideoUrl(url) && url != currentPageUrl) {
            currentPageUrl = url
            currentRouEpisodes = emptyList()
            currentVideoUrl = null
            currentVideoReferer = originForUrl(url)
            if (Uri.parse(url).path.orEmpty().startsWith("/v/")) checkForVideo()
        }
    }

    private fun onGeckoLoadRequest(url: String, isMainFrame: Boolean): Boolean {

        val lowerUrl = url.lowercase()

        if (SupjavNavigation.blocks(currentPageUrl, url, isMainFrame)) {
            android.util.Log.d("AdBlock", "SUPJAV main-frame advertisement blocked")
            return true
        }

        if (isRouVideoUrl(currentPageUrl) &&
            Uri.parse(url).path.orEmpty().startsWith("/api/hop/", ignoreCase = true)
        ) {
            android.util.Log.d("AdBlock", "ROU ad navigation blocked target=$url")
            return true
        }

        val navigationRules = siteRulesForPage(currentPageUrl)?.navigationBlock.orEmpty()
        if (navigationRules.any { it.matches(url, isMainFrame) }) {
            android.util.Log.d("AdBlock", "site navigation blocked page=$currentPageUrl target=$url")
            return true
        }

        // Block Shopee and Lazada redirects
        if (lowerUrl.contains("shopee") || lowerUrl.contains("shp.ee") || lowerUrl.contains("lazada")) {
            return true
        }

        // DMM region bypass: redirect "not-available-in-your-region" page
        if (lowerUrl.contains("dmm.co.jp/not-available-in-your-region") ||
            lowerUrl.contains("dmm.com/not-available-in-your-region")) {
            loadGeckoUrl("https://video.dmm.co.jp/av/")
            return true
        }

        // Handle APK download
        if (url.contains(".apk") || url.contains("down_ra")) {
            downloadAndInstallApk(url)
            return true
        }

        // 離開當前頁面前，先把目前的滾動位置存入 sessionStorage（以當前頁 URL 為 key）
        // 這樣按返回鍵回來時，onPageFinished 才能正確恢復位置
        GeckoEval.runJavascript(geckoSession, """
            (function() {
                var sy = window.scrollY || window.pageYOffset || document.documentElement.scrollTop || 0;
                if (sy > 0) {
                    var key = 'scrollPos__' + window.location.href;
                    sessionStorage.setItem(key, sy);
                }
            })();
        """.trimIndent())

        // Allow navigation to target URLs
        return false
    }

    private fun enterGeckoFullscreen() {
        if (isGeckoFullscreen) {
            exitGeckoFullscreen()
        }
        isGeckoFullscreen = true
        // Gecko 渲染全屏视频不需要另外的 custom view 容器；
        // 只做沉浸模式 + 方向锁定（旧 onShowCustomView 的其余部分原样保留）。

        btnCrossSiteSearch.visibility = View.GONE
        btnCopyPageUrl.visibility = View.GONE
        val isStripchatFullscreen = geckoCurrentUrl
            ?.contains("stripchat.com", ignoreCase = true) == true
        if (isStripchatFullscreen) {
            // Stripchat 同時有直式與橫式直播；依實際影片比例選擇方向。
            // 先保留手機目前方向，取得 metadata 後再鎖定對應感應方向。
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            GeckoEval.evaluate(geckoSession, 
                "(function(){var v=document.querySelector('video.video-element,video');" +
                    "return !!(v&&v.videoWidth>0&&v.videoHeight>0&&v.videoHeight>v.videoWidth);})()"
            ) { result ->
                requestedOrientation = if (result == "true") {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        } else {
            // 其他網站維持原有的全螢幕橫向行為。
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        // 沉浸模式
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun exitGeckoFullscreen() {
        if (!isGeckoFullscreen) return
        isGeckoFullscreen = false

        // 恢復直向 + 系統 UI
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
        if (isStripchatOverlayActive) {
            setStripchatPlayerChromeHidden(false)
        } else {
            updateCrossSiteSearchButtonVisibility()
        }

        runCatching { if (::geckoSession.isInitialized) geckoSession.exitFullScreen() }
    }

    // ── Gecko 主会话小帮手 ─────────────────────────────────────────────

    /** 显式激活主会话（1.2.6 教训：不依赖 Surface 路径；hyper-browser 同款）。 */
    private fun activateSession() {
        if (!::geckoSession.isInitialized) return
        runCatching {
            geckoSession.setActive(true)
            geckoSession.setFocused(true)
            geckoSession.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        }
    }

    /** 把 Gecko 侧 URL 映射回 app 用的地址：落地页是 data: URI 载入的，
     *  对外仍报 https://javbrowser.app/（isOnLandingPage 本来就接受
     *  data:，但剪贴板链接、分享预填等处比的是完整落地页地址）。 */
    private fun mapGeckoUrl(url: String?): String? {
        if (url == null) return null
        if (url.startsWith("data:") && landingDataShown) return LANDING_URL
        if (!url.startsWith("data:")) landingDataShown = false
        return url
    }

    /** 当前页标题（替代 webView.title；空标题返回 null，与旧行为一致）。 */
    private fun currentPageTitle(): String? = geckoPageTitle.takeIf { it.isNotEmpty() }

    /** 主会话导航（替代 webView.loadUrl）：显式激活后发 loadUri。 */
    private fun loadGeckoUrl(url: String) {
        if (!::geckoSession.isInitialized) return
        landingDataShown = false
        activateSession()
        geckoSession.loadUri(url)
    }

    /** 无界面的 GeckoSession（隐藏抓取用）：open + 显式激活，不挂 GeckoView。
     *  注意：1.2.6 的教训是 setActive(true) 是加载开始的关键；若真机上
     *  隐藏会话始终不触发 onPageStart，改成挂一个 1x1 的 GeckoView 再试。
     *  届时用 infra 的 GeckoHeadless.loadSession 替换这里。 */
    private fun openHeadlessSession(): GeckoSession {
        val session = GeckoSession()
        session.settings.apply {
            userAgentOverride = HIDDEN_UA
            allowJavascript = true
            useTrackingProtection = false
        }
        session.open(GeckoRuntimeProvider.get(this))
        // 桥扩展第一次使用前必须给过一次 Context（幂等；主会话 setup 时
        // 已给过，这里再给一次是防御，隐藏会话单独跑时也不至于没装上）。
        GeckoBridgeExtension.ensureInstalled(this)
        session.setActive(true)
        session.setFocused(true)
        session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        return session
    }

    private fun closeHiddenSession(session: GeckoSession?) {
        if (session == null) return
        JavhdGeckoExtension.detachMediaSniffer(session)
        runCatching { session.close() }
    }

    /** 从 Gecko cookie 仓读一批 URL 的 cookie（GeckoCookies.getCookie）。
     *  与 document.cookie 不同：能读到 HttpOnly；与
     *  android.webkit.CookieManager.getCookie 同格式（"a=b; c=d"）。
     *  任一 URL 失败/超时只贡献 ""，不影响其它；结果按名去重（排前
     *  的 URL 优先）。不走 eval 串行队列。 */
    private fun readGeckoCookies(urls: List<String>, onResult: (String) -> Unit) {
        val targets = urls.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) {
            onResult("")
            return
        }
        val results = arrayOfNulls<String>(targets.size)
        var remaining = targets.size
        targets.forEachIndexed { index, url ->
            GeckoCookies.getCookie(url) { value ->
                results[index] = value
                remaining -= 1
                if (remaining == 0) {
                    val merged = results.filterNotNull().filter { it.isNotBlank() }
                        .fold("") { acc, next -> mergeCookieStrings(acc, next) }
                    onResult(merged)
                }
            }
        }
    }

    /** 合并两份 "a=b; c=d" cookie 串，同名取第一份（Gecko 侧优先）。 */
    private fun mergeCookieStrings(first: String, second: String): String {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<String>()
        (first.split(";") + second.split(";")).forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@forEach
            val name = trimmed.substringBefore("=").trim()
            if (seen.add(name)) out += trimmed
        }
        return out.joinToString("; ")
    }

    // ── 媒体嗅探（原 shouldInterceptRequest 的捕获部分） ──────────────────
    //
    // Gecko 没有按请求拦截/阻断的 delegate：扩展的 webRequest 观察者只
    // 观察、不阻断（background.js），且只上报路径以 .m3u8/.mp4 结尾的
    // 请求（与 VideoPageSniffer 同款规则）。所以：
    // ① 动态广告拦截（commonBlock / site requestBlock / ROU /api/hop/）
    //    全部丢掉——只剩扩展静态拦的 11 个广告域（ensureInstalled 后
    //    runtime 级生效，VAST tsyndicate.com 永远放行）；
    // ② 捕获逻辑原样搬过来，但拿不到请求 Referer：AdditionalSiteMedia
    //    的 referer 参数改用页面 origin 代替（supjav 本来就排除在外，
    //    javrate 的 referer 就是页面本身，javday 不看 referer）；
    // ③ 原来“返回空响应阻断 WebView 继续加载影片（省流量）”没了——
    //    影片会在页内继续加载/播放，用户按 ▶ 再由外部播放器接管；
    // ④ streamtape get_video（无副档名）、.ts/.m4s 分段扩展不上报，
    //    只剩 .mp4/.m3u8；get_video 靠隐藏会话的 DOM 轮询补（见
    //    extractStreamtapeUrl）。
    // 另注意：扩展的 mediaCandidate 会广播给全部已挂嗅探回调（见
    // JavhdGeckoExtension 注释），主会话与隐藏会话同时挂着时，回调
    // 按 URL 形状各自过滤——隐藏会话的候选只可能是它正在抓的那个视频。

    /** 实时嗅探回调：扩展上报一个 .m3u8/.mp4 请求，按原拦截规则判定。 */
    private fun onGeckoMediaCandidate(url: String) {
        val lowerUrl = url.lowercase()
        val pageUrl = currentPageUrl

        // Video Sniffing: javhdporn.net / Streamtape CDN
        if (pageUrl.contains("javhdporn.net") &&
            (url.contains("streamtape") || url.contains("tapecontent"))) {
            android.util.Log.d("JAVHD_INTERCEPT", "intercepted: $url")
        }
        val isStreamtapeUrl = url.contains("streamtape") && (url.contains("get_video") || url.contains("/e/") || url.contains(".mp4"))
        val isTapecontentUrl = url.contains("tapecontent") && (url.contains(".mp4") || url.contains(".ts") || url.contains("m3u8"))
        if ((isStreamtapeUrl || isTapecontentUrl) && !AdditionalSiteMedia.isPage(pageUrl)) {
            val sniffedUrl = url
            if (currentVideoUrl != sniffedUrl) {
                currentVideoUrl = sniffedUrl
                showPlayButtonIfAllowed()
            }
        }

        // 网站专属规则优先：Avple 主 HLS 精确判定（与原来一致）。
        val isAvplePage = pageUrl.contains("avple.tv", ignoreCase = true)
        val requestUri = runCatching { Uri.parse(url) }.getOrNull()
        val isAvpleMainHls = isAvplePage &&
            (requestUri?.host.orEmpty().equals("cdnedge.live", ignoreCase = true) ||
                requestUri?.host.orEmpty().endsWith(".cdnedge.live", ignoreCase = true)) &&
            requestUri?.path.orEmpty().startsWith("/file/avple-asserts/hls/", ignoreCase = true) &&
            lowerUrl.contains(".m3u8")
        val isAvpleGrowMedia = isAvplePage &&
            (requestUri?.host.orEmpty().equals("growcdnssedge.com", ignoreCase = true) ||
                requestUri?.host.orEmpty().endsWith(".growcdnssedge.com", ignoreCase = true)) &&
            (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mp4") ||
                lowerUrl.contains(".m4s") || lowerUrl.contains(".ts"))
        val isAvpleMediaRequest = isAvpleMainHls || isAvpleGrowMedia

        // AdditionalSiteMedia：referer 拿不到，用页面 origin 代替（见上）。
        val additionalSite = AdditionalSiteMedia.isPage(pageUrl)
        val mediaReferer = originForUrl(pageUrl).orEmpty()
        if (additionalSite && !AdditionalSiteMedia.hostMatches(pageUrl, "supjav.com") &&
            AdditionalSiteMedia.accepts(pageUrl, url, mediaReferer)) {
            val sourcePage = pageUrl
            if (currentPageUrl == sourcePage) {
                currentVideoUrl = url
                currentVideoReferer = mediaReferer.ifBlank { originForUrl(sourcePage).orEmpty() }
                showPlayButtonIfAllowed()
            }
        }

        // Video Sniffing：m3u8 播放列表（与原来同款排除条件）。
        if ((isAvpleMainHls || isAvpleGrowMedia || (url.contains(".m3u8") && !url.contains("minisite") &&
            !pageUrl.contains("javhdporn.net") && !AvTodayMedia.isPage(pageUrl) &&
            RouVideoStreams.playlistForPage(pageUrl) == null && !isAvplePage && !additionalSite))) {
            val normalizedUrl = normalizeMissavM3u8Url(url)
            if (currentVideoUrl != normalizedUrl) {
                currentVideoUrl = normalizedUrl
                showPlayButtonIfAllowed()
                val pUrl = geckoCurrentUrl.orEmpty()
                if (currentVideoReferer == null) {
                    when {
                        pUrl.contains("missav") ->
                            currentVideoReferer = originForUrl(pUrl) ?: domainConfig.getMissAvBaseUrl()
                        pUrl.contains("pigav.ws") ->
                            currentVideoReferer = originForUrl(pUrl) ?: "https://pigav.ws/"
                        pUrl.contains("avtoday.io") ->
                            currentVideoReferer = originForUrl(pUrl) ?: "https://avtoday.io/"
                        pUrl.contains("avjoy.me") ->
                            currentVideoReferer = "https://avjoy.me/"
                        pUrl.contains("avple.tv", ignoreCase = true) ->
                            currentVideoReferer = originForUrl(pUrl) ?: domainConfig.getAvpleBaseUrl()
                        pUrl.contains("whos.tv", ignoreCase = true) ->
                            currentVideoReferer = originForUrl(pUrl) ?: domainConfig.getWhosBaseUrl()
                    }
                }
            }
        }
    }

    // ── 文件选择（原 WebChromeClient.onShowFileChooser） ─────────────────
    //
    // Gecko PromptDelegate.onFilePrompt 返回一个待完成的 GeckoResult；
    // 用户在系统选择器里选完（或取消）后才 complete。capture 属性
    // （<input capture=camera>）本次不单独处理——统一走系统选择器，
    // 用户可在选择器里选相机；旧版 FileChooserParams.createIntent()
    // 会直接拉起相机，这一级行为差记在这里。

    private fun onGeckoFilePrompt(
        prompt: GeckoSession.PromptDelegate.FilePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        if (fileChooserInFlight) {
            result.complete(prompt.dismiss())
            return result
        }
        pendingFilePrompt = prompt
        pendingFileResult = result
        try {
            val mimeTypes = (prompt.mimeTypes?.toList().orEmpty()).filter { it.isNotBlank() }
            val pickerIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeTypes.firstOrNull() ?: "*/*"
                if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            fileChooserInFlight = true
            chooseWebFiles.launch(pickerIntent)
        } catch (_: Exception) {
            fileChooserInFlight = false
            cancelPendingFilePrompt()
            Toast.makeText(this,
                LanguageManager.text(this, "無法開啟檔案選擇器", "Unable to open the file picker"),
                Toast.LENGTH_SHORT).show()
        }
        return result
    }

    private fun cancelPendingFilePrompt() {
        val prompt = pendingFilePrompt
        val pending = pendingFileResult
        pendingFilePrompt = null
        pendingFileResult = null
        if (prompt != null && pending != null) {
            runCatching { pending.complete(prompt.dismiss()) }
        }
    }

    // ── Stripchat 桥返回值 shim ──────────────────────────────────────────
    //
    // GeckoBridge 的 handler 是 (JSONArray) -> Unit，没有返回值；页面 JS
    // 里同步取返回值的 Android.* 调用（监录按钮与页内录制器，见
    // stripchat-watch-controls.js 与 playInsideCurrentWebView 注入的
    // 录制 JS）会拿到 undefined：按钮文案变 "undefined"、录制
    // begin/append 被当成失败。补救（不动 bridge API、不动 asset 文件）：
    // Kotlin 把状态推到 window.__javBridgeState，注入的 shim 在页面里
    // 整体换掉 window.Android（原桥是 Proxy：get 永远返回新的
    // dispatcher，逐个赋值包装无效），用推来的状态同步回答 getter；
    // begin/append 类调用先调原生桥（fire-and-forget，Kotlin 侧照常
    // 执行），再按 Kotlin 侧同一判定逻辑乐观返回。shim 在每次注入
    // 页面 JS 前先注入（与注入体拼在同一次 evaluate 里保证顺序），
    // 状态变化时由 notifyStripchatWatchState() 重新推送。

    /** 与原 @JavascriptInterface getStripchatWatchStateLabel 同一套判定，
     *  抽出来供 bridge handler 与状态推送共用。 */
    private fun stripchatWatchStateLabelFor(sourceUrl: String): String {
        val active = stripchatWatchSessionActive &&
            stripchatModelKey(sourceUrl) == stripchatWatchSessionModelKey
        return when (StripchatWatchState.resolve(active,
            stripchatWatchSessionRecording || stripchatStreamRecordingStore.isActive() ||
                StripchatRecordingService.isRecording,
            stripchatWatchSessionStarting, stripchatWatchLiveState, stripchatWatchStatusChecked)) {
            StripchatWatchState.INACTIVE -> LanguageManager.text(this, "◎ 監錄", "Watch and record")
            StripchatWatchState.RECORDING -> LanguageManager.text(this, "■ 停止監錄", "Stop recording")
            StripchatWatchState.STARTING -> LanguageManager.text(this, "◌ 正在啟動監錄", "Starting recording")
            StripchatWatchState.LIVE_RETRY -> LanguageManager.text(this, "◌ 直播中，重試錄製", "Live; retrying recording")
            StripchatWatchState.WAITING -> LanguageManager.text(this, "◌ 等待公開直播", "Waiting for public broadcast")
            StripchatWatchState.CHECKING -> LanguageManager.text(this, "◌ 正在確認直播狀態", "Checking live status")
            StripchatWatchState.UNKNOWN -> LanguageManager.text(this, "◌ 狀態無法確認，重試中", "Status unavailable; retrying")
        }
    }

    /** 把 Stripchat 监录/录制状态推到页面（window.__javBridgeState）。 */
    private fun pushStripchatBridgeState() {
        if (!::geckoSession.isInitialized) return
        val stateJson = org.json.JSONObject().apply {
            put("active", stripchatWatchSessionActive)
            put("modelKey", stripchatWatchSessionModelKey)
            put("recording", stripchatWatchSessionRecording ||
                stripchatStreamRecordingStore.isActive() ||
                StripchatRecordingService.isRecording)
            put("streamRecording", stripchatStreamRecordingStore.isActive())
            put("privacyMode", stripchatPrivacyDialog?.isShowing == true)
            put("label", stripchatWatchStateLabelFor(stripchatWatchSessionUrl))
        }.toString()
        GeckoEval.runJavascript(geckoSession, "window.__javBridgeState = $stateJson;")
    }

    /** 页面里的 Android.* 返回值 shim（见上）。幂等：window 级旗标
     *  __javShimInstalled（不能挂在 Android 对象上——原桥 Proxy 的
     *  get 对任意字符串键都返回 dispatcher，读到的永远是真值），
     *  重复注入直接返回；window.Android 不存在时静默跳过。 */
    private fun stripchatBridgeShimJs(): String = """
(function(){
  try {
    if (window.__javShimInstalled) return;
    if (!window.Android) return;
    window.__javBridgeState = window.__javBridgeState || {active:false, modelKey:'', recording:false, streamRecording:false, privacyMode:false, label:'◎ 監錄'};
    var S = window.__javBridgeState;
    var orig = window.Android;
    function modelKeyOf(url) {
      try {
        var u = new URL(url || location.href, location.href);
        if (!/(^|\.)stripchat\.com$/i.test(u.hostname)) return null;
        var p = (u.pathname || '').replace(/^\/+|\/+$/g, '');
        if (!p || p.indexOf('/') !== -1) return null;
        var low = p.toLowerCase();
        if (['login','signup','favorites','models','search','category','tags','live'].indexOf(low) !== -1) return null;
        return 'stripchat:' + low;
      } catch (e) { return null; }
    }
    var shimmed = {
      isStripchatWatchSessionActive: function(url) {
        var k = modelKeyOf(url);
        return !!S.active && !!k && S.modelKey === k;
      },
      isStripchatRecording: function() { return !!S.recording; },
      isStripchatStreamRecording: function() { return !!S.streamRecording; },
      isStripchatPrivacyMode: function() { return !!S.privacyMode; },
      getStripchatWatchStateLabel: function(url) {
        var k = modelKeyOf(url);
        if (!!S.active && !!k && S.modelKey === k) return S.label || '◌ 正在確認直播狀態';
        return '◎ 監錄';
      },
      beginStripchatWatchSession: function(url) {
        try { orig.beginStripchatWatchSession(url); } catch (e) {}
        var k = modelKeyOf(url);
        if (!k) return false;
        if (S.active && S.modelKey !== k) return false;
        return true;
      },
      beginStripchatStreamRecording: function(mimeType, sourceUrl, width, height) {
        try { orig.beginStripchatStreamRecording(mimeType, sourceUrl, width, height); } catch (e) {}
        return true;
      },
      appendStripchatStreamRecordingChunk: function(base64) {
        try { orig.appendStripchatStreamRecordingChunk(base64); } catch (e) {}
        return true;
      }
    };
    // 原桥是 Proxy：get 永远返回新的 dispatcher，逐个赋值包装无效；
    // 必须整体换掉 window.Android，未 shim 的方法透传回原 Proxy。
    // （shim 与录制 JS 拼在同一次 evaluate 里，整体包 try/catch，
    //  换不掉时静默退化为原桥，绝不能把后面的录制 JS 一起拖垮。）
    window.Android = new Proxy({}, {
      get: function(target, method) {
        if (method === '__jbBridge') return true;
        if (method === '__javShim') return true;
        if (method === 'then') return undefined;
        if (typeof method !== 'string') return undefined;
        if (Object.prototype.hasOwnProperty.call(shimmed, method)) return shimmed[method];
        try { return orig[method]; } catch (e) { return undefined; }
      },
      set: function(target, method, value) {
        try { target[method] = value; } catch (e) {}
        return true;
      }
    });
    window.__javShimInstalled = true;
  } catch (e) {}
})();
""".trimIndent()

    /**
     * GeckoView 版主浏览器设置（2026-11-14 全量迁移，主页簇）。
     *
     * 映射关系（旧 WebView → 新 Gecko）：
     * ① 首页本地 HTML：loadDataWithBaseURL → Loader().data()（data: 载入，
     *    无 base URI 选项；HTML 里没有相对引用，已核对；对外地址由
     *    mapGeckoUrl 映射回 https://javbrowser.app/）；
     * ② addJavascriptInterface 桥 → GeckoBridge.attachBridge（方法名不变，
     *    参数变 JSONArray；无返回值的方法见 stripchatBridgeShimJs）；
     * ③ shouldOverrideUrlLoading → NavigationDelegate.onLoadRequest /
     *    onSubframeLoadRequest（DENY = 拦截）；
     * ④ shouldInterceptRequest → JavhdGeckoExtension.attachMediaSniffer
     *    （只观察、不阻断；动态广告拦截丢掉，见 onGeckoMediaCandidate）；
     * ⑤ onShowFileChooser → PromptDelegate.onFilePrompt；
     * ⑥ onShowCustomView/onHideCustomView → ContentDelegate.onFullScreen
     *    （Gecko 渲染全屏视频不需要另外的 custom view 容器）；
     * ⑦ document-start 脚本（WebViewCompat.addDocumentStartJavaScript）
     *    Gecko 没有等价物：只在 onPageStop 注入一次 stPatchScript
     *    （脚本自带 _javhd_patched 守卫；时序晚一级。onPageStart 时
     *    新文档还没就绪，GeckoEval 注了也会超时，还占住串行队列）；
     * ⑧ onCreateWindow 黑洞 popup → onNewSession 返回 null；
     * ⑨ 长按屏蔽 hitTestResult → ContentDelegate.onContextMenu；
     * ⑩ onPageCommitVisible 没有等价物：它的三处注入本来在 onPageFinished
     *    也会跑一遍，现在只在 onPageStop 跑一次（注入函数本身幂等）。
     * 其余一律不动：搜索解析、播放地址提取、选集、/api/play/ 桌面 UA、
     * VAST 放行（tsyndicate.com 不拦）均未动。
     */
    private fun setupGeckoSession() {
        // 把布局里的 WebView 原位换成 GeckoView（布局 XML 不动，沿用同一
        // 位置、约束与 id；旧 WebView 从未加载过，直接销毁）。
        val oldWebView = findViewById<android.webkit.WebView>(R.id.webView)
        val parent = oldWebView.parent as ViewGroup
        val index = parent.indexOfChild(oldWebView)
        val params = oldWebView.layoutParams
        parent.removeView(oldWebView)
        runCatching { oldWebView.destroy() }
        geckoView = GeckoView(this).apply {
            id = R.id.webView
            layoutParams = params
        }
        parent.addView(geckoView, index)

        val runtime = GeckoRuntimeProvider.get(this)
        // 桥扩展第一次使用前必须给过一次 Context（幂等；否则 evaluate /
        // getCookie 直接回 null/""）。attachBridge 内部的 ensureInstalled()
        // 用的是存下来的 applicationContext，这里先给一次最稳妥。
        GeckoBridgeExtension.ensureInstalled(this)
        geckoSession = GeckoSession()
        geckoSession.settings.apply {
            userAgentOverride = MAIN_UA
            allowJavascript = true
            // 与嵌頁播放器/嗅探一致：关掉 Gecko 内建追踪防护，页面能打开优先。
            useTrackingProtection = false
        }
        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                geckoPageTitle = title.orEmpty()
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (fullScreen) enterGeckoFullscreen() else exitGeckoFullscreen()
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                val targetUrl = when (element.type) {
                    GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE,
                    GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO,
                    GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> element.srcUri
                    else -> element.linkUri ?: element.srcUri
                } ?: return
                val host = runCatching { android.net.Uri.parse(targetUrl).host }.getOrNull()
                    ?.lowercase()?.trim().orEmpty()
                if (host.isEmpty()) return
                showBlockSourceDialog(targetUrl, host)
            }

            override fun onCrash(session: GeckoSession) {
                android.util.Log.w("GeckoMain", "内容进程崩溃：$geckoCurrentUrl")
                cancelLoadTimeout()
                progressBar.visibility = View.GONE
            }

            override fun onKill(session: GeckoSession) {
                android.util.Log.w("GeckoMain", "内容进程被系统结束：$geckoCurrentUrl")
                cancelLoadTimeout()
                progressBar.visibility = View.GONE
            }
        }
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                // onPageStart 在 onLocationChange 之前到：先更新地址，
                // onGeckoPageStart 里的 isOnLandingPage() 才能读到新页。
                mapGeckoUrl(url)?.let { geckoCurrentUrl = it }
                onGeckoPageStart(geckoCurrentUrl)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                progressBar.progress = progress
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (success) {
                    onGeckoPageStop(geckoCurrentUrl)
                } else {
                    android.util.Log.i("GeckoMain", "页面载入失败：$geckoCurrentUrl")
                    progressBar.visibility = View.GONE
                    cancelLoadTimeout()
                }
            }
        }
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val url = request.uri ?: return null
                // TARGET_WINDOW_NEW 的请求先过同一套拦截，允许的再交给
                // onNewSession（返回 null，window.open() 拿不到窗口）。
                if (onGeckoLoadRequest(url, isMainFrame = true)) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }

            override fun onSubframeLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val url = request.uri ?: return null
                if (onGeckoLoadRequest(url, isMainFrame = false)) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val mapped = mapGeckoUrl(url)
                if (mapped != null) {
                    geckoCurrentUrl = mapped
                    onGeckoLocationChange(mapped)
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                geckoCanGoBack = canGoBack
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                android.util.Log.i(
                    "GeckoMain",
                    "载入失败：${uri ?: geckoCurrentUrl} code=${error.code} category=${error.category}"
                )
                progressBar.visibility = View.GONE
                cancelLoadTimeout()
                // 返回 null：不显示 Gecko 默认错误页（旧 WebView 会显示
                // 系统错误页；这里先保持空白，真机验证时再决定要不要
                // 做一个本地错误页）。
                return null
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                // 封杀所有 popup：旧版 onCreateWindow 只在 supjav/javrate
                // 接一个黑洞 popup 丢弃导航，其余直接拒绝；Gecko 里
                // 返回 null 让 window.open() 失败，站方同标签 fallback
                // 能走的照走（与黑洞等效，用户可见行为一致）。
                android.util.Log.d("GeckoMain", "拦截新窗口：$uri")
                return null
            }
        }
        geckoSession.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
                onGeckoFilePrompt(prompt)

            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage(prompt.message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result.complete(prompt.dismiss())
                    }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
                    .show()
                return result
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage(prompt.message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result.complete(prompt.confirm())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        result.complete(prompt.dismiss())
                    }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
                    .show()
                return result
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val input = android.widget.EditText(this@MainActivity).apply {
                    setText(prompt.defaultValue.orEmpty())
                }
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage(prompt.message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result.complete(prompt.confirm(input.text.toString()))
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        result.complete(prompt.dismiss())
                    }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
                    .show()
                return result
            }
        }

        // JS 桥：方法名与原 @JavascriptInterface 完全一致，参数改走
        // JSONArray（见 GeckoBridge）；无返回值的方法由页面里的
        // stripchatBridgeShimJs 补（见 helpers）。
        GeckoBridge.attachBridge(geckoSession, mapOf(
            "onVideoFound" to handler@{ args ->
                val videoUrl = args.optString(0)

                runOnUiThread {
                    // The new sites use episode lists / nested players. Only their validated
                    // network request may replace the selected stream.
                    if (AdditionalSiteMedia.isPage(geckoCurrentUrl ?: currentPageUrl) || AvTodayMedia.isPage(geckoCurrentUrl ?: currentPageUrl)) return@runOnUiThread
                    val pageUrl = geckoCurrentUrl ?: currentPageUrl
                    if (RouVideoStreams.playlistForPage(pageUrl) != null && !RouVideoStreams.accepts(pageUrl, videoUrl)) {
                        android.util.Log.d("ROU_PLAYER", "ignored non-video or other-episode request=$videoUrl")
                        return@runOnUiThread
                    }
                    currentVideoUrl = videoUrl
                    if (currentVideoReferer == null &&
                        (pageUrl.contains("missav", ignoreCase = true) ||
                         pageUrl.contains("pigav.ws", ignoreCase = true) ||
                         pageUrl.contains("avtoday.io", ignoreCase = true) ||
                         pageUrl.contains("avjoy.me", ignoreCase = true) ||
                         pageUrl.contains("7mmtv", ignoreCase = true) ||
                         pageUrl.contains("7tv", ignoreCase = true) ||
                         pageUrl.contains("avple.tv", ignoreCase = true) ||
                         pageUrl.contains("whos.tv", ignoreCase = true) ||
                         pageUrl.contains("stripchat.com", ignoreCase = true))) {
                        currentVideoReferer = originForUrl(pageUrl)
                    }
                    if (isRouVideoUrl(pageUrl)) {
                        currentVideoReferer = originForUrl(pageUrl)
                    }
                    showPlayButtonIfAllowed()
                    maybeStartStripchatWatchRecording(videoUrl)
                }
            },
            "onAvTodayMedia" to handler@{ args ->
                val media = args.optString(0)
                val player = args.optString(1)
                val sourcePage = args.optString(2)

                runOnUiThread {
                    val page = geckoCurrentUrl ?: currentPageUrl
                    if (sourcePage == page && AvTodayMedia.accepts(page, player, media)) {
                        currentVideoUrl = media
                        currentVideoReferer = player
                        showPlayButtonIfAllowed()
                    }
                }
            },
            "onCrossSiteCodeDetected" to handler@{ args ->
                val code = args.optString(0)

                runOnUiThread {
                    updateCrossSiteCode(code)
                }
            },
            "onAdditionalEpisode" to handler@{ args ->
                val videoUrl = args.optString(0)

                runOnUiThread {
                    val page = geckoCurrentUrl ?: currentPageUrl
                    if ((AdditionalSiteMedia.hostMatches(page, "javday.app") ||
                            AdditionalSiteMedia.hostMatches(page, "javrate.com")) &&
                        AdditionalSiteMedia.accepts(page, videoUrl, page)) {
                        currentVideoUrl = videoUrl
                        currentVideoReferer = originForUrl(page)
                        showPlayButtonIfAllowed()
                    }
                }
            },
            "onAdditionalEpisodes" to handler@{ args ->
                val html = args.optString(0)
                val sourcePage = args.optString(1)

                val episodes = VideoExtractor.extractJavdayEpisodes(html, sourcePage)
                runOnUiThread {
                    val page = geckoCurrentUrl ?: currentPageUrl
                    if (page == sourcePage && episodes.isNotEmpty()) {
                        currentJavdayEpisodes = episodes
                        if (currentVideoUrl !in episodes.map { it.url }) {
                            currentVideoUrl = episodes.first().url
                        }
                        currentVideoReferer = originForUrl(page)
                        showPlayButtonIfAllowed()
                    }
                }
            },
            "onAdditionalPlayerFrame" to handler@{ args ->
                val embedUrl = args.optString(0)

                runOnUiThread {
                    val sourcePage = geckoCurrentUrl ?: currentPageUrl
                    if (AdditionalSiteMedia.hostMatches(sourcePage, "javrate.com")) {
                        if (JavrateStreams.isPlayer(embedUrl)) {
                            currentVideoUrl = embedUrl
                            currentVideoReferer = sourcePage
                            showPlayButtonIfAllowed()
                        }
                        return@runOnUiThread
                    }
                    if (!AdditionalSiteMedia.hostMatches(sourcePage, "supjav.com")) return@runOnUiThread
                    resolveSupjavSource(embedUrl, "")
                }
            },
            "onSupjavServerChanging" to handler@{ args ->

                runOnUiThread {
                    if (AdditionalSiteMedia.hostMatches(geckoCurrentUrl ?: currentPageUrl, "supjav.com")) clearSupjavSource()
                }
            },
            "onSupjavPlayerFrame" to handler@{ args ->
                val embedUrl = args.optString(0)
                val label = args.optString(1)

                runOnUiThread { resolveSupjavSource(embedUrl, label.take(80)) }
            },
            "onStripchatDebug" to handler@{ args ->
                val message = args.optString(0)

                android.util.Log.d("STRIPCHAT_HLS", message.take(1000))
            },
            "setStripchatPlayerOverlayVisible" to handler@{ args ->
                val visible = args.optBoolean(0)

                runOnUiThread {
                    setStripchatPlayerChromeHidden(visible)
                }
            },
            "toggleStripchatRecording" to handler@{ args ->

                runOnUiThread { toggleStripchatRecordingFromPlayer() }
            },
            "stopStripchatRecording" to handler@{ args ->

                runOnUiThread {
                    if (StripchatRecordingService.isRecording) {
                        startService(
                            Intent(this@MainActivity, StripchatRecordingService::class.java)
                                .setAction(StripchatRecordingService.ACTION_STOP)
                        )
                    }
                }
            },
            "isStripchatRecording" to handler@{ args ->
                // 无返回值：页面里的 stripchatBridgeShimJs 用推来的状态同步回答。
            },
            "beginStripchatStreamRecording" to handler@{ args ->
                val mimeType = args.optString(0)
                val sourceUrl = args.optString(1)
                val width = args.optInt(2)
                val height = args.optInt(3)

                val accepted = stripchatStreamRecordingStore.begin(mimeType, sourceUrl, width, height)
                if (accepted) runOnUiThread {
                    stripchatWatchSessionRecording = true
                    stripchatWatchSessionStarting = false
                    stripchatWatchStartRequestedAt = 0L
                    startStripchatRecordingStatusMonitor(
                        stripchatWatchSessionUrl.takeIf { stripchatWatchSessionActive && it.isNotBlank() }
                            ?: sourceUrl,
                    )
                    notifyStripchatWatchState()
                }
            },
            "appendStripchatStreamRecordingChunk" to handler@{ args ->
                val base64 = args.optString(0)
                stripchatStreamRecordingStore.appendBase64(base64)
            },
            "finishStripchatStreamRecording" to handler@{ args ->
                val success = args.optBoolean(0)
                val detail = args.optString(1)

                val result = stripchatStreamRecordingStore.finish(success, detail)
                runOnUiThread {
                    stripchatWatchSessionRecording = false
                    stripchatWatchSessionStarting = false
                    stripchatWatchStartRequestedAt = 0L
                    if (stripchatWatchSessionActive) {
                        startStripchatRecordingStatusMonitor(stripchatWatchSessionUrl)
                    } else {
                        stopStripchatRecordingStatusMonitor()
                    }
                    GeckoEval.runJavascript(geckoSession, 
                        "if(window.__javSetStripchatRecordingState) window.__javSetStripchatRecordingState(false);")
                    notifyStripchatWatchState()
                    if (detail.contains("下播")) handleStripchatAutoStopPrivacyState()
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }
            },
            "isStripchatStreamRecording" to handler@{ args ->
                // 无返回值：页面里的 stripchatBridgeShimJs 用推来的状态同步回答。
            },
            "beginStripchatWatchSession" to handler@{ args ->
                val sourceUrl = args.optString(0)

                val modelKey = stripchatModelKey(sourceUrl) ?: return@handler
                if (stripchatWatchSessionActive && stripchatWatchSessionModelKey != modelKey) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "已有其他主播正在監錄，請先停止原工作",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@handler
                }
                stripchatWatchSessionActive = true
                stripchatWatchSessionRecording = stripchatStreamRecordingStore.isActive() ||
                    StripchatRecordingService.isRecording
                stripchatWatchSessionStarting = false
                stripchatWatchSessionUrl = normalizeStripchatModelUrl(sourceUrl)
                stripchatWatchSessionModelKey = modelKey
                stripchatWatchStartRequestedAt = 0L
                runOnUiThread {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    androidx.core.content.ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, StripchatPrivacyKeepAliveService::class.java)
                            .setAction(StripchatPrivacyKeepAliveService.ACTION_START),
                    )
                    startStripchatRecordingStatusMonitor(stripchatWatchSessionUrl)
                    notifyStripchatWatchState()
                    Toast.makeText(
                        this@MainActivity,
                        if (stripchatWatchSessionRecording) LanguageManager.text(this@MainActivity, "已啟用持續監錄", "Continuous recording enabled")
                        else LanguageManager.text(this@MainActivity, "已啟用監錄，正在確認直播狀態", "Watch enabled; checking live status"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            "stopStripchatWatchSession" to handler@{ args ->

                runOnUiThread { stopStripchatWatchSession(manual = true) }
            },
            "isStripchatWatchSessionActive" to handler@{ args ->
                // 无返回值：页面里的 stripchatBridgeShimJs 用推来的状态同步回答。
            },
            "getStripchatWatchStateLabel" to handler@{ args ->
                val sourceUrl = args.optString(0)
                stripchatWatchStateLabelFor(sourceUrl)
            },
            "onStripchatWatchAutoStartFailed" to handler@{ args ->

                runOnUiThread {
                    if (!stripchatWatchSessionActive) return@runOnUiThread
                    stripchatWatchSessionStarting = false
                    stripchatWatchStartRequestedAt = 0L
                    notifyStripchatWatchState()
                    scheduleStripchatRecordingStatusCheck(20_000L)
                }
            },
            "enterStripchatPrivacyMode" to handler@{ args ->

                runOnUiThread { requestStripchatPrivacyMode() }
            },
            "isStripchatPrivacyMode" to handler@{ args ->
                // 无返回值：页面里的 stripchatBridgeShimJs 用推来的状态同步回答。
            },
            "onStripchatRecordingHealth" to handler@{ args ->
                val message = args.optString(0)

                android.util.Log.w("STRIPCHAT_RECORD_HEALTH", message.take(1000))
                if (message.contains("no source progress", ignoreCase = true) ||
                    message.contains("source video ended", ignoreCase = true)
                ) {
                    runOnUiThread { scheduleStripchatRecordingStatusCheck(0L) }
                }
            },
            "onStripchatRecordingError" to handler@{ args ->
                val stage = args.optString(0)
                val detail = args.optString(1)

                val safeStage = stage.takeIf { it in setOf("capability", "source", "canvas", "storage", "recorder", "start", "write", "overlay") }
                    ?: "recording"
                val safeDetail = detail.replace(Regex("https?://[^\\s\"<>]+"), "[URL]").take(240)
                android.util.Log.e("STRIPCHAT_RECORD_ERROR", "stage=$safeStage detail=$safeDetail")
                runOnUiThread {
                    val now = System.currentTimeMillis()
                    if (now - stripchatLastErrorToastAt >= 30_000L) {
                        stripchatLastErrorToastAt = now
                        Toast.makeText(this@MainActivity, LanguageManager.text(this@MainActivity,
                            "錄製失敗：$safeDetail", "Recording failed: $safeDetail"), Toast.LENGTH_LONG).show()
                    }
                }
            },
            "onStripchatLocationChanged" to handler@{ args ->
                val url = args.optString(0)

                runOnUiThread {
                    if (stripchatModelKey(url) == null) return@runOnUiThread
                    currentPageUrl = url
                    rememberStripchatModelUrl(url)
                    // Stripchat 是 SPA；先清空上一位主播的狀態，再查目前主播。
                    btnAddFavorite.text = "♡"
                    updateFavoriteIcon(url)
                    injectStripchatWatchButton(url)
                }
            },
            "openUnifiedSearch" to handler@{ args ->
                val query = args.optString(0)

                runOnUiThread {
                    // Only the app-owned landing page may invoke this entry point.
                    if (geckoCurrentUrl?.startsWith("https://javbrowser.app/") == true || isOnLandingPage()) {
                        startActivity(Intent(this@MainActivity, UnifiedSearchActivity::class.java).apply {
                            putExtra(UnifiedSearchActivity.EXTRA_QUERY, query.trim().take(500))
                        })
                    }
                }
            },
            "openSiteBrowse" to handler@{ args ->
                val siteId = args.optString(0)
                val browseUrl = args.optString(1)

                runOnUiThread {
                    // Only the app-owned landing page may invoke this entry point.
                    // Opens the site as a native list (standard-app style)
                    // instead of loading its homepage in the browser.
                    if (geckoCurrentUrl?.startsWith("https://javbrowser.app/") == true || isOnLandingPage()) {
                        startActivity(Intent(this@MainActivity, UnifiedSearchActivity::class.java).apply {
                            putExtra(UnifiedSearchActivity.EXTRA_BROWSE_SITE, siteId)
                            putExtra(UnifiedSearchActivity.EXTRA_BROWSE_URL, browseUrl)
                        })
                    }
                }
            },
            "navigateToUrl" to handler@{ args ->
                val url = args.optString(0)

                runOnUiThread {
                    // 首页“直接前往”的站（无原生解析器）：走 GeckoView 打开。
                    // 原 WebView 方案在迁移时把 ECH 拦截改成放行（明文
                    // SNI），在用户网络上直接被 RST、断网。Gecko 原生
                    // DoH + ECH，不明文 SNI，不会被 RST。
                    startActivity(
                        GeckoBrowserActivity.intent(
                            this@MainActivity,
                            domainConfig.updateUrlIfNeeded(url)
                        )
                    )
                }
            },
            "showHelpDialog" to handler@{ args ->

                runOnUiThread {
                    this@MainActivity.showHelpDialog()
                }
            },
            "loadLandingPage" to handler@{ args ->

                runOnUiThread {
                    this@MainActivity.loadLandingPage()
                }
            },
            "onPatchLoaded" to handler@{ args ->
                val frameUrl = args.optString(0)

                android.util.Log.d("JAVHD_DEBUG", "patch loaded in frame: $frameUrl")
                // video1.javhdporn.net/p/... 播放器頁面：設為 currentVideoUrl，點 ▶ 後在 WebView 內開啟
                if (frameUrl.contains("video1.javhdporn.net/p/")) {
                    runOnUiThread {
                        if (currentVideoUrl != frameUrl) {
                            currentVideoUrl = frameUrl
                            showPlayButtonIfAllowed()
                        }
                    }
                }
            },
            "onStreamtapeEmbedFound" to handler@{ args ->
                val payload = args.optString(0)

                // payload 格式：frameUrl|||interceptedUrl
                val parts = payload.split("|||", limit = 2)
                val frameUrl = if (parts.size == 2) parts[0] else ""
                val rawUrl   = if (parts.size == 2) parts[1] else payload
                val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl

                android.util.Log.d("JAVHD_DEBUG", "intercepted frame=$frameUrl url=$url")

                // 只接受來自 javhdporn.net 相關 frame 的 URL，過濾廣告 frame
                val isJavhdFrame = frameUrl.contains("javhdporn.net") || frameUrl.contains("video1.javhdporn")
                if (!isJavhdFrame) {
                    android.util.Log.d("JAVHD_DEBUG", "skipped (ad frame): $frameUrl")
                    return@handler
                }

                runOnUiThread {
                    when {
                        // m3u8 / mp4 / tapecontent → 直接可播放
                        url.contains(".m3u8") || url.contains(".mp4") ||
                        url.contains("tapecontent") || url.contains("get_video") -> {
                            if (currentVideoUrl != url) {
                                currentVideoUrl = url
                                // streamhls.click 不鎖 Referer，直接給外部播放器即可
                                // 不設 currentVideoReferer，playVideo() 走直連
                                currentVideoReferer = null
                                showPlayButtonIfAllowed()
                                android.util.Log.d("JAVHD_DEBUG", "direct video URL: $url")
                            }
                        }
                        // Streamtape embed URL → hidden WebView 取真實 URL
                        url.contains("streamtape") && url.contains("/e/") -> {
                            extractStreamtapeUrl(url) { videoUrl ->
                                android.util.Log.d("JAVHD_DEBUG", "video URL from embed: $videoUrl")
                                if (videoUrl != null) {
                                    currentVideoUrl = videoUrl
                                    showPlayButtonIfAllowed()
                                }
                            }
                        }
                        else -> {
                            android.util.Log.d("JAVHD_DEBUG", "unhandled url: $url")
                        }
                    }
                }
            },
            "saveJavCodes" to handler@{ args ->
                val codesJson = args.optString(0)

                try {
                    val arr = org.json.JSONArray(codesJson)
                    val codes = (0 until arr.length()).map { arr.getString(it) }
                    if (codes.isEmpty()) return@handler
                    runOnUiThread { processJavCodeQueue(codes, 0) }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "saveJavCodes: ${e.message}")
                }
            },
            "checkClipboard" to handler@{ args ->

                runOnUiThread { refreshHomeClipboard() }
            },
            "openClipboardLink" to handler@{ args ->

                runOnUiThread {
                    val link = pendingHomeClipboardLink ?: return@runOnUiThread
                    if (geckoCurrentUrl == "https://javbrowser.app/" && (!privacySettings.isLockEnabled || isUnlocked)) {
                        loadGeckoUrl(link.url)
                    }
                }
            },
            "dismissClipboardLink" to handler@{ args ->

                runOnUiThread {
                    if (geckoCurrentUrl == "https://javbrowser.app/") {
                        dismissedHomeClipboardUrl = pendingHomeClipboardLink?.url
                        pendingHomeClipboardLink = null
                    }
                }
            },
            "openTodayNew" to handler@{ args ->

                runOnUiThread {
                    startActivity(Intent(this@MainActivity, TodayNewActivity::class.java))
                }
            },
            "openPlaybackHistory" to handler@{ args ->

                runOnUiThread { startActivity(Intent(this@MainActivity, PlaybackHistoryActivity::class.java)) }
            },
            "openSalesRankings" to handler@{ args ->

                runOnUiThread {
                    startActivity(Intent(this@MainActivity, SalesRankingActivity::class.java))
                }
            },
            "onDomBlockPicked" to handler@{ args ->
                val selector = args.optString(0)
                val tag = args.optString(1)

                runOnUiThread { showDomBlockConfirmDialog(selector, tag) }
            },
        ))

        // 装扩展：① 11 个广告域的 webRequest 拦截是 runtime 级的，装上后
        // 主会话同样生效（VAST tsyndicate.com 永远放行）；② 嗅探器的
        // native 端口要靠它建立，早装早收到候选（装晚到的候选会丢）。
        JavhdGeckoExtension.ensureInstalled(this)
        JavhdGeckoExtension.attachMediaSniffer(geckoSession) { url ->
            onGeckoMediaCandidate(url)
        }

        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)
        // 显式激活会话：不依赖 Surface 路径（1.2.6 教训；hyper-browser 同款）。
        activateSession()
        // 布局完成后再断言一次激活（GeckoBrowserActivity 同款做法）。
        geckoView.post { activateSession() }
    }

    private fun isAd(url: String): Boolean {
        val adKeywords = listOf(
            // General ad networks
            "googleads", "doubleclick", "adservice", "googlesyndication",
            "adnxs", "advertising", "adsystem", "adtech", "adform",
            
            // Adult ad networks (common on these sites)
            "popunder", "juicyads", "exoclick", "trafficjunky", 
            "plugrush", "adsterra", "popcash", "propeller", "popads",
            "tsyndicate", "realsrv", "hilltopads", "adcash",
            
            // Tracking & Analytics that might show popups
            "pubmatic", "outbrain", "taboola", "smartadserver",
            "criteo", "bidvertiser", "vibrantmedia",
            
            // Keywords in URL paths
            "/ads/", "/ad/", "/banner/", "/popup/", "/popunder/",
            "banner", "sponsor", "tracking", "clicktrack",
            
            // Specific domains requested by user
            "myavlive.com", "snaptrckr.fun", "stripchat.com", 
            "adxadserv.com", "fluxtrck.site", "ra12.xyz"
        )
        
        // Check if URL contains any ad keyword
        return adKeywords.any { url.contains(it) }
    }

    private fun checkForVideo() {
        val url = geckoCurrentUrl ?: return
        
        // Only check on likely video pages to save resources
        // Jable: /videos/
        // MissAV: usually has UUID or just check all pages on missav domain
        
        // Inject JS to monitor video element for src changes and intercept network requests for rou.video
        if (isRouVideoUrl(url)) {
            val monitorJs = """
                (function() {
                    if (window.rouVideoMonitor) return; // Already monitoring
                    window.rouVideoMonitor = true;
                    
                    // 1. Monitor <video> tag (for older implementation)
                    var checkInterval = setInterval(function() {
                        var video = document.querySelector('video');
                        if (video && video.src && video.src.startsWith('http') && video.src.indexOf('.m3u8') !== -1) {
                            Android.onVideoFound(video.src);
                            clearInterval(checkInterval);
                        }
                    }, 1000); // Check every second
                    
                    // Stop checking after 30 seconds
                    setTimeout(function() { clearInterval(checkInterval); }, 30000);

                    function reportRouVideo(value) {
                        try {
                            var resolved = new URL(String(value || ''), location.href);
                            if (!resolved.searchParams.has('kind') &&
                                (/\/api\/hls\//i.test(resolved.pathname) || /\.m3u8(?:$|[?#])/i.test(resolved.href))) {
                                Android.onVideoFound(resolved.href);
                                return true;
                            }
                        } catch (e) {}
                        return false;
                    }

                    // 2. Intercept Fetch API；新版入口是沒有副檔名的 /api/hls/{id}
                    var originalFetch = window.fetch;
                    window.fetch = async function() {
                        var fetchUrl = arguments[0];
                        var urlStr = typeof fetchUrl === 'string' ? fetchUrl : (fetchUrl && fetchUrl.url ? fetchUrl.url : '');
                        reportRouVideo(urlStr);
                        return originalFetch.apply(this, arguments);
                    };

                    // 3. Intercept XHR to sniff
                    var originalXhrOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, requestUrl) {
                        reportRouVideo(requestUrl);
                        return originalXhrOpen.apply(this, arguments);
                    };
                })();
            """.trimIndent()
            GeckoEval.runJavascript(geckoSession, monitorJs)
        }

        if (url.contains("pigav.ws")) {
            val monitorJs = """
                (function() {
                    if (window.pigAvMonitor) return;
                    window.pigAvMonitor = true;

                    function normalizeUrl(url) {
                        return String(url || '')
                            .replace(/\\u0026/g, '&')
                            .replace(/&amp;/g, '&');
                    }

                    function reportFromText(text) {
                        if (!text) return false;
                        var source = normalizeUrl(String(text));
                        var m3u8 = source.match(/https?:\/\/[^"'\\s<>]+\.m3u8[^"'\\s<>]*/i);
                        if (m3u8 && m3u8[0]) {
                            Android.onVideoFound(m3u8[0]);
                            return true;
                        }
                        var mp4 = source.match(/https?:\/\/[^"'\\s<>]+\.mp4[^"'\\s<>]*/i);
                        if (mp4 && mp4[0]) {
                            Android.onVideoFound(mp4[0]);
                            return true;
                        }
                        return false;
                    }

                    var checkInterval = setInterval(function() {
                        var video = document.querySelector('video');
                        if (video && video.src && video.src.startsWith('http')) {
                            if (reportFromText(video.src)) clearInterval(checkInterval);
                            return;
                        }
                        var source = document.querySelector('video source[src]');
                        if (source && source.src && source.src.startsWith('http')) {
                            if (reportFromText(source.src)) clearInterval(checkInterval);
                        }
                    }, 1000);

                    setTimeout(function() { clearInterval(checkInterval); }, 30000);

                    var originalFetch = window.fetch;
                    if (originalFetch) {
                        window.fetch = function() {
                            var fetchUrl = arguments[0];
                            var urlStr = typeof fetchUrl === 'string' ? fetchUrl : (fetchUrl && fetchUrl.url ? fetchUrl.url : '');
                            reportFromText(urlStr);
                            return originalFetch.apply(this, arguments).then(function(resp) {
                                try {
                                    reportFromText(resp && resp.url ? resp.url : '');
                                    var clone = resp.clone();
                                    clone.text().then(function(text) {
                                        reportFromText(text);
                                    }).catch(function(){});
                                } catch (e) {}
                                return resp;
                            });
                        };
                    }

                    var originalXhrOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, requestUrl) {
                        this._pigavRequestUrl = requestUrl;
                        if (typeof requestUrl === 'string') reportFromText(requestUrl);
                        return originalXhrOpen.apply(this, arguments);
                    };

                    var originalXhrSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.send = function() {
                        this.addEventListener('load', function() {
                            try {
                                reportFromText(this.responseURL || this._pigavRequestUrl || '');
                                if (typeof this.responseText === 'string') reportFromText(this.responseText);
                            } catch (e) {}
                        });
                        return originalXhrSend.apply(this, arguments);
                    };

                    setTimeout(function() {
                        try {
                            var videoId = '';
                            var pathMatch = (location.pathname || '').match(/\/w\/([A-Za-z0-9_-]+)/);
                            if (pathMatch && pathMatch[1]) videoId = pathMatch[1];
                            if (!videoId) {
                                var ogVideo = document.querySelector('meta[property="og:video:url"]');
                                var embedUrl = ogVideo ? (ogVideo.getAttribute('content') || '') : '';
                                var embedMatch = embedUrl.match(/\/videos\/embed\/([A-Za-z0-9_-]+)/);
                                if (embedMatch && embedMatch[1]) videoId = embedMatch[1];
                            }
                            if (!videoId) return;
                            fetch(location.origin + '/api/v1/videos/' + encodeURIComponent(videoId))
                                .then(function(resp) { return resp.text(); })
                                .then(function(text) { reportFromText(text); })
                                .catch(function(){});
                        } catch (e) {}
                    }, 800);
                })();
            """.trimIndent()
            GeckoEval.runJavascript(geckoSession, monitorJs)
        }

        if (url.contains("avtoday.io", ignoreCase = true)) {
            val monitorJs = """
                (function() {
                    if (window.avTodayMonitor) return;
                    window.avTodayMonitor = true;

                    function normalizeUrl(value) {
                        if (!value) return '';
                        try { return new URL(value, location.href).href; } catch (e) { return String(value); }
                    }

                    function applyPreviewPosters(root) {
                        var scope = root && root.querySelectorAll ? root : document;
                        scope.querySelectorAll('video.preview-video').forEach(function(video) {
                            if (video.getAttribute('poster')) return;
                            var background = video.style.backgroundImage ||
                                window.getComputedStyle(video).backgroundImage || '';
                            var match = background.match(/url\(["']?([^"')]+)["']?\)/i);
                            if (!match || !match[1]) return;
                            var posterUrl = normalizeUrl(match[1]);
                            if (posterUrl) video.setAttribute('poster', posterUrl);
                        });
                    }

                    function report(value, playerUrl) {
                        var candidate = normalizeUrl(value);
                        if (/^https?:\/\/[^"'\s<>]+\.(?:m3u8|mp4)(?:[?#][^"'\s<>]*)?$/i.test(candidate)) {
                            Android.onAvTodayMedia(candidate, playerUrl, location.href);
                            return true;
                        }
                        return false;
                    }

                    function reportFromText(text, playerUrl) {
                        if (!text) return false;
                        var match = String(text)
                            .replace(/\\u0026/g, '&')
                            .replace(/&amp;/g, '&')
                            .match(/(?:var|let|const)\s+(?:video_link|m3u8_url)\s*=\s*['"](https?:\/\/[^"'\s<>]+\.(?:m3u8|mp4)(?:[?#][^"'\s<>]*)?)['"]/i);
                        return !!(match && report(match[1], playerUrl));
                    }

                    function inspectPlayerFrame() {
                        var frame = document.querySelector('iframe.video-frame, iframe[src*="/player?s="]');
                        if (!frame) return;
                        var src = normalizeUrl(frame.getAttribute('src') || frame.src || '');
                        if (!src) return;
                        try {
                            var player = frame.contentWindow;
                            if (player && (report(player.video_link, src) || report(player.m3u8_url, src))) return;
                            var doc = frame.contentDocument;
                            if (doc && reportFromText(doc.documentElement.innerHTML || '', src)) return;
                        } catch (e) {}

                        fetch(src, { credentials: 'include' })
                            .then(function(response) { return response.text(); })
                            .then(function(text) { if (frame.src === src) reportFromText(text, src); })
                            .catch(function() {});
                    }

                    applyPreviewPosters(document);
                    try {
                        new MutationObserver(function(mutations) {
                            mutations.forEach(function(mutation) {
                                mutation.addedNodes.forEach(function(node) {
                                    if (!node || node.nodeType !== 1) return;
                                    if (node.matches && node.matches('video.preview-video')) {
                                        applyPreviewPosters(node.parentNode || document);
                                    } else {
                                        applyPreviewPosters(node);
                                    }
                                });
                            });
                        }).observe(document.documentElement, { childList: true, subtree: true });
                    } catch (e) {}
                    inspectPlayerFrame();
                    document.addEventListener('load', function(e) {
                        if (e.target && e.target.matches && e.target.matches('iframe.video-frame')) inspectPlayerFrame();
                    }, true);
                    setTimeout(function() { applyPreviewPosters(document); }, 500);
                    setTimeout(function() { applyPreviewPosters(document); }, 1800);
                    setTimeout(inspectPlayerFrame, 1200);
                    setTimeout(inspectPlayerFrame, 3000);
                })();
            """.trimIndent()
            GeckoEval.runJavascript(geckoSession, monitorJs)
        }

        if (url.contains("stripchat.com", ignoreCase = true)) {
            val knownStripchatUrl = org.json.JSONObject.quote(lastStripchatModelUrl ?: url)
            val monitorJs = """
                (function() {
                    if (window.stripchatMonitor) return;
                    window.stripchatMonitor = true;
                    var knownModelUrl = $knownStripchatUrl;

                    function log(message) {
                        try { Android.onStripchatDebug(String(message)); } catch (e) {}
                        try { console.log('[STRIPCHAT_HLS] ' + message); } catch (e) {}
                    }
                    log('inject monitor url=' + location.href + ' known=' + knownModelUrl);

                    function normalizeUrl(value) {
                        return String(value || '')
                            .replace(/\\u0026/g, '&')
                            .replace(/&amp;/g, '&');
                    }

                    function looksLikePlayableHls(value) {
                        var url = normalizeUrl(value);
                        return /^https?:\/\/[^"'\\s<>]+\.m3u8[^"'\\s<>]*$/i.test(url) &&
                            /(?:doppiocdn|\/hls\/)/i.test(url);
                    }

                    function reportPlayableUrl(value, reason) {
                        var playableUrl = normalizeUrl(value);
                        if (looksLikePlayableHls(playableUrl)) {
                            log('found ' + reason + '=' + playableUrl);
                            Android.onVideoFound(playableUrl);
                            return true;
                        }
                        return false;
                    }

                    function usernameFromUrl(value) {
                        try {
                            var u = new URL(value, location.href);
                            var path = (u.pathname || '').replace(/^\/+|\/+$/g, '');
                            if (!path || path.indexOf('/') !== -1) return '';
                            if (/^(login|signup|favorites|models|search|category|tags)$/i.test(path)) return '';
                            return decodeURIComponent(path);
                        } catch (e) {
                            return '';
                        }
                    }

                    function usernameFromPath() {
                        var fromKnown = usernameFromUrl(knownModelUrl);
                        if (fromKnown) return fromKnown;
                        var fromPath = usernameFromUrl(location.href);
                        if (fromPath) return fromPath;
                        var canonical = document.querySelector('link[rel="canonical"]');
                        var fromCanonical = canonical ? usernameFromUrl(canonical.getAttribute('href') || '') : '';
                        if (fromCanonical) return fromCanonical;
                        var ogUrl = document.querySelector('meta[property="og:url"]');
                        return ogUrl ? usernameFromUrl(ogUrl.getAttribute('content') || '') : '';
                    }

                    function reportApiPayload(payload) {
                        try {
                            var user = payload && payload.user && payload.user.user ? payload.user.user : null;
                            var cam = payload && (payload.cam || (payload.model && payload.model.cam) || {});
                            var roomId = (user && user.id) || cam.roomId || cam.room_id || cam.id || '';
                            var status = (user && (user.status || user.isLive)) || cam.status || cam.isCamAvailable || '';
                            if (!roomId) {
                                log('api payload missing room id status=' + status);
                                return false;
                            }
                            var candidates = [
                                'https://edge-hls.doppiocdn.com/hls/' + roomId + '/master/' + roomId + '_auto.m3u8',
                                'https://edge-hls.doppiocdn.org/hls/' + roomId + '/master/' + roomId + '_auto.m3u8',
                                'https://edge-hls.doppiocdn.net/hls/' + roomId + '/master/' + roomId + '_auto.m3u8',
                                'https://edge-hls.doppiocdn.com/hls/' + roomId + '/master/' + roomId + '.m3u8',
                                'https://edge-hls.doppiocdn.org/hls/' + roomId + '/master/' + roomId + '.m3u8',
                                'https://edge-hls.doppiocdn.net/hls/' + roomId + '/master/' + roomId + '.m3u8'
                            ];
                            log('api roomId=' + roomId + ' status=' + status + ' candidate=' + candidates[0]);
                            Android.onVideoFound(candidates[0]);
                            return true;
                        } catch (e) {
                            log('api parse failed=' + e);
                            return false;
                        }
                    }

                    var originalFetch = window.fetch;
                    if (originalFetch) {
                        window.fetch = function() {
                            var fetchUrl = arguments[0];
                            var urlStr = typeof fetchUrl === 'string' ? fetchUrl : (fetchUrl && fetchUrl.url ? fetchUrl.url : '');
                            reportPlayableUrl(urlStr, 'request hls');
                            return originalFetch.apply(this, arguments).then(function(resp) {
                                try {
                                    reportPlayableUrl(resp && resp.url ? resp.url : '', 'response hls');
                                    var clone = resp.clone();
                                    clone.text().then(function(text) {
                                        if (text && (text.indexOf('"user"') !== -1 || text.indexOf('"cam"') !== -1)) {
                                            try { reportApiPayload(JSON.parse(text)); } catch (e) {}
                                        }
                                    }).catch(function(){});
                                } catch (e) {}
                                return resp;
                            });
                        };
                    }

                    var originalXhrOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, requestUrl) {
                        this._stripchatRequestUrl = requestUrl;
                        if (typeof requestUrl === 'string') reportPlayableUrl(requestUrl, 'xhr hls');
                        return originalXhrOpen.apply(this, arguments);
                    };

                    var originalXhrSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.send = function() {
                        this.addEventListener('load', function() {
                            try {
                                reportPlayableUrl(this.responseURL || this._stripchatRequestUrl || '', 'xhr response hls');
                                if (typeof this.responseText === 'string') {
                                    if (this.responseText.indexOf('"user"') !== -1 || this.responseText.indexOf('"cam"') !== -1) {
                                        try { reportApiPayload(JSON.parse(this.responseText)); } catch (e) {}
                                    }
                                }
                            } catch (e) {}
                        });
                        return originalXhrSend.apply(this, arguments);
                    };

                    setTimeout(function() {
                        var username = usernameFromPath();
                        if (!username) {
                            log('no model username from path=' + location.pathname);
                            return;
                        }
                        var apiBases = [location.origin, 'https://stripchat.com'];
                        function tryApi(index) {
                            if (index >= apiBases.length) return;
                            var base = apiBases[index];
                            var idUrl = base + '/api/front/users/user-ids/' + encodeURIComponent(username);
                            log('fetch model id=' + idUrl);
                            fetch(idUrl, { credentials: 'include' })
                                .then(function(resp) { return resp.json(); })
                                .then(function(idPayload) {
                                    var modelId = idPayload && idPayload.id;
                                    if (!modelId) throw new Error('model id missing');
                                    var camUrl = base + '/api/front/v2/models/' + encodeURIComponent(modelId) +
                                        '/cam?uniq=' + Math.floor(Math.random() * 1000000000);
                                    log('fetch cam=' + camUrl);
                                    return fetch(camUrl, { credentials: 'include' });
                                })
                                .then(function(resp) { return resp.json(); })
                                .then(function(payload) {
                                    if (!reportApiPayload(payload)) tryApi(index + 1);
                                })
                                .catch(function(e) { log('api fetch failed=' + e); tryApi(index + 1); });
                        }
                        tryApi(0);
                    }, 1200);
                })();
            """.trimIndent()
            GeckoEval.runJavascript(geckoSession, monitorJs)
        }

        // Also parse HTML to extract URL instantly for all supported sites
        GeckoEval.evaluate(geckoSession, "(function() { return document.documentElement.outerHTML; })();") { html ->
            // html is a JSON string, e.g. "\u003Chtml>..."
            // We need to unescape it.
            val rawHtml = unescapeJsString(html)

            var extractedUrl: String? = null

            if (url.contains("jable.tv")) {
                currentJableVttUrl = VideoExtractor.extractJableVttUrl(rawHtml)
                extractedUrl = VideoExtractor.extractJable(rawHtml)
            } else if (url.contains("missav")) {
                currentMissAvThumbnailConfig = VideoExtractor.extractMissAvThumbnailConfig(rawHtml)
                extractedUrl = VideoExtractor.extractMissAV(rawHtml)
                if (extractedUrl != null) {
                    currentVideoReferer = originForUrl(url) ?: domainConfig.getMissAvBaseUrl()
                    android.util.Log.d("MISSAV_PATH", "page=$url extracted=$extractedUrl referer=$currentVideoReferer")
                }
            } else if (isRouVideoUrl(url)) {
                extractedUrl = VideoExtractor.extractRouVideo(rawHtml, url)
                currentRouEpisodes = VideoExtractor.extractRouEpisodes(rawHtml)
                if (extractedUrl != null) {
                    currentVideoReferer = originForUrl(url)
                    android.util.Log.d("ROU_PLAYER", "page=$url extracted=$extractedUrl")
                }
            } else if (AdditionalSiteMedia.hostMatches(url, "javday.app")) {
                currentJavdayEpisodes = VideoExtractor.extractJavdayEpisodes(rawHtml, url)
                extractedUrl = currentJavdayEpisodes.firstOrNull()?.url
                if (extractedUrl != null) currentVideoReferer = originForUrl(url)
            } else if (url.contains("avjoy.me")) {
                extractedUrl = VideoExtractor.extractAvJoy(rawHtml)
                if (extractedUrl != null) {
                    currentVideoReferer = "https://avjoy.me/"
                }
            } else if (url.contains("pigav.ws")) {
                extractedUrl = VideoExtractor.extractPigAV(rawHtml)
                if (extractedUrl != null) {
                    currentVideoReferer = originForUrl(url) ?: "https://pigav.ws/"
                }
            } else if (url.contains("avtoday.io", ignoreCase = true)) {
                // The selected player supplies signed MP4 or HLS; page HTML also contains previews.
                extractedUrl = null
            } else if (url.contains("7mmtv", ignoreCase = true) ||
                url.contains("7tv", ignoreCase = true)) {
                // 7MMTV 詳情頁的 JSON-LD 會提供 Playmogo embed；頁面內其他卡片
                // 也含有預覽 MP4，故只接受主要嵌入播放器網址。
                val embed = Regex(
                    """(?:embedUrl|contentUrl)\s*[:=]\s*[\"'](https?://[^\"']*(?:playmogo|dood)[^\"']*)""",
                    RegexOption.IGNORE_CASE
                ).find(rawHtml)?.groupValues?.getOrNull(1)
                    ?: Regex("""https?://(?:www\.)?playmogo\.com/e/[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE)
                        .find(rawHtml)?.value
                if (!embed.isNullOrBlank()) {
                    extractedUrl = embed.replace("\\/", "/").replace("&amp;", "&")
                    currentVideoReferer = extractedUrl
                }
            } else if (url.contains("avple.tv", ignoreCase = true)) {
                // Avple 的主影片是動態分配 CDN，實際 HLS 由 shouldInterceptRequest
                // 以 cdnedge.live/file/avple-asserts/hls/ 精確攔截。
                GeckoEval.runJavascript(geckoSession, 
                    """
                    (function() {
                        if (window.__javAvpleMonitor) return;
                        window.__javAvpleMonitor = true;
                        function report(value) {
                            try {
                                var u = new URL(String(value || ''), location.href);
                                if (/\\.cdnedge\\.live$/i.test(u.hostname) &&
                                    /^\\/file\\/avple-asserts\\/hls\\//i.test(u.pathname) &&
                                    /\\.m3u8(?:$|[?#])/i.test(u.href)) {
                                    Android.onVideoFound(u.href);
                                }
                            } catch (e) {}
                        }
                        document.querySelectorAll('video, source').forEach(function(node) {
                            report(node.currentSrc || node.src || node.getAttribute('src') || '');
                        });
                        var oldFetch = window.fetch;
                        if (oldFetch) window.fetch = function(input, init) {
                            report(typeof input === 'string' ? input : (input && input.url));
                            return oldFetch.call(this, input, init).then(function(response) {
                                report(response && response.url);
                                return response;
                            });
                        };
                        var oldOpen = XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open = function(method, requestUrl) {
                            report(requestUrl);
                            return oldOpen.apply(this, arguments);
                        };
                    })();
                    """.trimIndent())
            } else if (url.contains("whos.tv", ignoreCase = true)) {
                // Whos.tv 詳情頁的主播放器以 data-preview-source/source 提供 HLS。
                // 只取 data-main="true" 的播放器，避免抓到推薦卡片的預覽串流。
                val mainPlayer = Regex(
                    """data-main=["']true["'][\s\S]*?data-preview-source=["'](https?://[^"']+\.m3u8[^"']*)""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ).find(rawHtml)?.groupValues?.getOrNull(1)
                    ?: Regex("""data-main=["']true["'][\s\S]*?<source[^>]+src=["'](https?://[^"']+\.m3u8[^"']*)""",
                        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(rawHtml)?.groupValues?.getOrNull(1)
                if (!mainPlayer.isNullOrBlank()) {
                    extractedUrl = mainPlayer.replace("\\/", "/").replace("&amp;", "&")
                    currentVideoReferer = originForUrl(url) ?: domainConfig.getWhosBaseUrl()
                }
            } else if (url.contains("javhdporn.net")) {
                // 全程監控：每秒掃描一次頁面所有 innerHTML / script 內容，
                // 找出 streamtape embed URL，持續 60 秒直到找到為止。
                // 同時也嘗試從 blob iframe 的 contentDocument 或 src 取得 URL。
                GeckoEval.runJavascript(geckoSession, """
                    (function(){
                        if(window._javhd_scan) return;
                        window._javhd_scan = true;
                        var maxTries = 60;
                        var tries = 0;
                        function scan() {
                            tries++;
                            if(tries > maxTries) return;

                            // 1. 掃描整個頁面 HTML（包含 script 內容）
                            var html = document.documentElement.innerHTML || '';
                            var m = html.match(/(?:https?:)?\/\/streamtape\.[a-z]+\/e\/[A-Za-z0-9_-]+/);
                            if(m){ Android.onStreamtapeEmbedFound(m[0].indexOf('http') === 0 ? m[0] : 'https:' + (m[0].indexOf('//') === 0 ? m[0] : '//' + m[0])); return; }

                            // 2. 試圖讀取 blob iframe contentDocument（有時同源可讀）
                            var frames = document.querySelectorAll('iframe');
                            for(var i=0;i<frames.length;i++){
                                try {
                                    var doc = frames[i].contentDocument || (frames[i].contentWindow && frames[i].contentWindow.document);
                                    if(doc){
                                        var inner = doc.documentElement ? doc.documentElement.innerHTML : '';
                                        var m2 = inner.match(/(?:https?:)?\/\/streamtape\.[a-z]+\/e\/[A-Za-z0-9_-]+/);
                                        if(m2){ Android.onStreamtapeEmbedFound('https:' + (m2[0].indexOf('//') === 0 ? m2[0] : '//' + m2[0])); return; }
                                    }
                                } catch(e){}
                                // 3. 嘗試讀取 iframe 的目前 src（blob 導航後可能已是 streamtape URL）
                                try {
                                    var fsrc = frames[i].src || '';
                                    if(fsrc.indexOf('streamtape') !== -1){
                                        Android.onStreamtapeEmbedFound(fsrc.indexOf('http') === 0 ? fsrc : 'https:' + fsrc);
                                        return;
                                    }
                                } catch(e){}
                            }

                            setTimeout(scan, 1000);
                        }
                        scan();
                    })();
                """.trimIndent())
                return@evaluate  // 非同步，此 branch 不用 extractedUrl
            }

            if (extractedUrl != null) {
                currentVideoUrl = extractedUrl
                showPlayButtonIfAllowed()
                // Toast.makeText(this, R.string.video_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 用隱藏 WebView 把 Streamtape embed URL 當主框架載入，
     * 攔截 shouldInterceptRequest 取得真實 mp4 URL。
     * Android 13+ cross-origin iframe 不觸發 shouldInterceptRequest，
     * 必須用此方式繞過。
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    /**
     * 用隐藏 GeckoSession 把 Streamtape embed URL 当主框架载入，
     * 嗅探取得真实 mp4 URL（替代旧隐藏 WebView + shouldInterceptRequest）。
     * Android 13+ cross-origin iframe 不触发拦截，必须用此方式绕过。
     *
     * 变化：① 拦截改嗅探（只观察、不阻断）；② 扩展嗅探器只上报路径以
     * .m3u8/.mp4 结尾的请求，streamtape get_video（无副档名）它看
     * 不到——点播放按钮后改由 DOM 轮询 video.currentSrc/src 补；
     * ③ 旧版返回空响应阻断加载以省流量的行为没了。
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun extractStreamtapeUrl(embedUrl: String, callback: (String?) -> Unit) {
        // 用 Handler 确保所有会话操作在 main thread
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var hiddenSession: GeckoSession? = null
        var found = false

        val timeoutRunnable = Runnable {
            if (!found) {
                found = true
                closeHiddenSession(hiddenSession)
                hiddenSession = null
                callback(null)
            }
        }

        handler.post {
            val session = openHeadlessSession()
            hiddenSession = session

            JavhdGeckoExtension.ensureInstalled(this)
            JavhdGeckoExtension.attachMediaSniffer(session) { candidate ->
                android.util.Log.d("JAVHD_HIDDEN", "request: $candidate")
                val isStreamtape = candidate.contains("streamtape") && candidate.contains("get_video")
                val isTapecontent = candidate.contains("tapecontent") && candidate.contains(".mp4")
                if ((isStreamtape || isTapecontent) && !found) {
                    found = true
                    handler.removeCallbacks(timeoutRunnable)
                    val captured = candidate
                    handler.post {
                        closeHiddenSession(hiddenSession)
                        hiddenSession = null
                        callback(captured)
                    }
                }
            }

            session.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    android.util.Log.d("JAVHD_HIDDEN", "onPageStop success=$success")
                    // 等 2 秒让 JS 生成播放按钮，再模拟点击
                    handler.postDelayed({
                        if (!found) {
                            // DEBUG: 先印出页面上的元素
                            GeckoEval.evaluate(session, """
                                (function(){
                                    var els = document.querySelectorAll('*[id],[class*="play"],[class*="btn"],[class*="overlay"]');
                                    var ids = [];
                                    for(var i=0;i<Math.min(els.length,20);i++){
                                        ids.push(els[i].tagName+':'+(els[i].id||els[i].className).substring(0,30));
                                    }
                                    return JSON.stringify(ids);
                                })();
                            """.trimIndent()) { elementsRaw ->
                                android.util.Log.d("JAVHD_HIDDEN", "Elements: $elementsRaw")
                            }
                            GeckoEval.evaluate(session, """
                                (function(){
                                    var selectors = ['#overlay','#mainvideo','video','[id*="play"]','[class*="play"]','[class*="btn"]'];
                                    for(var i=0;i<selectors.length;i++){
                                        var el=document.querySelector(selectors[i]);
                                        if(el){el.click();return 'clicked:'+selectors[i];}
                                    }
                                    return 'no element found';
                                })();
                            """.trimIndent()) { clickResult ->
                                android.util.Log.d("JAVHD_HIDDEN", "Click result: $clickResult")
                            }
                            // 再等 2 秒读 video 元素：点播放后播放器会把
                            // get_video 地址放到 video.src（嗅探器看不到
                            // 无副档名的 get_video 请求，这里是唯一补位）。
                            handler.postDelayed({
                                if (found) return@postDelayed
                                GeckoEval.evaluate(session,
                                    "(function(){var v=document.querySelector('video');return (v&&(v.currentSrc||v.src))||'';})();"
                                ) { raw ->
                                    val src = runCatching {
                                        org.json.JSONTokener(raw).nextValue() as? String
                                    }.getOrNull().orEmpty()
                                    if (src.isNotBlank() && !found) {
                                        found = true
                                        handler.removeCallbacks(timeoutRunnable)
                                        closeHiddenSession(hiddenSession)
                                        hiddenSession = null
                                        callback(src)
                                    }
                                }
                            }, 2000)
                        }
                    }, 2000)
                }
            }

            android.util.Log.d("JAVHD_DEBUG", "Loading hidden Gecko session: $embedUrl")
            session.loadUri(embedUrl)
            // 10 秒逾时
            handler.postDelayed(timeoutRunnable, 10000)
        }
    }


    private fun unescapeJsString(jsString: String): String {
        // Remove surrounding quotes
        var s = jsString
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        try {
            return org.json.JSONTokener(jsString).nextValue().toString()
        } catch (e: Exception) {
            return s.replace("\\u003C", "<").replace("\\\"", "\"").replace("\\\\", "\\")
        }
    }

    /**
     * 解析 7MMTV 使用的 Playmogo/Dood 類嵌入播放器。
     * 播放網址由 pass_md5 產生短效 token，所以只在使用者按下播放時解析。
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    /**
     * 解析 7MMTV 使用的 Playmogo/Dood 类嵌入播放器。
     * 播放网址由 pass_md5 产生短效 token，所以只在使用者按下播放时解析。
     * （隐藏 WebView → 隐藏 GeckoSession；拦截改嗅探 + DOM 扫描。）
     *
     * 注意：扩展嗅探器只上报 .m3u8/.mp4 结尾的请求，cloudatacdn /
     * doodcdn 上无副档名的地址它看不到——那部分只剩 onPageStop 后的
     * DOM 扫描（video/source/data-src），与旧版拦截互补。
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun resolvePlaymogoVideo(embedUrl: String, callback: (String?) -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var hiddenSession: GeckoSession? = null
        var finished = false

        fun finish(result: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            closeHiddenSession(hiddenSession)
            hiddenSession = null
            callback(result)
        }

        handler.post {
            val session = openHeadlessSession()
            hiddenSession = session

            val timeout = Runnable { finish(null) }
            handler.postDelayed(timeout, 15_000)

            JavhdGeckoExtension.ensureInstalled(this)
            JavhdGeckoExtension.attachMediaSniffer(session) { candidate ->
                val lower = candidate.lowercase()
                val host = runCatching { android.net.Uri.parse(candidate).host }
                    .getOrNull().orEmpty().lowercase()
                val isPlayable = candidate.startsWith("http", ignoreCase = true) &&
                    !lower.contains("pass_md5") &&
                    (lower.contains(".mp4") || host.contains("cloudatacdn") || host.contains("doodcdn"))
                if (isPlayable) {
                    handler.post { finish(candidate) }
                }
            }

            session.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    handler.postDelayed({
                        if (finished) return@postDelayed
                        GeckoEval.evaluate(session,
                            """
                            (function() {
                                var nodes = document.querySelectorAll('video, source, [data-src]');
                                var urls = [];
                                nodes.forEach(function(node) {
                                    ['src', 'data-src'].forEach(function(name) {
                                        var value = node.getAttribute && node.getAttribute(name);
                                        if (value) urls.push(value);
                                    });
                                    if (node.currentSrc) urls.push(node.currentSrc);
                                });
                                var buttons = document.querySelectorAll('#play, .play, [class*=play], [id*=play]');
                                if (buttons.length) { try { buttons[0].click(); } catch (e) {} }
                                return JSON.stringify(urls);
                            })();
                            """.trimIndent()
                        ) { raw ->
                            val decoded = runCatching { org.json.JSONTokener(raw).nextValue().toString() }
                                .getOrDefault("")
                            val array = runCatching { org.json.JSONArray(decoded) }.getOrNull()
                            val direct = (0 until (array?.length() ?: 0)).asSequence()
                                .mapNotNull { array?.optString(it) }
                                .map { it.replace("\\/", "/") }
                                .firstOrNull {
                                    it.startsWith("http", ignoreCase = true) &&
                                        !it.contains("pass_md5", ignoreCase = true) &&
                                        (it.contains(".mp4", ignoreCase = true) ||
                                            it.contains("cloudatacdn", ignoreCase = true) ||
                                            it.contains("doodcdn", ignoreCase = true))
                                }
                            if (direct != null) finish(direct)
                        }
                    }, 900)
                }
            }
            session.loadUri(embedUrl)
        }
    }

    private fun injectStripchatWatchButton(pageUrl: String?) {
        if (stripchatModelKey(pageUrl.orEmpty()) == null) return
        // 先装 bridge shim，再注入监录按钮 JS（shim 要在 asset JS 的
        // Android.* 同步调用之前就位；见 stripchatBridgeShimJs）。
        GeckoEval.runJavascript(geckoSession, stripchatBridgeShimJs())
        pushStripchatBridgeState()
        GeckoEval.evaluate(geckoSession, stripchatWatchControlsJs) { result ->
            android.util.Log.i("STRIPCHAT_WATCH_UI", "control mount=$result")
        }
    }

    private fun notifyStripchatWatchState() {
        if (!::geckoSession.isInitialized) return
        val recording = stripchatWatchSessionRecording ||
            stripchatStreamRecordingStore.isActive() ||
            StripchatRecordingService.isRecording
        // 状态变化时先把新状态推到页面（shim 的 getter 读它），再刷新按钮。
        pushStripchatBridgeState()
        GeckoEval.runJavascript(geckoSession,
            """
            (function() {
                try {
                    if (window.__javSetStripchatRecordingState) {
                        window.__javSetStripchatRecordingState(${if (recording) "true" else "false"});
                    }
                    if (window.__javRefreshStripchatWatchUi) window.__javRefreshStripchatWatchUi();
                } catch (e) {}
            })();
            """.trimIndent())
    }


    private fun originForUrl(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "$scheme://$host$port/"
        } catch (e: Exception) {
            null
        }
    }

    private fun setupPlayButton() {
        btnPlay.setOnClickListener {
            // ── JavDB 影片頁：存入書籤 ───────────────────────────
            if (isOnJavDbVideoPage) {
                saveJavDbPageAsBookmark()
                return@setOnClickListener
            }
            // ── JavTrailers 影片頁：存入書籤 ─────────────────────
            if (isOnJavTrailersVideoPage) {
                saveJavTrailersPageAsBookmark()
                return@setOnClickListener
            }
            // ── 原有播放邏輯 ──────────────────────────────────────
            val pageUrl = geckoCurrentUrl ?: currentPageUrl
            RouVideoStreams.playlistForPage(pageUrl)?.let { playlist ->
                currentVideoUrl = playlist
                currentVideoReferer = originForUrl(pageUrl)
                playVideo(playlist)
                return@setOnClickListener
            }
            currentVideoUrl?.let { url ->
                if ((url.contains("playmogo.com", ignoreCase = true) ||
                        url.contains("dood", ignoreCase = true)) &&
                    (currentPageUrl.contains("7mmtv", ignoreCase = true) ||
                        currentPageUrl.contains("7tv", ignoreCase = true))) {
                    btnPlay.isEnabled = false
                    btnPlay.text = "…"
                    resolvePlaymogoVideo(url) { resolved ->
                        runOnUiThread {
                            btnPlay.isEnabled = true
                            btnPlay.text = "▶"
                            if (resolved.isNullOrBlank()) {
                                Toast.makeText(this, "7MMTV 播放網址解析失敗", Toast.LENGTH_SHORT).show()
                            } else {
                                currentVideoUrl = resolved
                                currentVideoReferer = url
                                playVideo(resolved)
                            }
                        }
                    }
                    return@let
                }
                when {
                    url.contains("streamhls") || url.contains(".m3u8") -> playVideo(url)
                    url.contains("video1.javhdporn.net/p/") -> loadGeckoUrl(url)
                    else -> playVideo(url)
                }
            }
        }
    }

    private fun saveJavDbPageAsBookmark() {
        val pageUrl = geckoCurrentUrl ?: return

        // Toggle：已收藏則移除
        val favorites = favoritesManager.getFavorites()
        val existing = favorites.find { it.url == pageUrl }
        if (existing != null) {
            favoritesManager.removeFavorite(pageUrl)
            Toast.makeText(this, "已從書籤移除", Toast.LENGTH_SHORT).show()
            updateFavoriteIcon(pageUrl)
            return
        }

        btnPlay.text = "⏳"
        btnPlay.isEnabled = false
        btnAddFavorite.isEnabled = false

        // 從當前 JavDB DOM 抽取完整資料（與 JavDbWebViewScraper.extractDetailData 一致）
        val js = """
            (function() {
                var codeEl = document.querySelector('.title strong:not(.current-title)');
                var currentTitleEl = document.querySelector('.current-title');
                var coverImg = document.querySelector('.video-cover');
                function findStrong(lbl) {
                    var els = document.querySelectorAll('strong');
                    for (var i = 0; i < els.length; i++) {
                        if (els[i].textContent.indexOf(lbl) !== -1) return els[i];
                    }
                    return null;
                }
                function metaVal() {
                    for (var i = 0; i < arguments.length; i++) {
                        var el = findStrong(arguments[i]);
                        if (el && el.nextElementSibling) return el.nextElementSibling.textContent.trim();
                    }
                    return '';
                }
                function metaList() {
                    for (var i = 0; i < arguments.length; i++) {
                        var el = findStrong(arguments[i]);
                        if (el && el.nextElementSibling) {
                            var links = el.nextElementSibling.querySelectorAll('a');
                            var r = [];
                            for (var k = 0; k < links.length; k++) {
                                var t = links[k].textContent.trim();
                                if (t) r.push(t);
                            }
                            if (r.length) return r;
                        }
                    }
                    return [];
                }
                function extractActors() {
                    for (var i = 0; i < arguments.length; i++) {
                        var el = findStrong(arguments[i]);
                        if (!el || !el.nextElementSibling) continue;
                        var links = el.nextElementSibling.querySelectorAll('a');
                        var r = [];
                        for (var k = 0; k < links.length; k++) {
                            var name = links[k].textContent.trim();
                            if (!name) continue;
                            var sym = links[k].nextElementSibling;
                            var s = (sym && sym.classList && sym.classList.contains('symbol'))
                                ? sym.textContent.trim() : '';
                            r.push(name + s);
                        }
                        if (r.length) return r;
                    }
                    return [];
                }
                return {
                    code:        codeEl ? codeEl.textContent.trim() : '',
                    title:       currentTitleEl ? currentTitleEl.textContent.trim() : '',
                    coverUrl:    coverImg ? (coverImg.getAttribute('src') || '') : '',
                    releaseDate: metaVal('\u65E5\u671F:', '\u767C\u884C\u65E5\u671F:', 'Released Date:'),
                    rating:      metaVal('\u8A55\u5206:', 'Rating:').trim(),
                    maker:       metaVal('\u7247\u5546:', '\u767C\u884C\u5546:', 'Maker:'),
                    series:      metaVal('\u7CFB\u5217:', 'Series:'),
                    genres:      metaList('\u985E\u5225:', '\u6A19\u7C64:', 'Tags:'),
                    actors:      extractActors('\u6F14\u54E1:', '\u5973\u512A:', 'Actor(s):')
                };
            })()
        """.trimIndent()

        GeckoEval.evaluate(geckoSession, js) { result ->
            runOnUiThread {
                btnPlay.isEnabled = true
                btnAddFavorite.isEnabled = true
                btnPlay.text = "📚 書籤"
                if (result == null || result == "null") {
                    Toast.makeText(this, "無法取得頁面資料", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                try {
                    val obj = org.json.JSONObject(result)
                    val code     = obj.optString("code", "").trim()
                    val title    = obj.optString("title", "").trim()
                    val coverUrl = obj.optString("coverUrl", "").trim()
                    val genres   = mutableListOf<String>().also { list ->
                        val arr = obj.optJSONArray("genres")
                        if (arr != null) for (i in 0 until arr.length()) list.add(arr.getString(i))
                    }
                    val actors   = mutableListOf<String>().also { list ->
                        val arr = obj.optJSONArray("actors")
                        if (arr != null) for (i in 0 until arr.length()) list.add(arr.getString(i))
                    }

                    val displayTitle = when {
                        code.isNotEmpty() && title.isNotEmpty() -> "$code $title".trim()
                        code.isNotEmpty() -> code
                        else -> currentPageTitle() ?: pageUrl
                    }

                    val added = favoritesManager.addFavorite(
                        displayTitle,
                        pageUrl,
                        coverUrl.ifEmpty { null },
                        javCode = code.takeIf { it.isNotEmpty() }
                    )
                    if (!added) {
                        Toast.makeText(this, "已在書籤中", Toast.LENGTH_SHORT).show()
                        updateFavoriteIcon(pageUrl)
                        return@runOnUiThread
                    }

                    if (code.isNotEmpty()) {
                        val detail = JavVideoDetail(
                            code      = code,
                            title     = title,
                            coverUrl  = coverUrl,
                            date      = obj.optString("releaseDate", ""),
                            duration  = "",
                            maker     = obj.optString("maker", ""),
                            series    = obj.optString("series", ""),
                            rating    = obj.optString("rating", ""),
                            genres    = genres,
                            actors    = actors,
                            detailUrl = pageUrl
                        )
                        favoritesManager.updateFavoriteDetail(pageUrl, detail)
                        CrossSiteChecker.checkAll(this, code, pageUrl) { found ->
                            if (found.isNotEmpty()) favoritesManager.updateRelatedUrls(pageUrl, found)
                        }
                    }
                    Toast.makeText(this, "📚 已加入書籤：$displayTitle", Toast.LENGTH_SHORT).show()
                    updateFavoriteIcon(pageUrl)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "saveJavDbPageAsBookmark: ${e.message}")
                    Toast.makeText(this, "書籤儲存失敗", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveJavTrailersPageAsBookmark() {
        val pageUrl = geckoCurrentUrl ?: return

        // Toggle：已收藏則移除
        val favorites = favoritesManager.getFavorites()
        if (favorites.any { it.url == pageUrl }) {
            favoritesManager.removeFavorite(pageUrl)
            Toast.makeText(this, "已從書籤移除", Toast.LENGTH_SHORT).show()
            updateFavoriteIcon()
            return
        }

        btnPlay.text = "⏳"
        btnPlay.isEnabled = false

        // 從頁面 DOM 抽取標題 + 封面
        val js = """
            (function(){
                var h1 = document.querySelector('h1.lead');
                var title = h1 ? h1.textContent.trim() : (document.title || '');
                var cover = '';
                var largeImg = document.querySelector('img[data-src*="pl.jpg"]')
                            || document.querySelector('img[src*="pl.jpg"]');
                if (largeImg) cover = largeImg.getAttribute('data-src') || largeImg.getAttribute('src') || '';
                if (!cover) {
                    var thumb = document.querySelector('#thumbnailContainer img');
                    if (thumb) cover = thumb.getAttribute('src') || thumb.getAttribute('data-src') || '';
                }
                return { title: title, cover: cover };
            })()
        """.trimIndent()

        GeckoEval.evaluate(geckoSession, js) { raw ->
            runOnUiThread {
                btnPlay.isEnabled = true
                btnPlay.text = "📚 書籤"
                try {
                    val obj = org.json.JSONObject(raw ?: "{}")
                    val pageTitle = obj.optString("title", "").trim()
                    val coverUrl  = obj.optString("cover", "").trim()

                    // 從標題萃取番號；若失敗則從 URL 路徑推導
                    val javCode: String? = JavDbScraper.extractFc2Code(pageUrl, pageTitle)
                        ?: JavDbScraper.extractJavCode(pageTitle)
                        ?: run {
                            // URL: /ja/video/mida00271 → MIDA-271
                            val seg = pageUrl.substringAfterLast("/video/").substringBefore("?").lowercase()
                            val m = Regex("^([a-z]+?)(\\d+)$").find(seg)
                            if (m != null) {
                                val num = m.groupValues[2].trimStart('0').ifEmpty { "0" }
                                JavDbScraper.normalizeJavCode("${m.groupValues[1].uppercase()}-$num")
                            } else null
                        }

                    val displayTitle = pageTitle.ifEmpty { javCode ?: pageUrl }
                    val added = favoritesManager.addFavorite(
                        displayTitle, pageUrl, coverUrl.ifEmpty { null }, javCode = javCode
                    )
                    if (!added) {
                        Toast.makeText(this, "已在書籤中", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    Toast.makeText(this, "📚 已加入書籤：$displayTitle", Toast.LENGTH_SHORT).show()
                    updateFavoriteIcon()

                    // 背景：JavDB enrichment + CrossSiteChecker
                    if (javCode != null) {
                        CrossSiteChecker.checkAll(this, javCode, pageUrl) { found ->
                            if (found.isNotEmpty()) {
                                favoritesManager.updateRelatedUrls(pageUrl, found)
                                androidx.localbroadcastmanager.content.LocalBroadcastManager
                                    .getInstance(this)
                                    .sendBroadcast(android.content.Intent(FavoritesActivity.ACTION_FAVORITE_ENRICHED))
                            }
                        }
                        enrichMetadataForCode(javCode) { detail ->
                            if (detail != null) {
                                favoritesManager.updateFavoriteDetail(pageUrl, detail)
                                runOnUiThread {
                                    Toast.makeText(this,
                                        "已補充 $javCode 資料：${detail.actors.joinToString("、").ifEmpty { "無演員" }}",
                                        Toast.LENGTH_LONG).show()
                                    androidx.localbroadcastmanager.content.LocalBroadcastManager
                                        .getInstance(this)
                                        .sendBroadcast(android.content.Intent(FavoritesActivity.ACTION_FAVORITE_ENRICHED))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "saveJavTrailersPageAsBookmark: ${e.message}")
                    Toast.makeText(this, "書籤儲存失敗", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun savePigAvPageAsBookmark() {
        val pageUrl = geckoCurrentUrl ?: return

        val favorites = favoritesManager.getFavorites()
        val existing = favorites.find { it.url == pageUrl }
        if (existing != null) {
            favoritesManager.removeFavorite(pageUrl)
            Toast.makeText(this, "已從收藏移除", Toast.LENGTH_SHORT).show()
            btnAddFavorite.text = "♡"
            return
        }

        val js = """
            (function() {
                function meta(selector) {
                    var el = document.querySelector(selector);
                    return el ? (el.getAttribute('content') || '').trim() : '';
                }
                function text(selector) {
                    var el = document.querySelector(selector);
                    return el ? (el.textContent || '').trim() : '';
                }
                function normalizeUrl(value) {
                    if (!value) return '';
                    try { return new URL(value, location.href).href; } catch (e) { return value; }
                }
                function scoreCover(url) {
                    if (!url) return -9999;
                    var value = normalizeUrl(url);
                    var score = 0;
                    if (/\/thumbnails?\//i.test(value)) score += 1000;
                    if (/lazy-static\/thumbnails\//i.test(value)) score += 1200;
                    if (/vjs-poster/i.test(value)) score += 50;
                    if (/\.jpe?g(?:\?|$)/i.test(value)) score += 20;
                    if (/\/avatars?\//i.test(value)) score -= 2000;
                    if (/\/client\//i.test(value)) score -= 2000;
                    if (/default|placeholder|blank/i.test(value)) score -= 500;
                    return score;
                }
                function collectCandidates() {
                    var values = [];
                    function push(value) {
                        var normalized = normalizeUrl(value);
                        if (normalized && values.indexOf(normalized) === -1) values.push(normalized);
                    }
                    [
                        'picture.vjs-poster img',
                        'div.vjs-poster picture.vjs-poster img',
                        '.vjs-poster img',
                        'video[poster]',
                        'meta[property="og:image"]',
                        'meta[name="twitter:image"]'
                    ].forEach(function(selector) {
                        document.querySelectorAll(selector).forEach(function(el) {
                            push(el.currentSrc || el.src || el.getAttribute('poster') || el.getAttribute('content') || '');
                        });
                    });
                    document.querySelectorAll('img[src], source[srcset]').forEach(function(el) {
                        var value = el.currentSrc || el.src || el.getAttribute('srcset') || '';
                        if (/thumbnails?/i.test(value)) push(value.split(/\s+/)[0]);
                    });
                    return values;
                }
                var title = text('h1.video-info-name') || meta('meta[property="og:title"]') || document.title || '';
                title = title.replace(/\s*-\s*PIGAV[^-]*$/i, '').trim();
                var coverCandidates = collectCandidates();
                var cover = coverCandidates
                    .map(function(value) { return { value: value, score: scoreCover(value) }; })
                    .sort(function(a, b) { return b.score - a.score; })
                    .map(function(item) { return item.value; })
                    .find(function(value) { return scoreCover(value) > 0; }) || '';
                var tags = Array.prototype.slice.call(
                    document.querySelectorAll('.attribute.attribute-tags a.attribute-value')
                ).map(function(el) {
                    return (el.textContent || '').trim();
                }).filter(function(text) { return text.length > 0; });
                return {
                    title: title,
                    coverUrl: cover,
                    tags: tags,
                    debugCoverCandidates: coverCandidates
                };
            })()
        """.trimIndent()

        btnAddFavorite.isEnabled = false
        GeckoEval.evaluate(geckoSession, js) { raw ->
            runOnUiThread {
                btnAddFavorite.isEnabled = true
                try {
                    val obj = org.json.JSONObject(raw ?: "{}")
                    val title = obj.optString("title", "").trim().ifEmpty { currentPageTitle() ?: pageUrl }
                    val coverUrl = obj.optString("coverUrl", "").trim()
                    android.util.Log.d("PIGAV_BOOKMARK", "page=$pageUrl cover=$coverUrl raw=${obj.optJSONArray("debugCoverCandidates")}")
                    val tags = mutableListOf<String>().also { list ->
                        val arr = obj.optJSONArray("tags")
                        if (arr != null) for (i in 0 until arr.length()) list.add(arr.getString(i).trim())
                    }.filter { it.isNotEmpty() }

                    val javCode = JavDbScraper.extractFc2Code(pageUrl, title, tags.joinToString(" "))
                        ?: JavDbScraper.extractJavCode(title)
                        ?: tags.firstNotNullOfOrNull { tag -> JavDbScraper.extractJavCode(tag) }

                    val added = favoritesManager.addFavorite(
                        title = title,
                        url = pageUrl,
                        thumbnailUrl = coverUrl.ifEmpty { null },
                        javCode = javCode
                    )

                    if (!added) {
                        Toast.makeText(this, "已在書籤中", Toast.LENGTH_SHORT).show()
                        btnAddFavorite.text = "♥"
                        return@runOnUiThread
                    }

                    if (tags.isNotEmpty()) {
                        val genreTags = tags.filterNot { tag ->
                            JavDbScraper.extractJavCode(tag) != null
                        }
                        favoritesManager.updateFavoriteDetail(
                            pageUrl,
                            JavVideoDetail(
                                code = javCode ?: "",
                                title = title,
                                coverUrl = coverUrl,
                                date = "",
                                duration = "",
                                maker = "",
                                series = "",
                                rating = "",
                                genres = genreTags,
                                actors = emptyList(),
                                detailUrl = pageUrl
                            )
                        )
                    }

                    Toast.makeText(this, "已加入收藏", Toast.LENGTH_SHORT).show()
                    btnAddFavorite.text = "♥"

                    if (javCode != null) {
                        CrossSiteChecker.checkAll(this, javCode, pageUrl) { found ->
                            if (found.isNotEmpty()) {
                                favoritesManager.updateRelatedUrls(pageUrl, found)
                                androidx.localbroadcastmanager.content.LocalBroadcastManager
                                    .getInstance(this)
                                    .sendBroadcast(android.content.Intent(FavoritesActivity.ACTION_FAVORITE_ENRICHED))
                            }
                        }

                        enrichMetadataForCode(javCode) { detail ->
                            if (detail != null) {
                                favoritesManager.updateFavoriteDetail(pageUrl, detail)
                                runOnUiThread {
                                    androidx.localbroadcastmanager.content.LocalBroadcastManager
                                        .getInstance(this)
                                        .sendBroadcast(android.content.Intent(FavoritesActivity.ACTION_FAVORITE_ENRICHED))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "savePigAvPageAsBookmark: ${e.message}")
                    Toast.makeText(this, "書籤儲存失敗", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveAvTodayPageAsBookmark() {
        val pageUrl = geckoCurrentUrl ?: return
        val existing = favoritesManager.getFavorites().find { it.url == pageUrl }
        if (existing != null) {
            favoritesManager.removeFavorite(pageUrl)
            Toast.makeText(this, "已從書籤移除", Toast.LENGTH_SHORT).show()
            btnAddFavorite.text = "♡"
            return
        }

        val js = """
            (function() {
                function content(selector) {
                    var el = document.querySelector(selector);
                    return el ? (el.getAttribute('content') || '').trim() : '';
                }
                function text(selector) {
                    var el = document.querySelector(selector);
                    return el ? (el.textContent || '').trim() : '';
                }
                function absolute(value) {
                    if (!value) return '';
                    try { return new URL(value, location.href).href; } catch (e) { return value; }
                }
                var title = content('meta[property="og:title"]') || text('h1.title') || document.title || '';
                var cover = content('meta[property="og:image"]') || content('meta[name="twitter:image"]');
                var code = text('.video-info span') || '';
                if (!code) {
                    var match = (location.pathname + ' ' + title).match(/(FC2(?:[-_\s]?PPV)?[-_\s]?\d{5,10}|[A-Za-z]{2,8}-\d{1,5}(?:-[A-Za-z])?)/i);
                    code = match ? match[1] : '';
                }
                return { title: title, cover: absolute(cover), code: code };
            })();
        """.trimIndent()

        btnAddFavorite.isEnabled = false
        GeckoEval.evaluate(geckoSession, js) { raw ->
            runOnUiThread {
                btnAddFavorite.isEnabled = true
                try {
                    val obj = org.json.JSONObject(raw ?: "{}")
                    val title = obj.optString("title", "").trim().ifEmpty { currentPageTitle() ?: pageUrl }
                    val cover = obj.optString("cover", "").trim()
                    val javCode = JavDbScraper.extractFc2Code(pageUrl, title, obj.optString("code", ""))
                        ?: JavDbScraper.extractJavCode(obj.optString("code", ""))
                        ?: JavDbScraper.extractJavCode(title)
                    val added = favoritesManager.addFavorite(
                        title = title,
                        url = pageUrl,
                        thumbnailUrl = cover.ifEmpty { null },
                        javCode = javCode
                    )
                    if (!added) {
                        Toast.makeText(this, "已在書籤中", Toast.LENGTH_SHORT).show()
                        btnAddFavorite.text = "♥"
                        return@runOnUiThread
                    }

                    Toast.makeText(this, "已加入書籤", Toast.LENGTH_SHORT).show()
                    btnAddFavorite.text = "♥"
                    if (javCode != null) {
                        CrossSiteChecker.checkAll(this, javCode, pageUrl) { found ->
                            if (found.isNotEmpty()) {
                                favoritesManager.updateRelatedUrls(pageUrl, found)
                                androidx.localbroadcastmanager.content.LocalBroadcastManager
                                    .getInstance(this)
                                    .sendBroadcast(android.content.Intent(FavoritesActivity.ACTION_FAVORITE_ENRICHED))
                            }
                        }
                        enrichMetadataForCode(javCode) { detail ->
                            if (detail != null) {
                                favoritesManager.updateFavoriteDetail(pageUrl, detail)
                                androidx.localbroadcastmanager.content.LocalBroadcastManager
                                    .getInstance(this)
                                    .sendBroadcast(android.content.Intent(FavoritesActivity.ACTION_FAVORITE_ENRICHED))
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AVTODAY_BOOKMARK", "save failed: ${e.message}")
                    Toast.makeText(this, "書籤儲存失敗", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * If the URL is a resolution-specific HLS variant (e.g. surrit.com/UUID/640x360/video.m3u8),
     * return the master playlist URL (surrit.com/UUID/playlist.m3u8) instead.
     * This ensures fetchBestQualityUrl can compare all available resolutions.
     */
    private fun normalizeMissavM3u8Url(url: String): String {
        val variantPattern = Regex(
            """(https?://[^/]+/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})/(?:\d+x\d+|\d+p)/[^?#]*\.m3u8""",
            RegexOption.IGNORE_CASE
        )
        val match = variantPattern.find(url)
        return if (match != null) "${match.groupValues[1]}/playlist.m3u8" else url
    }

    private fun setupFavoritesButtons() {
        btnAddFavorite.setOnClickListener {
            val url = geckoCurrentUrl
            if (url != null && url.startsWith("http")) {
                // JavDB 頁面沿用專用解析流程；收藏結果仍寫入同一份 App 書籤。
                if (isOnJavDbVideoPage || url.matches(Regex("https://javdb\\.com/v/[A-Za-z0-9]+.*"))) {
                    saveJavDbPageAsBookmark()
                    return@setOnClickListener
                }
                if (url.contains("pigav.ws", ignoreCase = true)) {
                    savePigAvPageAsBookmark()
                    return@setOnClickListener
                }
                if (url.contains("avtoday.io", ignoreCase = true) &&
                    url.contains("/video/", ignoreCase = true)) {
                    saveAvTodayPageAsBookmark()
                    return@setOnClickListener
                }
                resolveCurrentFavoriteUrl(url) { effectiveUrl ->
                    val title = currentPageTitle() ?: "Unknown Page"
                    val favorites = favoritesManager.getFavorites()
                    val currentNormUrl = favoriteComparisonKey(effectiveUrl)
                    val isFavorite = favorites.any { favoriteComparisonKey(it.url) == currentNormUrl }

                    if (isFavorite) {
                        favorites.find { favoriteComparisonKey(it.url) == currentNormUrl }?.let {
                            favoritesManager.removeFavorite(it.url)
                        }
                        Toast.makeText(this, "已從書籤移除", Toast.LENGTH_SHORT).show()
                        btnAddFavorite.text = "♡"
                    } else {
                        GeckoEval.evaluate(geckoSession, """
                            (function() {
                                function normalizeUrl(value) {
                                    if (!value) return '';
                                    try { return new URL(value, location.href).href; } catch (e) { return value; }
                                }
                                var thumbnail = '';
                                var phPoster = document.querySelector('.mgp_videoPoster picture img, .mgp_videoPoster img');
                                if (phPoster) {
                                    thumbnail = phPoster.currentSrc || phPoster.src || '';
                                }
                                var video = document.querySelector('video[poster]');
                                if (!thumbnail && video && video.poster) {
                                    thumbnail = video.poster;
                                }
                                if (!thumbnail) {
                                    var ogImage = document.querySelector('meta[property="og:image"]');
                                    if (ogImage) thumbnail = ogImage.content;
                                }
                                if (!thumbnail) {
                                    var twitterImage = document.querySelector('meta[name="twitter:image"]');
                                    if (twitterImage) thumbnail = twitterImage.content;
                                }
                                if (!thumbnail) {
                                    thumbnail = window.__javMainPoster || '';
                                    var playerPoster = document.getElementById('player-wrap');
                                    if (!thumbnail && playerPoster) {
                                        var background = getComputedStyle(playerPoster).backgroundImage.match(/url\(["']?(.*?)["']?\)/);
                                        if (background) thumbnail = background[1];
                                    }
                                }
                                if (!thumbnail) {
                                    var img = document.querySelector('img');
                                    if (img) thumbnail = img.src;
                                }
                                return normalizeUrl(thumbnail);
                            })();
                        """.trimIndent()) { thumbnailUrl ->
                            val cleanedThumbnailUrl = thumbnailUrl?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" }
                            val isStripchat = effectiveUrl.contains("stripchat.com", ignoreCase = true)
                            val javCode = if (isStripchat) null else
                                JavDbScraper.extractFc2Code(effectiveUrl, title)
                                    ?: JavDbScraper.extractJavCode(title)
                            favoritesManager.addFavorite(title, effectiveUrl, cleanedThumbnailUrl, javCode = javCode)
                            Toast.makeText(this, "已加入書籤", Toast.LENGTH_SHORT).show()
                            btnAddFavorite.text = "♥"

                            if (javCode != null) {
                                CrossSiteChecker.checkAll(this, javCode, effectiveUrl) { found ->
                                    if (found.isNotEmpty()) {
                                        favoritesManager.updateRelatedUrls(effectiveUrl, found)
                                        androidx.localbroadcastmanager.content.LocalBroadcastManager
                                            .getInstance(this)
                                            .sendBroadcast(android.content.Intent(FavoritesActivity.ACTION_FAVORITE_ENRICHED))
                                    }
                                }

                                enrichMetadataForCode(javCode) { detail ->
                                    if (detail != null) {
                                        favoritesManager.updateFavoriteDetail(effectiveUrl, detail)
                                        runOnUiThread {
                                            androidx.localbroadcastmanager.content.LocalBroadcastManager
                                                .getInstance(this)
                                                .sendBroadcast(android.content.Intent(FavoritesActivity.ACTION_FAVORITE_ENRICHED))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Toast.makeText(this, "Cannot add this page", Toast.LENGTH_SHORT).show()
            }
        }

        btnViewFavorites.setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
    }

    private fun updateFavoriteIcon(explicitUrl: String? = null) {
        val url = explicitUrl ?: geckoCurrentUrl
        if (url != null && url.startsWith("http")) {
            val requestedKey = favoriteComparisonKey(url)
            resolveCurrentFavoriteUrl(url) { effectiveUrl ->
                if (url.contains("stripchat.com", ignoreCase = true)) {
                    val latestUrl = currentPageUrl.takeIf { it.contains("stripchat.com", ignoreCase = true) }
                        ?: geckoCurrentUrl.orEmpty()
                    if (favoriteComparisonKey(latestUrl) != requestedKey) return@resolveCurrentFavoriteUrl
                }
                val favorites = favoritesManager.getFavorites()
                val currentNormUrl = favoriteComparisonKey(effectiveUrl)
                val isFavorite = favorites.any { favoriteComparisonKey(it.url) == currentNormUrl }
                btnAddFavorite.text = if (isFavorite) "♥" else "♡"
            }
        } else {
            btnAddFavorite.text = "♡"
        }
    }

    private fun resolveCurrentFavoriteUrl(currentUrl: String, callback: (String) -> Unit) {
        if (!currentUrl.contains("stripchat.com", ignoreCase = true)) {
            callback(currentUrl)
            return
        }
        val parsed = runCatching { Uri.parse(currentUrl) }.getOrNull()
        val modelKey = stripchatModelKey(currentUrl)
        if (parsed != null && modelKey != null) {
            callback(parsed.buildUpon().clearQuery().fragment(null).build().toString().trimEnd('/'))
        } else {
            callback(currentUrl.substringBefore('#').substringBefore('?').trimEnd('/'))
        }
    }

    private fun favoriteComparisonKey(url: String): String {
        return stripchatModelKey(url) ?: domainConfig.updateUrlIfNeeded(url)
    }

    private fun stripchatModelKey(url: String): String? {
        if (!url.contains("stripchat.com", ignoreCase = true)) return null
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = parsed.host.orEmpty()
        if (!host.equals("stripchat.com", ignoreCase = true) &&
            !host.endsWith(".stripchat.com", ignoreCase = true)) return null
        val path = parsed.path.orEmpty().trim('/')
        if (path.isBlank() || path.contains('/')) return null
        if (path.lowercase() in setOf(
                "login", "signup", "favorites", "models", "search", "category", "tags", "live"
            )) return null
        return "stripchat:${path.lowercase()}"
    }
    private fun playVideo(url: String) {
        val page = geckoCurrentUrl ?: currentPageUrl
        if (AdditionalSiteMedia.hostMatches(page, "javrate.com")) {
            GeckoEval.evaluate(geckoSession, "document.querySelector('#v2-player')?.src || ''") { raw ->
                val embed = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                if (JavrateStreams.isPlayer(embed)) {
                    recordPlaybackPage(page, currentPageTitle().orEmpty())
                    startActivity(Intent(this, FullscreenInternalPlayerActivity::class.java).apply {
                        putExtra(FullscreenInternalPlayerActivity.EXTRA_JAVRATE_PLAYER, embed)
                        putExtra(FullscreenInternalPlayerActivity.EXTRA_DOWNLOAD_NAME, currentPageTitle() ?: "video")
                        putExtra(FullscreenInternalPlayerActivity.EXTRA_PAGE_URL, page)
                    })
                } else {
                    Toast.makeText(this, "請先開啟網站播放器", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        playResolvedVideo(url)
    }

    private fun playResolvedVideo(url: String) {
        // Gecko cookie 仓（含 HttpOnly）+ WebView/OkHttp 仓合并（Gecko 优先）。
        // 取 cookie 的 URL 清单与下面 WebView 侧的分支一致（supjav 读视频
        // 地址或 referer；javhdporn 读 www + video1 两个 host；其余读当前页）。
        val referer = currentVideoReferer
        val pageUrl = geckoCurrentUrl ?: referer ?: ""
        val cookieUrls = if (AdditionalSiteMedia.hostMatches(pageUrl, "supjav.com")) {
            listOf(url, referer.orEmpty())
        } else if (referer?.contains("javhdporn.net") == true) {
            listOf("https://www.javhdporn.net/", "https://video1.javhdporn.net/")
        } else {
            listOf(pageUrl, referer.orEmpty())
        }
        readGeckoCookies(cookieUrls) { geckoCookies ->
            playResolvedVideoWithCookies(url, geckoCookies)
        }
    }

    private fun playResolvedVideoWithCookies(url: String, geckoCookies: String) {
        val referer = currentVideoReferer
        val cookieManager = android.webkit.CookieManager.getInstance()
        val currentPageUrl = geckoCurrentUrl ?: referer ?: ""
        // javhdporn.net：cookie 需要從主頁和 video1 子域都取
        val webCookies = if (AdditionalSiteMedia.hostMatches(currentPageUrl, "supjav.com")) {
            cookieManager.getCookie(url).orEmpty().ifEmpty { cookieManager.getCookie(referer.orEmpty()).orEmpty() }
        } else if (referer?.contains("javhdporn.net") == true) {
            listOf(
                cookieManager.getCookie("https://www.javhdporn.net/"),
                cookieManager.getCookie("https://video1.javhdporn.net/")
            ).filterNotNull().filter { it.isNotEmpty() }.joinToString("; ")
        } else {
            cookieManager.getCookie(currentPageUrl) ?: cookieManager.getCookie(referer ?: "") ?: ""
        }

        val cookies = mergeCookieStrings(geckoCookies, webCookies)

        if (privacySettings.alwaysUseInternalPlayer) {
            val isStripchatPage = referer?.contains("stripchat.com", ignoreCase = true) == true ||
                currentPageUrl.contains("stripchat.com", ignoreCase = true)
            if (isStripchatPage) {
                // Stripchat 的 MOUFLON v2 片段檔名由官方 Doppio 播放器動態解碼。
                // 沿用頁面上已正常播放的 video/MSE，避免把假 media.mp4 URL
                // 交給一般 HLS 播放器後得到 404。
                playInsideCurrentWebView(url, reusePageVideo = true)
                return
            }
            val internalReferer = referer
                ?: currentPageUrl.takeIf { it.startsWith("http") }
                ?: url
            launchFullscreenInternalPlayer(url, internalReferer)
            return
        }

        // Copy real URL to clipboard (not proxy URL)
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Video URL", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Video URL copied to clipboard", Toast.LENGTH_SHORT).show()

        if (referer != null && MediaStreamFormats.isHls(url)) {
            // For HLS with referer protection: fetch master m3u8 via proxy to pick highest quality,
            // then pass the best-quality proxy URL to the external player.
            btnPlay.isEnabled = false
            btnPlay.text = "..."
            withHealthyProxy { proxy ->
                val proxyMasterUrl = proxy.buildProxyUrl(url, referer, cookies)
                runPlaybackPreflight(url, referer, cookies, proxyMasterUrl) { ok, report ->
                    if (!ok) {
                        btnPlay.isEnabled = true
                        btnPlay.text = "▶"
                        if (isCloudflareBlocked(report)) {
                            Toast.makeText(
                                this,
                                "外部 proxy 被 Cloudflare 擋住，改用內建 WebView 播放",
                                Toast.LENGTH_LONG
                            ).show()
                            launchFullscreenInternalPlayer(url, referer)
                        } else {
                            showPlaybackDebugDialog(report)
                        }
                        return@runPlaybackPreflight
                    }

                    VideoExtractor.fetchBestQualityUrl(proxyMasterUrl) { bestUrl ->
                        btnPlay.isEnabled = true
                        btnPlay.text = "▶"
                        // bestUrl is already a proxy URL (rewritten by proxy from the m3u8)
                        launchExternalPlayer(bestUrl)
                    }
                }
            }
        } else {
            if (referer != null) {
                withHealthyProxy { proxy ->
                    launchExternalPlayer(proxy.buildProxyUrl(url, referer, cookies))
                }
            } else {
                launchExternalPlayer(url)
            }
        }
    }

    private fun isCloudflareBlocked(report: String): Boolean {
        return report.contains("Cloudflare", ignoreCase = true) ||
            report.contains("Attention Required", ignoreCase = true) ||
            report.contains("HTTP 403")
    }

    private fun launchFullscreenInternalPlayer(videoUrl: String, referer: String) {
        recordPlaybackPage(geckoCurrentUrl ?: currentPageUrl, currentPageTitle().orEmpty())
        val isMissAv = referer.contains("missav", ignoreCase = true) ||
            currentPageUrl.contains("missav", ignoreCase = true)
        val internalVideoUrl = if (isMissAv) normalizeMissavM3u8Url(videoUrl) else videoUrl
        val thumbnailConfig = currentMissAvThumbnailConfig.takeIf {
            referer.contains("missav", ignoreCase = true) ||
                currentPageUrl.contains("missav", ignoreCase = true)
        }
        val jableVttUrl = currentJableVttUrl.takeIf {
            referer.contains("jable.tv", ignoreCase = true) ||
                currentPageUrl.contains("jable.tv", ignoreCase = true)
        }
        val intent = Intent(this, FullscreenInternalPlayerActivity::class.java).apply {
            putExtra(FullscreenInternalPlayerActivity.EXTRA_VIDEO_URL, internalVideoUrl)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_REFERER, referer)
            val playerPageUrl = geckoCurrentUrl ?: currentPageUrl
            putExtra(FullscreenInternalPlayerActivity.EXTRA_PAGE_URL, playerPageUrl)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_DOWNLOAD_NAME, currentPageTitle() ?: "video")
            if (AdditionalSiteMedia.hostMatches(playerPageUrl, "javday.app") && currentJavdayEpisodes.size > 1) {
                val episodesJson = org.json.JSONArray()
                currentJavdayEpisodes.forEach { episode ->
                    episodesJson.put(org.json.JSONObject().apply {
                        put("url", episode.url)
                        put("title", episode.title)
                    })
                }
                putExtra(FullscreenInternalPlayerActivity.EXTRA_ADDITIONAL_EPISODES_JSON, episodesJson.toString())
            }
            if (isRouVideoUrl(playerPageUrl) && currentRouEpisodes.size > 1) {
                val episodesJson = org.json.JSONArray()
                currentRouEpisodes.forEach { episode ->
                    episodesJson.put(org.json.JSONObject().apply {
                        put("id", episode.id)
                        put("number", episode.number)
                    })
                }
                putExtra(FullscreenInternalPlayerActivity.EXTRA_ROU_EPISODES_JSON, episodesJson.toString())
            }
            thumbnailConfig?.let {
                putExtra(FullscreenInternalPlayerActivity.EXTRA_THUMB_PIC_NUM, it.picNum)
                putExtra(FullscreenInternalPlayerActivity.EXTRA_THUMB_WIDTH, it.width)
                putExtra(FullscreenInternalPlayerActivity.EXTRA_THUMB_HEIGHT, it.height)
                putExtra(FullscreenInternalPlayerActivity.EXTRA_THUMB_COLUMNS, it.columns)
                putExtra(FullscreenInternalPlayerActivity.EXTRA_THUMB_ROWS, it.rows)
                putExtra(FullscreenInternalPlayerActivity.EXTRA_THUMB_URL_TEMPLATE, it.urlTemplate)
            }
            jableVttUrl?.let {
                putExtra(FullscreenInternalPlayerActivity.EXTRA_PREVIEW_VTT_URL, it)
            }
        }
        startActivity(intent)
    }

    private fun recordPlaybackPage(pageUrl: String, title: String) {
        PlaybackHistoryRepository.record(this, pageUrl, title)
        GeckoEval.evaluate(geckoSession, """
            (function() {
                var video = document.querySelector('video[poster]');
                var meta = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
                var cover = (video && video.poster) || window.__javMainPoster || (meta && meta.content) || '';
                if (!cover) {
                    var poster = document.querySelector('.art-poster, #player-wrap');
                    var match = poster && getComputedStyle(poster).backgroundImage.match(/url\(["']?(.*?)["']?\)/);
                    if (match) cover = match[1];
                }
                var icon = document.querySelector('link[rel="icon"], link[rel="shortcut icon"], link[rel="apple-touch-icon"]');
                function absolute(value) { try { return value ? new URL(value, location.href).href : ''; } catch(e) { return ''; } }
                var metadata = [];
                var actorLabel = /^(女優|女优|演員|演员|主演|Actress(es)?|Actor(s|\(s\))?|Cast|出演)[：:\s]*$/i;
                document.querySelectorAll('strong,b,span,dt,th,label,div,p').forEach(function(el) {
                    var own = Array.from(el.childNodes).filter(function(n) { return n.nodeType === 3; })
                        .map(function(n) { return n.textContent; }).join('').trim();
                    if (actorLabel.test(own) && el.parentElement && el.parentElement.outerHTML.length < 100000) {
                        metadata.push(el.parentElement.outerHTML);
                    }
                });
                document.querySelectorAll('a[href*="/actress/"],a[href*="/actresses/"],a[href*="/actor/"],a[href*="/star/"],a[href*="/models/"],.actor-name,.actress-name,script[type="application/ld+json"]').forEach(function(el) {
                    for (var p = el.parentElement; p; p = p.parentElement) {
                        if (/recommend|related|sidebar|footer|header|nav/i.test(p.className + ' ' + p.id) || /^(NAV|FOOTER|HEADER)$/.test(p.tagName)) return;
                    }
                    metadata.push(el.outerHTML);
                });
                return JSON.stringify({ cover: absolute(cover), icon: icon ? icon.href : absolute('/favicon.ico'),
                    actorsHtml: metadata.join('\n').slice(0, 300000) });
            })();
        """.trimIndent()) { raw ->
            if (geckoCurrentUrl != pageUrl) return@evaluate
            runCatching {
                val json = org.json.JSONTokener(raw).nextValue() as? String ?: return@runCatching
                val images = org.json.JSONObject(json)
                PlaybackHistoryRepository.updateArtwork(this, pageUrl, images.optString("cover"), images.optString("icon"),
                    PlaybackHistoryArtwork.actresses(images.optString("actorsHtml")))
            }
        }
    }

    private fun playInsideCurrentWebView(videoUrl: String, reusePageVideo: Boolean = false) {
        recordPlaybackPage(geckoCurrentUrl ?: currentPageUrl, currentPageTitle().orEmpty())
        val videoUrlJson = org.json.JSONObject.quote(videoUrl)
        val reusePageVideoJs = if (reusePageVideo) "true" else "false"
        val shortSeekSeconds = privacySettings.playerShortSeekSeconds
        val longSeekSeconds = privacySettings.playerLongSeekSeconds
        val speedOptionsJson = org.json.JSONArray(privacySettings.playbackSpeedOptions).toString()
        geckoMainHandler.post {
            GeckoEval.evaluate(geckoSession, 
                """
                ${stripchatBridgeShimJs()}
                (function() {
                    var targetUrl = $videoUrlJson;
                    var reusePageVideo = $reusePageVideoJs;
                    $stripchatPlaybackProbeJs
                    var shortSeekSeconds = $shortSeekSeconds;
                    var longSeekSeconds = $longSeekSeconds;
                    var speedOptions = $speedOptionsJson;
                    var oldPanel = document.getElementById('jav-internal-player-panel');
                    if (reusePageVideo && oldPanel &&
                        oldPanel.getAttribute('data-jav-model-url') === location.href &&
                        typeof window.__javStartStripchatDirectRecording === 'function') return 'overlay-ready';
                    if (oldPanel) oldPanel.remove();

                    var video = reusePageVideo
                        ? window.__javStripchatPlaybackProbe.selectVideo()
                        : document.createElement('video');
                    if (!video) return 'stripchat-video-not-ready';
                    var originalParent = reusePageVideo ? video.parentNode : null;
                    var originalNextSibling = reusePageVideo ? video.nextSibling : null;
                    var originalStyle = reusePageVideo ? video.getAttribute('style') : null;

                    var panel = document.createElement('div');
                    panel.id = 'jav-internal-player-panel';
                    panel.setAttribute('data-jav-model-url', location.href);
                    panel.style.cssText = [
                        'position:fixed',
                        'left:0',
                        'right:0',
                        'top:0',
                        'z-index:2147483647',
                        'background:#050505',
                        'border-bottom:2px solid #8b00ff',
                        'box-shadow:0 8px 24px rgba(0,0,0,.55)',
                        'padding:10px',
                        'box-sizing:border-box'
                    ].join(';');

                    var bar = document.createElement('div');
                    bar.style.cssText = 'display:flex;align-items:center;justify-content:space-between;color:#fff;font-size:14px;margin-bottom:8px;';

                    var status = document.createElement('div');
                    status.textContent = '內建播放器：載入中';
                    status.style.cssText = 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding-right:8px;';

                    var close = document.createElement('button');
                    close.textContent = '關閉';
                    close.style.cssText = 'background:#8b00ff;color:#fff;border:0;border-radius:6px;padding:8px 12px;font-size:14px;';
                    close.onclick = function() {
                        try {
                            if (reusePageVideo && window.__javStopStripchatDirectRecording) {
                                window.__javStopStripchatDirectRecording();
                            }
                            if (reusePageVideo && window.Android && Android.stopStripchatRecording) {
                                Android.stopStripchatRecording();
                            }
                            if (document.fullscreenElement && document.exitFullscreen) {
                                document.exitFullscreen();
                            }
                            if (reusePageVideo && originalParent) {
                                if (originalNextSibling && originalNextSibling.parentNode === originalParent) {
                                    originalParent.insertBefore(video, originalNextSibling);
                                } else {
                                    originalParent.appendChild(video);
                                }
                                if (originalStyle === null) video.removeAttribute('style');
                                else video.setAttribute('style', originalStyle);
                            } else {
                                video.pause();
                            }
                        } catch (e) {}
                        panel.remove();
                        if (reusePageVideo && window.Android && Android.setStripchatPlayerOverlayVisible) {
                            Android.setStripchatPlayerOverlayVisible(false);
                        }
                    };

                    var expand = document.createElement('button');
                    expand.textContent = '大視窗';
                    expand.style.cssText = 'background:#222;color:#fff;border:1px solid #8b00ff;border-radius:6px;padding:8px 12px;font-size:14px;margin-right:6px;';

                    var record = document.createElement('button');
                    record.id = 'jav-stripchat-player-watch-button';
                    record.style.cssText = 'background:rgba(210,25,45,.92);color:#fff;border:0;border-radius:18px;padding:8px 14px;font-size:14px;margin-right:8px;';
                    var privacy = document.createElement('button');
                    privacy.textContent = '◼ 隱私';
                    privacy.style.cssText = 'background:rgba(10,10,10,.94);color:#fff;border:1px solid #777;border-radius:18px;padding:8px 12px;font-size:13px;margin-right:8px;';
                    privacy.onclick = function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        try { Android.enterStripchatPrivacyMode(); } catch (err) {}
                    };
                    var directRecorder = null;
                    var directRecordingFailed = false;
                    var directStopDetail = '';
                    var directChunkChain = Promise.resolve();
                    var directDrawFrameId = 0;
                    var directDrawUsesVideoFrameCallback = false;
                    var directFrameWatchdogId = 0;
                    var directLastFrameAt = 0;
                    var directLastVideoTime = -1;
                    var directStallStartedAt = 0;
                    var directCanvasStream = null;
                    var directSourceStream = null;

                    function stopDirectFramePump() {
                        if (directDrawFrameId) {
                            try {
                                if (directDrawUsesVideoFrameCallback && video.cancelVideoFrameCallback) {
                                    video.cancelVideoFrameCallback(directDrawFrameId);
                                } else {
                                    cancelAnimationFrame(directDrawFrameId);
                                }
                            } catch (e) {}
                        }
                        directDrawFrameId = 0;
                        directDrawUsesVideoFrameCallback = false;
                        if (directFrameWatchdogId) clearInterval(directFrameWatchdogId);
                        directFrameWatchdogId = 0;
                    }
                    function setRecordingState(active) {
                        var watching = false;
                        try { watching = Android.isStripchatWatchSessionActive(location.href); } catch (e) {}
                        try { record.textContent = Android.getStripchatWatchStateLabel(location.href); }
                        catch (e) { record.textContent = watching ? '◌ 正在確認直播狀態' : '◎ 監錄'; }
                        record.style.background = active
                            ? 'rgba(110,15,25,.94)'
                            : (watching ? 'rgba(176,108,0,.94)' : 'rgba(210,25,45,.92)');
                        try { if (window.__javRefreshStripchatWatchUi) window.__javRefreshStripchatWatchUi(); } catch (e) {}
                    }
                    var initialRecording = false;
                    try {
                        initialRecording = reusePageVideo &&
                            (Android.isStripchatRecording() || Android.isStripchatStreamRecording());
                    } catch (e) {}
                    setRecordingState(initialRecording);
                    window.__javSetStripchatRecordingState = setRecordingState;

                    function arrayBufferToBase64(buffer) {
                        var bytes = new Uint8Array(buffer);
                        var binary = '';
                        var step = 32768;
                        for (var i = 0; i < bytes.length; i += step) {
                            binary += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + step, bytes.length)));
                        }
                        return btoa(binary);
                    }

                    function finishDirectRecording(success, detail) {
                        directChunkChain.then(function() {
                            try { Android.finishStripchatStreamRecording(success && !directRecordingFailed, detail || ''); } catch (e) {}
                        });
                    }

                    function stopDirectRecording(detail) {
                        if (directRecorder && directRecorder.state !== 'inactive') {
                            directStopDetail = detail || '';
                            try { directRecorder.stop(); } catch (e) {
                                finishDirectRecording(false, e && e.message ? e.message : '停止錄製失敗');
                            }
                            return true;
                        }
                        return false;
                    }
                    window.__javStopStripchatDirectRecording = stopDirectRecording;
                    window.__javAutoStopStripchatRecording = function(detail) {
                        return stopDirectRecording(detail || '主播已下播，自動停止錄製');
                    };

                    function startDirectRecording() {
                        var storeOpened = false;
                        var recordingStage = 'capability';
                        function reportRecordingError(stage, detail) {
                            try { Android.onStripchatRecordingError(stage, detail); } catch (_) {}
                        }
                        try {
                            var capture = video.captureStream || video.mozCaptureStream;
                            var canvas = document.createElement('canvas');
                            if (!capture || typeof canvas.captureStream !== 'function' ||
                                typeof MediaRecorder === 'undefined') {
                                reportRecordingError('capability', 'This WebView does not support live media capture');
                                return false;
                            }

                            recordingStage = 'source';
                            directSourceStream = capture.call(video);
                            if (!directSourceStream || directSourceStream.getVideoTracks().length === 0) {
                                if (directSourceStream) directSourceStream.getTracks().forEach(function(track){ track.stop(); });
                                directSourceStream = null;
                                reportRecordingError('source', 'No live video track is available; start the webpage video first');
                                return false;
                            }
                            var fixedWidth = Math.max(2, video.videoWidth || 720);
                            var fixedHeight = Math.max(2, video.videoHeight || 1280);
                            fixedWidth -= fixedWidth % 2;
                            fixedHeight -= fixedHeight % 2;
                            canvas.width = fixedWidth;
                            canvas.height = fixedHeight;
                            var context2d = canvas.getContext('2d', { alpha: false });
                            if (!context2d) {
                                directSourceStream.getTracks().forEach(function(track){ track.stop(); });
                                directSourceStream = null;
                                reportRecordingError('canvas', 'Unable to create the recording canvas');
                                return false;
                            }
                            recordingStage = 'canvas';

                            function scheduleNextVideoFrame() {
                                if (typeof video.requestVideoFrameCallback === 'function') {
                                    directDrawUsesVideoFrameCallback = true;
                                    directDrawFrameId = video.requestVideoFrameCallback(drawVideoFrame);
                                } else {
                                    directDrawUsesVideoFrameCallback = false;
                                    directDrawFrameId = requestAnimationFrame(drawVideoFrame);
                                }
                            }
                            function drawVideoFrame() {
                                try {
                                    context2d.drawImage(video, 0, 0, fixedWidth, fixedHeight);
                                    directLastFrameAt = Date.now();
                                } catch (drawError) {
                                    try { Android.onStripchatRecordingHealth('drawImage: ' + (drawError.message || drawError)); } catch (e) {}
                                }
                                scheduleNextVideoFrame();
                            }
                            context2d.drawImage(video, 0, 0, fixedWidth, fixedHeight);
                            directLastFrameAt = Date.now();
                            directLastVideoTime = Number(video.currentTime || 0);
                            scheduleNextVideoFrame();
                            directFrameWatchdogId = setInterval(function() {
                                if (!directRecorder || directRecorder.state === 'inactive') return;
                                var now = Date.now();
                                var currentTime = Number(video.currentTime || 0);
                                if (Math.abs(currentTime - directLastVideoTime) < 0.05) {
                                    if (!directStallStartedAt) directStallStartedAt = now;
                                    if (now - directStallStartedAt > 8000) {
                                        try {
                                            Android.onStripchatRecordingHealth(
                                                'no source progress for ' + Math.round((now - directStallStartedAt) / 1000) +
                                                's; readyState=' + video.readyState
                                            );
                                        } catch (e) {}
                                        directStallStartedAt = now;
                                        try { video.play(); } catch (e) {}
                                    }
                                } else {
                                    directStallStartedAt = 0;
                                    directLastVideoTime = currentTime;
                                }
                                // 某些 WebView 會暫停動畫回呼；在仍有可用畫面時補畫一幀。
                                if (now - directLastFrameAt > 2500 && video.readyState >= 2) {
                                    try {
                                        context2d.drawImage(video, 0, 0, fixedWidth, fixedHeight);
                                        directLastFrameAt = now;
                                    } catch (e) {}
                                }
                            }, 2500);
                            directCanvasStream = canvas.captureStream(30);
                            directSourceStream.getAudioTracks().forEach(function(track) {
                                directCanvasStream.addTrack(track);
                            });
                            // 影片軌一律來自固定尺寸 Canvas；原始動態影片軌不送進編碼器。
                            directSourceStream.getVideoTracks().forEach(function(track) { track.stop(); });
                            var mediaStream = directCanvasStream;

                            var candidates = [
                                'video/mp4;codecs=avc1.42E01E,mp4a.40.2',
                                'video/webm;codecs=vp9,opus',
                                'video/webm;codecs=vp8,opus',
                                'video/webm'
                            ];
                            var mime = '';
                            for (var i = 0; i < candidates.length; i++) {
                                if (MediaRecorder.isTypeSupported(candidates[i])) {
                                    mime = candidates[i];
                                    break;
                                }
                            }
                            var options = {
                                videoBitsPerSecond: 8000000,
                                audioBitsPerSecond: 128000
                            };
                            if (mime) options.mimeType = mime;
                            recordingStage = 'recorder';
                            directRecorder = new MediaRecorder(mediaStream, options);
                            var actualMime = directRecorder.mimeType || mime || 'video/webm';
                            var sourceUrl = location.href;
                            recordingStage = 'storage';
                            var accepted = Android.beginStripchatStreamRecording(
                                actualMime,
                                sourceUrl,
                                fixedWidth,
                                fixedHeight
                            );
                            if (!accepted) {
                                directRecorder = null;
                                mediaStream.getTracks().forEach(function(track){ track.stop(); });
                                stopDirectFramePump();
                                directCanvasStream = null;
                                directSourceStream = null;
                                reportRecordingError('storage', 'Unable to create the recording file, or another recording is active');
                                return false;
                            }
                            storeOpened = true;

                            directRecordingFailed = false;
                            directChunkChain = Promise.resolve();
                            directRecorder.ondataavailable = function(event) {
                                if (!event.data || event.data.size === 0) return;
                                var blob = event.data;
                                directChunkChain = directChunkChain.then(function() {
                                    return blob.arrayBuffer();
                                }).then(function(buffer) {
                                    if (!Android.appendStripchatStreamRecordingChunk(arrayBufferToBase64(buffer))) {
                                        if (!directRecordingFailed) reportRecordingError('write', 'Unable to write recorded media data');
                                        directRecordingFailed = true;
                                        stopDirectRecording('Unable to write recorded media data');
                                    }
                                }).catch(function(error) {
                                    if (!directRecordingFailed) reportRecordingError('write', error && error.message ? error.message : 'Unable to prepare recorded media data');
                                    directRecordingFailed = true;
                                    stopDirectRecording('Unable to prepare recorded media data');
                                });
                            };
                            directRecorder.onerror = function(event) {
                                directRecordingFailed = true;
                                var error = event && event.error;
                                directStopDetail = error && error.message ? error.message : 'Browser media recorder failed';
                                reportRecordingError('recorder', directStopDetail);
                                if (directRecorder && directRecorder.state !== 'inactive') {
                                    try { directRecorder.stop(); } catch (e) {}
                                } else {
                                    finishDirectRecording(false, error && error.message ? error.message : '瀏覽器錄製失敗');
                                }
                            };
                            directRecorder.onstop = function() {
                                stopDirectFramePump();
                                mediaStream.getTracks().forEach(function(track){ track.stop(); });
                                var completionDetail = directStopDetail ||
                                    (mediaStream.getAudioTracks().length > 0 ? '' : '來源未提供可錄製音軌');
                                finishDirectRecording(true, completionDetail);
                                directStopDetail = '';
                                directRecorder = null;
                                directCanvasStream = null;
                                directSourceStream = null;
                            };
                            recordingStage = 'start';
                            directRecorder.start(2000);
                            video.addEventListener('ended', function() {
                                try { Android.onStripchatRecordingHealth('source video ended'); } catch (e) {}
                            }, { once: true });
                            setRecordingState(true);
                            return true;
                        } catch (e) {
                            var failure = (e && e.name ? e.name + ': ' : '') +
                                (e && e.message ? e.message : 'Unable to start recording');
                            reportRecordingError(recordingStage, failure);
                            stopDirectFramePump();
                            if (directCanvasStream) directCanvasStream.getTracks().forEach(function(track){ track.stop(); });
                            if (directSourceStream) directSourceStream.getTracks().forEach(function(track){ track.stop(); });
                            directCanvasStream = null;
                            directSourceStream = null;
                            directRecorder = null;
                            if (storeOpened) {
                                try { Android.finishStripchatStreamRecording(false, failure); } catch (_) {}
                            }
                            return false;
                        }
                    }
                    window.__javStartStripchatDirectRecording = startDirectRecording;

                    record.onclick = function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        try {
                            if (Android.isStripchatWatchSessionActive(location.href)) {
                                Android.stopStripchatWatchSession();
                                if (stopDirectRecording('已手動停止監錄')) return;
                                if (Android.isStripchatRecording()) Android.toggleStripchatRecording();
                                setRecordingState(false);
                                return;
                            }
                            if (!Android.beginStripchatWatchSession(location.href)) return;
                            if (Android.isStripchatRecording()) {
                                Android.toggleStripchatRecording();
                                return;
                            }
                            if (!startDirectRecording()) {
                                Android.onStripchatWatchAutoStartFailed();
                            }
                        } catch (err) {}
                    };

                    var actions = document.createElement('div');
                    actions.style.cssText = 'display:flex;align-items:center;flex-shrink:0;';
                    if (!reusePageVideo) actions.appendChild(expand);
                    if (reusePageVideo) actions.appendChild(record);
                    if (reusePageVideo) actions.appendChild(privacy);
                    actions.appendChild(close);

                    if (!reusePageVideo) bar.appendChild(status);
                    bar.appendChild(actions);

                    video.controls = false;
                    video.autoplay = true;
                    if (!reusePageVideo) video.muted = false;
                    video.playsInline = false;
                    video.setAttribute('playsinline', 'false');
                    video.setAttribute('webkit-playsinline', 'false');
                    video.style.cssText = 'display:block;width:100%;height:auto;max-height:58vh;background:#000;';

                    if (reusePageVideo) {
                        panel.style.bottom = '0';
                        panel.style.height = '100vh';
                        panel.style.overflow = 'hidden';
                        panel.style.padding = '0';
                        panel.style.border = '0';
                        panel.style.boxShadow = 'none';
                        bar.style.cssText = 'position:absolute;top:10px;right:10px;z-index:2;display:flex;margin:0;';
                        close.style.cssText = 'background:rgba(139,0,255,.88);color:#fff;border:0;border-radius:18px;padding:8px 14px;font-size:14px;';
                        video.style.cssText = 'display:block;width:100%;height:100%;max-height:none;background:#000;object-fit:contain;';
                    }

                    panel.appendChild(bar);
                    panel.appendChild(video);

                    var controls = document.createElement('div');
                    controls.style.cssText = [
                        'display:flex',
                        'flex-wrap:wrap',
                        'gap:6px',
                        'margin-top:8px'
                    ].join(';');

                    function addControl(label, action) {
                        var btn = document.createElement('button');
                        btn.textContent = label;
                        btn.style.cssText = [
                            'flex:1 1 22%',
                            'background:#151515',
                            'color:#fff',
                            'border:1px solid #8b00ff',
                            'border-radius:6px',
                            'padding:8px 4px',
                            'font-size:13px'
                        ].join(';');
                        btn.onclick = function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            action();
                        };
                        controls.appendChild(btn);
                    }

                    var playBtn = null;

                    function updatePlayButton() {
                        if (playBtn) playBtn.textContent = video.paused ? '播放' : '暫停';
                    }

                    function seekBy(seconds) {
                        try {
                            video.currentTime = Math.max(0, video.currentTime + seconds);
                            setStatus('跳轉到 ' + Math.floor(video.currentTime) + 's');
                        } catch (e) {
                            setStatus('跳轉失敗');
                        }
                    }

                    function setSpeed(rate) {
                        try {
                            video.playbackRate = rate;
                            setStatus('速度 ' + rate + 'x');
                        } catch (e) {
                            setStatus('速度設定失敗');
                        }
                    }

                    playBtn = document.createElement('button');
                    playBtn.textContent = '播放';
                    playBtn.style.cssText = [
                        'flex:1 1 22%',
                        'background:#8b00ff',
                        'color:#fff',
                        'border:1px solid #8b00ff',
                        'border-radius:6px',
                        'padding:8px 4px',
                        'font-size:13px'
                    ].join(';');
                    playBtn.onclick = function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        if (video.paused) {
                            var p = video.play();
                            if (p && p.catch) p.catch(function(err){ setStatus('播放失敗：' + (err && err.message ? err.message : 'unknown')); });
                        } else {
                            video.pause();
                        }
                        updatePlayButton();
                    };
                    controls.appendChild(playBtn);

                    addControl('⟲ ' + shortSeekSeconds + 's', function(){ seekBy(-shortSeekSeconds); });
                    addControl(shortSeekSeconds + 's ⟳', function(){ seekBy(shortSeekSeconds); });
                    addControl('⟲ ' + longSeekSeconds + 's', function(){ seekBy(-longSeekSeconds); });
                    addControl(longSeekSeconds + 's ⟳', function(){ seekBy(longSeekSeconds); });

                    var speedSelect = document.createElement('select');
                    speedSelect.style.cssText = [
                        'flex:1 1 46%',
                        'background:#151515',
                        'color:#fff',
                        'border:1px solid #8b00ff',
                        'border-radius:6px',
                        'padding:8px 4px',
                        'font-size:13px'
                    ].join(';');
                    speedOptions.forEach(function(rate) {
                        var option = document.createElement('option');
                        option.value = String(rate);
                        option.textContent = rate + 'x';
                        if (rate === 1) option.selected = true;
                        speedSelect.appendChild(option);
                    });
                    speedSelect.onchange = function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        setSpeed(parseFloat(speedSelect.value));
                    };
                    controls.appendChild(speedSelect);

                    var progress = document.createElement('input');
                    progress.type = 'range';
                    progress.min = '0';
                    progress.max = '1000';
                    progress.value = '0';
                    progress.style.cssText = 'flex:1 1 100%;accent-color:#8b00ff;';
                    progress.oninput = function() {
                        if (video.duration && isFinite(video.duration)) {
                            video.currentTime = video.duration * (parseInt(progress.value, 10) / 1000);
                        }
                    };
                    controls.appendChild(progress);

                    var expanded = false;
                    expand.onclick = function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        expanded = !expanded;
                        if (expanded) {
                            panel.style.bottom = '0';
                            panel.style.height = '100vh';
                            panel.style.overflow = 'auto';
                            video.style.maxHeight = '72vh';
                            expand.textContent = '縮小';
                        } else {
                            panel.style.bottom = '';
                            panel.style.height = '';
                            panel.style.overflow = '';
                            video.style.maxHeight = '58vh';
                            expand.textContent = '大視窗';
                        }
                    };

                    if (!reusePageVideo) panel.appendChild(controls);
                    document.body.appendChild(panel);
                    if (reusePageVideo && window.Android && Android.setStripchatPlayerOverlayVisible) {
                        Android.setStripchatPlayerOverlayVisible(true);
                    }

                    function setStatus(text) {
                        status.textContent = '內建播放器：' + text;
                    }

                    try {
                        video.addEventListener('loadstart', function(){ setStatus('開始載入'); });
                        video.addEventListener('loadedmetadata', function(){ setStatus('已載入 metadata'); });
                        video.addEventListener('canplay', function(){ setStatus('可播放'); });
                        video.addEventListener('playing', function(){ setStatus('播放中'); updatePlayButton(); });
                        video.addEventListener('pause', updatePlayButton);
                        video.addEventListener('timeupdate', function() {
                            if (video.duration && isFinite(video.duration)) {
                                progress.value = String(Math.floor((video.currentTime / video.duration) * 1000));
                            }
                        });
                        video.addEventListener('waiting', function(){ setStatus('緩衝中'); });
                        video.addEventListener('error', function(){
                            var err = video.error;
                            var code = err ? err.code : 'unknown';
                            setStatus('載入失敗 code=' + code);
                        });
                        if (!reusePageVideo) {
                            video.src = targetUrl;
                            video.load();
                        } else {
                            setStatus('使用 Stripchat 官方串流');
                        }

                        var p = video.play();
                        if (p && p.catch) {
                            p.catch(function(e) {
                                setStatus('等待手動播放：' + (e && e.message ? e.message : 'play rejected'));
                            });
                        }
                        if (reusePageVideo && panel.requestFullscreen) {
                            var fullscreenPromise = panel.requestFullscreen();
                            if (fullscreenPromise && fullscreenPromise.catch) fullscreenPromise.catch(function(){});
                        }
                        return 'overlay-injected:' + targetUrl;
                    } catch (e) {
                        return 'inject-error:' + e.message;
                    }
                })();
                """.trimIndent()
            ) { result ->
                android.util.Log.d("InternalPlayback", "result=$result url=${geckoCurrentUrl}")
                val overlayResult = runCatching { org.json.JSONTokener(result).nextValue() as? String }.getOrNull().orEmpty()
                if (overlayResult != "overlay-ready" && !overlayResult.startsWith("overlay-injected:")) {
                    val detail = if (overlayResult == "stripchat-video-not-ready") {
                        LanguageManager.text(this, "找不到可播放的直播畫面，請先啟動網頁影片", "No live player found; start the webpage video first")
                    } else LanguageManager.text(this, "無法準備播放介面，請重新開啟影片", "Unable to prepare the player; reopen the video")
                    android.util.Log.w("STRIPCHAT_RECORD_ERROR", "stage=overlay result=" +
                        overlayResult.replace(Regex("https?://[^\\s\"<>]+"), "[URL]").take(200))
                    Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
                    if (reusePageVideo && stripchatWatchSessionActive) {
                        stripchatWatchSessionStarting = false
                        stripchatWatchStartRequestedAt = 0L
                        notifyStripchatWatchState()
                        scheduleStripchatRecordingStatusCheck(20_000L)
                    }
                    return@evaluate
                }
                if (reusePageVideo && stripchatWatchSessionActive &&
                    !stripchatStreamRecordingStore.isActive()
                ) {
                    geckoMainHandler.postDelayed({
                        if (!stripchatWatchSessionActive || stripchatStreamRecordingStore.isActive()) {
                            return@postDelayed
                        }
                        GeckoEval.evaluate(geckoSession, 
                            """
                            (function() {
                                try {
                                    if (window.__javStartStripchatDirectRecording) {
                                        return !!window.__javStartStripchatDirectRecording();
                                    }
                                } catch (e) {}
                                return false;
                            })();
                            """.trimIndent()) { started ->
                            if (!started.equals("true", ignoreCase = true)) {
                                stripchatWatchSessionStarting = false
                                stripchatWatchStartRequestedAt = 0L
                                notifyStripchatWatchState()
                                scheduleStripchatRecordingStatusCheck(20_000L)
                            }
                        }
                    }, 1_200L)
                }
            }
        }
    }

    private fun setStripchatPlayerChromeHidden(hidden: Boolean) {
        if (hidden) {
            val pageUrl = geckoCurrentUrl ?: currentPageUrl
            if (!pageUrl.contains("stripchat.com", ignoreCase = true)) return
        }

        isStripchatOverlayActive = hidden
        findViewById<View>(R.id.nav_container).visibility = if (hidden) View.GONE else View.VISIBLE
        btnSettings.visibility = if (hidden) View.GONE else View.VISIBLE
        btnPlay.visibility = if (!hidden && currentVideoUrl != null) View.VISIBLE else View.GONE
        updateCrossSiteSearchButtonVisibility()
        findViewById<View>(R.id.tv_footer_github).visibility = if (hidden) View.GONE else View.VISIBLE
        if (hidden) progressBar.visibility = View.GONE

        window.decorView.systemUiVisibility = if (hidden) {
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }

        val params = geckoView.layoutParams
        if (params is android.view.ViewGroup.MarginLayoutParams) {
            params.topMargin = if (hidden) {
                0
            } else {
                (60 * resources.displayMetrics.density).toInt()
            }
            geckoView.layoutParams = params
        }
    }

    private fun showPlayButtonIfAllowed() {
        btnPlay.visibility = if (
            isStripchatOverlayActive || isOnJavDbVideoPage || isOnJavTrailersVideoPage
        ) View.GONE else View.VISIBLE
    }

    private fun toggleStripchatRecordingFromPlayer() {
        if (StripchatRecordingService.isRecording) {
            startService(
                Intent(this, StripchatRecordingService::class.java)
                    .setAction(StripchatRecordingService.ACTION_STOP)
            )
            return
        }
        val pageUrl = geckoCurrentUrl ?: currentPageUrl
        if (!pageUrl.contains("stripchat.com", ignoreCase = true)) {
            Toast.makeText(this, "直播錄製僅支援 Stripchat 播放介面", Toast.LENGTH_SHORT).show()
            return
        }

        if (privacySettings.isScreenSecure) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            screenSecureClearedForRecording = true
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as
            android.media.projection.MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE_STRIPCHAT_RECORDING)
    }

    private fun requestStripchatPrivacyMode() {
        val isRecording = stripchatStreamRecordingStore.isActive() ||
            StripchatRecordingService.isRecording
        if (!isRecording && !stripchatWatchSessionActive) {
            Toast.makeText(this, "請先開始 Stripchat 監錄", Toast.LENGTH_SHORT).show()
            return
        }
        if (stripchatPrivacyDialog?.isShowing == true) return
        val pin = privacySettings.pinCode.orEmpty()
        if (!pin.matches(Regex("\\d{4}"))) {
            showSetStripchatPrivacyPinDialog()
            return
        }
        showStripchatPrivacyShield()
        // 隐私模式开关变化：推给页面，shim 的 isStripchatPrivacyMode() 读它。
        pushStripchatBridgeState()
    }

    private fun showSetStripchatPrivacyPinDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            hint = "4 位數字"
            setPadding(48, 20, 48, 20)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("設定錄影隱私 PIN")
            .setMessage("設定四位數字。之後觸碰黑色隱私畫面時，必須輸入此 PIN 才能繼續操作。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("儲存並啟用", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text?.toString().orEmpty()
                if (!pin.matches(Regex("\\d{4}"))) {
                    input.error = "請輸入四位數字"
                    return@setOnClickListener
                }
                privacySettings.pinCode = pin
                dialog.dismiss()
                showStripchatPrivacyShield()
            }
        }
        dialog.show()
    }

    private fun showStripchatPrivacyShield() {
        if (isFinishing || isDestroyed || stripchatPrivacyDialog?.isShowing == true) return
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            isFocusable = true
        }
        val pinPanel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            setPadding(28, 24, 28, 24)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(17, 17, 17))
                cornerRadius = 22f * resources.displayMetrics.density
                setStroke((1f * resources.displayMetrics.density).toInt(), android.graphics.Color.DKGRAY)
            }
        }
        val title = android.widget.TextView(this).apply {
            text = "輸入四位 PIN 解鎖"
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(12, 6, 12, 14)
        }
        val pinDisplay = android.widget.TextView(this).apply {
            text = "○ ○ ○ ○"
            textSize = 25f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(12, 4, 12, 16)
        }
        val pinGrid = android.widget.GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
        }
        val enteredPin = StringBuilder()

        fun updatePinDisplay(error: Boolean = false) {
            pinDisplay.text = (0 until 4).joinToString(" ") { index ->
                if (index < enteredPin.length) "●" else "○"
            }
            pinDisplay.setTextColor(
                if (error) android.graphics.Color.rgb(239, 83, 80)
                else android.graphics.Color.LTGRAY
            )
        }

        fun unlockIfValid() {
            if (enteredPin.length != 4) return
            if (privacySettings.validatePin(enteredPin.toString())) {
                dismissStripchatPrivacyShield()
            } else {
                updatePinDisplay(error = true)
                enteredPin.clear()
                stripchatPrivacyHandler.postDelayed({ updatePinDisplay() }, 650L)
            }
        }

        fun numberButton(label: String, action: () -> Unit): android.widget.Button =
            android.widget.Button(this).apply {
                text = label
                textSize = 20f
                setTextColor(android.graphics.Color.WHITE)
                minWidth = 0
                minHeight = 0
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.rgb(35, 35, 35)
                )
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = (72f * resources.displayMetrics.density).toInt()
                    height = (56f * resources.displayMetrics.density).toInt()
                    setMargins(5, 5, 5, 5)
                }
                setOnClickListener {
                    scheduleStripchatPinPadHide(pinPanel, dialog)
                    action()
                }
            }

        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9").forEach { digit ->
            pinGrid.addView(numberButton(digit) {
                if (enteredPin.length < 4) enteredPin.append(digit)
                updatePinDisplay()
                unlockIfValid()
            })
        }
        pinGrid.addView(numberButton("隱藏") {
            enteredPin.clear()
            updatePinDisplay()
            hideStripchatPinPad(pinPanel, dialog)
        })
        pinGrid.addView(numberButton("0") {
            if (enteredPin.length < 4) enteredPin.append('0')
            updatePinDisplay()
            unlockIfValid()
        })
        pinGrid.addView(numberButton("⌫") {
            if (enteredPin.isNotEmpty()) enteredPin.deleteCharAt(enteredPin.lastIndex)
            updatePinDisplay()
        })

        pinPanel.addView(title)
        pinPanel.addView(pinDisplay)
        pinPanel.addView(pinGrid)
        root.addView(
            pinPanel,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER,
            ),
        )
        root.setOnClickListener {
            if (pinPanel.visibility != View.VISIBLE) {
                pinPanel.visibility = View.VISIBLE
                setStripchatPrivacyBrightness(dialog, 0.18f)
                scheduleStripchatPinPadHide(pinPanel, dialog)
            }
        }
        pinPanel.setOnClickListener { scheduleStripchatPinPadHide(pinPanel, dialog) }

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            stripchatPrivacyHidePinRunnable?.let(stripchatPrivacyHandler::removeCallbacks)
            stripchatPrivacyHidePinRunnable = null
            if (!stripchatWatchSessionActive) {
                stopService(Intent(this, StripchatPrivacyKeepAliveService::class.java))
            }
            stripchatPrivacyDialog = null
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        setStripchatPrivacyBrightness(dialog, 0.01f)
        stripchatPrivacyDialog = dialog
        androidx.core.content.ContextCompat.startForegroundService(
            this,
            Intent(this, StripchatPrivacyKeepAliveService::class.java)
                .setAction(StripchatPrivacyKeepAliveService.ACTION_START),
        )
    }

    private fun scheduleStripchatPinPadHide(
        pinPanel: View,
        dialog: android.app.Dialog,
    ) {
        stripchatPrivacyHidePinRunnable?.let(stripchatPrivacyHandler::removeCallbacks)
        stripchatPrivacyHidePinRunnable = Runnable {
            hideStripchatPinPad(pinPanel, dialog)
        }.also { stripchatPrivacyHandler.postDelayed(it, 12_000L) }
    }

    private fun hideStripchatPinPad(pinPanel: View, dialog: android.app.Dialog) {
        pinPanel.visibility = View.GONE
        setStripchatPrivacyBrightness(dialog, 0.01f)
    }

    private fun setStripchatPrivacyBrightness(dialog: android.app.Dialog, brightness: Float) {
        dialog.window?.let { privacyWindow ->
            val attributes = privacyWindow.attributes
            attributes.screenBrightness = brightness.coerceIn(0.01f, 1f)
            privacyWindow.attributes = attributes
        }
    }

    private fun dismissStripchatPrivacyShield() {
        stripchatPrivacyDialog?.takeIf { it.isShowing }?.dismiss()
        pushStripchatBridgeState()
    }

    private fun restoreSecureWindowAfterRecording() {
        if (screenSecureClearedForRecording && privacySettings.isScreenSecure) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        screenSecureClearedForRecording = false
    }

    private fun startStripchatRecordingStatusMonitor(sourceUrl: String) {
        stripchatWatchLiveState = null
        stripchatWatchStatusChecked = false
        stripchatRecordingStatusUrl = sourceUrl
        stripchatRecordingModelId = 0L
        stripchatOfflineConfirmations = 0
        stripchatLastOfflineConfirmationAt = 0L
        stripchatRecordingStatusCheckInFlight.set(false)
        stripchatRecordingStatusGeneration.incrementAndGet()
        scheduleStripchatRecordingStatusCheck(
            if (stripchatWatchSessionActive && !stripchatWatchSessionRecording) 0L else 8_000L,
        )
    }

    private fun stopStripchatRecordingStatusMonitor() {
        stripchatWatchLiveState = null
        stripchatWatchStatusChecked = false
        stripchatRecordingStatusGeneration.incrementAndGet()
        stripchatRecordingStatusRunnable?.let(stripchatRecordingStatusHandler::removeCallbacks)
        stripchatRecordingStatusRunnable = null
        stripchatRecordingStatusUrl = ""
        stripchatRecordingModelId = 0L
        stripchatOfflineConfirmations = 0
        stripchatLastOfflineConfirmationAt = 0L
        stripchatRecordingStatusCheckInFlight.set(false)
    }

    private fun scheduleStripchatRecordingStatusCheck(delayMs: Long) {
        if (!stripchatStreamRecordingStore.isActive() &&
            !StripchatRecordingService.isRecording &&
            stripchatRecordingStatusUrl.isBlank()
        ) return
        if (stripchatRecordingStatusUrl.isBlank()) {
            stripchatRecordingStatusUrl = geckoCurrentUrl ?: currentPageUrl
        }
        val generation = stripchatRecordingStatusGeneration.get()
        stripchatRecordingStatusRunnable?.let(stripchatRecordingStatusHandler::removeCallbacks)
        stripchatRecordingStatusRunnable = Runnable {
            performStripchatRecordingStatusCheck(generation)
        }.also { stripchatRecordingStatusHandler.postDelayed(it, delayMs) }
    }

    private fun performStripchatRecordingStatusCheck(generation: Int) {
        if (generation != stripchatRecordingStatusGeneration.get()) return
        if (!stripchatRecordingStatusCheckInFlight.compareAndSet(false, true)) return
        val sourceUrl = stripchatRecordingStatusUrl
        val username = runCatching {
            Uri.parse(sourceUrl).path.orEmpty().trim('/').substringBefore('/')
        }.getOrDefault("")
        if (username.isBlank()) {
            stripchatRecordingStatusCheckInFlight.set(false)
            scheduleStripchatRecordingStatusCheck(15_000L)
            return
        }
        GeckoCookies.getCookie("https://stripchat.com/") { pageCookie ->
            performStripchatRecordingStatusCheckWithCookies(generation, sourceUrl, username, pageCookie)
        }
    }

    private fun performStripchatRecordingStatusCheckWithCookies(
        generation: Int,
        sourceUrl: String,
        username: String,
        pageCookie: String,
    ) {
        val cookie = mergeCookieStrings(
            pageCookie,
            android.webkit.CookieManager.getInstance()
                .getCookie("https://stripchat.com/").orEmpty(),
        )
        stripchatRecordingStatusExecutor.execute {
            val isLive = fetchStripchatRecordingLiveState(username, sourceUrl, cookie)
            runOnUiThread {
                if (generation != stripchatRecordingStatusGeneration.get()) return@runOnUiThread
                fun applyState(isLive: Boolean?, pageLive: Boolean = false) {
                if (generation != stripchatRecordingStatusGeneration.get() || isDestroyed) return
                stripchatRecordingStatusCheckInFlight.set(false)
                stripchatWatchLiveState = isLive
                stripchatWatchStatusChecked = true
                if (stripchatWatchSessionStarting &&
                    System.currentTimeMillis() - stripchatWatchStartRequestedAt >= 35_000L) {
                    stripchatWatchSessionStarting = false
                    stripchatWatchStartRequestedAt = 0L
                }
                val recordingActive = stripchatStreamRecordingStore.isActive() ||
                    StripchatRecordingService.isRecording
                when (isLive) {
                    true -> {
                        stripchatOfflineConfirmations = 0
                        stripchatLastOfflineConfirmationAt = 0L
                        if (stripchatWatchSessionActive && !recordingActive) {
                            val now = System.currentTimeMillis()
                            if (stripchatWatchSessionStarting &&
                                now - stripchatWatchStartRequestedAt >= 35_000L
                            ) {
                                stripchatWatchSessionStarting = false
                                stripchatWatchStartRequestedAt = 0L
                            }
                            startStripchatWatchRecordingIfReady(pageLive)
                        }
                    }
                    false -> {
                        if (recordingActive) {
                            val now = System.currentTimeMillis()
                            if (stripchatOfflineConfirmations == 0 ||
                                now - stripchatLastOfflineConfirmationAt >= 10_000L
                            ) {
                                stripchatOfflineConfirmations++
                                stripchatLastOfflineConfirmationAt = now
                                android.util.Log.i(
                                    "STRIPCHAT_RECORD_STATUS",
                                    "$username unavailable confirmation $stripchatOfflineConfirmations/3",
                                )
                            }
                        } else {
                            stripchatOfflineConfirmations = 0
                            stripchatLastOfflineConfirmationAt = 0L
                            stripchatWatchSessionStarting = false
                            stripchatWatchStartRequestedAt = 0L
                            notifyStripchatWatchState()
                        }
                    }
                    null -> {
                        stripchatOfflineConfirmations = 0
                        stripchatLastOfflineConfirmationAt = 0L
                        android.util.Log.w("STRIPCHAT_RECORD_STATUS", "$username status unknown; keeping recording")
                    }
                }
                notifyStripchatWatchState()
                if (recordingActive && stripchatOfflineConfirmations >= 3) {
                    autoStopStripchatRecording("公開直播已結束（下播或私人秀），自動停止錄製")
                } else {
                    val nextDelay = when {
                        recordingActive -> 15_000L
                        stripchatWatchSessionActive && stripchatWatchSessionStarting -> 20_000L
                        isLive == null -> 15_000L
                        stripchatWatchSessionActive -> 60_000L
                        else -> 15_000L
                    }
                    scheduleStripchatRecordingStatusCheck(nextDelay)
                }
                }
                if (isLive == false) applyState(false)
                else probeStripchatPagePlayback(sourceUrl) { pageLive ->
                    applyState(isLive ?: true.takeIf { pageLive }, pageLive)
                }
            }
        }
    }

    private fun probeStripchatPagePlayback(sourceUrl: String, callback: (Boolean) -> Unit) {
        val expectedKey = stripchatModelKey(sourceUrl)
        fun samePage() = expectedKey != null && !isDestroyed &&
            stripchatModelKey(geckoCurrentUrl.orEmpty()) == expectedKey
        if (!samePage()) { callback(false); return }
        val sample = "window.__javStripchatPlaybackProbe.sample(${org.json.JSONObject.quote(sourceUrl)})"
        GeckoEval.evaluate(geckoSession, stripchatPlaybackProbeJs + "\n" + sample) {
            geckoMainHandler.postDelayed({
                if (!samePage()) { callback(false); return@postDelayed }
                GeckoEval.evaluate(geckoSession, sample) { value -> callback(samePage() && value == "true") }
            }, 1_250L)
        }
    }

    private fun fetchStripchatRecordingLiveState(
        username: String,
        sourceUrl: String,
        cookie: String,
    ): Boolean? {
        return try {
            val snapshot = StripchatStatusApi.fetchSnapshot(
                client = stripchatRecordingStatusClient,
                username = username,
                referer = sourceUrl,
                cookie = cookie,
                knownModelId = stripchatRecordingModelId,
            ) ?: return null
            stripchatRecordingModelId = snapshot.modelId
            val root = snapshot.root
            val user = root.optJSONObject("user")?.optJSONObject("user") ?: return null
                val roomStatus = user.optString("status").trim().lowercase(java.util.Locale.US)
                val showMode = root.optJSONObject("cam")
                    ?.optJSONObject("show")
                    ?.optString("mode")
                    .orEmpty()
                    .trim()
                    .lowercase(java.util.Locale.US)
                val unavailableModes = setOf(
                    "off",
                    "away",
                    "p2p",
                    "private",
                    "privateshow",
                    "group",
                    "groupshow",
                    "ticket",
                    "ticketshow",
                )
                android.util.Log.d(
                    "STRIPCHAT_RECORD_STATUS",
                    "$username isLive=${user.optBoolean("isLive", false)} " +
                        "isOnline=${user.optBoolean("isOnline", false)} " +
                        "status=$roomStatus showMode=$showMode",
                )
            when {
                roomStatus in unavailableModes -> false
                showMode in unavailableModes -> false
                user.optBoolean("isLive", false) -> true
                user.has("isLive") -> false
                user.optBoolean("isOnline", false) -> true
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w("STRIPCHAT_RECORD_STATUS", "$username lookup failed", e)
            null
        }
    }

    private fun autoStopStripchatRecording(reason: String) {
        stopStripchatRecordingStatusMonitor()
        if (stripchatStreamRecordingStore.isActive()) {
            val quotedReason = org.json.JSONObject.quote(reason)
            GeckoEval.runJavascript(geckoSession, 
                "if(window.__javAutoStopStripchatRecording) window.__javAutoStopStripchatRecording($quotedReason);")
        }
        if (StripchatRecordingService.isRecording) {
            startService(
                Intent(this, StripchatRecordingService::class.java)
                    .setAction(StripchatRecordingService.ACTION_STOP)
                    .putExtra(StripchatRecordingService.EXTRA_STOP_REASON, reason),
            )
        }
    }

    private fun normalizeStripchatModelUrl(sourceUrl: String): String {
        val parsed = runCatching { Uri.parse(sourceUrl) }.getOrNull() ?: return sourceUrl
        return parsed.buildUpon().clearQuery().fragment(null).build().toString().trimEnd('/')
    }

    private fun startStripchatWatchRecordingIfReady(reusePlayingPage: Boolean = false) {
        if (!stripchatWatchSessionActive || stripchatWatchSessionRecording) return
        if (stripchatStreamRecordingStore.isActive() || StripchatRecordingService.isRecording) return
        if (stripchatWatchSessionStarting) return
        stripchatWatchSessionStarting = true
        stripchatWatchStartRequestedAt = System.currentTimeMillis()
        notifyStripchatWatchState()

        val currentUrl = geckoCurrentUrl ?: currentPageUrl
        val currentKey = stripchatModelKey(currentUrl)
        if (reusePlayingPage && currentKey == stripchatWatchSessionModelKey) {
            playInsideCurrentWebView(currentVideoUrl.orEmpty(), reusePageVideo = true)
            return
        }
        currentVideoUrl = null
        currentVideoReferer = originForUrl(stripchatWatchSessionUrl)
        if (currentKey == stripchatWatchSessionModelKey) {
            geckoSession.reload()
        } else {
            loadGeckoUrl(stripchatWatchSessionUrl)
        }
    }

    private fun maybeStartStripchatWatchRecording(videoUrl: String) {
        if (!stripchatWatchSessionActive || !stripchatWatchSessionStarting) return
        if (stripchatStreamRecordingStore.isActive() || StripchatRecordingService.isRecording) return
        val currentUrl = geckoCurrentUrl ?: currentPageUrl
        if (stripchatModelKey(currentUrl) != stripchatWatchSessionModelKey) return
        currentVideoReferer = originForUrl(stripchatWatchSessionUrl)
        playInsideCurrentWebView(videoUrl, reusePageVideo = true)
    }

    private fun stopStripchatWatchSession(manual: Boolean) {
        val wasActive = stripchatWatchSessionActive
        stripchatWatchSessionActive = false
        stripchatWatchSessionRecording = false
        stripchatWatchSessionStarting = false
        stripchatWatchStartRequestedAt = 0L
        stripchatWatchSessionUrl = ""
        stripchatWatchSessionModelKey = ""
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopStripchatRecordingStatusMonitor()
        if (stripchatStreamRecordingStore.isActive()) {
            val reason = org.json.JSONObject.quote("已手動停止監錄")
            GeckoEval.runJavascript(geckoSession, 
                "if(window.__javStopStripchatDirectRecording) window.__javStopStripchatDirectRecording($reason);")
        }
        if (StripchatRecordingService.isRecording) {
            startService(
                Intent(this, StripchatRecordingService::class.java)
                    .setAction(StripchatRecordingService.ACTION_STOP)
                    .putExtra(StripchatRecordingService.EXTRA_STOP_REASON, "已手動停止監錄"),
            )
        }
        notifyStripchatWatchState()
        if (stripchatPrivacyDialog?.isShowing != true) {
            stopService(Intent(this, StripchatPrivacyKeepAliveService::class.java))
        }
        if (manual && wasActive) {
            Toast.makeText(this, "已停止目前主播的持續監錄", Toast.LENGTH_SHORT).show()
        }
    }


    private fun handleStripchatAutoStopPrivacyState() {
        if (stripchatWatchSessionActive) return
        stripchatPrivacyDialog?.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        stopService(Intent(this, StripchatPrivacyKeepAliveService::class.java))
    }

    private fun runPlaybackPreflight(
        realUrl: String,
        referer: String,
        cookies: String,
        proxyMasterUrl: String,
        callback: (Boolean, String) -> Unit
    ) {
        kotlin.concurrent.thread {
            val report = StringBuilder()
            var ok = true

            fun appendStep(name: String, passed: Boolean, detail: String, blocking: Boolean = true) {
                if (!passed && blocking) ok = false
                report.append(if (passed) "✓ " else if (blocking) "✗ " else "！ ")
                    .append(name)
                    .append(": ")
                    .append(detail)
                    .append('\n')
            }

            val cdn = probeUrl(
                url = realUrl,
                referer = referer,
                cookies = "",
                range = null
            )
            appendStep(
                "CDN m3u8",
                cdn.isM3u8,
                "HTTP ${cdn.responseCode}, type=${cdn.contentType.ifEmpty { "-" }}, bytes=${cdn.snippet.length}",
                blocking = false
            )

            val proxy = probeUrl(
                url = proxyMasterUrl,
                referer = null,
                cookies = "",
                range = null
            )
            appendStep(
                "Proxy m3u8",
                proxy.isM3u8,
                "HTTP ${proxy.responseCode}, type=${proxy.contentType.ifEmpty { "-" }}, bytes=${proxy.snippet.length}"
            )

            val firstChildUrl = firstPlaylistUrl(proxy.snippet)
            if (firstChildUrl != null) {
                val child = probeUrl(
                    url = firstChildUrl,
                    referer = null,
                    cookies = "",
                    range = "bytes=0-1"
                )
                val childOk = child.responseCode == 200 || child.responseCode == 206 || child.isM3u8
                appendStep(
                    "Proxy first segment",
                    childOk,
                    "HTTP ${child.responseCode}, type=${child.contentType.ifEmpty { "-" }}, url=${redactUrlForDebug(firstChildUrl)}"
                )
            } else {
                appendStep("Proxy first segment", false, "playlist 裡找不到可播放子項目")
            }

            report.append('\n')
                .append("realUrl=").append(redactUrlForDebug(realUrl)).append('\n')
                .append("referer=").append(referer).append('\n')
                .append("cookieLen=").append(cookies.length).append('\n')
                .append("proxyUrl=").append(redactUrlForDebug(proxyMasterUrl)).append('\n')

            if (!ok) {
                report.append('\n').append("最後錯誤片段：").append('\n')
                val failedSnippet = listOf(cdn, proxy).firstOrNull { !it.isM3u8 }?.snippet
                if (!failedSnippet.isNullOrBlank()) report.append(failedSnippet.take(800))
            }

            runOnUiThread { callback(ok, report.toString()) }
        }
    }

    private data class ProbeResult(
        val responseCode: Int,
        val contentType: String,
        val snippet: String,
        val isM3u8: Boolean
    )

    private fun probeUrl(
        url: String,
        referer: String?,
        cookies: String,
        range: String?
    ): ProbeResult {
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            if (!referer.isNullOrBlank()) connection.setRequestProperty("Referer", referer)
            if (cookies.isNotBlank()) connection.setRequestProperty("Cookie", cookies)
            if (!range.isNullOrBlank()) connection.setRequestProperty("Range", range)
            val responseCode = connection.responseCode
            val contentType = connection.contentType ?: ""
            val stream = if (responseCode >= 400) connection.errorStream else connection.inputStream
            val snippet = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buffer = CharArray(4096)
                val count = reader.read(buffer)
                if (count > 0) String(buffer, 0, count) else ""
            } ?: ""
            ProbeResult(
                responseCode = responseCode,
                contentType = contentType,
                snippet = snippet,
                isM3u8 = responseCode in 200..299 &&
                    (contentType.lowercase().contains("mpegurl") || snippet.trimStart().startsWith("#EXTM3U"))
            )
        } catch (e: Exception) {
            ProbeResult(-1, "", e.javaClass.simpleName + ": " + (e.message ?: ""), false)
        } finally {
            connection?.disconnect()
        }
    }

    private fun firstPlaylistUrl(playlist: String): String? {
        return playlist.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun redactUrlForDebug(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: return url.take(180)
            val host = uri.host ?: return url.take(180)
            val port = if (uri.port != -1) ":${uri.port}" else ""
            val path = uri.path ?: ""
            val queryMarker = if (uri.query != null) "?..." else ""
            "$scheme://$host$port$path$queryMarker"
        } catch (e: Exception) {
            url.take(180)
        }
    }

    private fun showPlaybackDebugDialog(report: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("播放診斷")
            .setMessage(report.take(4000))
            .setPositiveButton("複製") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Playback Debug", report))
                Toast.makeText(this, "診斷內容已複製", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun startVideoProxyServer(): Boolean {
        return try {
            videoProxyServer?.stop()
            videoProxyServer = VideoProxyServer().also { proxy ->
                proxy.start()
                android.util.Log.d("VideoProxy", "Proxy started: ${proxy.healthUrl()}")
            }
            true
        } catch (e: Exception) {
            videoProxyServer = null
            android.util.Log.e("VideoProxy", "Failed to start proxy: ${e.message}", e)
            false
        }
    }

    private fun checkProxyHealth(proxy: VideoProxyServer): Boolean {
        val healthUrl = proxy.healthUrl()
        return try {
            val connection = java.net.URL(healthUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            val responseCode = connection.responseCode
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            responseCode == 200 && body == "OK"
        } catch (e: Exception) {
            android.util.Log.e("VideoProxy", "Proxy health check failed: $healthUrl", e)
            false
        }
    }

    private fun withHealthyProxy(onHealthy: (VideoProxyServer) -> Unit) {
        kotlin.concurrent.thread {
            var proxy = videoProxyServer
            var ok = proxy != null && checkProxyHealth(proxy)

            if (!ok) {
                android.util.Log.w("VideoProxy", "Proxy unhealthy, restarting before playback")
                startVideoProxyServer()
                proxy = videoProxyServer
                ok = proxy != null && checkProxyHealth(proxy)
            }

            runOnUiThread {
                val healthyProxy = proxy
                if (ok && healthyProxy != null) {
                    android.util.Log.d("VideoProxy", "Proxy health OK: ${healthyProxy.healthUrl()}")
                    onHealthy(healthyProxy)
                } else {
                    btnPlay.isEnabled = true
                    btnPlay.text = "▶"
                    Toast.makeText(this, "Proxy 重新啟動失敗，請完全關閉 APP 後再開啟", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchExternalPlayer(playUrl: String) {
        try {
            val mimeType = when {
                playUrl.contains(".mp4", ignoreCase = true) -> "video/mp4"
                playUrl.contains(".m3u8", ignoreCase = true) || playUrl.contains(".m3u", ignoreCase = true) -> "application/x-mpegURL"
                else -> "video/*"
            }
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.parse(playUrl), mimeType)

            // streamhls.click 相容性問題：強制每次彈出選擇器，同時包含媒體播放器和瀏覽器
            val useChooser = playUrl.contains("streamhls.click")

            if (useChooser) {
                // 額外加一個無 MIME type 的 Intent，讓瀏覽器也出現在選擇器裡
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(playUrl))
                val chooser = Intent.createChooser(intent, "選擇播放器或瀏覽器")
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(browserIntent))
                startActivity(chooser)
            } else if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                intent.setDataAndType(Uri.parse(playUrl), "video/*")
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.error_no_player, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onBackPressed() {
        // 點選屏蔽模式中，返回鍵先取消點選，不後退頁面。
        if (domBlockPickerActive) {
            exitDomBlockPicker()
            return
        }
        // 全螢幕模式中，返回鍵先退出全螢幕
        if (isGeckoFullscreen) {
            exitGeckoFullscreen()
            return
        }
        if (geckoCanGoBack()) {
            // 返回上一頁前，把當前的 Y 軸位置直接寫進該網頁的 sessionStorage
            GeckoEval.evaluate(geckoSession, 
                """
                (function() {
                    var sy = window.scrollY || window.pageYOffset || document.documentElement.scrollTop || 0;
                    var key = 'scrollPos__' + window.location.href;
                    sessionStorage.setItem(key, sy);
                    return sy;
                })();
                """.trimIndent()
            ) { sy ->
                android.util.Log.d("ScrollRestore", "Saved position to session: $sy")
                // 寫完後立刻執行返回
                geckoSession.goBack()
            }
        } else if (intent.getBooleanExtra(BrowserNavigator.EXTRA_RETURN_TO_SEARCH, false) ||
            intent.getBooleanExtra(BrowserNavigator.EXTRA_RETURN_TO_HISTORY, false)) {
            // 搜尋結果開啟的瀏覽頁已沒有上一頁，返回搜尋結果。
            finish()
        } else if (returnToSalesRanking) {
            // 排行頁仍在目前 task 下方；沒有網頁上一頁時回到原排行與捲動位置。
            returnToSalesRanking = false
            finish()
        } else {
            if (backPressedTime + 2000 > System.currentTimeMillis()) {
                showExitConfirmationDialog()
            } else {
                Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()
                backPressedTime = System.currentTimeMillis()
            }
        }
    }

    private fun showExitConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("退出應用程式")
            .setMessage("確定要退出嗎？")
            .setPositiveButton("是") { _, _ ->
                finish()
            }
            .setNegativeButton("否", null)
            .show()
    }
    
    private fun setupHomeButton() {
        updateHomeButtonForNavigationContext()
    }

    private fun updateHomeButtonForNavigationContext() {
        if (intent.getBooleanExtra(BrowserNavigator.EXTRA_RETURN_TO_HISTORY, false)) {
            btnHome.text = "×"
            btnHome.contentDescription = LanguageManager.text(this, "關閉並返回播放紀錄", "Close and return to playback history")
            btnHome.setOnClickListener { finish() }
        } else if (intent.getBooleanExtra(BrowserNavigator.EXTRA_RETURN_TO_SEARCH, false)) {
            btnHome.text = "×"
            btnHome.contentDescription = LanguageManager.text(
                this,
                "關閉並返回搜尋結果",
                "Close and return to search results"
            )
            btnHome.setOnClickListener { finish() }
        } else if (returnToSalesRanking) {
            btnHome.text = "×"
            btnHome.contentDescription = LanguageManager.text(
                this,
                "關閉並返回銷售排行",
                "Close and return to sales ranking"
            )
            btnHome.setOnClickListener { finish() }
        } else {
            btnHome.text = "🏠"
            btnHome.contentDescription = LanguageManager.text(this, "首頁", "Home")
            btnHome.setOnClickListener { loadLandingPage() }
        }
    }
    
    private fun isOnLandingPage(): Boolean {
        val url = geckoCurrentUrl
        return url == null || url == "about:blank" || url.startsWith("data:") || url == "https://javbrowser.app/"
    }
    
    // ── JAV code bookmark helpers ─────────────────────────────────────────────

    private var pendingJavCodesFromShare: List<String> = emptyList()

    private fun extractJavCodesFromText(text: String): List<String> {
        val re = Regex(
            "(?:FC2(?:[-_\\s]?PPV)?[-_\\s]?\\d{5,10}|[A-Za-z]{2,8}-\\d{1,5}(?:-\\d{2,5})?)",
            RegexOption.IGNORE_CASE
        )
        return re.findAll(text).map { it.value.uppercase() }.distinct().toList()
    }

    private fun processJavCodeQueue(codes: List<String>, index: Int) {
        if (index >= codes.size) return
        val rawCode  = codes[index].trim().uppercase()          // 原始格式，給 JavTrailers 搜尋用
        val code     = JavDbScraper.normalizeJavCode(rawCode)  // 正規化，給 JavDB / 去重 / CrossSite 用
        if (code.startsWith("FC2-PPV-", ignoreCase = true)) {
            processFc2CodeQueue(codes, index, code)
            return
        }

        // Phase 1: use JavTrailers for title + cover + gallery (no JavDB, no CrossSiteChecker)
        // JavDB enrichment (actors / genres / rating) is deferred to user-triggered lazy load
        JavTrailersScraper(this).scrape(rawCode) { result ->   // 傳原始格式給 JavTrailers
            runOnUiThread {
                // 優先用 JavTrailers 頁面 URL；FC2 或找不到時 fallback 到 JavDB 搜尋
                val bookmarkUrl = result?.pageUrl?.takeIf { it.isNotEmpty() }
                    ?: "https://javdb.com/search?q=${java.net.URLEncoder.encode(code, "UTF-8")}&f=all"
                val displayTitle = result?.title?.takeIf { it.isNotEmpty() } ?: code
                val coverUrl     = result?.coverUrl?.takeIf { it.isNotEmpty() }

                // addFavorite returns false if code/url already exists → silently skip duplicate
                val added = favoritesManager.addFavorite(displayTitle, bookmarkUrl, coverUrl, javCode = code)
                // 儲存 gallery 圖片 + 預告片（只在成功新增時存入，避免覆蓋已有資料）
                if (added && result?.galleryImages?.isNotEmpty() == true) {
                    val trailerUrl = result.trailerUrl.takeIf { it.isNotEmpty() }
                    favoritesManager.updateGalleryImages(bookmarkUrl, result.galleryImages, trailerUrl)
                }

                // 跨站連結檢測（背景靜默完成）
                if (added) {
                    CrossSiteChecker.checkAll(this, code, bookmarkUrl) { found ->
                        if (found.isNotEmpty()) {
                            favoritesManager.updateRelatedUrls(bookmarkUrl, found)
                            androidx.localbroadcastmanager.content.LocalBroadcastManager
                                .getInstance(this)
                                .sendBroadcast(android.content.Intent(FavoritesActivity.ACTION_FAVORITE_ENRICHED))
                        }
                    }
                }

                val done = index + 1
                GeckoEval.runJavascript(geckoSession, "updateBatchProgress($done, ${codes.size})")
                if (done < codes.size) processJavCodeQueue(codes, done)
            }
        }
    }

    private fun processFc2CodeQueue(codes: List<String>, index: Int, code: String) {
        MissavScraper(this, domainConfig).scrapeByCode(code) { result ->
            runOnUiThread {
                val bookmarkUrl = result?.pageUrl?.takeIf { it.isNotEmpty() }
                    ?: domainConfig.getMissAvSearchUrl(code)
                val displayTitle = result?.title?.takeIf { it.isNotEmpty() } ?: code
                val coverUrl = result?.coverUrl?.takeIf { it.isNotEmpty() }

                val added = favoritesManager.addFavorite(displayTitle, bookmarkUrl, coverUrl, javCode = code)
                if (added && result != null) {
                    val detailTitle = result.title
                        .removePrefix(code)
                        .removePrefix(code.lowercase())
                        .trim()
                    val detail = JavVideoDetail(
                        code = code,
                        title = detailTitle,
                        coverUrl = result.coverUrl,
                        date = result.releaseDate,
                        duration = "",
                        maker = result.maker,
                        series = result.series,
                        rating = "",
                        genres = result.genres,
                        actors = result.actors,
                        detailUrl = bookmarkUrl
                    )
                    favoritesManager.updateFavoriteDetail(bookmarkUrl, detail)
                }

                if (added) {
                    CrossSiteChecker.checkAll(this, code, bookmarkUrl) { found ->
                        if (found.isNotEmpty()) {
                            favoritesManager.updateRelatedUrls(bookmarkUrl, found)
                            androidx.localbroadcastmanager.content.LocalBroadcastManager
                                .getInstance(this)
                                .sendBroadcast(android.content.Intent(FavoritesActivity.ACTION_FAVORITE_ENRICHED))
                        }
                    }
                }

                val done = index + 1
                GeckoEval.runJavascript(geckoSession, "updateBatchProgress($done, ${codes.size})")
                if (done < codes.size) processJavCodeQueue(codes, done)
            }
        }
    }

    /** FC2 一律向 MISSAV 取得選擇性補全；找不到時回傳 null，絕不改送 JavDB 猜結果。 */
    private fun enrichMetadataForCode(javCode: String, callback: (JavVideoDetail?) -> Unit) {
        val fc2Code = JavDbScraper.extractFc2Code(javCode)
        if (fc2Code == null) {
            JavDbScraper(this).enrichFavorite(javCode, callback)
            return
        }
        MissavScraper(this, domainConfig).scrapeByCode(fc2Code) { result ->
            callback(result?.let {
                JavVideoDetail(
                    code = fc2Code,
                    title = it.title.removePrefix(fc2Code).trim(),
                    coverUrl = it.coverUrl,
                    date = it.releaseDate,
                    duration = "",
                    maker = it.maker,
                    series = it.series,
                    rating = "",
                    genres = it.genres,
                    actors = it.actors,
                    detailUrl = it.pageUrl
                )
            })
        }
    }

    private fun loadLandingPage() {
        // Reload the landing page
        val english = LanguageManager.isEnglish(this)
        val searchPlaceholder = if (english) "Search keywords or video code..." else "輸入搜尋關鍵字 / 番號..."
        val addBookmark = if (english) "Add Bookmark" else "存入書籤"
        val directLinks = if (english) "Go directly to" else "或直接前往"
        val recentReleases = if (english) "📅 Releases from the last 3 days" else "📅 近3日新着リスト"
        val salesRankings = if (english) "📊 Sales rankings" else "📊 銷售排行"
        val searchMissAvLabel = if (english) "Search MissAV" else "在 MissAV 搜尋"
        val searchJableLabel = if (english) "Search Jable.TV" else "在 Jable.TV 搜尋"
        val searchAvJoyLabel = if (english) "Search AvJoy" else "在 AvJoy 搜尋"
        val searchPigAvLabel = if (english) "Search PigAV" else "在 PigAV 搜尋"
        val searchAvTodayLabel = if (english) "Search AVToday" else "在 AVToday 搜尋"
        val searchJavHdLabel = if (english) "Search JavHDPorn" else "在 JavHDPorn 搜尋"
        val search7MmTvLabel = if (english) "Search 7MMTV" else "在 7MMTV 搜尋"
        val searchAvpleLabel = if (english) "Search Avple" else "在 Avple 搜尋"
        val searchWhosLabel = if (english) "Search Whos.tv" else "在 Whos.tv 搜尋"
        val bookmarkSinglePrefix = org.json.JSONObject.quote(if (english) "Add bookmark: " else "存入書籤：")
        val bookmarkBatchPrefix = org.json.JSONObject.quote(if (english) "Add bookmarks (" else "批量存入書籤 (")
        val bookmarkCountSuffix = org.json.JSONObject.quote(if (english) "): " else "個)：")
        val savingLabel = org.json.JSONObject.quote(if (english) "Saving... " else "存入中... ")
        val savedPrefix = org.json.JSONObject.quote(if (english) "✅ Saved " else "✅ 已存入 ")
        val savedSuffix = org.json.JSONObject.quote(if (english) " items" else " 個")
        val searchMissAvPrefix = org.json.JSONObject.quote("$searchMissAvLabel: ")
        val searchJablePrefix = org.json.JSONObject.quote("$searchJableLabel: ")
        val searchAvJoyPrefix = org.json.JSONObject.quote("$searchAvJoyLabel: ")
        val searchPigAvPrefix = org.json.JSONObject.quote("$searchPigAvLabel: ")
        val searchAvTodayPrefix = org.json.JSONObject.quote("$searchAvTodayLabel: ")
        val searchJavHdPrefix = org.json.JSONObject.quote("$searchJavHdLabel: ")
        val search7MmTvPrefix = org.json.JSONObject.quote("$search7MmTvLabel: ")
        val searchAvplePrefix = org.json.JSONObject.quote("$searchAvpleLabel: ")
        val searchWhosPrefix = org.json.JSONObject.quote("$searchWhosLabel: ")
        val adHeadline = if (english) "🐱 Lingmao Games — discounts, bonuses and member perks" else "🐱 靈貓遊戲 - 超低折扣 | 海量福利 | 專屬特權"
        val adAction = if (english) "Tap to download the app" else "點擊下載 APP"
        val landingHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    * { box-sizing: border-box; }
                    html { -webkit-text-size-adjust: 100%; }
                    body { 
                        background-color: #121212; 
                        color: white; 
                        font-family: system-ui, -apple-system, sans-serif;
                        min-height: 100vh;
                        margin: 0;
                        padding: 20px 14px 18px;
                    }
                    .page {
                        width: 100%;
                        max-width: 760px;
                        margin: 0 auto;
                    }
                    .title-row {
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        margin: 0 2px 14px;
                    }
                    h1 {
                        margin: 0;
                        font-size: 24px;
                        letter-spacing: .2px;
                    }
                    .search-container {
                        width: 100%;
                        margin-bottom: 12px;
                    }
                    .search-box {
                        width: 100%;
                        height: 48px;
                        padding: 0 14px;
                        font-size: 16px;
                        border: 2px solid #BB86FC;
                        border-radius: 12px;
                        background-color: #1E1E1E;
                        color: white;
                    }
                    .search-box:focus {
                        outline: none;
                        border-color: #CF6FFF;
                    }
                    .search-results {
                        width: 100%;
                        display: none;
                        box-sizing: border-box;
                        margin-top: 12px;
                        padding: 12px;
                        border-radius: 12px;
                        background: #1b1b1b;
                        border: 1px solid #38313f;
                    }
                    .search-results.show {
                        display: block;
                    }
                    .search-results > summary {
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        gap: 12px;
                        min-height: 40px;
                        width: 100%;
                        cursor: pointer;
                        list-style: none;
                        font-size: 14px;
                        line-height: 1.5;
                        color: #ddd;
                    }
                    .search-results > summary::-webkit-details-marker { display: none; }
                    .search-results > summary::after { content: '+'; color: #BB86FC; font-size: 22px; flex-shrink: 0; }
                    .search-results[open] > summary::after { content: '−'; }
                    .search-query-hint {
                        margin: 4px 0 12px;
                        font-size: 12px;
                        color: #a9a2b1;
                        overflow-wrap: anywhere;
                        line-height: 1.5;
                    }
                    .search-site-grid {
                        display: grid;
                        grid-template-columns: repeat(2, minmax(0, 1fr));
                        gap: 10px;
                    }
                    .search-results:not([open]) > .search-site-grid,
                    .search-results:not([open]) > .search-query-hint { display: none; }
                    a {
                        text-decoration: none;
                    }
                    .search-results a,
                    .site-grid a {
                        display: flex;
                        min-height: 44px;
                        padding: 8px 9px;
                        align-items: center;
                        justify-content: center;
                        background-color: #BB86FC; 
                        color: black; 
                        border-radius: 10px;
                        font-size: 14px;
                        font-weight: 700;
                        text-align: center;
                        line-height: 1.2;
                    }
                    .search-results a:active,
                    .site-grid a:active {
                        background-color: #CF6FFF;
                    }
                    .search-site-grid a {
                        box-sizing: border-box;
                        min-width: 0;
                        min-height: 48px;
                        padding: 10px 8px;
                        color: #24152B;
                        overflow-wrap: anywhere;
                    }
                    .search-site-grid a:focus-visible { outline: 2px solid white; outline-offset: 2px; }
                    .quick-grid {
                        display: grid;
                        grid-template-columns: repeat(2, minmax(0, 1fr));
                        gap: 8px;
                        margin: 0 0 14px;
                    }
                    .quick-grid a {
                        display: flex;
                        min-height: 54px;
                        padding: 9px;
                        align-items: center;
                        justify-content: center;
                        border-radius: 12px;
                        color: #fff;
                        font-size: 14px;
                        font-weight: 800;
                        line-height: 1.25;
                        text-align: center;
                    }
                    .divider {
                        width: 100%;
                        margin: 6px 2px 8px;
                        color: #aaa;
                        font-size: 13px;
                        font-weight: 700;
                    }
                    .site-grid {
                        display: grid;
                        grid-template-columns: repeat(2, minmax(0, 1fr));
                        gap: 8px;
                    }
                    #playback-history-link {
                        display: block; width: 100%; box-sizing: border-box; margin-top: 14px;
                        padding: 16px; min-height: 54px; border-radius: 12px; background: #776014;
                        color: #FFF4C4; font-size: 16px; font-weight: 800; text-align: center;
                    }
                    #playback-history-link:active { background: #967B22; }
                    .help-button {
                        width: 32px;
                        height: 32px;
                        border-radius: 50%;
                        background-color: #333;
                        color: white;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        font-weight: bold;
                        font-size: 14px;
                        cursor: pointer;
                        border: 1px solid #666;
                    }
                    #jav-hint { margin-top: 8px !important; }
                    #clipboard-link { margin: 0 0 14px; padding: 14px; border: 1px solid #BB86FC; border-radius: 12px; background: #251b32; }
                    #clipboard-link[hidden] { display: none; }
                    #clipboard-link p { margin: 6px 0 12px; }
                    #clipboard-link-url { display: block; color: #ccc; font-size: 13px; overflow-wrap: anywhere; margin-bottom: 12px; }
                    .clipboard-actions { display: flex; gap: 8px; }
                    .clipboard-actions button { min-height: 44px; padding: 10px 16px; border: 0; border-radius: 8px; font-weight: bold; }
                    #clipboard-link-open { background: #BB86FC; color: #24152B; }
                    #clipboard-link-dismiss { background: #3b3345; color: #fff; }
                    #btn-save-jav {
                        min-height: 44px;
                        padding: 8px 12px !important;
                        border-radius: 10px !important;
                        font-size: 14px !important;
                    }
                    .ad-wrap {
                        position: relative;
                        margin-top: 14px;
                    }
                    .ad-card {
                        display: flex;
                        min-height: 58px;
                        padding: 10px 46px 10px 12px;
                        align-items: center;
                        justify-content: space-between;
                        gap: 8px;
                        background: linear-gradient(135deg, #586bd8 0%, #7447a8 100%);
                        border-radius: 12px;
                        color: white;
                        box-shadow: 0 3px 10px rgba(0,0,0,.25);
                    }
                    .ad-main { font-size: 13px; font-weight: 800; line-height: 1.25; }
                    .ad-action { flex: 0 0 auto; font-size: 12px; opacity: .9; }
                    .ad-toggle {
                        position: absolute;
                        top: 9px;
                        right: 8px;
                        width: 32px;
                        height: 32px;
                        padding: 0;
                        border: 0;
                        border-radius: 8px;
                        background: rgba(0,0,0,.25);
                        color: #fff;
                        font-size: 18px;
                    }
                    .ad-wrap.collapsed .ad-card { min-height: 42px; padding-top: 7px; padding-bottom: 7px; }
                    .ad-wrap.collapsed .ad-action { display: none; }
                    @media (min-width: 620px) {
                        .site-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
                        .search-site-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
                    }
                </style>
            </head>
            <body>
              <main class="page">
                <div class="title-row">
                    <h1>JAV Browser</h1>
                    <div class="help-button" onclick="showHelp()">?</div>
                </div>

                <section id="clipboard-link" hidden aria-live="polite">
                    <strong>${if (english) "Copied link from " else "剪貼簿網址："}<span id="clipboard-link-site"></span></strong>
                    <p>${if (english) "Open this page?" else "要前往這個頁面嗎？"}</p>
                    <span id="clipboard-link-url"></span>
                    <div class="clipboard-actions">
                        <button id="clipboard-link-open" onclick="Android.openClipboardLink()">${if (english) "Open page" else "前往此頁面"}</button>
                        <button id="clipboard-link-dismiss" onclick="document.getElementById('clipboard-link').hidden=true;Android.dismissClipboardLink()">${if (english) "Not now" else "暫時不要"}</button>
                    </div>
                </section>
                
                <div class="search-container">
                    <input type="text" id="searchInput" class="search-box" placeholder="$searchPlaceholder" />
                    <button id="unifiedSearchButton" onclick="Android.openUnifiedSearch(document.getElementById('searchInput').value)" style="display:block;width:100%;margin-top:10px;padding:14px;background:#BB86FC;color:#24152B;border:0;border-radius:8px;font-size:16px;font-weight:bold;">${if (english) "Search all sites · Unified results" else "統一跨站搜尋 · 彙整結果"}</button>
                    <div id="jav-hint" style="display:none; margin-top:10px;">
                        <button id="btn-save-jav" onclick="handleSaveJav()" style="display:block; width:100%; padding:15px 30px; background:#BB86FC; color:black; border:none; border-radius:8px; cursor:pointer; font-size:16px; font-weight:bold; text-align:center; box-sizing:border-box; transition:background 0.3s ease;">📚 <span id="jav-detected-label">$addBookmark</span></button>
                    </div>
                    <details id="searchResults" class="search-results">
                        <summary>${if (english) "Fallback: search on an original site" else "備用：到個別原站搜尋"}</summary>
                        <p id="searchQueryHint" class="search-query-hint"></p>
                        <div class="search-site-grid">
                            <a href="#" id="searchMissAV">MissAV</a>
                            <a href="#" id="searchJable">Jable.TV</a>
                            <a href="#" id="searchAvJoy">AvJoy</a>
                            <a href="#" id="searchPigAV">PigAV</a>
                            <a href="#" id="searchAVToday">AVToday</a>
                            <a href="#" id="searchJavHD">JavHDPorn</a>
                            <a href="#" id="search7MMTV">7MMTV</a>
                            <a href="#" id="searchAvple">Avple</a>
                            <a href="#" id="searchWhos">Whos.tv</a>
                            <a href="#" id="searchJavday">JAVDAY</a>
                            <a href="#" id="searchRouVideos">${if (english) "ROU · Videos" else "ROU · 影片"}</a>
                            <a href="#" id="searchRouSeries">${if (english) "ROU · Series" else "ROU · 影集"}</a>
                        </div>
                    </details>
                </div>

                <div class="quick-grid">
                    <a href="javascript:Android.openTodayNew()" style="background:#1565C0;">$recentReleases</a>
                    <a href="javascript:Android.openSalesRankings()" style="background:#00897B;">$salesRankings</a>
                </div>

                <div class="divider">$directLinks</div>
                <div class="site-grid">
                
                    <a href="javascript:Android.openSiteBrowse('missav', '${domainConfig.getMissAvBaseUrl()}')">MissAV</a>
                    <a href="javascript:Android.openSiteBrowse('jable', 'https://${domainConfig.getJableDomain()}/')">Jable</a>
                    <a href="javascript:Android.openSiteBrowse('rou_video', 'https://${domainConfig.getRouVideoDomain()}/home')">Rou.Video</a>
                    <a href="javascript:Android.openSiteBrowse('avjoy', 'https://${domainConfig.getAvJoyDomain()}/')">AvJoy</a>
                    <a href="javascript:Android.openSiteBrowse('pigav', 'https://pigav.ws/')">PigAV</a>
                    <a href="javascript:Android.openSiteBrowse('avtoday', 'https://avtoday.io/cht/index.html')">AVToday</a>
                    <a href="javascript:Android.openSiteBrowse('javhd', 'https://www.javhdporn.net/')">JavHDPorn</a>
                    <a href="javascript:Android.openSiteBrowse('7mmtv', '${domainConfig.get7MmTvBaseUrl()}')">7MMTV</a>
                    <a href="javascript:Android.openSiteBrowse('avple', '${domainConfig.getAvpleBaseUrl()}')">Avple</a>
                    <a href="javascript:Android.openSiteBrowse('whos', '${domainConfig.getWhosBaseUrl()}')">Whos.tv</a>
                    <a href="javascript:Android.openSiteBrowse('javday', 'https://javday.app/')">JAVDAY</a>
                    <a href="javascript:Android.openSiteBrowse('supjav', 'https://supjav.com/')">SUPJAV</a>
                    <a href="javascript:Android.openSiteBrowse('javrate', 'https://www.javrate.com/')">JavRate</a>
                    <a href="javascript:Android.navigateToUrl('https://javdb.com/')">JavDB</a>
                    <a href="javascript:Android.navigateToUrl('https://javtrailers.com/ja/videos')">JavTrailers</a>
                    <a href="javascript:Android.navigateToUrl('https://zt.stripchat.com/')">Stripchat</a>
                    <a href="javascript:Android.navigateToUrl('https://cn.pornhub.com/')">Pornhub CN</a>
                    <a href="javascript:Android.navigateToUrl('https://www.xvideos.com/')">XVideos</a>
                </div>
                <a id="playback-history-link" href="javascript:Android.openPlaybackHistory()">${if (english) "📜 Playback history" else "📜 播放紀錄"}</a>

                <script>
                    const searchInput = document.getElementById('searchInput');
                    const searchResults = document.getElementById('searchResults');
                    const searchMissAV = document.getElementById('searchMissAV');
                    const searchJable = document.getElementById('searchJable');
                    const searchAvJoy = document.getElementById('searchAvJoy');
                    const searchPigAV = document.getElementById('searchPigAV');
                    const searchAVToday = document.getElementById('searchAVToday');
                    const searchJavHD = document.getElementById('searchJavHD');
                    const search7MMTV = document.getElementById('search7MMTV');
                    const searchAvple = document.getElementById('searchAvple');
                    const searchWhos = document.getElementById('searchWhos');
                    const searchJavday = document.getElementById('searchJavday');
                    const searchRouVideos = document.getElementById('searchRouVideos');
                    const searchRouSeries = document.getElementById('searchRouSeries');

                    // ── JAV code detection ──────────────────────────────────────
                    var _javCodes = [];
                    var _JAV_RE = /\b(FC2(?:[-_\s]?PPV)?[-_\s]?\d{5,10}|[A-Za-z]{2,8}-\d{1,5}(?:-\d{2,5})?)\b/gi;
                    function detectJavCodes(text) {
                        var m, codes = [];
                        _JAV_RE.lastIndex = 0;
                        while ((m = _JAV_RE.exec(text)) !== null) {
                            var c = m[1].toUpperCase();
                            if (codes.indexOf(c) === -1) codes.push(c);
                        }
                        return codes;
                    }
                    function updateJavHint(codes) {
                        var hint = document.getElementById('jav-hint');
                        var label = document.getElementById('jav-detected-label');
                        if (codes.length > 0) {
                            hint.style.display = 'block';
                            if (codes.length === 1) {
                                label.textContent = $bookmarkSinglePrefix + codes[0];
                            } else {
                                label.textContent = $bookmarkBatchPrefix + codes.length + $bookmarkCountSuffix +
                                    codes.slice(0, 3).join(', ') + (codes.length > 3 ? '…' : '');
                            }
                        } else {
                            hint.style.display = 'none';
                        }
                    }
                    function handleSaveJav() {
                        if (_javCodes.length === 0) return;
                        var btn = document.getElementById('btn-save-jav');
                        btn.disabled = true;
                        btn.style.cursor = 'not-allowed';
                        btn.style.background = 'linear-gradient(to right, #7B1FA2 0%, #BB86FC 0%)';
                        document.getElementById('jav-detected-label').textContent = $savingLabel + '0 / ' + _javCodes.length;
                        Android.saveJavCodes(JSON.stringify(_javCodes));
                    }
                    function updateBatchProgress(done, total) {
                        var btn = document.getElementById('btn-save-jav');
                        var pct = Math.round(done / total * 100);
                        btn.style.background = 'linear-gradient(to right, #7B1FA2 ' + pct + '%, #BB86FC ' + pct + '%)';
                        document.getElementById('jav-detected-label').textContent = $savingLabel + done + ' / ' + total;
                        if (done >= total) {
                            btn.style.background = '#7B1FA2';
                            document.getElementById('jav-detected-label').textContent = $savedPrefix + total + $savedSuffix;
                            setTimeout(function() {
                                btn.disabled = false;
                                btn.style.cursor = 'pointer';
                                btn.style.background = '#BB86FC';
                                updateJavHint(_javCodes);
                            }, 1800);
                        }
                    }
                    // 剪貼簿回呼：直接填入搜尋框，顯示紫色存入書籤按鈕
                    function onClipboardResult(text, link) {
                        var card = document.getElementById('clipboard-link');
                        card.hidden = !link;
                        document.getElementById('clipboard-link-site').textContent = link ? link.site : '';
                        document.getElementById('clipboard-link-url').textContent = link ? link.url : '';
                        if (link) return;
                        var codes = detectJavCodes(text);
                        if (codes.length > 0) {
                            searchInput.value = codes.join(' ');
                            searchInput.dispatchEvent(new Event('input'));
                        }
                    }
                    function prefillJavCodes(codesJson) {
                        var codes = JSON.parse(codesJson);
                        if (codes.length > 0) {
                            searchInput.value = codes.join(' ');
                            searchInput.dispatchEvent(new Event('input'));
                        }
                    }
                    window.addEventListener('load', function() {
                        if (typeof Android !== 'undefined') Android.checkClipboard();
                    });
                    // ── Search input listener ────────────────────────────────────
                    searchInput.addEventListener('input', function() {
                        const keyword = this.value.trim();
                        _javCodes = detectJavCodes(keyword);
                        updateJavHint(_javCodes);
                        if (keyword.length > 0) {
                            searchResults.classList.add('show');
                            document.getElementById('searchQueryHint').textContent =
                                ${org.json.JSONObject.quote(if (english) "Search: " else "搜尋內容：")} + keyword;
                            document.querySelectorAll('.search-site-grid a').forEach(function(link) {
                                link.setAttribute('aria-label', link.textContent + ': ' + keyword);
                            });

                            // Update URLs
                            searchMissAV.href = 'https://${domainConfig.getMissAvDomain()}/search/' + encodeURIComponent(keyword);
                            searchJable.href = 'https://jable.tv/search/' + encodeURIComponent(keyword) + '/';
                            searchAvJoy.href = 'https://${domainConfig.getAvJoyDomain()}/search/videos/' + encodeURIComponent(keyword);
                            searchPigAV.href = 'https://pigav.ws/search?search=' + encodeURIComponent(keyword) + '&searchTarget=local';
                            searchAVToday.href = 'https://avtoday.io/search?s=' + encodeURIComponent(keyword);
                            searchJavHD.href = 'https://www.javhdporn.net/?s=' + encodeURIComponent(keyword);
                            search7MMTV.href = 'https://${domainConfig.get7MmTvDomain()}/zh/searchall_search/all/' + encodeURIComponent(keyword) + '/1.html';
                            searchAvple.href = 'https://${domainConfig.getAvpleDomain()}/search?key=' + encodeURIComponent(keyword);
                            searchWhos.href = 'https://${domainConfig.getWhosDomain()}/result?search=' + encodeURIComponent(keyword);
                            searchJavday.href = 'https://javday.app/search/?wd=' + encodeURIComponent(keyword);
                            searchRouVideos.href = 'https://${domainConfig.getRouVideoDomain()}/search?q=' + encodeURIComponent(keyword);
                            searchRouSeries.href = searchRouVideos.href + '&tab=series';
                        } else {
                            searchResults.classList.remove('show');
                        }
                    });

                    searchInput.addEventListener('keypress', function(e) {
                        if (e.key === 'Enter' && this.value.trim().length > 0) {
                            e.preventDefault();
                            Android.openUnifiedSearch(this.value.trim());
                        }
                    });

                    function showHelp() {
                        Android.showHelpDialog();
                    }
                    function toggleLandingAd(event) {
                        event.preventDefault();
                        event.stopPropagation();
                        var ad = document.getElementById('lingmaoAd');
                        var collapsed = ad.classList.toggle('collapsed');
                        event.currentTarget.textContent = collapsed ? '+' : '−';
                        try { localStorage.setItem('landingAdCollapsed', collapsed ? '1' : '0'); } catch (e) {}
                    }
                    window.addEventListener('DOMContentLoaded', function() {
                        try {
                            if (localStorage.getItem('landingAdCollapsed') === '1') {
                                var ad = document.getElementById('lingmaoAd');
                                ad.classList.add('collapsed');
                                ad.querySelector('.ad-toggle').textContent = '+';
                            }
                        } catch (e) {}
                    });
                </script>
                
                <div class="ad-wrap" id="lingmaoAd">
                    <a class="ad-card" href="https://www.277sy.com/index.php/Rmiddle/down_ra/?appid=401&tgid=da0003500&type=1">
                        <span class="ad-main">$adHeadline</span>
                        <span class="ad-action">$adAction</span>
                    </a>
                    <button class="ad-toggle" onclick="toggleLandingAd(event)" aria-label="收合廣告">−</button>
                </div>
              </main>
            </body>
            </html>
        """.trimIndent()
        // A null history URL makes WebView.getUrl() report about:blank on some devices.
        // Keep the native home check consistent with the document's base URL.
        // Gecko 的 Loader().data() 没有 base URL 参数；首页 HTML 里没有
        // 相对引用（无 href="/、src="/、fetch()），直接用 data: 加载是安全的。
        // mapGeckoUrl() 会把 data: 地址映射回 LANDING_URL，
        // isOnLandingPage() 与 refreshHomeClipboard() 的判断不受影响。
        geckoCurrentUrl = LANDING_URL
        landingDataShown = true
        geckoSession.load(GeckoSession.Loader().data(landingHtml, "text/html"))
    }
    
    private fun downloadAndInstallApk(url: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setData(android.net.Uri.parse(url))
        startActivity(intent)
    }

    private fun setupCrossSiteSearchButton() {
        btnCrossSiteSearch.setOnClickListener {
            showCrossSiteSearchSheet()
        }
    }

    private fun updateCrossSiteSearchButtonVisibility() {
        updateCopyPageUrlVisibility()
        btnCrossSiteSearch.visibility = if (
            isCrossSiteSupportedPage(currentPageUrl) && !isStripchatOverlayActive
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun isCrossSiteSupportedPage(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        return host == "javdb.com" || host.endsWith(".javdb.com") ||
            host == "javtrailers.com" || host.endsWith(".javtrailers.com") ||
            host == "javhdporn.net" || host.endsWith(".javhdporn.net") ||
            host.contains("missav.") || host == "jable.tv" || host.endsWith(".jable.tv") ||
            host == "avjoy.me" || host.endsWith(".avjoy.me") ||
            host == "pigav.ws" || host.endsWith(".pigav.ws") ||
            host == "avtoday.io" || host.endsWith(".avtoday.io") ||
            host == "7mmtv.sx" || host.endsWith(".7mmtv.sx") ||
            host.matches(Regex("7tv\\d*\\.com")) || host.endsWith(".7tv.com") ||
            host == "avple.tv" || host.endsWith(".avple.tv") ||
            host == "whos.tv" || host.endsWith(".whos.tv") || AdditionalSiteMedia.isPage(url)
    }

    private fun updateCopyPageUrlVisibility() {
        if (!::btnCopyPageUrl.isInitialized || !::geckoSession.isInitialized) return
        btnCopyPageUrl.visibility = if (!isGeckoFullscreen && !isStripchatOverlayActive &&
            PageUrlClipboard.copyableUrl(geckoCurrentUrl) != null) View.VISIBLE else View.GONE
    }

    private fun copyCurrentPageUrl() {
        // Query WebView on click so redirects and client-side history are reflected immediately.
        val url = PageUrlClipboard.copyableUrl(geckoCurrentUrl)
        if (url == null) {
            Toast.makeText(this, LanguageManager.text(this, "目前沒有可複製的網頁網址", "No page URL to copy"), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
        Toast.makeText(this, LanguageManager.text(this, "已複製網址", "URL copied"), Toast.LENGTH_SHORT).show()
    }

    private fun updateCrossSiteCode(rawCode: String?) {
        val normalized = rawCode.orEmpty().trim().uppercase(Locale.ROOT)
        val valid = normalized.matches(Regex("[A-Z0-9]{2,10}(?:-[A-Z0-9]{1,10})+")) &&
            normalized.length <= 40 && isCrossSiteSupportedPage(currentPageUrl)
        if (!valid) {
            crossSiteCode = null
            btnCrossSiteSearch.contentDescription = "統一跨站搜尋"
            updateCrossSiteSearchButtonVisibility()
            return
        }

        crossSiteCode = normalized
        btnCrossSiteSearch.contentDescription = "跨站搜尋 $normalized"
        updateCrossSiteSearchButtonVisibility()
    }

    private fun clearCrossSiteCode() {
        crossSiteCode = null
        updateCrossSiteSearchButtonVisibility()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)


    /** 將舊的九個按鈕入口改為統一抓取結果頁。 */
    private fun showCrossSiteSearchSheet() {
        val code = crossSiteCode.orEmpty()
        startActivity(Intent(this, UnifiedSearchActivity::class.java).apply {
            putExtra(UnifiedSearchActivity.EXTRA_QUERY, code)
            putExtra(UnifiedSearchActivity.EXTRA_FORCE_CODE, code.isNotBlank())
        })
    }
    
    private fun setupSettingsButton() {
        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupDownloadsButton() {
        btnDownloads.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
    }

    private fun startLoadTimeout() {
        // Cancel any existing timeout
        cancelLoadTimeout()
        
        // Initialize handler if needed
        if (timeoutHandler == null) {
            timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        }
        
        // Create and schedule timeout runnable
        timeoutRunnable = Runnable {
            android.util.Log.w("JAVBrowser", "[TIMEOUT] Page load timeout after ${TIMEOUT_DURATION}ms")
            
            runOnUiThread {
                progressBar.visibility = View.GONE
                
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Connection Timeout")
                    .setMessage("Page load took too long.\n\nSuggestions:\n• Check your internet connection\n• Switch between WiFi/4G\n• Tap Retry")
                    .setPositiveButton("Retry") { _, _ ->
                        geckoSession.reload()
                    }
                    .setNegativeButton("Go Home") { _, _ ->
                        loadLandingPage()
                    }
                    .setNeutralButton("Cancel", null)
                    .show()
            }
        }
        
        timeoutHandler?.postDelayed(timeoutRunnable!!, TIMEOUT_DURATION)
    }
    
    private fun cancelLoadTimeout() {
        timeoutRunnable?.let {
            timeoutHandler?.removeCallbacks(it)
            timeoutRunnable = null
        }
    }
    
    private fun getErrorPageHtml(errorDescription: String, failingUrl: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        background-color: #121212;
                        color: white;
                        font-family: Arial, sans-serif;
                        text-align: center;
                        padding: 40px 20px;
                        margin: 0;
                    }
                    .error-icon {
                        font-size: 80px;
                        margin-bottom: 20px;
                    }
                    h1 {
                        color: #FF6B6B;
                        margin-bottom: 15px;
                    }
                    p {
                        color: #CCCCCC;
                        line-height: 1.6;
                        margin-bottom: 10px;
                    }
                    .url {
                        background-color: #1E1E1E;
                        padding: 10px;
                        border-radius: 5px;
                        word-break: break-all;
                        margin: 20px 0;
                        font-size: 14px;
                        color: #888;
                    }
                    .button {
                        display: inline-block;
                        background-color: #BB86FC;
                        color: black;
                        padding: 12px 30px;
                        margin: 10px 5px;
                        border-radius: 8px;
                        text-decoration: none;
                        font-weight: bold;
                    }
                    .button:active {
                        background-color: #CF6FFF;
                    }
                    .suggestions {
                        text-align: left;
                        max-width: 400px;
                        margin: 30px auto;
                        background-color: #1E1E1E;
                        padding: 20px;
                        border-radius: 10px;
                    }
                    .suggestions h3 {
                        color: #BB86FC;
                        margin-top: 0;
                    }
                    .suggestions li {
                        margin: 10px 0;
                        color: #CCCCCC;
                    }
                </style>
            </head>
            <body>
                <div class="error-icon">⚠️</div>
                <h1>無法載入頁面</h1>
                <p>$errorDescription</p>
                <div class="url">$failingUrl</div>
                
                <div class="suggestions">
                    <h3>💡 建議解決方式：</h3>
                    <ul>
                        <li>檢查網路連線是否正常</li>
                        <li>嘗試切換 WiFi 和行動數據</li>
                        <li>重新整理頁面</li>
                        <li>稍後再試</li>
                    </ul>
                </div>

                <a href="javascript:location.reload();" class="button">🔄 重新載入</a>
                <br><br>
                <a href="javascript:Android.loadLandingPage();" class="button" style="background-color: #333; color: white; border: 1px solid #555;">🏠 返回首頁</a>
                
                <script>
                    // Ensure Android interface exists for the back button
                    if (typeof Android === 'undefined') {
                        Android = {
                            loadLandingPage: function() { window.history.back(); }
                        };
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }
    
    private fun showHelpDialog() {
        val message = """
        
            JAV Browser - Video Player & Downloader
            
            🎬 Features:
            • Auto-detect m3u8 video streams
            • Play externally (VLC, MX Player)
            • Download support
            • Ad blocking
            • Favorites system
            
            📱 Recommended Players:
            • VLC Media Player
            • MX Player
            • KM Player
            
            💾 Recommended Downloader:
            Lj Video Downloader (m3u8, mp4, mpd)
            
            💡 Tip:
            Ad-free MOD versions of Lj Downloader are available online.
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("❓ Help")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        cancelPendingFilePrompt()
        supjavMediaResolver.close()
        // 取消註冊書籤頁 URL 廣播接收器
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(favUrlReceiver)
        runCatching { unregisterReceiver(stripchatRecordingReceiver) }
        cancelLoadTimeout()
        videoProxyServer?.stop()
        videoProxyServer = null
        dismissStripchatPrivacyShield()
        stopStripchatRecordingStatusMonitor()
        stripchatRecordingStatusExecutor.shutdownNow()
        stopService(Intent(this, StripchatPrivacyKeepAliveService::class.java))
        // 拆掉桥与嗅探，关闭主会话（GeckoView 随布局一起销毁）。
        if (::geckoSession.isInitialized) {
            runCatching { GeckoBridge.detachBridge(geckoSession) }
            runCatching { JavhdGeckoExtension.detachMediaSniffer(geckoSession) }
            runCatching { geckoSession.close() }
        }
        super.onDestroy()
    }
}
