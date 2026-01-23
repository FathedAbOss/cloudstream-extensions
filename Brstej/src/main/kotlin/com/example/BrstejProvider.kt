package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class BrstejProvider : MainAPI() {

    override var mainUrl = "https://pro.brstej.com"
    override var name = "Brstej"
    override var lang = "ar"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(request.data).document

        val items = document.select("div.movie-item")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items
            ),
            hasNext = false
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".title")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null

        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(
            name = title,
            url = fixUrl(href),
            type = TvType.Movie
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.trim()}"
        val document = app.get(url).document

        return document.select("div.movie-item")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.trim()
            ?: "Brstej"

        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val plot = document.selectFirst(".story, .description, .content")?.text()?.trim()

        return newMovieLoadResponse(
            name = title,
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

        val document = app.get(data).document

        // This is generic: it tries to find any video/iframe links
        val links = mutableListOf<String>()

        document.select("iframe").forEach { iframe ->
            iframe.attr("src")?.let { src ->
                if (src.isNotBlank()) links.add(fixUrl(src))
            }
        }

        document.select("a").forEach { a ->
            val href = a.attr("href")
            if (href.contains("mp4") || href.contains("m3u8") || href.contains("embed")) {
                links.add(fixUrl(href))
            }
        }

        links.distinct().forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        return links.isNotEmpty()
    }
}
