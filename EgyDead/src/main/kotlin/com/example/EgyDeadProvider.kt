package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class EgyDeadProvider : MainAPI() {
    override var mainUrl = "https://egydead.media" // Ensure no trailing slash here
    override var name = "EgyDead"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"
    override var hasMainPage = true

    // 1. ADD THIS: This tells Cloudstream what tabs to show on the home screen
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Movies",
        "$mainUrl/series/" to "Latest Series" // Adjust if the series URL is different
    )

    // 2. USE "loadMainPage": This is the standard override for v3/v4
    override suspend fun loadMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Use the URL passed by the request (defined in mainPage above)
        val document = app.get(request.data).document 
        
        // 3. DEBUGGING: If this is still empty, the selector "div.mov-item" is wrong.
        // Try "div.movieItem", "div.item", or "div.box" if this fails.
        val items = document.select("div.mov-item").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.mov-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title")?.text() ?: "Unknown"
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.story")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }
}
