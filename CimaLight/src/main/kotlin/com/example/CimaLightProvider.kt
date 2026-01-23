package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CimaLightProvider : MainAPI() {

    override var mainUrl = "https://w.cimalight.co"
    override var name = "CimaLight"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/movies.php" to "أحدث الأفلام",
        "$mainUrl/main15" to "جديد الموقع",
        "$mainUrl/most.php" to "الأكثر مشاهدة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val items = document.select("h3 a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href"))

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = a.parent()?.parent()
                ?.selectFirst("img")
                ?.attr("src")
                ?.trim()
                ?.let { fixUrlNull(it) }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/search?q=$q").document

        return document.select("h3 a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href"))

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = a.parent()?.parent()
                ?.selectFirst("img")
                ?.attr("src")
                ?.trim()
                ?.let { fixUrlNull(it) }

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

        val vid = Regex("vid=([A-Za-z0-9]+)").find(url)?.groupValues?.getOrNull(1)
        val downloadsUrl = if (vid != null) "$mainUrl/downloads.php?vid=$vid" else url

        return newMovieLoadResponse(
            name = title.ifBlank { "CimaLight" },
            url = url,
            type = TvType.Movie,
            dataUrl = downloadsUrl
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

        val linksA = document.select("a[href]")
            .mapNotNull { fixUrlNull(it.attr("href").trim()) }
            .filter { it.startsWith("http") }
            .distinct()

        val linksIframe = document.select("iframe[src]")
            .mapNotNull { fixUrlNull(it.attr("src").trim()) }
            .filter { it.startsWith("http") }
            .distinct()

        val linksData = document.select("[data-url], [data-href]")
            .flatMap { el -> listOf(el.attr("data-url"), el.attr("data-href")) }
            .mapNotNull { fixUrlNull(it.trim()) }
            .filter { it.startsWith("http") }
            .distinct()

        val linksOnClick = document.select("[onclick]")
            .mapNotNull { el ->
                val on = el.attr("onclick")
                Regex("(https?://[^'\"\\s]+)").find(on)?.value
            }
            .mapNotNull { fixUrlNull(it.trim()) }
            .filter { it.startsWith("http") }
            .distinct()

        val allLinks = (linksA + linksIframe + linksData + linksOnClick).distinct()

        allLinks.forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        return allLinks.isNotEmpty()
    }
}
