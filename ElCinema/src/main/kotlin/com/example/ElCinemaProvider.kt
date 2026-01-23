package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ElCinemaProvider : MainAPI() {

    override var mainUrl = "https://elcinema.com"
    override var name = "ElCinema"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // ✅ Main page: använder "Now Playing" sidan
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/en/now/eg/?page=$page"
        val document = app.get(url).document

        val items = document.select("h3 a").mapNotNull { a ->
            val title = a.text()?.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val href = a.attr("href")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            val parent = a.closest("div") // lite tolerant struktur
            val poster = parent?.selectFirst("img")?.attr("src")

            newMovieSearchResponse(
                title = title,
                url = fixUrl(href),
                type = TvType.Movie
            ) {
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

    // ✅ Search: elcinema använder /search/?q=
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?q=${query.urlEncoded()}"
        val document = app.get(url).document

        // Samla work-länkar (filmer/serier) robust
        val links = document.select("a[href*=/work/]")
            .mapNotNull { a ->
                val href = a.attr("href") ?: return@mapNotNull null
                if (!href.contains("/work/")) return@mapNotNull null
                val title = a.text()?.trim()
                if (title.isNullOrEmpty()) return@mapNotNull null
                Pair(title, fixUrl(href))
            }
            .distinctBy { it.second }
            .take(40)

        return links.map { (title, href) ->
            newMovieSearchResponse(title, href, TvType.Movie)
        }
    }

    // ✅ Load: visar info + poster + plot
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }

        // synopsis: ofta första texten efter rubriken
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

    // ✅ Viktigt: utan loadLinks blir plugin "tomt" när man klickar
    // ElCinema hostar inte filmer direkt, men vi kan åtminstone ge Watch/Subscribe-länkar
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        // "Watch Online" knappar brukar vara externa (netflix/watchit/sling osv.)
        val externalLinks = document.select("a[href]")
            .mapNotNull { a ->
                val href = a.attr("href") ?: return@mapNotNull null
                val fixed = fixUrl(href)
                if (fixed.startsWith(mainUrl)) return@mapNotNull null
                if (!fixed.startsWith("http")) return@mapNotNull null
                fixed
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

        // Om inga länkar hittas -> return false
        return externalLinks.isNotEmpty()
    }

    private fun Element.closest(tag: String): Element? {
        var el: Element? = this
        while (el != null) {
            if (el.tagName() == tag) return el
            el = el.parent()
        }
        return null
    }
}
