package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class ElCinemaProvider : MainAPI() {

    override var mainUrl = "https://elcinema.com"
    override var name = "ElCinema"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"
    override var hasMainPage = true

    // ✅ HomePage: Now Playing (fungerar bättre än startsidan)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/en/now/eg/?page=$page"
        val document = app.get(url).document

        val items = document.select("h3 a").mapNotNull { a ->
            val title = a.text().trim()
            if (title.isEmpty()) return@mapNotNull null

            val href = a.attr("href").trim()
            if (href.isEmpty()) return@mapNotNull null

            newMovieSearchResponse(title, fixUrl(href), TvType.Movie)
        }

        return newHomePageResponse("Now Playing", items)
    }

    // ✅ Search
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search/?q=$q"
        val document = app.get(url).document

        return document.select("a[href*=/work/]").mapNotNull { a ->
            val title = a.text().trim()
            val href = a.attr("href").trim()

            if (title.isEmpty()) return@mapNotNull null
            if (href.isEmpty()) return@mapNotNull null

            newMovieSearchResponse(title, fixUrl(href), TvType.Movie)
        }.distinctBy { it.url }
    }

    // ✅ Load (detaljer)
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val plot = document.selectFirst("meta[name=description]")?.attr("content")

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
}
