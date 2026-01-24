package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class WeCimaProvider : MainAPI() {

    override var mainUrl = "https://wecima.date"
    override var name = "WeCima"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    private val safeHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/cartoon/" to "أنمي و كرتون"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = safeHeaders).document

        val items = document.select("div.GridItem").mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null
            val title = element.selectFirst("strong")?.text()?.trim()
                ?: a.attr("title")?.trim().orEmpty()

            val link = fixUrl(a.attr("href").trim())
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            var poster = element.selectFirst("div.BG--GridItem")?.attr("data-image")
            if (poster.isNullOrBlank()) {
                val style = element.selectFirst("div.BG--GridItem")?.attr("style") ?: ""
                poster = style.substringAfter("url(").substringBefore(")").trim('"').trim('\'')
            }

            if (poster.isNullOrBlank() || poster.length < 5) {
                poster = element.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                posterUrl = fixUrlNull(poster)
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/search/$q", headers = safeHeaders).document

        return document.select("div.GridItem").mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null
            val title = element.selectFirst("strong")?.text()?.trim()
                ?: a.attr("title")?.trim().orEmpty()

            val link = fixUrl(a.attr("href").trim())
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            var poster = element.selectFirst("div.BG--GridItem")?.attr("data-image")
            if (poster.isNullOrBlank()) {
                val style = element.selectFirst("div.BG--GridItem")?.attr("style") ?: ""
                poster = style.substringAfter("url(").substringBefore(")").trim('"').trim('\'')
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                posterUrl = fixUrlNull(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "WeCima"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img")?.attr("src")

        val plot = document.selectFirst("div.StoryMovieContent, div.Desc p")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = fixUrlNull(poster)
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
        val document = app.get(watchUrl, headers = safeHeaders).document

        // ✅ FIXED selector: get real data-url from li or inside buttons/links
        val serverLinks = document.select("ul.WatchServersList li")
            .mapNotNull { li ->
                li.attr("data-url").ifBlank {
                    li.selectFirst("[data-url]")?.attr("data-url").orEmpty()
                }.takeIf { it.isNotBlank() }
            }
            .map { fixUrl(it) }
            .distinct()

        serverLinks.forEach { link ->
            loadExtractor(link, watchUrl, subtitleCallback, callback)
        }

        return serverLinks.isNotEmpty()
    }
}
