package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class WeCimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.click/"
    override var name = "WeCima"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries )
    override var lang = "ar"
    override var hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val sections = document.select("div.GridItem")
        val home = sections.mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.Thumb--Title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("span.Thumb--Image")?.attr("style")?.let {
            Regex("url\\\\((.*)\\\\)").find(it)?.groupValues?.get(1)
        }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query").document
        return document.select("div.GridItem").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: ""
        val poster = document.selectFirst("div.Post--Image span")?.attr("style")?.let {
            Regex("url\\\\((.*)\\\\)").find(it)?.groupValues?.get(1)
        }
        val plot = document.selectFirst("div.Post--Content")?.text()
        
        return if (url.contains("series")) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf()) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }
}
