package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AkSvProvider : MainAPI() {

    override var mainUrl = "https://ak.sv"
    override var name = "Akoam"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // 1. HEADERS: Akoam requires a valid User-Agent
    private val safeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/new" to "الأحدث",
        "$mainUrl/sections/29/movies" to "أفلام",
        "$mainUrl/sections/30/series" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = safeHeaders).document

        // 2. FIXED SELECTOR: This captures the exact layout of Akoam (div.entry-box)
        val items = document.select("div.entry-box, div.box, div.movie, div.col-md-2, div.col-6").mapNotNull { element ->
            val a = element.selectFirst("h3.entry-title a, a") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            // Intelligent Image Finder
            val img = element.selectFirst("div.entry-image img, img")
            val poster = img?.attr("data-src")?.ifBlank { 
                img.attr("src") 
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/search?q=$q", headers = safeHeaders).document

        return document.select("div.entry-box, div.box, div.movie, div.col-md-2, div.col-6").mapNotNull { element ->
            val a = element.selectFirst("h3.entry-title a, a") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("div.entry-image img, img")
            val poster = img?.attr("data-src")?.ifBlank { 
                img.attr("src") 
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Akoam"
        
        // High Quality Poster
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.entry-image img")?.attr("src")
        
        val plot = document.selectFirst("div.entry-content, div.desc")?.text()?.trim()

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

        // Strategy 1: Find "Watch" button URL
        val watchLink = document.select("a.watch-link, a:contains(مشاهدة)").attr("href")
        if (watchLink.isNotBlank()) {
            val realWatchUrl = fixUrl(watchLink)
            val watchDoc = app.get(realWatchUrl, headers = safeHeaders).document
            
            // Extract iframe from the watch page
            watchDoc.select("iframe").forEach { 
                loadExtractor(fixUrl(it.attr("src")), realWatchUrl, subtitleCallback, callback)
            }
        }

        // Strategy 2: Direct Iframes on the main page
        document.select("iframe").forEach {
             loadExtractor(fixUrl(it.attr("src")), watchUrl, subtitleCallback, callback)
        }

        return true
    }
}
