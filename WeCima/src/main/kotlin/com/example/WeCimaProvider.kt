package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class WeCimaProvider : MainAPI() {

    override var mainUrl = "https://wecima.date"
    override var name = "WeCima"
    override var lang = "ar"
    override var hasMainPage = true

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val safeHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    // ✅ Website-like sections (no JS needed)
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية (جديد)",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/episodes/" to "آخر الحلقات",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    // ---------------------------
    // Helpers: Title / Poster / Type
    // ---------------------------

    private fun Element.extractTitleStrong(): String? {
        // Titles can be in h3/h2/h1 or in <a title="..."> or img alt
        val h = this.selectFirst("h1,h2,h3,.title,.name")?.text()?.trim()
        if (!h.isNullOrBlank()) return h

        val a = this.selectFirst("a") ?: return null
        val t = a.attr("title")?.trim()
        if (!t.isNullOrBlank()) return t

        val imgAlt = this.selectFirst("img")?.attr("alt")?.trim()
        if (!imgAlt.isNullOrBlank()) return imgAlt

        val at = a.text()?.trim()
        if (!at.isNullOrBlank() && at.length > 2) return at

        return null
    }

    private fun Element.extractPosterStrong(): String? {
        val img = this.selectFirst("img")
        val src = img?.attr("src")?.trim()
        if (!src.isNullOrBlank()) return fixUrl(src)

        val lazy = img?.attr("data-src")?.trim()
            ?: img?.attr("data-original")?.trim()
            ?: img?.attr("data-lazy-src")?.trim()
            ?: img?.attr("data-image")?.trim()

        if (!lazy.isNullOrBlank()) return fixUrl(lazy)

        val srcset = img?.attr("srcset")?.trim()
        if (!srcset.isNullOrBlank()) {
            val first = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
            if (!first.isNullOrBlank()) return fixUrl(first)
        }

        // background-image: url(...)
        val style = this.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            val bg = m?.groupValues?.getOrNull(2)?.trim()
            if (!bg.isNullOrBlank()) return fixUrl(bg)
        }

        return null
    }

    private fun guessTypeFrom(url: String, title: String): TvType {
        val u = url.lowercase()
        val t = title.lowercase()

        if (u.contains("/series/") || u.contains("/episodes/") || t.contains("الحلقة") || t.contains("موسم"))
            return TvType.TvSeries

        if (u.contains("انمي") || t.contains("انمي"))
            return TvType.Anime

        return TvType.Movie
    }

    private fun unwrapProtectedLink(input: String): String {
        // Sometimes sites wrap links like: https://example.com/?url=https%3A%2F%2Fvinovo.to%2F...
        // We try to extract the real target.
        val u = input.trim()

        val m = Regex("""[?&](url|u|r)=([^&]+)""").find(u)
        if (m != null) {
            val encoded = m.groupValues.getOrNull(2)
            if (!encoded.isNullOrBlank()) {
                return try {
                    URLDecoder.decode(encoded, "UTF-8")
                } catch (_: Throwable) {
                    u
                }
            }
        }
        return u
    }

    // ---------------------------
    // Main Page (LISTING) ✅ stable
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // ✅ Keep it simple: fetch the section URL directly (no special pagination logic)
        val doc = app.get(request.data, headers = safeHeaders).document

        // ✅ This selector style is closer to your earlier working script
        val items = doc.select(
            "article, div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item, div.BlockItem, div.GridItem, div.Item"
        ).mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href").trim())

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val poster = element.extractPosterStrong()

            val type = guessTypeFrom(link, title)

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    // ---------------------------
    // Search
    // ---------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val doc = app.get("$mainUrl/?s=$q", headers = safeHeaders).document

        return doc.select(
            "article, div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item, div.BlockItem, div.GridItem, div.Item"
        ).mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href").trim())

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val poster = element.extractPosterStrong()

            val type = guessTypeFrom(link, title)

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
            }
        }.distinctBy { it.url }
    }

    // ---------------------------
    // Load details (type not hardcoded anymore ✅)
    // ---------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = safeHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "WeCima"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src]")?.attr("src")?.trim()

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val type = guessTypeFrom(url, title)

        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
        }
    }

    // ---------------------------
    // Links extraction ✅ FIXED (adds data-watch)
    // ---------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl = data.trim()
        if (pageUrl.isBlank()) return false

        val doc = app.get(pageUrl, headers = safeHeaders).document

        val found = LinkedHashSet<String>()

        // ✅ 1) iframe
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) found.add(fixUrl(src))
        }

        // ✅ 2) MAIN FIX: data-watch (Most important on current WeCima)
        doc.select("li[data-watch], [data-watch]").forEach { el ->
            val v = el.attr("data-watch").trim()
            if (v.isNotBlank()) found.add(fixUrl(v))
        }

        // ✅ 3) Other data attributes (keep resilience)
        doc.select("[data-url], [data-href], [data-embed], [data-embed-url]").forEach { el ->
            val v = el.attr("data-embed-url").ifBlank { el.attr("data-url") }
                .ifBlank { el.attr("data-href") }
                .ifBlank { el.attr("data-embed") }
                .trim()
            if (v.isNotBlank()) found.add(fixUrl(v))
        }

        // ✅ 4) onclick
        doc.select("[onclick]").forEach { el ->
            val on = el.attr("onclick")
            val m = Regex("""(https?://[^\s'"]+|//[^\s'"]+)""").find(on)
            val u = m?.value?.trim()
            if (!u.isNullOrBlank()) found.add(fixUrl(u))
        }

        // ✅ 5) unwrap protector links
        val expanded = found.map { unwrapProtectedLink(it) }.toSet()
        found.clear()
        found.addAll(expanded)

        if (found.isEmpty()) return false

        found.forEach { link ->
            loadExtractor(link, pageUrl, subtitleCallback, callback)
        }

        return true
    }
}
