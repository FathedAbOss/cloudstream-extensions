package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LaroozaProvider : MainAPI() {

    // 1. UPDATE: Switching to the working site you found (Akoam)
    override var mainUrl = "https://ak.sv" 
    override var name = "Akoam (Larooza Fix)" // I changed the name so you see it in the app
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // 2. SAFE HEADERS: Akoam needs a valid User-Agent
    private val safeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
    )

    // 3. MAIN PAGE: Akoam uses specific paths for movies and series
    override val mainPage = mainPageOf(
        "$mainUrl/new" to "الأحدث",
        "$mainUrl/sections/29/movies" to "أفلام", // Common Akoam path
        "$mainUrl/sections/30/series" to "مسلسلات" // Common Akoam path
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // We use the safe headers
        val document = app.get(request.data, headers = safeHeaders).document

        // 4. AKOAM SELECTOR: This is the correct "map" for Akoam's layout
        // They use "div.entry-box" and "div.entry-image"
        val items = document.select("div.entry-box, div.box, div.movie").mapNotNull { element ->
            val a = element.selectFirst("h3.entry-title a, a") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            // Find the image in Akoam's specific structure
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
        // Akoam Search URL
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
        
        // Strategy 1: Look for "Watch" button links or iframes directly
        val iframes = document.select("iframe[src]").map { it.attr("src") }
        if (iframes.isNotEmpty()) {
            iframes.forEach { link ->
                 loadExtractor(fixUrl(link), watchUrl, subtitleCallback, callback)
            }
            return true
        }
        
        // Strategy 2: Akoam often puts the download/watch links in a specific "a" tag
        // Look for links that say "Watch" or "Download"
        val links = document.select("a.download-link, a.watch-link, a[href*='watch'], a[href*='download']")
            .map { fixUrl(it.attr("href")) }
            .distinct()
            
        links.forEach { link ->
             // If it's a direct file or external link, try to load it
             if (link.startsWith("http")) {
                 loadExtractor(link, watchUrl, subtitleCallback, callback)
             }
        }

        return iframes.isNotEmpty() || links.isNotEmpty()
    }
}
