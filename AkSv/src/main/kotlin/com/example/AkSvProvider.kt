package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// ✅ FIXED: Changed class name from "LaroozaProvider" to "AkSvProvider"
class AkSvProvider : MainAPI() {

    override var mainUrl = "https://ak.sv" 
    override var name = "Akoam"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    private val safeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/new" to "الأحدث",
        "$mainUrl/sections/29/movies" to "أفلام", 
        "$mainUrl/sections/30/series" to "مسلسلات" 
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = safeHeaders).document

        // Akoam Selector
        val items = document.select("div.entry-box, div.box, div.movie").mapNotNull { element ->
            val a = element.selectFirst("h3.entry-title a, a") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("div.entry-image img, img")
            val poster = img?.attr("src")?.ifBlank { 
                img.attr("data-src").ifBlank { img.attr("data-image") } 
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

        return document.select("div.entry-box, div.box, div.movie").mapNotNull { element ->
            val a = element.selectFirst("h3.entry-title a, a") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("div.entry-image img, img")
            val poster = img?.attr("src")?.ifBlank { 
                img.attr("data-src").ifBlank { img.attr("data-image") } 
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Akoam"
        val poster = document.selectFirst("div.entry-image img, div.poster img")?.attr("src")
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
        
        // 1. Check for iframes
        val iframes = document.select("iframe[src]").map { it.attr("src") }
        if (iframes.isNotEmpty()) {
            iframes.forEach { link ->
                 loadExtractor(fixUrl(link), watchUrl, subtitleCallback, callback)
            }
            return true
        }
        
        // 2. Check for "Watch" or "Download" buttons
        val links = document.select("a.download-link, a.watch-link, a[href*='watch'], a[href*='download']")
            .map { fixUrl(it.attr("href")) }
            .distinct()
            
        links.forEach { link ->
             if (link.startsWith("http")) {
                 loadExtractor(link, watchUrl, subtitleCallback, callback)
             }
        }

        return iframes.isNotEmpty() || links.isNotEmpty()
    }
}
