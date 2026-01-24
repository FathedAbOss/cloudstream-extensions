package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LaroozaProvider : MainAPI() {

    // 1. FIXED URL: Added "www" to match your error screenshot exactly
    override var mainUrl = "https://www.larooza.makeup" 
    override var name = "Larooza"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // 2. SAFE HEADERS: We use this 'private' variable to avoid the build error
    private val safeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
    )

    // 3. FIXED TABS: Removed specific pages that might be dead. Pointing to main categories.
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/category/movies/" to "أفلام",
        "$mainUrl/category/series/" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Pass the safeHeaders here
        val document = app.get(request.data, headers = safeHeaders).document

        // 4. "CATCH-ALL" SELECTOR: This looks for ANY common movie card class.
        // It covers div.movie, div.box, div.col-md-2, article.post, etc.
        val items = document.select("div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            // Intelligent image finder (checks src, data-src, etc.)
            val img = element.selectFirst("img")
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
        val document = app.get("$mainUrl/?s=$q", headers = safeHeaders).document

        return document.select("div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img")
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

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Larooza"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.poster img")?.attr("src")
        
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

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
        
        // Strategy 1: Direct Iframe
        val document = app.get(watchUrl, headers = safeHeaders).document
        val iframes = document.select("iframe[src]").map { it.attr("src") }
        
        if (iframes.isNotEmpty()) {
            iframes.forEach { link ->
                 loadExtractor(fixUrl(link), watchUrl, subtitleCallback, callback)
            }
            return true
        }
        
        // Strategy 2: ID based
        val vid = Regex("vid=([A-Za-z0-9]+)").find(watchUrl)?.groupValues?.get(1)
        if (vid != null) {
            val playUrl = "$mainUrl/play.php?vid=$vid"
            val playDoc = app.get(playUrl, headers = safeHeaders).document
            playDoc.select("iframe[src]").forEach { 
                loadExtractor(fixUrl(it.attr("src")), playUrl, subtitleCallback, callback) 
            }
            return true
        }

        return false
    }
}
