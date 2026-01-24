package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AkSvProvider : MainAPI() {

    override var mainUrl = "https://ak.sv" // Removed trailing slash for safety
    override var name = "AkSv"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"
    override var hasMainPage = true

    // 1. ADDED HEADERS: Prevents the website from returning empty results
    private val safeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
    )

    // 2. DEFINED MAIN PAGE: Correctly maps the home categories
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = safeHeaders).document

        // 3. SMART SELECTOR: Looks for ALL common box types (entry-box, movie, post, etc.)
        val items = document.select("div.entry-box, div.box-item, div.movie, div.col-md-2, div.col-6, article.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3, .title, h2")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // 4. IMAGE FIX: Checks for 'data-src' (lazy load) first, then 'src'
        val img = this.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.ifBlank { img.attr("src") }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // 5. SEARCH FIX: Tries standard query format "?s="
        val q = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/?s=$q", headers = safeHeaders).document
        
        return document.select("div.entry-box, div.box-item, div.movie, div.col-md-2, div.col-6, article.post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document
        
        val title = document.selectFirst("h1.title, h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("div.poster img, .poster img")?.attr("src")
        val plot = document.selectFirst("div.story, .desc, .description")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
        }
    }

    // 6. ADDED LOAD LINKS: Basic extractor to find video links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = safeHeaders).document

        // Find standard Iframes
        document.select("iframe[src]").forEach {
            val src = it.attr("src")
            loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }

        // Find "Watch" buttons or links
        document.select("a.watch-btn, a[href*='watch']").forEach {
            val href = it.attr("href")
            if (href.contains("http")) {
                loadExtractor(fixUrl(href), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
