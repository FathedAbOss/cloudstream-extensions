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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data.trim()
        
        // 1. Get the initial watch page
        val watchDoc = app.get(watchUrl).document
        
        // 2. Find the redirect link (xtgo) which leads to the real servers (e.g., elif.news)
        val redirectLink = watchDoc.selectFirst("a.xtgo")?.attr("href") 
            ?: watchDoc.selectFirst(".video-bibplayer-poster")?.parent()?.attr("href")
        
        if (redirectLink != null) {
            val fullRedirectUrl = fixUrl(redirectLink)
            
            // 3. Navigate to the redirect page (e.g., elif.news)
            // This page contains the actual server list in <div id="sServer">
            val serverPage = app.get(fullRedirectUrl, referer = watchUrl).document
            
            // 4. Extract servers from the hidden list and send to extractors
            // The links on this page are often the ones CloudStream extractors can handle
            serverPage.select("#sServer li").forEach { li ->
                // In some cases, the server link is in an onclick or data attribute
                // Here we try to find any usable link within the list item
                val serverLink = li.selectFirst("a")?.attr("href") ?: li.attr("data-url")
                if (serverLink.isNotEmpty() && serverLink.startsWith("http")) {
                    runCatching {
                        loadExtractor(serverLink, fullRedirectUrl, subtitleCallback, callback)
                    }
                }
            }
            
            // Fallback: If the list items don't have direct links, they might trigger AJAX.
            // However, most CloudStream extractors work best with the direct hoster URLs.
        }

        // 5. COMPREHENSIVE FALLBACK: Scrape the downloads page
        // This is often the most reliable way to get high-quality servers like Updown, Voe, etc.
        val vid = Regex("vid=([A-Za-z0-9]+)").find(watchUrl)?.groupValues?.getOrNull(1)
        if (vid != null) {
            val downloadsUrl = "$mainUrl/downloads.php?vid=$vid"
            val downloadDoc = runCatching { 
                app.get(downloadsUrl, referer = watchUrl).document 
            }.getOrNull()
            
            downloadDoc?.select("a[href]")?.forEach { a ->
                val link = fixUrl(a.attr("href"))
                // Filter for external hosters only
                if (!link.startsWith(mainUrl) && !link.contains("cimalight") && link.startsWith("http")) {
                    runCatching {
                        loadExtractor(link, downloadsUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }
}
