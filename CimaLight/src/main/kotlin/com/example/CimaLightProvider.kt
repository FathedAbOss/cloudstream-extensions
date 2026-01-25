package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

    // ✅ Poster extractor that handles lazy-load and different HTML structures
    private fun extractPosterFromAnchor(a: Element): String? {
        val container = a.closest("article, li, .movie, .post, .item, .Thumb--GridItem, .Thumb--Grid")
        val img = (container ?: a).selectFirst("img") ?: return null

        val posterRaw =
            img.attr("src").trim().ifBlank {
                img.attr("data-src").trim().ifBlank {
                    img.attr("data-original").trim().ifBlank {
                        img.attr("data-lazy-src").trim().ifBlank {
                            img.attr("srcset").trim()
                                .split(" ")
                                .firstOrNull { it.startsWith("http") || it.startsWith("/") }
                                ?.trim()
                                .orEmpty()
                        }
                    }
                }
            }

        return fixUrlNull(posterRaw)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val items = document.select("h3 a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = extractPosterFromAnchor(a)

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
            val link = fixUrl(a.attr("href").trim())

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = extractPosterFromAnchor(a)

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

        // ✅ IMPORTANT: dataUrl must be WATCH url so loadLinks can extract vid correctly
        return newMovieLoadResponse(
            name = title.ifBlank { "CimaLight" },
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String, // watch URL e.g. https://w.cimalight.co/watch.php?vid=xxxx
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val watchUrl = data.trim()

        val vid = Regex("vid=([A-Za-z0-9]+)")
            .find(watchUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        // ✅ STEP 1: Try streaming servers from play.php first
        val playUrl = "$mainUrl/play.php?vid=$vid"

        val playDoc = runCatching {
            app.get(playUrl, referer = watchUrl).document
        }.getOrNull()

        if (playDoc != null) {
            val playLinks = mutableListOf<String>()

            playLinks += playDoc.select("iframe[src]")
                .mapNotNull { fixUrlNull(it.attr("src").trim()) }

            playLinks += playDoc.select("[data-embed-url]")
                .mapNotNull { fixUrlNull(it.attr("data-embed-url").trim()) }

            playLinks += playDoc.select("a[href]")
                .mapNotNull { fixUrlNull(it.attr("href").trim()) }

            val externalPlayLinks = playLinks
                .filter { it.startsWith("http") }
                .filter { !it.contains("cimalight", ignoreCase = true) }
                .distinct()

            externalPlayLinks.forEach { link ->
                runCatching {
                    loadExtractor(link, playUrl, subtitleCallback, callback)
                }
            }

            if (externalPlayLinks.isNotEmpty()) {
                return true
            }
        }

        // ✅ STEP 2: Fallback to downloads.php
        val downloadsUrl = "$mainUrl/downloads.php?vid=$vid"

        val downloadsDoc = runCatching {
            app.get(downloadsUrl, referer = watchUrl).document
        }.getOrNull() ?: return false

        val allLinks = downloadsDoc.select("a[href]")
            .mapNotNull { fixUrlNull(it.attr("href").trim()) }
            .filter { it.startsWith("http") }
            .distinct()

        val externalLinks = allLinks
            .filter { !it.startsWith(mainUrl) && !it.contains("cimalight", ignoreCase = true) }
            .distinct()

        externalLinks.forEach { link ->
            runCatching {
                // ✅ MultiUp: expand mirrors
                if (link.contains("multiup.io", ignoreCase = true)) {
                    val multiDoc = app.get(link, referer = downloadsUrl).document

                    val mirrors = multiDoc.select("a[href]")
                        .mapNotNull { fixUrlNull(it.attr("href").trim()) }
                        .filter { it.startsWith("http") }
                        .filter { !it.contains("multiup.io", ignoreCase = true) }
                        .distinct()
                        .take(20)

                    mirrors.forEach { mirror ->
                        runCatching {
                            loadExtractor(mirror, link, subtitleCallback, callback)
                        }
                    }
                } else {
                    loadExtractor(link, downloadsUrl, subtitleCallback, callback)
                }
            }
        }

        return externalLinks.isNotEmpty()
    }
}
