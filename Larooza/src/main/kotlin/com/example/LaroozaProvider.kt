package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LaroozaProvider : MainAPI() {

    override var mainUrl = "https://www.larooza.makeup"
    override var name = "Larooza"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // ✅ startsida enligt din adress
    private val sectionUrl = "$mainUrl/gaza.20"

    override val mainPage = mainPageOf(
        sectionUrl to "الرئيسية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val items = document.select("h3 a, h2 a, .postTitle a, .entry-title a, a[href*=\"vid=\"]")
            .mapNotNull { a ->
                val title = a.text().trim()
                val link = fixUrl(a.attr("href").trim())
                if (title.isBlank() || link.isBlank()) return@mapNotNull null

                val poster = a.closest("article, li, .movie, .post, .item")
                    ?.selectFirst("img")
                    ?.attr("src")
                    ?.trim()
                    ?.let { fixUrlNull(it) }

                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val document = app.get("$sectionUrl/?s=$q").document

        return document.select("h3 a, h2 a, .postTitle a, .entry-title a, a[href*=\"vid=\"]")
            .mapNotNull { a ->
                val title = a.text().trim()
                val link = fixUrl(a.attr("href").trim())
                if (title.isBlank() || link.isBlank()) return@mapNotNull null

                val poster = a.closest("article, li, .movie, .post, .item")
                    ?.selectFirst("img")
                    ?.attr("src")
                    ?.trim()
                    ?.let { fixUrlNull(it) }

                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.trim()
            ?.let { fixUrlNull(it) }

        val plot = document.selectFirst("meta[property=og:description]")?.attr("cont
