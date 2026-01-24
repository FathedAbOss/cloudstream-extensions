package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CimaLightProvider : MainAPI() {

    // 1. Domain Check: Ensure this URL works in your browser. 
    // If they changed domains, update this line.
    override var mainUrl = "https://w.cimalight.co" 
    override var name = "CimaLight"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // 2. CRITICAL FIX: Add User-Agent to prevent blocking
    override val headers = super.headers.newBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36")
        .build()

    override val mainPage = mainPageOf(
        "$mainUrl/movies.php" to "أحدث الأفلام",
        "$mainUrl/main15" to "جديد الموقع",
        "$mainUrl/most.php" to "الأكثر مشاهدة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        
        // Selector Strategy: Try "div.movie" or general "a" inside grid
        val items = document.select("div.movie, div.post, li.item, div.Thumb--GridItem").mapNotNull { 
            it.toSearchResult() 
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // 3. Search Fix: Standard encoding and corrected selector
        // Note: If "?q=" doesn't work, try "?s=" (WordPress style)
        val url = "$mainUrl/search?q=$query" 
        val document = app.get(url).document

        return document.select("div.movie, div.post, li.item, div.Thumb--GridItem").mapNotNull { 
            it.toSearchResult() 
        }
    }

    // 4. Simplified Image Extractor
    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val title = a.attr("title").ifBlank { this.selectFirst("h3")?.text() }?.trim() ?: return null
        val href = fixUrl(a.attr("href"))

        // Robust Image Finding
        val img = this.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("data-original")?.ifBlank { null }
            ?: img?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(posterUrl) // Handles relative URLs automatically
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        
        // Better Poster Logic for Load Page
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.poster img")?.attr("src")
        
        val plot = document.selectFirst("div.desc, div.story, meta[property=og:description]")
            ?.let { it.text().ifBlank { it.attr("content") } }

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
        // Your existing loadLinks logic was good, keeping it mostly intact
        val watchUrl = data.trim()
        val vid = Regex("vid=([A-Za-z0-9]+)").find(watchUrl)?.groupValues?.get(1) ?: return false

        val playUrl = "$mainUrl/play.php?vid=$vid"
        
        // Add headers here too just in case
        val playDoc = app.get(playUrl, referer = watchUrl).document 

        val playLinks = playDoc.select("iframe, [data-embed-url], a[href]")
            .mapNotNull { 
                it.attr("src").ifBlank { it.attr("data-embed-url").ifBlank { it.attr("href") } } 
            }
            .map { fixUrl(it) }
            .filter { it.startsWith("http") && !it.contains("cimalight", true) }
            .distinct()

        playLinks.forEach { link ->
            loadExtractor(link, playUrl, subtitleCallback, callback)
        }

        return true
    }
}
