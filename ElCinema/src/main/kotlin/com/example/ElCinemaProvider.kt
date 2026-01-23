package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ElCinemaProvider : MainAPI() {

    override var mainUrl = "https://elcinema.com"
    override var name = "ElCinema"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // ✅ Main page: Now Playing
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/en/now/eg/?page=$page"
        val document = app.get(url).document

        val items = document.select("h3 a").mapNotNull { a ->
            val title = a.text().trim()
            if (title.isEmpty()) return@mapNotNull null

            val href = a.attr("href").trim()
            if (href.isEmpty()) return@mapNotNull null

            val poster = a.parent()?.parent()?.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster?.let { fixUrlNull(it) }
            }
        }

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = "Now Playing",
                    list = items,
                    isHorizontalImages = true
                )
            ),
            hasNext = items.isNotEmpty()
        )
    }

    // ✅ Search
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search/?q=$q"
        val document = app.get(url).document

        val results = document.select("a[href*=/work/]").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (!href.contains("/work/")) return@mapNotNull null

            val title = a.text().trim()
            if (title.isEmpty()) return@mapNotNull null

            newMovieSearchResponse(title, fixUrl(href), TvType.Movie)
        }.distinctBy { it.url }

        return results
    }

    // ✅ Load details page
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }

        val plot = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.select("p").firstOrNull { it.text().length > 40 }?.text()

        return newMovieLoadResponse(
            name = if (title.isNotEmpty()) title else "ElCinema",
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ✅ Load links
    // ElCinema hostar oftast inte streams direkt, men vi försöker ta externa länkar.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        val externalLinks = document.select("a[href]")
            .mapNotNull { a ->
                val href = a.attr("href").trim()
                if (!href.startsWith("http")) return@mapNotNull null
                if (href.startsWith(mainUrl)) return@mapNotNull null
                href
            }
            .distinct()
            .take(20)

        externalLinks.forEach { link ->
            callback(
                ExtractorLink(
                    source = name,
                    name = "Open Link",
                    url = link,
                    referer = data,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
        }

        return externalLinks.isNotEmpty()
    }
}
