package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MyCimaProvider : MainAPI() {

    override var mainUrl = "https://my-cima.club"
    override var name = "MyCima"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    private val safeHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = safeHeaders).document

        val items = document.select("article, div.GridItem, div.BlockItem, div.col-md-2, div.col-xs-6, div.movie, li.item")
            .mapNotNull { element ->
                val a = element.selectFirst("a[href]") ?: return@mapNotNull null
                val link = fixUrl(a.attr("href").trim())

                val img = element.selectFirst("img")
                val title =
                    a.attr("title").trim().ifBlank {
                        img?.attr("alt")?.trim().orEmpty().ifBlank { a.text().trim() }
                    }

                if (title.isBlank() || link.isBlank()) return@mapNotNull null

                val poster = img?.attr("src")?.ifBlank {
                    img.attr("data-src").ifBlank { img.attr("data-image") }
                }

                newMovieSearchResponse(title, link, TvType.Movie) {
                    posterUrl = fixUrlNull(poster)
                }
            }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "MyCima"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img")?.attr("src")

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

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

        val iframeLinks = document.select("iframe[src]")
            .map { fixUrl(it.attr("src")) }
            .filter { it.isNotBlank() }
            .distinct()

        iframeLinks.forEach { link ->
            loadExtractor(link, watchUrl, subtitleCallback, callback)
        }

        return iframeLinks.isNotEmpty()
    }
}
