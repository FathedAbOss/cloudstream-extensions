package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LaroozaProvider : MainAPI() {

    override var mainUrl = "https://larooza.makeup"
    override var name = "Larooza"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // ✅ Browser-like headers for HTML requests
    private val safeHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    // ✅ Poster requests usually need Referer too
    private val posterHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/gaza.20" to "الرئيسية",
        "$mainUrl/category/movies/" to "أفلام",
        "$mainUrl/category/series/" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = safeHeaders).document

        val items = document.select(
            "div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item, div.BlockItem"
        ).mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null

            // ⚠️ a.text() is often empty on card grids, so we try stronger title sources
            val title =
                element.selectFirst("h1,h2,h3,.title,.name")?.text()?.trim()
                    ?: a.attr("title")?.trim()
                    ?: a.text().trim()

            val link = fixUrl(a.attr("href").trim())
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img")
            val poster = img?.attr("src")?.ifBlank {
                img.attr("data-src").ifBlank { img.attr("data-image") }
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = posterHeaders
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/?s=$q", headers = safeHeaders).document

        return document.select(
            "div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item, div.BlockItem"
        ).mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null

            val title =
                element.selectFirst("h1,h2,h3,.title,.name")?.text()?.trim()
                    ?: a.attr("title")?.trim()
                    ?: a.text().trim()

            val link = fixUrl(a.attr("href").trim())
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img")
            val poster = img?.attr("src")?.ifBlank {
                img.attr("data-src").ifBlank { img.attr("data-image") }
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = posterHeaders
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Larooza"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.poster img")?.attr("src")

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = posterHeaders
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

        // ✅ 1) Direct iframe extraction
        val iframeLinks = document.select("iframe[src]")
            .map { fixUrl(it.attr("src")) }
            .filter { it.isNotBlank() }

        if (iframeLinks.isNotEmpty()) {
            iframeLinks.forEach { link ->
                loadExtractor(link, watchUrl, subtitleCallback, callback)
            }
            return true
        }

        // ✅ 2) REAL Larooza servers: WatchList data attributes
        val watchListLinks = document.select(".WatchList li")
            .mapNotNull { li ->
                li.attr("data-embed-url").ifBlank {
                    li.attr("data-url").ifBlank {
                        li.attr("data-href").ifBlank {
                            null
                        }
                    }
                }.takeIf { !it.isNullOrBlank() }
            }
            .map { fixUrl(it) }

        if (watchListLinks.isNotEmpty()) {
            watchListLinks.forEach { link ->
                loadExtractor(link, watchUrl, subtitleCallback, callback)
            }
            return true
        }

        // ✅ 3) Sometimes link is hidden inside onclick="..."
        val onclickLinks = document.select("[onclick]")
            .mapNotNull { el ->
                val oc = el.attr("onclick")
                // Grab first URL inside quotes
                Regex("""(https?:\/\/[^'"]+|\/[^'"]+)""").find(oc)?.value
            }
            .map { fixUrl(it) }
            .distinct()

        if (onclickLinks.isNotEmpty()) {
            onclickLinks.forEach { link ->
                loadExtractor(link, watchUrl, subtitleCallback, callback)
            }
            return true
        }

        // ✅ 4) Fallback: if page contains vid=... somewhere, go to play.php
        val vid = Regex("""vid=([A-Za-z0-9]+)""")
            .find(document.html())
            ?.groupValues
            ?.getOrNull(1)

        if (!vid.isNullOrBlank()) {
            val playUrl = "$mainUrl/play.php?vid=$vid"
            val playDoc = app.get(playUrl, headers = safeHeaders).document

            playDoc.select("iframe[src]").forEach {
                loadExtractor(fixUrl(it.attr("src")), playUrl, subtitleCallback, callback)
            }
            return true
        }

        return false
    }
}
