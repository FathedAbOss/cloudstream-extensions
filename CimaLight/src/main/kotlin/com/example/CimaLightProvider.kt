package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document

class CimaLightProvider : MainAPI() {

    override var mainUrl = "https://w.cimalight.co"
    override var name = "CimaLight"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/movies.php" to "أحدث الأفلام",
        "$mainUrl/main15" to "جديد الموقع",
        "$mainUrl/most.php" to "الأكثر مشاهدة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val items = document.select("h3 a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href"))

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = a.parent()?.parent()
                ?.selectFirst("img")
                ?.attr("src")
                ?.trim()
                ?.let { fixUrlNull(it) }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/search?q=$q").document

        return document.select("h3 a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href"))

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = a.parent()?.parent()
                ?.selectFirst("img")
                ?.attr("src")
                ?.trim()
                ?.let { fixUrlNull(it) }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.trim()
            ?.let { fixUrlNull(it) }

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newMovieLoadResponse(
            name = title.ifBlank { "CimaLight" },
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    /**
     * The core fix for CimaLight.
     * It scrapes the downloads.php page directly because it contains the most reliable 
     * list of external hosters (Updown, Multiup, Vikingfile, etc.) that CloudStream can extract.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data.trim()
        val vid = Regex("vid=([A-Za-z0-9]+)").find(watchUrl)?.groupValues?.getOrNull(1) ?: return false
        
        // CimaLight's most reliable server list is on the downloads page
        val downloadsUrl = "$mainUrl/downloads.php?vid=$vid"
        
        // We must use the watch page as the referer to bypass basic protection
        val downloadDoc = runCatching { 
            app.get(downloadsUrl, referer = watchUrl).document 
        }.getOrNull() ?: return false
        
        var linksFound = 0

        // Scrape all external hoster links from the downloads page
        downloadDoc.select("a[href]").forEach { a ->
            val link = fixUrl(a.attr("href"))
            
            // Filter: Must be an external link (not on cimalight domain) and must be a valid URL
            if (!link.startsWith(mainUrl) && !link.contains("cimalight") && link.startsWith("http")) {
                runCatching {
                    // loadExtractor is the correct CloudStream way to handle these hoster pages
                    loadExtractor(link, downloadsUrl, subtitleCallback, callback)
                    linksFound++
                }
            }
        }

        // If no links were found on the downloads page, we try to find the 'xtgo' redirect link
        // as a secondary source, which often leads to a page like elif.news with more servers.
        if (linksFound == 0) {
            val redirectLink = downloadDoc.selectFirst("a.xtgo")?.attr("href")
            if (redirectLink != null) {
                val fullRedirectUrl = fixUrl(redirectLink)
                val serverPage = runCatching { app.get(fullRedirectUrl, referer = downloadsUrl).document }.getOrNull()
                
                serverPage?.select("#sServer li")?.forEach { li ->
                    val serverLink = li.selectFirst("a")?.attr("href") ?: li.attr("data-url")
                    if (serverLink.isNotEmpty() && serverLink.startsWith("http")) {
                        runCatching {
                            loadExtractor(serverLink, fullRedirectUrl, subtitleCallback, callback)
                            linksFound++
                        }
                    }
                }
            }
        }

        return linksFound > 0
    }
}
