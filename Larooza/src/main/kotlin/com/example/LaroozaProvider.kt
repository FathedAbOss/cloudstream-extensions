package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class LaroozaProvider : MainAPI() {

    // 1. URL YOU CONFIRMED WORKS
    override var mainUrl = "https://larooza.makeup" 
    override var name = "Larooza"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // 2. CRITICAL: Headers to make the app look like a browser
    override val headers = super.headers.newBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36")
        .build()

    // 3. MAIN PAGE: Points to the specific page you saw (/gaza.20) and standard categories
    override val mainPage = mainPageOf(
        "$mainUrl/gaza.20" to "الرئيسية", // The page you were redirected to
        "$mainUrl/category/movies/" to "أفلام",
        "$mainUrl/category/series/" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        // 4. SELECTOR: Generic selector that works on almost all Laroza themes
        val items = document.select("div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            // Find image in any common attribute
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
        val document = app.get("$mainUrl/?s=$q").document

        return document.select("div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block").mapNotNull { element ->
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
        
        // 1. Direct Iframes
        val document = app.get(watchUrl).document
        val iframes = document.select("iframe[src]").map { it.attr("src") }
        
        if (iframes.isNotEmpty()) {
            iframes.forEach { link ->
                 loadExtractor(fixUrl(link), watchUrl, subtitleCallback, callback)
            }
            return true
        }
        
        // 2. Fallback to "vid=" ID logic
        val vid = Regex("vid=([A-Za-z0-9]+)").find(watchUrl)?.groupValues?.get(1)
        if (vid != null) {
            val playUrl = "$mainUrl/play.php?vid=$vid"
            val playDoc = app.get(playUrl).document
            playDoc.select("iframe[src]").forEach { 
                loadExtractor(fixUrl(it.attr("src")), playUrl, subtitleCallback, callback) 
            }
            return true
        }

        return false
    }
}
