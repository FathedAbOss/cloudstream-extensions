package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class YallaCimaProvider : MainAPI() {
    override var mainUrl = "https://yallacima.net"
    override var name = "YallaCima"
    override var lang = "ar"

    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
override val mainPage = mainPageOf(
    mainUrl to "الرئيسية"
)

    override val mainPage = mainPageOf(
        mainUrl to "الرئيسية"
    )

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val document = app.get(mainUrl).document
    val sections = mutableListOf<HomePageList>()

    // محاولة 1: sections
    document.select("section.section").forEach { section ->
        val title = section.selectFirst("h2.title, h2, .title")?.text()?.trim().orEmpty()
        val items = section.select("div.movie-item, article, .movie-item").mapNotNull { it.toSearchResult() }
        if (title.isNotBlank() && items.isNotEmpty()) {
            sections.add(HomePageList(title, items))
        }
    }

    // ✅ محاولة 2: لو طلع فاضي (fallback)
    if (sections.isEmpty()) {
        val items = document.select("div.movie-item, article, .movie-item").mapNotNull { it.toSearchResult() }
        if (items.isNotEmpty()) {
            sections.add(HomePageList("أحدث الإضافات", items))
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
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.movie-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst(".story")?.text()
        
        // Extract the internal Post ID needed for servers
        val postId = document.html().let { 
            Regex("vo_postID\\s*=\\s*\"(\\d+)\"").find(it)?.groupValues?.get(1) 
        } ?: ""

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
        val postId = parts.getOrNull(1) ?: return false
        var foundAny = false

        // 1. Extract Streaming Servers via AJAX (Bypasses multi-click)
        val watchDoc = app.get("$url/watch").document
        val serverItems = watchDoc.select("li[onclick^=getServer2]")
        
        serverItems.forEach { li ->
            val onclick = li.attr("onclick")
            val match = Regex("getServer2\\(this\\.id,(\\d+),(\\d+)\\)").find(onclick)
            if (match != null) {
                val videoIndex = match.groupValues[1]
                val sId = match.groupValues[2]
                
                // Simulate the internal AJAX call that the "Play" button makes
                val ajaxUrl = "$mainUrl/wp-content/themes/yallcima/temp/ajax/iframe2.php?id=$postId&video=$videoIndex&sId=$sId"
                runCatching {
                    val iframeHtml = app.get(ajaxUrl, referer = "$url/watch").text
                    val iframeSrc = Regex("src=\"([^\"]+)\"").find(iframeHtml)?.groupValues?.get(1)
                    
                    if (iframeSrc != null) {
                        if (loadExtractor(iframeSrc, ajaxUrl, subtitleCallback, callback)) {
                            foundAny = true
                        }
                    }
                }
            }
        }

        // 2. Extract Download Servers
        runCatching {
            val downloadDoc = app.get("$url/download").document
            downloadDoc.select("a.download-link").forEach { a ->
                val link = a.attr("href")
                if (loadExtractor(link, "$url/download", subtitleCallback, callback)) {
                    foundAny = true
                }
            }
        }

        return foundAny
    }
}
