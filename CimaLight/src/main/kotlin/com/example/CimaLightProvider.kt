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

    // =========================
    // MAIN PAGE
    // =========================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document

        val items = document.select("div.box-item")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".title")?.text()?.trim()
            ?: this.selectFirst("h3")?.text()?.trim()
            ?: return null

        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        val fixedUrl = fixUrl(href)

        return newMovieSearchResponse(title, fixedUrl, TvType.Movie) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
        }
    }

    // =========================
    // SEARCH
    // =========================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim()}"
        val document = app.get(url).document
        return document.select("div.box-item").mapNotNull { it.toSearchResult() }
    }

    // =========================
    // LOAD DETAILS
    // =========================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().trim()

        val poster = document.selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) }

        val plot = document.selectFirst(".desc")?.text()?.trim()
            ?: document.selectFirst(".story")?.text()?.trim()

        // We return a MovieLoadResponse so the app can show "Play"
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // =========================
    // PLAY LINKS (IMPORTANT!)
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // data = the same url returned in load() (watch page)
        val watchUrl = data

        // Example:
        // https://w.cimalight.co/watch.php?vid=b23fecba5
        val vid = Regex("vid=([A-Za-z0-9]+)")
            .find(watchUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        val downloadsUrl = "$mainUrl/downloads.php?vid=$vid"

        val downloadsDoc = app.get(downloadsUrl, referer = watchUrl).document

        // Collect all external server links
        val links = downloadsDoc.select("a[href]")
            .map { it.attr("href").trim() }
            .filter { it.startsWith("http") }
            .distinct()

        var foundAny = false

        for (link in links) {
            try {
                // CloudStream will try to extract playable links from hosts like:
                // vidnest, fastream, goodstream, etc.
                loadExtractor(link, referer = mainUrl, subtitleCallback, callback)
                foundAny = true
            } catch (e: Exception) {
                // ignore broken hosts and continue
            }
        }

        return foundAny
    }
}
