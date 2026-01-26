package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class YallaCimaProvider : MainAPI() {

    override var mainUrl = "https://yallacima.net"
    override var name = "YallaCima"
    override var lang = "ar"

    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // ✅ Keep ONLY ONE mainPage
    override val mainPage = mainPageOf(
        mainUrl to "الرئيسية"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = headers, referer = mainUrl).document
        val sections = mutableListOf<HomePageList>()

        // Try #1: sections
        document.select("section.section").forEach { section ->
            val title = section.selectFirst("h2.title, h2, .title")?.text()?.trim().orEmpty()
            val items = section.select("div.movie-item, article, .movie-item")
                .mapNotNull { it.toSearchResult() }

            if (title.isNotBlank() && items.isNotEmpty()) {
                sections.add(HomePageList(title, items))
            }
        }

        // ✅ Try #2: fallback if empty
        if (sections.isEmpty()) {
            val items = document.select("div.movie-item, article, .movie-item")
                .mapNotNull { it.toSearchResult() }

            if (items.isNotEmpty()) {
                sections.add(HomePageList("أحدث الإضافات", items))
            }
        }

        // ✅ Final fallback: grab all cards by a:has(img)
        if (sections.isEmpty()) {
            val items = document.select("a:has(img)")
                .mapNotNull { a ->
                    val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    if (!href.contains("/movies/") && !href.contains("/series/")) return@mapNotNull null

                    val img = a.selectFirst("img")
                    val title = a.attr("title").ifBlank { img?.attr("alt").orEmpty() }.trim()
                    if (title.isBlank()) return@mapNotNull null

                    val poster = fixUrlNull(
                        img?.attr("data-src").ifBlank { img?.attr("src").orEmpty() }
                    )

                    val type = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie

                    newMovieSearchResponse(title, href, type) {
                        this.posterUrl = poster
                    }
                }
                .distinctBy { it.url }

            if (items.isNotEmpty()) {
                sections.add(HomePageList("محتوى الموقع", items))
            }
        }

        return newHomePageResponse(sections)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".title")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null

        val hrefRaw = this.selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(hrefRaw)

        val posterUrl =
            this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }

        return if (href.contains("/series/") || title.contains("مسلسل")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = headers, referer = mainUrl).document
        return document.select("div.movie-item, article, .movie-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, referer = mainUrl).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst(".story")?.text()

        // Extract postId if present
        val postId = document.html().let {
            Regex("vo_postID\\s*=\\s*\"(\\d+)\"").find(it)?.groupValues?.get(1)
        }.orEmpty()

        if (url.contains("/series/")) {
            val episodes = document.select("div.episodes-list a").map {
                Episode(it.attr("href"), it.text())
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, "$url|#|$postId") {
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

        val parts = data.split("|#|")
        val url = parts[0]
        val postId = parts.getOrNull(1).orEmpty()

        var foundAny = false

        // 1) WATCH page
        val watchUrl = url.trimEnd('/') + "/watch"
        val watchDoc = app.get(watchUrl, headers = headers, referer = mainUrl).document

        // Try direct iframe first
        watchDoc.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            runCatching {
                loadExtractor(src, watchUrl, subtitleCallback, callback)
                foundAny = true
            }
        }

        // 2) AJAX server extraction
        val serverItems = watchDoc.select("li[onclick^=getServer2]")

        serverItems.forEach { li ->
            val onclick = li.attr("onclick")
            val match = Regex("getServer2\\(this\\.id,(\\d+),(\\d+)\\)").find(onclick)

            if (match != null && postId.isNotBlank()) {
                val videoIndex = match.groupValues[1]
                val sId = match.groupValues[2]

                val ajaxUrl =
                    "$mainUrl/wp-content/themes/yallcima/temp/ajax/iframe2.php?id=$postId&video=$videoIndex&sId=$sId"

                runCatching {
                    val iframeHtml = app.get(ajaxUrl, headers = headers, referer = watchUrl).text
                    val iframeSrc = Regex("src=\"([^\"]+)\"").find(iframeHtml)?.groupValues?.get(1)

                    if (!iframeSrc.isNullOrBlank()) {
                        loadExtractor(iframeSrc, ajaxUrl, subtitleCallback, callback)
                        foundAny = true
                    }
                }
            }
        }

        // 3) Download servers
        runCatching {
            val downloadDoc = app.get(url.trimEnd('/') + "/download", headers = headers, referer = url).document
            downloadDoc.select("a.download-link, a[href*=\"download\"]").forEach { a ->
                val link = fixUrlNull(a.attr("href")) ?: return@forEach
                loadExtractor(link, url, subtitleCallback, callback)
                foundAny = true
            }
        }

        return foundAny
    }
}
