package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LaroozaProvider : MainAPI() {

    override var mainUrl = "https://www.larooza.makeup"
    override var name = "Larooza"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // ✅ Start from gaza.20 section
    private val sectionUrl = "$mainUrl/gaza.20"

    override val mainPage = mainPageOf(
        sectionUrl to "الرئيسية",
        "$sectionUrl/films" to "أفلام",
        "$sectionUrl/series" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val items = document.select("h3 a, h2 a, .postTitle a, .entry-title a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val img = a.parent()?.parent()?.selectFirst("img")
            val poster = img?.attr("src")?.trim()?.let { fixUrlNull(it) }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        // ✅ Search within the section
        val document = app.get("$sectionUrl/?s=$q").document

        return document.select("h3 a, h2 a, .postTitle a, .entry-title a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val img = a.parent()?.parent()?.selectFirst("img")
            val poster = img?.attr("src")?.trim()?.let { fixUrlNull(it) }

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
            name = title.ifBlank { "Larooza" },
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

        val vid = Regex("vid=([A-Za-z0-9]+)")
            .find(watchUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        val playUrl = "$mainUrl/play.php?vid=$vid"

        val doc = runCatching {
            app.get(playUrl, referer = watchUrl).document
        }.getOrNull() ?: return false

        val embedLinks = doc.select(".WatchList li[data-embed-url]")
            .mapNotNull { fixUrlNull(it.attr("data-embed-url").trim()) }
            .filter { it.startsWith("http") }
            .distinct()

        embedLinks.forEach { link ->
            runCatching {
                loadExtractor(link, playUrl, subtitleCallback, callback)
            }
        }

        return embedLinks.isNotEmpty()
    }
}
