package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class LaroozaProvider : MainAPI() {

    override var mainUrl = "https://www.larooza.makeup"
    override var name = "Larooza"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // Simplified MainPage logic using the standard "mainPageOf"
    override val mainPage = mainPageOf(
        "$mainUrl/gaza.20" to "الرئيسية"
    )

    override suspend fun loadMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(request.data).document

        // Logic inlined here to prevent scope/compiler errors
        val items = document.select("article, li.post, div.movie, div.item, .entry-title").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img")
            val poster = img?.attr("src")?.ifBlank { img.attr("data-src") }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/gaza.20/?s=$q").document

        return document.select("article, li.post, div.movie, div.item, .entry-title").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img")
            val poster = img?.attr("src")?.ifBlank { img.attr("data-src") }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

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
        val vid = Regex("vid=([A-Za-z0-9]+)").find(watchUrl)?.groupValues?.get(1) ?: return false
        val playUrl = "$mainUrl/play.php?vid=$vid"

        val doc = runCatching {
            app.get(playUrl, referer = watchUrl).document
        }.getOrNull() ?: return false

        val embedLinks = doc.select(".WatchList li[data-embed-url]")
            .mapNotNull { fixUrlNull(it.attr("data-embed-url").trim()) }
            .filter { it.startsWith("http") }
            .distinct()

        embedLinks.forEach { link ->
            loadExtractor(link, playUrl, subtitleCallback, callback)
        }
        return embedLinks.isNotEmpty()
    }
}
