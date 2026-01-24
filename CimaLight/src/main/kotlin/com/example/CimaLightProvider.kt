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

        // ✅ IMPORTANT: dataUrl must be WATCH url
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

        // ✅ STEP 1: Try STREAM servers from play.php first
        val playUrl = "$mainUrl/play.php?vid=$vid"

        val playDoc = runCatching {
            app.get(playUrl, referer = watchUrl).document
        }.getOrNull()

        if (playDoc != null) {

            val playLinks = mutableListOf<String>()

            // iframe players
            playLinks += playDoc.select("iframe[src]")
                .mapNotNull { fixUrlNull(it.attr("src").trim()) }

            // some servers hide in data-embed-url (like Larooza)
            playLinks += playDoc.select("[data-embed-url]")
                .mapNotNull { fixUrlNull(it.attr("data-embed-url").trim()) }

            // any direct links
            playLinks += playDoc.select("a[href]")
                .mapNotNull { fixUrlNull(it.attr("href").trim()) }

            val httpLinks = playLinks
                .filter { it.startsWith("http") }
                .distinct()

            // ✅ split internal/external
            val externalPlayLinks = httpLinks
                .filter { !it.contains("cimalight", ignoreCase = true) }
                .distinct()

            val internalPlayLinks = httpLinks
                .filter { it.contains("cimalight", ignoreCase = true) }
                .distinct()
                .take(15)

            // ✅ external directly
            externalPlayLinks.forEach { link ->
                runCatching {
                    loadExtractor(link, playUrl, subtitleCallback, callback)
                }
            }

            // ✅ NEW FIX: follow internal play links 1 step to find real external hosters
            internalPlayLinks.forEach { internal ->
                runCatching {
                    val doc2 = app.get(internal, referer = playUrl).document

                    val secondLinks = doc2.select("a[href], iframe[src]")
                        .mapNotNull { el ->
                            val attr = if (el.hasAttr("href")) el.attr("href") else el.attr("src")
                            fixUrlNull(attr.trim())
                        }
                        .filter { it.startsWith("http") }
                        .distinct()

                    val externalSecond = secondLinks
                        .filter { !it.contains("cimalight", ignoreCase = true) }
                        .distinct()

                    externalSecond.forEach { ext ->
                        runCatching {
                            loadExtractor(ext, internal, subtitleCallback, callback)
                        }
                    }
                }
            }

            // ✅ if we found anything at all, return true
            if (externalPlayLinks.isNotEmpty() || internalPlayLinks.isNotEmpty()) {
                return true
            }
        }

        // ✅ STEP 2 (Fallback): downloads.php links
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
