package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

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

    private fun isInternal(url: String): Boolean {
        return url.startsWith(mainUrl) || url.contains("cimalight", ignoreCase = true)
    }

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

        // ✅ 1) försök hitta vid i URL
        var vid = Regex("vid=([A-Za-z0-9]+)").find(url)?.groupValues?.getOrNull(1)

        // ✅ 2) annars försök hitta vid i HTML (länkar som downloads.php?vid=xxxx)
        if (vid.isNullOrBlank()) {
            val vidLink = document.selectFirst("a[href*=\"downloads.php?vid=\"]")?.attr("href")
            vid = vidLink?.let {
                Regex("vid=([A-Za-z0-9]+)").find(it)?.groupValues?.getOrNull(1)
            }
        }

        val downloadsUrl = if (!vid.isNullOrBlank()) "$mainUrl/downloads.php?vid=$vid" else url

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

    private fun extractAllLinks(document: Document): List<String> {
        val linksA = document.select("a[href]")
            .mapNotNull { fixUrlNull(it.attr("href").trim()) }

        val linksIframe = document.select("iframe[src]")
            .mapNotNull { fixUrlNull(it.attr("src").trim()) }

        val linksSource = document.select("source[src], video[src]")
            .mapNotNull { fixUrlNull(it.attr("src").trim()) }

        val linksData = document.select("[data-url], [data-href], [data-src], [data-link]")
            .flatMap { el ->
                listOf(
                    el.attr("data-url"),
                    el.attr("data-href"),
                    el.attr("data-src"),
                    el.attr("data-link")
                )
            }
            .mapNotNull { fixUrlNull(it.trim()) }

        val linksOnClick = document.select("[onclick]")
            .mapNotNull { el ->
                val on = el.attr("onclick")
                Regex("(https?://[^'\"\\s]+)").find(on)?.value
            }
            .mapNotNull { fixUrlNull(it.trim()) }

        // ✅ Script-länkar (många servrar göms här)
        val linksScript = document.select("script")
            .map { it.data() + " " + it.html() }
            .flatMap { scriptText ->
                Regex("(https?://[^\"'\\s<>]+)")
                    .findAll(scriptText)
                    .map { it.value }
                    .toList()
            }
            .mapNotNull { fixUrlNull(it.trim()) }

        return (linksA + linksIframe + linksSource + linksData + linksOnClick + linksScript)
            .filter { it.startsWith("http") }
            .distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc1 = app.get(data).document
        val firstLinks = extractAllLinks(doc1)

        val externalLinks = firstLinks.filter { !isInternal(it) }.distinct()
        val internalLinks = firstLinks.filter { isInternal(it) }.distinct().take(15)

        // 1) kör externa direkt
        externalLinks.forEach { link ->
            runCatching {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        // 2) följ interna EN gång och extrahera externa därifrån
        internalLinks.forEach { internal ->
            runCatching {
                val doc2 = app.get(internal, referer = data).document
                val secondLinks = extractAllLinks(doc2)

                val secondExternal = secondLinks.filter { !isInternal(it) }.distinct()
                secondExternal.forEach { ext ->
                    loadExtractor(ext, internal, subtitleCallback, callback)
                }
            }
        }

        return firstLinks.isNotEmpty()
    }
}
