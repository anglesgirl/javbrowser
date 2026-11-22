package com.example.javbrowser

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 解析规则中心（1.2.27）：各站的取片方法放远程 rules.json，App 启动时
 * 拉取，本地缓存，拉不到用缓存，再没有用 assets 兜底。加新站只改
 * rules.json 推上去，不用重新发版。
 *
 * 规则只放提取方法，不放任何具体的 ech= 或视频地址（ech= 每小时
 * 更新、5 小时过期，内置具体值几小时就失效）。
 *
 * type 三种：
 * - regex-mp4 / regex-m3u8：详情页 HTML 里按 patterns 顺序取第一个
 *   匹配地址（RuleResolver），m3u8 照常过时长过滤。
 * - native：走内置原生解析器（7mmtv 这种要 AES 解 token 的，JSON
 *   表达不了，只能走原生；规则里只写解析器名）。
 */
data class SiteRule(
    val host: String,
    val type: String,
    val patterns: List<String> = emptyList(),
    val referer: String = "",
    val resolver: String? = null,
    /** 详情页路径正则（1.2.29）：只有路径匹配才在内置浏览器里自动提片；
     *  为空则不自动提，避免在搜索/列表页把第一条结果当成当前页的片。 */
    val detail: String = "",
)

data class RuleSet(val version: Int, val updatedAt: String, val sites: List<SiteRule>)

object RuleCenter {
    private const val TAG = "RuleCenter"
    private const val CACHE_FILE = "rules.json"

    // 镜像先行（gh.anglesya.win 的 raw 格式已实测 200），失败走直连。
    private val REMOTE_URLS = listOf(
        "https://gh.anglesya.win/anglesgirl/javbrowser/raw/feat/geckoview/rules.json",
        "https://raw.githubusercontent.com/anglesgirl/javbrowser/feat/geckoview/rules.json",
    )

    // 合成站点：只为走 SearchHttpFetcher 同栈（ECH/Cronet）拉规则；
    // allowlist 只含规则托管方，拉别的 host 会被 cookie 拦截器拒绝。
    private val rulesSite = SearchSite(
        id = "rulecenter",
        displayName = "RuleCenter",
        order = 0,
        parserKind = SearchParserKind.RULES,
        configurationKey = "rulecenter",
        buildSearchUrl = { _, _ -> "" },
        allowedHosts = setOf("gh.anglesya.win", "raw.githubusercontent.com"),
    )

    @Volatile
    private var current: RuleSet? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // 1. assets 兜底先上，保证没网也有规则。
        current = loadFromAssets() ?: current
        // 2. 缓存（上次拉到的新版）优先于 assets。
        loadFromCache()?.let { cached ->
            if ((current?.version ?: 0) <= cached.version) current = cached
        }
        // 3. 异步拉远程，有新版就换。
        refreshAsync()
    }

    /** 按 host 查规则：精确匹配或子域名匹配（www. 前缀）。无规则返回 null。 */
    fun ruleFor(host: String): SiteRule? {
        val h = host.lowercase()
        return current?.sites?.firstOrNull { rule ->
            h == rule.host || h.endsWith(".${rule.host}")
        }
    }

    fun version(): Int = current?.version ?: 0

    fun refreshAsync() {
        SearchScheduler.workers.execute { refresh() }
    }

    private fun refresh() {
        val ctx = appContext ?: return
        for (url in REMOTE_URLS) {
            try {
                val resp = SearchHttpFetcher().fetch(url, SearchCancellation(), rulesSite)
                if (resp.transportError != null || resp.statusCode !in 200..299 || resp.body.isBlank()) {
                    Log.d(TAG, "rules fetch failed $url: ${resp.transportError ?: "HTTP ${resp.statusCode}"}")
                    continue
                }
                val parsed = parse(resp.body) ?: continue
                if (parsed.version <= version()) {
                    Log.d(TAG, "rules up to date (v${parsed.version}) from $url")
                    return
                }
                current = parsed
                runCatching { File(ctx.filesDir, CACHE_FILE).writeText(resp.body) }
                Log.d(TAG, "rules updated to v${parsed.version} from $url")
                return
            } catch (e: Exception) {
                Log.d(TAG, "rules fetch error $url: ${e.message}")
            }
        }
    }

    private fun loadFromAssets(): RuleSet? {
        val ctx = appContext ?: return null
        return runCatching {
            ctx.assets.open(CACHE_FILE).bufferedReader().use { it.readText() }.let(::parse)
        }.getOrNull()
    }

    private fun loadFromCache(): RuleSet? {
        val ctx = appContext ?: return null
        val f = File(ctx.filesDir, CACHE_FILE)
        if (!f.exists()) return null
        return runCatching { parse(f.readText()) }.getOrNull()
    }

    fun parse(json: String): RuleSet? {
        return try {
            val root = JSONObject(json)
            val version = root.optInt("version", 0)
            val updatedAt = root.optString("updatedAt", "")
            val arr = root.optJSONArray("sites") ?: return null
            val sites = mutableListOf<SiteRule>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val host = o.optString("host", "").lowercase()
                val type = o.optString("type", "")
                if (host.isBlank() || type.isBlank()) continue
                val patterns = mutableListOf<String>()
                val parr = o.optJSONArray("patterns")
                if (parr != null) {
                    for (j in 0 until parr.length()) {
                        parr.optString(j)?.takeIf { it.isNotBlank() }?.let { patterns += it }
                    }
                }
                sites += SiteRule(
                    host = host,
                    type = type,
                    patterns = patterns,
                    referer = o.optString("referer", ""),
                    resolver = o.optString("resolver", "").ifBlank { null },
                    detail = o.optString("detail", ""),
                )
            }
            if (sites.isEmpty()) null else RuleSet(version, updatedAt, sites)
        } catch (e: Exception) {
            Log.w(TAG, "rules parse failed: ${e.message}")
            null
        }
    }
}
