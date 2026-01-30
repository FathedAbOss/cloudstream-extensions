package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder

class Cima4UProvider : MainAPI() {

    override var mainUrl = "https://cfu.cam"
    override var name = "Cima4U"
    override var lang = "ar"
    override var hasMainPage = true

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private fun headersOf(referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer
    )

    private fun canonical(u: String): String = fixUrl(u.trim(), mainUrl).substringBefore("#").trim()

    // -----------------------
    // MainPage
    // -----------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الأحدث",
        "$mainUrl/category/movies/" to "أفلام",
        "$mainUrl/category/series/" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headersOf("$mainUrl/")).document
        val items = parseListing(doc)
        return newHomePageResponse(request.name, items)
    }

    // -----------------------
    // Search
    // -----------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val enc = URLEncoder.encode(q, "UTF-8")
        val url = "$mainUrl/?s=$enc"

        val doc = app.get(url, headers = headersOf("$mainUrl/")).document
        val results = parseListing(doc)
        if (results.isNotEmpty()) return results

        // fallback: just return empty (you can later add crawl fallback like WeCima)
        return emptyList()
    }

    // -----------------------
    // Load (details)
    // -----------------------
    override suspend fun load(url: String): LoadResponse {
        val pageUrl = canonical(url)
        val doc = app.get(pageUrl, headers = headersOf("$mainUrl/")).document

        val title =
            doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: "Cima4U"

        val poster =
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?: doc.selectFirst("img[src]")?.attr("src")?.trim()

        val plot =
            doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val type = guessType(title, pageUrl)

        return if (type == TvType.TvSeries) {
            val episodes = doc.extractEpisodesSimple(pageUrl)
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = fixUrlNull(poster?.let { canonical(it) })
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, pageUrl, type, pageUrl) {
                posterUrl = fixUrlNull(poster?.let { canonical(it) })
                this.plot = plot
            }
        }
    }

    // -----------------------
    // loadLinks (details -> watch -> external hosts)
    // -----------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val detailsUrl = canonical(data)
        if (detailsUrl.isBlank()) return false

        val detailsDoc = app.get(detailsUrl, headers = headersOf("$mainUrl/")).document

        // Find watch link reliably (don’t guess patterns)
        val watchHref = detailsDoc.selectFirst(
            "a:contains(مشاهدة الآن), a[href*=/watch]"
        )?.attr("href")?.trim()

        val watchUrl = watchHref?.let { canonical(it) }
            ?: run {
                val clean = if (detailsUrl.endsWith("/")) detailsUrl else "$detailsUrl/"
                "${clean}watch/"
            }

        val watchDoc = app.get(watchUrl, headers = headersOf(detailsUrl)).document

        // Collect external hosts (download servers are usually direct anchors on watch page)
        val candidates = LinkedHashSet<String>()
        watchDoc.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            if (href.isBlank()) return@forEach
            val u = href.substringBefore("#").trim()
            val isExternal = u.startsWith("http") && !u.contains("cfu.cam")
            if (isExternal) candidates.add(u)
        }

        if (candidates.isEmpty()) return false

        var foundAny = false
        for (u in candidates) {
            try {
                loadExtractor(u, watchUrl, subtitleCallback, callback)
                foundAny = true
            } catch (_: Throwable) {
                // ignore per-host failures
            }
        }
        return foundAny
    }

    // -----------------------
    // Listing parser (simple but works on these sites)
    // -----------------------
    private fun parseListing(doc: Document): List<SearchResponse> {
        val out = LinkedHashSet<SearchResponse>()

        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach

            val url = canonical(href)
            if (!url.contains("cfu.cam")) return@forEach
            if (url.contains("/watch", ignoreCase = true)) return@forEach

            val title = a.text().trim()
            if (title.length < 5) return@forEach

            // Many posts start with "مشاهدة ..."
            if (!title.contains("مشاهدة")) return@forEach

            val type = guessType(title, url)

            out.add(newMovieSearchResponse(title, url, type))
        }

        return out.toList()
    }

    private fun guessType(title: String, url: String): TvType {
        val t = title.lowercase()
        val u = url.lowercase()
        if (t.contains("مسلسل") || t.contains("الحلقة") || t.contains("موسم") || u.contains("series")) return TvType.TvSeries
        if (t.contains("انمي") || u.contains("انمي")) return TvType.Anime
        return TvType.Movie
    }

    private fun Document.extractEpisodesSimple(seriesUrl: String): List<Episode> {
        val eps = LinkedHashMap<String, Episode>()
        val epRegex = Regex("""الحلقة\s*(\d{1,4})""")

        select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach
            val link = canonical(href)
            if (!link.contains("cfu.cam")) return@forEach
            if (link == seriesUrl) return@forEach

            val txt = a.text().trim()
            if (!txt.contains("الحلقة") && !link.contains("الحلقة")) return@forEach

            val epNum = epRegex.find(txt)?.groupValues?.getOrNull(1)?.toIntOrNull()

            eps.putIfAbsent(link, newEpisode(link) {
                name = if (epNum != null) "الحلقة $epNum" else txt.ifBlank { "مشاهدة" }
                season = 1
                episode = epNum
            })
        }

        if (eps.isEmpty()) {
            return listOf(newEpisode(seriesUrl) {
                name = "مشاهدة"
                season = 1
                episode = 1
            })
        }

        return eps.values.toList().sortedBy { it.episode ?: 999999 }
    }
}
