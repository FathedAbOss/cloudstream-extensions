package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CimaLightProvider : MainAPI() {

    override var mainUrl = "https://w.cimalight.co"
    override var name = "CimaLight"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"
    override var hasMainPage = true

    // ✅ This is the MOST IMPORTANT part for Cloudstream home rows
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // ✅ Cloudstream will call this using request.data
        val document = app.get(request.data).document

        val items = document.select("div.box-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".title")?.text()?.trim()
            ?: this.selectFirst("h3")?.text()?.trim()
            ?: return null

        val href = this.selectFirst("a")?.attr("href")?.trim() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")?.trim()

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = posterUrl?.let { fixUrlNull(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // ✅ Cimalight search works better with ?s=
        val url = "$mainUrl/?s=${query.trim()}"
        val document = app.get(url).document

        return document.select("div.box-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().trim()

        val poster = document.selectFirst("img[src]")?.attr("src")?.let { fixUrlNull(it) }

        val plot = document.selectFirst(".desc")?.text()?.trim()
            ?: document.selectFirst(".story")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ✅ Links: only if the page has a "watch.php?vid=" structure
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val vid = Regex("vid=([A-Za-z0-9]+)")
            .find(data)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        val downloadsUrl = "$mainUrl/downloads.php?vid=$vid"
        val downloadsDoc = app.get(downloadsUrl, referer = data).document

        val links = downloadsDoc.select("a[href]")
            .map { it.attr("href").trim() }
            .filter { it.startsWith("http") }
            .distinct()

        var foundAny = false

        for (link in links) {
            try {
                loadExtractor(link, referer = mainUrl, subtitleCallback, callback)
                foundAny = true
            } catch (e: Exception) {
                // ignore
            }
        }

        return foundAny
    }
}
