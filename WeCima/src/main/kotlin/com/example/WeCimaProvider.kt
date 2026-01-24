package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LaroozaProvider : MainAPI() {

    // 1. UPDATE: Using the working WeCima URL
    override var mainUrl = "https://wecima.date"
    override var name = "WeCima" // Name changed to match the site
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // 2. HEADERS: WeCima is strict, so we send a full User-Agent
    private val safeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // 3. MAIN PAGE: Standard WeCima categories
    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/cartoon/" to "أنمي و كرتون"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = safeHeaders).document

        // 4. WECIMA SELECTOR: They use "GridItem"
        val items = document.select("div.GridItem").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst("strong")?.text()?.trim() ?: a.attr("title")
            val link = fixUrl(a.attr("href").trim())

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            // WeCima stores the image in a special style attribute or data-image
            // We look for "background-image" style or standard lazy-load attributes
            var poster = element.selectFirst("div.BG--GridItem")?.attr("data-image")
            if (poster.isNullOrBlank()) {
                val style = element.selectFirst("div.BG--GridItem")?.attr("style") ?: ""
                poster = style.substringAfter("url(").substringBefore(")").trim('"').trim('\'')
            }
            
            // Fallback for search results which might look different
            if (poster.isNullOrBlank() || poster.length < 5) {
                poster = element.selectFirst("img")?.let { 
                     it.attr("data-src").ifBlank { it.attr("src") } 
                }
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        // WeCima Search format: /search/Query
        val document = app.get("$mainUrl/search/$q", headers = safeHeaders).document

        return document.select("div.GridItem").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst("strong")?.text()?.trim() ?: a.attr("title")
            val link = fixUrl(a.attr("href").trim())

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            // Intelligent Image Finder for WeCima
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
        
        // Poster on Load Page
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("wecima-poster img")?.attr("src")
        
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

        // WeCima lists servers in a list called "WatchServersList"
        val serverLinks = document.select("ul.WatchServersList li btn")
            .mapNotNull { it.attr("data-url").ifBlank { null } }
            .map { fixUrl(it) }
            .distinct()

        serverLinks.forEach { link ->
            loadExtractor(link, watchUrl, subtitleCallback, callback)
        }

        return serverLinks.isNotEmpty()
    }
}
