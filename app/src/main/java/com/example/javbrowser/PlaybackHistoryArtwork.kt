package com.example.javbrowser

import org.jsoup.Jsoup
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object PlaybackHistoryArtwork {
    data class Images(val cover: String?, val icon: String?, val actresses: List<String>)

    fun parse(html: String, pageUrl: String): Images {
        val document = Jsoup.parse(html, pageUrl)
        fun resolve(raw: String?): String? = raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { URI(pageUrl).resolve(it).toString() }.getOrNull()?.let(PlaybackHistoryEntries::imageUrl)
        }
        val cover = resolve(document.selectFirst("video[poster]")?.attr("poster"))
            ?: resolve(document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content"))
            ?: resolve(Regex("""\bposter\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1))
            ?: document.selectFirst("#player-wrap[style]")?.attr("style")?.let {
                resolve(Regex("""url\(['"]?([^)'"\s]+)""").find(it)?.groupValues?.get(1))
            }
        val icon = resolve(document.selectFirst("link[rel=icon], link[rel='shortcut icon'], link[rel=apple-touch-icon]")?.attr("href"))
            ?: resolve("/favicon.ico")
        return Images(cover, icon, actresses(html))
    }

    fun actresses(html: String): List<String> {
        val document = Jsoup.parse(html)
        val names = linkedSetOf<String>()
        fun add(value: String) {
            value.trim().removeSuffix("♀").trim().takeIf {
                it.isNotEmpty() && it.length <= 100 && !it.startsWith("http") &&
                    it !in setOf("更多", "全部", "未知", "查看全部", "More", "Unknown")
            }?.let(names::add)
        }
        val label = Regex("^(?:女優|女优|演員|演员|主演|Actress(?:es)?|Actor(?:s|\\(s\\))?|Cast|出演)[：:\\s]*$", RegexOption.IGNORE_CASE)
        document.select("strong, b, span, dt, th, label, div, p").toList().filter { label.matches(it.ownText().trim()) }.forEach { element ->
            val container = if (element.tagName() == "dt") element.nextElementSibling() else element.parent()
            val links = container?.select("a").orEmpty().filter { it.text().isNotBlank() }
            if (links.isNotEmpty()) links.forEach { add(it.text()) }
            else element.nextElementSibling()?.text()?.split(Regex("[,，、;；]"))?.forEach(::add)
        }
        if (names.isEmpty()) {
            document.select("a[href*=/actress/], a[href*=/actresses/], a[href*=/actor/], a[href*=/star/], a[href*=/models/], .actor-name, .actress-name").toList().filter { element ->
                element.parents().none { parent ->
                    Regex("recommend|related|sidebar|footer|header|nav", RegexOption.IGNORE_CASE)
                        .containsMatchIn(parent.className() + " " + parent.id()) || parent.tagName() in setOf("nav", "footer", "header")
                }
            }.forEach { add(it.text()) }
        }
        fun actor(value: Any?) {
            when (value) {
                is JSONArray -> (0 until value.length()).forEach { actor(value.opt(it)) }
                is JSONObject -> add(value.optString("name"))
                is String -> add(value)
            }
        }
        fun schema(value: Any?) {
            when (value) {
                is JSONArray -> (0 until value.length()).forEach { schema(value.opt(it)) }
                is JSONObject -> {
                    actor(value.opt("actor"))
                    schema(value.opt("@graph"))
                }
            }
        }
        document.select("script[type=application/ld+json]").forEach {
            runCatching { schema(JSONTokener(it.data()).nextValue()) }
        }
        return names.take(50)
    }
}
