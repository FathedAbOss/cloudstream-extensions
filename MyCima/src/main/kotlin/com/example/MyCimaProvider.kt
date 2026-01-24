package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class WeCimaProvider : MainAPI() {

    // 1. CLASS NAME CHECK: The line above MUST say "class WeCimaProvider"
    override var mainUrl = "https://wecima.date" 
    override var name = "WeCima"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // 2. HEADERS: WeCima needs these to load images properly
    private val safeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/cartoon/" to "أنمي و كرتون"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = safeHeaders).document

        // 3. WECIMA SELECTOR: Correct selectors for WeCima's layout
        val items = document.select("div.GridItem").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst("strong")?.text()?.trim() ?: a.attr("title")
            val link = fixUrl(a.attr("href").trim())

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            // Image Extractor
            var poster = element.selectFirst("div.BG--GridItem")?.attr("data-image")
            if (poster.isNullOrBlank()) {
                val style = element.selectFirst("div.BG--GridItem")?.attr("style") ?: ""
                poster = style.substringAfter("url(").substringBefore(")").trim('"').trim('\'')
            }
            
            if (poster.isNullOrBlank()) {
                 poster = element.selectFirst("img")?.attr("data-src")
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/search/$q", headers = safeHeaders).document

        return document.select("div.GridItem").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst("strong")?.text()?.trim() ?: a.attr("title")
            val link = fixUrl(a.attr("href").trim())

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            var poster = element.selectFirst("div.BG--GridItem")?.attr("data-image")
            if (poster.isNullOrBlank()) {
                val style = element.selectFirst("div.BG--GridItem")?.attr("style") ?: ""
                poster = style.substringAfter("url(").substringBefore(")").trim('"').trim('\'')
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "WeCima"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.StoryMovieContent, div.Desc p")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data.trim()
        val document = app.get(watchUrl, headers = safeHeaders).document

        // WeCima Server List
        val serverLinks = document.select("ul.WatchServersList li btn, .WatchServersList a")
            .mapNotNull { it.attr("data-url").ifBlank { it.attr("href") } }
            .map { fixUrl(it) }
            .distinct()

        serverLinks.forEach { link ->
            loadExtractor(link, watchUrl, subtitleCallback, callback)
        }

        return serverLinks.isNotEmpty()
    }
}
