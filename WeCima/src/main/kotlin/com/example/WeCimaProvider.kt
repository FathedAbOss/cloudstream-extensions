package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import android.util.Base64

class WeCimaProvider : MainAPI() {

    override var mainUrl = "https://wecima.date"
    override var name = "WeCima"
    override var lang = "ar"
    override var hasMainPage = true

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val safeHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
    )

    // ---------------------------
    // 1. Aggressive Image Extractor
    // ---------------------------
    private fun Element.getAnyPoster(): String? {
        // Safe styling extraction
        val style = this.attr("style").ifBlank { 
            this.selectFirst("span.BG--GridItem")?.attr("style") ?: "" 
        }

        if (style.contains("--image")) {
            val url = style.substringAfter("url(").substringBefore(")").trim('"', '\'', ' ')
            if (url.length > 5) return fixUrl(url)
        }

        if (style.contains("background-image")) {
             val url = style.substringAfter("url(").substringBefore(")").trim('"', '\'', ' ')
             if (url.length > 5) return fixUrl(url)
        }

        val img = this.selectFirst("img") ?: return null
        return img.attr("data-src").ifBlank {
            img.attr("data-image").ifBlank {
                img.attr("data-lazy-style").ifBlank {
                    img.attr("src")
                }
            }
        }.let { if (it.length > 5) fixUrl(it) else null }
    }

    // ---------------------------
    // Main Page
    // ---------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/episodes/" to "الحلقات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = safeHeaders).document
        
        val items = doc.select(".GridItem, .BlockItem, .movie, article.post").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href"))
            val title = element.selectFirst("a[title], h3, .Title, strong")?.text() ?: "Unknown"
            val poster = element.getAnyPoster()
            
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/AjaxCenter/Searching/$query/"
        val doc = app.get(url, headers = safeHeaders).document

        return doc.select(".GridItem, .BlockItem, .movie").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href"))
            val title = element.text()
            val poster = element.getAnyPoster()

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ---------------------------
    // 2. Load (Details & Episodes)
    // ---------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = safeHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "WeCima"
        
        val poster = doc.selectFirst(".Poster img, .P-Poster img")?.let { 
            it.attr("data-src").ifBlank { it.attr("src") } 
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        
        val plot = doc.selectFirst(".Story, .story, .desc")?.text()?.trim()
        val year = doc.select("a[href*='year']").text().toIntOrNull()

        // Series Detection
        val episodeElements = doc.select(".EpisodesList a, .Seasons--Episodes a, .List--Seasons--Episodes a")
        
        if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull {
                val href = it.attr("href")
                if (href.isBlank()) return@mapNotNull null
                val name = it.text().trim()
                val epNum = Regex("\\d+").findAll(name).lastOrNull()?.value?.toIntOrNull()
                
                // Fixed: Explicit named arguments for Episode to avoid constructor errors
                Episode(
                    data = fixUrl(href),
                    name = name,
                    episode = epNum
                )
            }.reversed()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.year = year
        }
    }

    // ---------------------------
    // 3. Link Extraction
    // ---------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val doc = app.get(data, headers = safeHeaders).document
        val foundLinks = mutableSetOf<String>()

        // Direct Iframes
        doc.select("iframe").forEach { 
            val src = it.attr("src").ifBlank { it.attr("data-src") }
            if (src.contains("http")) foundLinks.add(fixUrl(src))
        }

        // Watch Servers
        doc.select(".WatchServers li, .ServersList li, ul.Servers li").forEach { li ->
            val potentialUrl = li.attr("data-watch").ifBlank { 
                li.attr("data-url").ifBlank { 
                    li.attr("data-link").ifBlank {
                        li.selectFirst("a")?.attr("data-watch") ?: ""
                    }
                }
            }

            if (potentialUrl.isNotBlank()) {
                if (!potentialUrl.contains("/") && potentialUrl.length > 20) {
                     val decoded = decodeBase64(potentialUrl)
                     if (decoded != null && decoded.contains("http")) {
                         foundLinks.add(fixUrl(decoded))
                     }
                } 
                else if (potentialUrl.contains("=")) {
                    val decoded = decodeSmart(potentialUrl)
                    if (decoded != null) foundLinks.add(fixUrl(decoded))
                    else foundLinks.add(fixUrl(potentialUrl))
                } 
                else if (potentialUrl.contains("http")) {
                    foundLinks.add(fixUrl(potentialUrl))
                }
            }
            
            val href = li.selectFirst("a")?.attr("href") ?: ""
            if (href.contains("http") && !href.contains(mainUrl)) {
                foundLinks.add(fixUrl(href))
            }
        }

        foundLinks.forEach { link ->
            val cleanLink = if (link.contains("url=")) {
                try {
                    link.substringAfter("url=").substringBefore("&")
                        .let { URLDecoder.decode(it, "UTF-8") }
                } catch(e: Exception) { link }
            } else link

            loadExtractor(cleanLink, data, subtitleCallback, callback)
        }

        return true
    }

    private fun decodeBase64(input: String): String? {
        return try {
            // Android-specific Base64 to ensure build compatibility
            val bytes = Base64.decode(input, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeSmart(input: String): String? {
        try {
            val parts = input.split("&")
            for (part in parts) {
                if (part.contains("=")) {
                    val value = part.substringAfter("=")
                    if (value.length > 20) {
                        val decoded = decodeBase64(value)
                        if (decoded != null && decoded.contains("http")) return decoded
                    }
                }
            }
        } catch (e: Exception) { }
        return null
    }
}
