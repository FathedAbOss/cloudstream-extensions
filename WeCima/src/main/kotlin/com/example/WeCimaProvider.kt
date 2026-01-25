package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class WeCimaProvider : MainAPI() {

    override var mainUrl = "https://wecima.date"
    override var name = "WeCima"
    override var lang = "ar"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val safeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية (جديد)",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/episodes/" to "آخر الحلقات",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    // ---------------------------
    // Helpers
    // ---------------------------

    private fun Element.extractTitle(): String? {
        val t1 = this.selectFirst("h3, h2, h1")?.text()?.trim()
        if (!t1.isNullOrBlank()) return t1

        val a = this.selectFirst("a")
        val t2 = a?.attr("title")?.trim()
        if (!t2.isNullOrBlank()) return t2

        val t3 = a?.text()?.trim()
        if (!t3.isNullOrBlank() && t3.length > 3) return t3

        return null
    }

    private fun Element.extractLink(): String? {
        val a = this.selectFirst("a[href]")
        val href = a?.attr("href")?.trim()
        if (!href.isNullOrBlank()) return fixUrl(href)
        return null
    }

    private fun Element.extractPoster(): String? {
        val img = this.selectFirst("img")
        val p1 = img?.attr("src")?.trim()
        if (!p1.isNullOrBlank()) return fixUrl(p1)

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

        val style = this.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            val bg = m?.groupValues?.getOrNull(2)?.trim()
            if (!bg.isNullOrBlank()) return fixUrl(bg)
        }

        val any = this.selectFirst("[data-src], [data-original], [data-image], [data-lazy-src]")
        val anyPoster = any?.attr("data-src")?.ifBlank { any.attr("data-original") }
            ?.ifBlank { any.attr("data-image") }
            ?.ifBlank { any.attr("data-lazy-src") }
        if (!anyPoster.isNullOrBlank()) return fixUrl(anyPoster)

        return null
    }

    // ---------------------------
    // Main Page
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url, headers = safeHeaders).document

        val items = doc.select(
            "article, div.BlockItem, div.col-md-2, div.col-xs-6, li.item, div.post, div.box, div.movie"
        ).mapNotNull { el ->
            val title = el.extractTitle() ?: return@mapNotNull null
            val link = el.extractLink() ?: return@mapNotNull null
            val poster = el.extractPoster()

            val isEpisode = title.contains("حلقة") || link.contains("/episodes/")
            val type = if (isEpisode) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, link, type) {
                this.posterUrl = fixUrlNull(poster)
            }
        }

        return newHomePageResponse(request.name, items)
    }

    // ---------------------------
    // Search
    // ---------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val doc = app.get("$mainUrl/?s=$q", headers = safeHeaders).document

        return doc.select(
            "article, div.BlockItem, div.col-md-2, div.col-xs-6, li.item, div.post, div.box, div.movie"
        ).mapNotNull { el ->
            val title = el.extractTitle() ?: return@mapNotNull null
            val link = el.extractLink() ?: return@mapNotNull null
            val poster = el.extractPoster()

            val isSeries = link.contains("/series/") || title.contains("مسلسل")
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, link, type) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    // ---------------------------
    // Load details
    // ---------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = safeHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "WeCima"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src]")?.attr("src")?.trim()

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val isSeries = url.contains("/series/") || title.contains("مسلسل")
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
        }
    }

    // ---------------------------
    // Load Links (THE REAL FIX)
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

        // ✅ 1) iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) found.add(fixUrl(src))
        }

        // ✅ 2) Data attributes (ADDED data-watch - THIS IS THE MAIN FIX)
        doc.select("[data-url], [data-href], [data-embed], [data-embed-url], [data-watch]").forEach { el ->
            val v = el.attr("data-watch").ifBlank { el.attr("data-url") }
                .ifBlank { el.attr("data-href") }
                .ifBlank { el.attr("data-embed") }
                .ifBlank { el.attr("data-embed-url") }
                .trim()
            if (v.isNotBlank()) found.add(fixUrl(v))
        }

        // ✅ 3) onclick="..."
        doc.select("[onclick]").forEach { el ->
            val on = el.attr("onclick")
            val m = Regex("""(https?://[^\s'"]+|//[^\s'"]+)""").find(on)
            val u = m?.value?.trim()
            if (!u.isNullOrBlank()) found.add(fixUrl(u))
        }

        // ✅ 4) Internal play.php
        val html = doc.html()
        val vid = Regex("""vid=([A-Za-z0-9]+)""").find(html)?.groupValues?.getOrNull(1)
        if (!vid.isNullOrBlank()) {
            val playUrl = "$mainUrl/play.php?vid=$vid"
            val playDoc = app.get(playUrl, headers = safeHeaders).document
            playDoc.select("iframe[src]").forEach {
                val src = it.attr("src").trim()
                if (src.isNotBlank()) found.add(fixUrl(src))
            }
        }

        // ✅ 5) Extract from scripts
        doc.select("script").forEach { s ->
            val txt = s.data()
            Regex("""https?://[^\s"']+""").findAll(txt).forEach { m ->
                val u = m.value.trim()
                if (!u.contains("google") && !u.contains("facebook") && !u.contains("schema.org")) {
                    found.add(u)
                }
            }
        }

        if (found.isEmpty()) return false

        found.forEach { link ->
            // Handle common protector/redirector if found
            val finalLink = if (link.contains("akhbarworld.online?mycimafsd=")) {
                link.substringAfter("mycimafsd=").let { java.net.URLDecoder.decode(it, "UTF-8") }
            } else link
            
            loadExtractor(finalLink, pageUrl, subtitleCallback, callback)
        }

        return true
    }
}
