package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LaroozaProvider : MainAPI() {

    // ✅ FIX: NO WWW (because Cloudstream can't resolve www.larooza.makeup)
    override var mainUrl = "https://larooza.makeup"
    override var name = "Larooza"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    private val safeHeaders = mapOf(
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
            "div.BlockItem, div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item"
        ).mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href").trim())
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img")
            val title =
                a.attr("title").trim().ifBlank {
                    img?.attr("alt")?.trim().orEmpty().ifBlank {
                        a.text().trim()
                    }
                }

            if (title.isBlank()) return@mapNotNull null

            val poster = img?.attr("src")?.ifBlank {
                img.attr("data-src").ifBlank { img.attr("data-image") }
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/?s=$q", headers = safeHeaders).document

        return document.select(
            "div.BlockItem, div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item"
        ).mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href").trim())
            if (link.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img")
            val title =
                a.attr("title").trim().ifBlank {
                    img?.attr("alt")?.trim().orEmpty().ifBlank {
                        a.text().trim()
                    }
                }

            if (title.isBlank()) return@mapNotNull null

            val poster = img?.attr("src")?.ifBlank {
                img.attr("data-src").ifBlank { img.attr("data-image") }
            }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Larooza"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img")?.attr("src")

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
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

        // 1) direct iframe
        val iframes = document.select("iframe[src]")
            .map { it.attr("src") }
            .filter { it.isNotBlank() }

        if (iframes.isNotEmpty()) {
            iframes.forEach { link ->
                loadExtractor(fixUrl(link), watchUrl, subtitleCallback, callback)
            }
            return true
        }

        // 2) try vid from HTML
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
