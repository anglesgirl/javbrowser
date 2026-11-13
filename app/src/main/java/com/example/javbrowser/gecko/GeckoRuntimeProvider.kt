package com.example.javbrowser.gecko

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.AllowOrDeny
import java.io.File

/**
 * 全局单例 GeckoRuntime（GeckoView 151）。
 *
 * DoH 与 ECH 全部走 Gecko 原生能力，不再用自建 Conscrypt ECH / DoH：
 * - DoH：GeckoRuntimeSettings 的 trustedRecursiveResolver（TRR_ONLY），
 *   provider 与 bootstrap IP 池与 hyper-browser 同款。
 * - ECH：config YAML prefs（network.dns.echconfig.enabled 等），
 *   跟 DoH 走（echEnabled = dohEnabled）。
 *
 * 参考 hyper-browser 的 GeckoRuntimeProvider，简化：javbrowser 不做
 * 严格隐私/全局隐私控制那套，只留 DoH + ECH + 基础内容拦截。
 */
object GeckoRuntimeProvider {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime =
        runtime ?: synchronized(this) {
            val appContext = context.applicationContext
            runtime ?: GeckoRuntime.create(
                appContext,
                geckoRuntimeSettingsBuilder(appContext)
                    .remoteDebuggingEnabled(appContext.isDebuggable())
                    .consoleOutput(appContext.isDebuggable())
                    .configFilePath(ensureGeckoConfig(appContext).absolutePath)
                    .build()
            ).also { createdRuntime ->
                createdRuntime.webExtensionController.promptDelegate =
                    object : WebExtensionController.PromptDelegate {
                        override fun onInstallPromptRequest(
                            extension: WebExtension,
                            permissions: Array<out String>,
                            origins: Array<out String>,
                            dataCollectionPermissions: Array<out String>
                        ): GeckoResult<WebExtension.PermissionPromptResponse> =
                            GeckoResult.fromValue(
                                WebExtension.PermissionPromptResponse(
                                    true,
                                    false,
                                    false
                                )
                            )

                        override fun onUpdatePrompt(
                            extension: WebExtension,
                            newPermissions: Array<out String>,
                            newOrigins: Array<out String>,
                            newDataCollectionPermissions: Array<out String>
                        ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)

                        override fun onOptionalPrompt(
                            extension: WebExtension,
                            permissions: Array<out String>,
                            origins: Array<out String>,
                            dataCollectionPermissions: Array<out String>
                        ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }
                runtime = createdRuntime
            }
        }

    private fun geckoRuntimeSettingsBuilder(
        context: Context
    ): GeckoRuntimeSettings.Builder =
        GeckoRuntimeSettings.Builder()
            .trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY)
            .trustedRecursiveResolverUri(DOH_PROVIDER_URL)
            .defaultRecursiveResolverUri(DOH_PROVIDER_URL)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
                    .build()
            )

    private fun ensureGeckoConfig(context: Context): File {
        val file = File(context.filesDir, GECKO_CONFIG_FILE)
        val desired = geckoConfigContent()
        if (!file.exists() || runCatching { file.readText() }.getOrNull() != desired) {
            file.writeText(desired)
        }
        return file
    }

    private fun geckoConfigContent(): String =
        buildString {
            appendLine("prefs:")
            GECKO_STATIC_PREFS.trimIndent().lines().forEach { line ->
                appendLine("  $line")
            }
            appendLine("  network.trr.mode: ${GeckoRuntimeSettings.TRR_MODE_ONLY}")
            appendLine("  network.trr.uri: \"$DOH_PROVIDER_URL\"")
            // bootstrap IP 随机化：每次启动从池中随机取一个，移动宽带偶发
            // 阻断单个 IP 时，下次启动自动换，避免全死。
            val bootstrapIp = DOH_BOOTSTRAP_POOL.random()
            Log.i("GeckoRuntime", "DoH bootstrap 随机选择: $bootstrapIp (池: ${DOH_BOOTSTRAP_POOL.size} 个)")
            appendLine("  network.trr.bootstrapAddress: \"$bootstrapIp\"")
            appendLine("  doh-rollout.mode: ${GeckoRuntimeSettings.TRR_MODE_ONLY}")
            appendLine("  doh-rollout.uri: \"$DOH_PROVIDER_URL\"")
            appendLine("  network.dns.native_https_query: true")
            appendLine("  network.dns.echconfig.enabled: true")
            appendLine("  network.dns.http3_echconfig.enabled: true")
            appendLine("  network.dns.force_waiting_https_rr: true")
            appendLine("  network.dns.echconfig.fallback_to_origin_when_all_failed: false")
            appendLine("  security.tls.ech.grease_http3: true")
            appendLine("  security.tls.ech.grease_probability: 100")
        }

    private fun Context.isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private const val GECKO_CONFIG_FILE = "geckoview-config.yaml"

    /** DoH provider 与 bootstrap 池：与 hyper-browser 同款。 */
    private const val DOH_PROVIDER_URL = "https://m2b4x7vw98.cloudflare-gateway.com/dns-query"
    private val DOH_BOOTSTRAP_POOL = listOf(
        "172.64.146.66",
        "104.16.140.137",
        "104.16.141.137",
        "104.18.40.152",
        "172.64.147.104"
    )

    private const val GECKO_STATIC_PREFS = """
        media.audioFocus.management: false
        media.suspend-bkgnd-video.enabled: false
        media.block-autoplay-until-in-foreground: false
        dom.suspend_inactive.enabled: false
    """
}
