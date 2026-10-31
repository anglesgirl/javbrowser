package com.example.javbrowser

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : LocalizedActivity() {

    private fun t(zh: String, en: String) = LanguageManager.text(this, zh, en)

    private lateinit var switchLock: SwitchCompat
    private lateinit var switchScreenSecure: SwitchCompat
    private lateinit var switchAlwaysInternalPlayer: SwitchCompat
    private lateinit var radioGroup: RadioGroup
    private lateinit var btnBack: Button
    private lateinit var privacySettings: PrivacySettings
    private lateinit var appIconManager: AppIconManager
    private lateinit var biometricHelper: BiometricHelper
    private lateinit var adFilterRules: AdFilterRules
    private lateinit var etCloudUrl: android.widget.EditText
    private lateinit var btnUpdateFromCloud: Button
    private lateinit var tvRulesStatus: android.widget.TextView
    private lateinit var btnExportRules: Button
    private lateinit var btnImportRules: Button
    private lateinit var etScrapeDoToken: android.widget.EditText
    private lateinit var btnSaveScrapeDoToken: Button
    private lateinit var rgEnrichMethod: RadioGroup
    private lateinit var btnImportFavorites: Button
    private lateinit var btnExportFavorites: Button
    private lateinit var favoritesManager: FavoritesManager
    private lateinit var etDoubleTapSeekSeconds: android.widget.EditText
    private lateinit var btnSaveDoubleTapSeekSeconds: Button
    private lateinit var etPlayerShortSeekSeconds: android.widget.EditText
    private lateinit var etPlayerLongSeekSeconds: android.widget.EditText
    private lateinit var btnSavePlayerSeekButtons: Button
    private lateinit var etPlaybackSpeedOptions: android.widget.EditText
    private lateinit var btnSavePlaybackSpeedOptions: Button
    private lateinit var etPressHoldPlaybackRate: android.widget.EditText
    private lateinit var btnSavePressHoldPlaybackRate: Button
    private lateinit var rgLanguage: RadioGroup

    companion object {
        private const val REQUEST_CODE_EXPORT_FAV = 3001
        private const val REQUEST_CODE_IMPORT_FAV = 3002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots and hide content in recent apps
        if (PrivacySettings(this).isScreenSecure) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(R.layout.activity_settings)
        findViewById<android.widget.TextView>(R.id.tv_telemetry_notice).text = t(
            "使用統計與閃退回報已啟用。此版本使用 Firebase 收集啟動、功能使用、播放與下載結果、裝置資訊及錯誤診斷；不傳送瀏覽網址、搜尋詞、影片名稱或播放紀錄。",
            "Usage analytics and crash reporting are enabled. This build uses Firebase to collect launches, feature usage, playback and download results, device information, and error diagnostics. Browsing URLs, search terms, video titles, and playback history are not sent."
        )
        findViewById<Button>(R.id.btn_playback_history).apply {
            text = t("播放紀錄", "Playback history")
            setOnClickListener { startActivity(android.content.Intent(this@SettingsActivity, PlaybackHistoryActivity::class.java)) }
        }
        findViewById<Button>(R.id.btn_user_block_list).apply {
            text = t("我的屏蔽列表", "My block list")
            setOnClickListener { showUserBlockListDialog() }
        }

        privacySettings = PrivacySettings(this)
        appIconManager = AppIconManager(this)
        adFilterRules = AdFilterRules(this)
        biometricHelper = BiometricHelper(this, privacySettings)

        switchLock = findViewById(R.id.switch_lock)
        switchScreenSecure = findViewById(R.id.switch_screen_secure)
        switchAlwaysInternalPlayer = findViewById(R.id.switch_always_internal_player)
        radioGroup = findViewById(R.id.radio_group_icon)
        btnBack = findViewById(R.id.btn_back)
        etCloudUrl = findViewById(R.id.et_cloud_url)
        btnUpdateFromCloud = findViewById(R.id.btn_update_from_cloud)
        tvRulesStatus = findViewById(R.id.tv_rules_status)
        btnExportRules = findViewById(R.id.btn_export_rules)
        btnImportRules = findViewById(R.id.btn_import_rules)
        etScrapeDoToken = findViewById(R.id.et_scrapedo_token)
        btnSaveScrapeDoToken = findViewById(R.id.btn_save_scrapedo_token)
        rgEnrichMethod = findViewById(R.id.rg_enrich_method)
        btnImportFavorites = findViewById(R.id.btn_import_favorites)
        btnExportFavorites = findViewById(R.id.btn_export_favorites)
        etDoubleTapSeekSeconds = findViewById(R.id.et_double_tap_seek_seconds)
        btnSaveDoubleTapSeekSeconds = findViewById(R.id.btn_save_double_tap_seek_seconds)
        etPlayerShortSeekSeconds = findViewById(R.id.et_player_short_seek_seconds)
        etPlayerLongSeekSeconds = findViewById(R.id.et_player_long_seek_seconds)
        btnSavePlayerSeekButtons = findViewById(R.id.btn_save_player_seek_buttons)
        etPlaybackSpeedOptions = findViewById(R.id.et_playback_speed_options)
        btnSavePlaybackSpeedOptions = findViewById(R.id.btn_save_playback_speed_options)
        etPressHoldPlaybackRate = findViewById(R.id.et_press_hold_playback_rate)
        btnSavePressHoldPlaybackRate = findViewById(R.id.btn_save_press_hold_playback_rate)
        rgLanguage = findViewById(R.id.rg_language)
        favoritesManager = FavoritesManager(this)

        loadSettings()
        setupListeners()
        updateRulesStatus()
        setupScrapeDoToken()
        setupFavoritesBackup()
        FooterHelper.setup(this)
    }

    private fun loadSettings() {
        // Load lock setting
        switchLock.isChecked = privacySettings.isLockEnabled
        switchScreenSecure.isChecked = privacySettings.isScreenSecure
        switchAlwaysInternalPlayer.isChecked = privacySettings.alwaysUseInternalPlayer
        etDoubleTapSeekSeconds.setText(privacySettings.doubleTapSeekSeconds.toString())
        etPlayerShortSeekSeconds.setText(privacySettings.playerShortSeekSeconds.toString())
        etPlayerLongSeekSeconds.setText(privacySettings.playerLongSeekSeconds.toString())
        etPlaybackSpeedOptions.setText(
            privacySettings.playbackSpeedOptions.joinToString(",") { PrivacySettings.formatPlaybackRate(it) }
        )
        etPressHoldPlaybackRate.setText(
            PrivacySettings.formatPlaybackRate(privacySettings.pressHoldPlaybackRate)
        )
        if (LanguageManager.isEnglish(this)) {
            findViewById<android.widget.TextView>(R.id.tv_press_hold_speed_label).text = "Press-and-hold speed"
            etPressHoldPlaybackRate.hint = "Default 3x"
            btnSavePressHoldPlaybackRate.text = "Save press-and-hold speed"
            findViewById<android.widget.TextView>(R.id.tv_press_hold_speed_help).text =
                "Hold the video area to speed up temporarily; release to restore the previous speed. Range: 1.25x–16x."
        }
        when (LanguageManager.preference(this)) {
            LanguageManager.TRADITIONAL_CHINESE -> findViewById<RadioButton>(R.id.rb_language_zh_tw).isChecked = true
            LanguageManager.ENGLISH -> findViewById<RadioButton>(R.id.rb_language_en).isChecked = true
            else -> findViewById<RadioButton>(R.id.rb_language_auto).isChecked = true
        }

        // Load icon setting
        when (privacySettings.selectedIcon) {
            PrivacySettings.ICON_DEFAULT -> findViewById<RadioButton>(R.id.radio_default).isChecked = true
            PrivacySettings.ICON_CALCULATOR -> findViewById<RadioButton>(R.id.radio_calculator).isChecked = true
            PrivacySettings.ICON_NOTES -> findViewById<RadioButton>(R.id.radio_notes).isChecked = true
            PrivacySettings.ICON_FILE -> findViewById<RadioButton>(R.id.radio_file).isChecked = true
        }
    }

    private fun setupListeners() {
        rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val language = when (checkedId) {
                R.id.rb_language_zh_tw -> LanguageManager.TRADITIONAL_CHINESE
                R.id.rb_language_en -> LanguageManager.ENGLISH
                else -> LanguageManager.AUTO
            }
            if (language != LanguageManager.preference(this)) {
                LanguageManager.setPreference(this, language)
                recreate()
            }
        }

        // Lock switch
        switchLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Test biometric availability
                if (biometricHelper.canAuthenticate()) {
                    biometricHelper.authenticate(
                        onSuccess = {
                            privacySettings.isLockEnabled = true
                            privacySettings.updateUnlockTime()
                            Toast.makeText(this, t("應用鎖已啟用", "App lock enabled"), Toast.LENGTH_SHORT).show()
                        },
                        onError = { error ->
                            switchLock.isChecked = false
                            Toast.makeText(this, t("驗證失敗: $error", "Authentication failed: $error"), Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    switchLock.isChecked = false
                    Toast.makeText(this, t("此裝置不支援生物識別", "Biometric authentication is unavailable"), Toast.LENGTH_LONG).show()
                }
            } else {
                privacySettings.isLockEnabled = false
                Toast.makeText(this, t("應用鎖已停用", "App lock disabled"), Toast.LENGTH_SHORT).show()
            }
        }

        // Icon selection
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedIcon = when (checkedId) {
                R.id.radio_default -> PrivacySettings.ICON_DEFAULT
                R.id.radio_calculator -> PrivacySettings.ICON_CALCULATOR
                R.id.radio_notes -> PrivacySettings.ICON_NOTES
                R.id.radio_file -> PrivacySettings.ICON_FILE
                else -> PrivacySettings.ICON_DEFAULT
            }

            if (selectedIcon != privacySettings.selectedIcon) {
                showIconChangeDialog(selectedIcon)
            }
        }

        // Back button
        btnBack.setOnClickListener {
            finish()
        }

        // Set PIN button
        findViewById<Button>(R.id.btn_set_pin).setOnClickListener {
            showSetPinDialog()
        }

        btnSaveDoubleTapSeekSeconds.setOnClickListener {
            val seconds = etDoubleTapSeekSeconds.text.toString().trim().toIntOrNull()
            if (seconds == null) {
                Toast.makeText(this, t("請輸入秒數", "Enter a number of seconds"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val normalized = seconds.coerceIn(5, 300)
            privacySettings.doubleTapSeekSeconds = normalized
            etDoubleTapSeekSeconds.setText(normalized.toString())
            Toast.makeText(this, t("雙擊快進/快退已設定為 ${normalized} 秒", "Double-tap seek set to $normalized seconds"), Toast.LENGTH_SHORT).show()
        }

        btnSavePlayerSeekButtons.setOnClickListener {
            val shortSeconds = etPlayerShortSeekSeconds.text.toString().trim().toIntOrNull()
            val longSeconds = etPlayerLongSeekSeconds.text.toString().trim().toIntOrNull()
            if (shortSeconds == null || longSeconds == null) {
                Toast.makeText(this, t("請輸入短距離與長距離秒數", "Enter both short and long seek intervals"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            privacySettings.playerShortSeekSeconds = shortSeconds
            privacySettings.playerLongSeekSeconds = longSeconds
            etPlayerShortSeekSeconds.setText(privacySettings.playerShortSeekSeconds.toString())
            etPlayerLongSeekSeconds.setText(privacySettings.playerLongSeekSeconds.toString())
            Toast.makeText(this, t("播放器快退/快進按鈕已更新", "Player seek buttons updated"), Toast.LENGTH_SHORT).show()
        }

        btnSavePlaybackSpeedOptions.setOnClickListener {
            val rates = etPlaybackSpeedOptions.text.toString()
                .split(',', '，', ' ')
                .mapNotNull { it.trim().removeSuffix("x").toDoubleOrNull() }
            if (rates.isEmpty()) {
                Toast.makeText(this, t("請輸入至少一個有效倍速", "Enter at least one valid playback speed"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            privacySettings.playbackSpeedOptions = rates
            etPlaybackSpeedOptions.setText(
                privacySettings.playbackSpeedOptions.joinToString(",") { PrivacySettings.formatPlaybackRate(it) }
            )
            Toast.makeText(this, t("播放速度選項已更新", "Playback speed options updated"), Toast.LENGTH_SHORT).show()
        }

        btnSavePressHoldPlaybackRate.setOnClickListener {
            val rate = etPressHoldPlaybackRate.text.toString().trim().removeSuffix("x").toDoubleOrNull()
            if (rate == null || rate < 1.25 || rate > 16.0) {
                Toast.makeText(this, t("請輸入 1.25 到 16 倍", "Enter a speed from 1.25x to 16x"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            privacySettings.pressHoldPlaybackRate = rate
            etPressHoldPlaybackRate.setText(PrivacySettings.formatPlaybackRate(privacySettings.pressHoldPlaybackRate))
            Toast.makeText(this, t("長按快轉倍速已更新", "Press-and-hold speed updated"), Toast.LENGTH_SHORT).show()
        }

        switchAlwaysInternalPlayer.setOnCheckedChangeListener { _, isChecked ->
            privacySettings.alwaysUseInternalPlayer = isChecked
            Toast.makeText(
                this,
                if (isChecked) t("已設定永遠使用內建播放器", "Internal player is now always used") else t("已恢復自動選擇播放器", "Automatic player selection restored"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    private fun showSetPinDialog() {
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        input.hint = "Enter 4-6 digit PIN"
        
        // Add padding
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = 50
        params.rightMargin = 50
        input.layoutParams = params
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Set PIN Code")
            .setMessage("Enter a backup PIN code (4-6 digits)")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..6) {
                    privacySettings.pinCode = pin
                    Toast.makeText(this, "PIN Code saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN must be 4-6 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIconChangeDialog(newIcon: String) {
        val iconName = when (newIcon) {
            PrivacySettings.ICON_DEFAULT -> "JAV Browser"
            PrivacySettings.ICON_CALCULATOR -> "Calculator"
            PrivacySettings.ICON_NOTES -> "Notes"
            PrivacySettings.ICON_FILE -> "File Manager"
            else -> "JAV Browser"
        }

        AlertDialog.Builder(this)
            .setTitle(t("更換應用圖標", "Change App Icon"))
            .setMessage(t("確定要將圖標更換為「$iconName」嗎？\n\n舊圖標會從桌面消失，新圖標會出現。", "Change the app icon to $iconName?\n\nThe old launcher icon will disappear."))
            .setPositiveButton(t("確定", "Confirm")) { _, _ ->
                appIconManager.switchIcon(newIcon)
                privacySettings.selectedIcon = newIcon
                Toast.makeText(this, t("圖標已更換，請在桌面尋找新圖標", "Icon changed. Find the new icon on your launcher."), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(t("取消", "Cancel")) { _, _ ->
                loadSettings() // Revert selection
            }
            .show()
    }
    
    private fun setupFavoritesBackup() {
        btnExportFavorites.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_TITLE, "javbrowser_favorites.json")
            }
            startActivityForResult(intent, REQUEST_CODE_EXPORT_FAV)
        }
        btnImportFavorites.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            startActivityForResult(intent, REQUEST_CODE_IMPORT_FAV)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data?.data != null) {
            when (requestCode) {
                REQUEST_CODE_EXPORT_FAV -> {
                    val success = favoritesManager.exportFavoritesToFile(this, data.data!!)
                    Toast.makeText(this, if (success) t("書籤匯出成功", "Bookmarks exported") else t("書籤匯出失敗", "Bookmark export failed"), Toast.LENGTH_SHORT).show()
                }
                REQUEST_CODE_IMPORT_FAV -> {
                    val result = favoritesManager.importFavoritesFromFile(this, data.data!!)
                    Toast.makeText(this, result.second, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupScrapeDoToken() {
        val prefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        etScrapeDoToken.setText(prefs.getString("scrapedo_token", ""))
        btnSaveScrapeDoToken.setOnClickListener {
            val token = etScrapeDoToken.text.toString().trim()
            prefs.edit().putString("scrapedo_token", token).apply()
            Toast.makeText(this, if (token.isEmpty()) t("Token 已清除", "Token cleared") else t("Token 已儲存", "Token saved"), Toast.LENGTH_SHORT).show()
        }

        // Load enrich method selection
        val method = prefs.getString("enrich_method", "scrapedo")
        if (method == "webview") {
            findViewById<android.widget.RadioButton>(R.id.rb_method_webview).isChecked = true
        } else {
            findViewById<android.widget.RadioButton>(R.id.rb_method_scrapedo).isChecked = true
        }

        rgEnrichMethod.setOnCheckedChangeListener { _, checkedId ->
            val selected = if (checkedId == R.id.rb_method_webview) "webview" else "scrapedo"
            prefs.edit().putString("enrich_method", selected).apply()
            Toast.makeText(this, if (selected == "webview") t("已切換為 WebView 免費模式", "Switched to free WebView mode") else t("已切換為 Scrape.do API 模式", "Switched to Scrape.do API mode"), Toast.LENGTH_SHORT).show()
        }

        switchScreenSecure.setOnCheckedChangeListener { _, isChecked ->
            privacySettings.isScreenSecure = isChecked
            Toast.makeText(this,
                if (isChecked) t("防截圖已開啟，重新開啟 APP 後生效", "Screenshot protection enabled; reopen the app to apply") else t("防截圖已關閉，重新開啟 APP 後生效", "Screenshot protection disabled; reopen the app to apply"),
                Toast.LENGTH_SHORT).show()
        }
    }

    /** 長按屏蔽的管理入口：列出使用者屏蔽的域名，點一項確認後刪除，返回瀏覽器即生效。 */
    private fun showUserBlockListDialog() {
        val list = adFilterRules.getUserBlockList()
        if (list.isEmpty()) {
            Toast.makeText(this, t("還沒有屏蔽任何來源", "No blocked sources yet"), Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(t("我的屏蔽列表", "My block list"))
            .setItems(list.toTypedArray()) { _, which ->
                val host = list[which]
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(t("刪除屏蔽？", "Remove block?"))
                    .setMessage(t("以後將不再攔截 $host。", "Requests from $host will no longer be blocked."))
                    .setPositiveButton(t("刪除", "Remove")) { _, _ ->
                        adFilterRules.removeUserBlock(host)
                        updateRulesStatus()
                        Toast.makeText(this, t("已刪除 $host", "Removed $host"), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(t("取消", "Cancel"), null)
                    .show()
            }
            .setNegativeButton(t("關閉", "Close"), null)
            .show()
    }

    private fun updateRulesStatus() {
        // Load cloud URL
        etCloudUrl.setText(adFilterRules.cloudUrl)
        
        // Update stats
        val stats = adFilterRules.getRulesStats()
        val lastUpdate = if (adFilterRules.getLastUpdateTime() > 0) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(adFilterRules.getLastUpdateTime()))
        } else {
            t("從未更新", "Never updated")
        }
        
        val statusText = if (LanguageManager.isEnglish(this)) {
            """
                Version: ${adFilterRules.getVersion()}
                Last updated: $lastUpdate

                Common blocks: ${stats["commonBlock"]}
                Network blocks: ${stats["networkBlock"]}
                Link blocks: ${stats["linkBlock"]}
                Iframe blocks: ${stats["iframeBlock"]}
                Redirect blocks: ${stats["redirectBlock"]}

                Total: ${stats["total"]} rules

                User blocks: ${adFilterRules.getUserBlockList().size}
            """.trimIndent()
        } else {
            """
                版本: ${adFilterRules.getVersion()}
                最後更新: $lastUpdate

                通用遮蔽 (commonBlock): ${stats["commonBlock"]} 個
                網路攔截 (僅專用): ${stats["networkBlock"]} 個
                超連結遮蔽 (僅專用): ${stats["linkBlock"]} 個
                Iframe 遮蔽 (僅專用): ${stats["iframeBlock"]} 個
                重定向阻擋 (僅專用): ${stats["redirectBlock"]} 個

                總計: ${stats["total"]} 個規則

                使用者屏蔽: ${adFilterRules.getUserBlockList().size} 個
            """.trimIndent()
        }
        
        tvRulesStatus.text = statusText
        
        // Setup listeners
        btnUpdateFromCloud.setOnClickListener {
            val url = etCloudUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, t("請輸入雲端規則網址", "Enter the cloud rules URL"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Save URL
            adFilterRules.cloudUrl = url
            
            // Show progress
            btnUpdateFromCloud.isEnabled = false
            btnUpdateFromCloud.text = t("更新中...", "Updating...")
            
            adFilterRules.updateRulesFromCloud(url) { success, message ->
                btnUpdateFromCloud.isEnabled = true
                btnUpdateFromCloud.text = t("從雲端更新規則", "Update rules from cloud")
                
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                
                if (success) {
                    updateRulesStatus()
                }
            }
        }
        
        btnExportRules.setOnClickListener {
            val json = adFilterRules.exportToJson()
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Ad Filter Rules", json)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "規則已複製到剪貼簿", Toast.LENGTH_SHORT).show()
        }
        
        btnImportRules.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clipData = clipboard.primaryClip
            
            if (clipData != null && clipData.itemCount > 0) {
                val json = clipData.getItemAt(0).text.toString()
                
                if (adFilterRules.importFromJson(json)) {
                    Toast.makeText(this, "規則導入成功", Toast.LENGTH_SHORT).show()
                    updateRulesStatus()
                } else {
                    Toast.makeText(this, "規則格式錯誤，導入失敗", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "剪貼簿中沒有內容", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
