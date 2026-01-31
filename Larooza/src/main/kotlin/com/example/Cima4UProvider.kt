package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.LinkedHashMap
import kotlin.math.min

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

    // ---------------------------
    // Safety limits
    // ---------------------------
    private val MAX_SEARCH_RESULTS = 30
    private val MAX_CRAWL_PAGES = 6
    private val MAX_CANDIDATES = 90
    private val MAX_FINAL_LINKS = 60
    private val MAX_INTERNAL_RESOLVE = 14
    private val PER_REQ_TIMEOUT_MS = 6500L

    // Small poster cache to reduce requests
    private val posterCache = LinkedHashMap<String, String?>()

    private fun headersOf(referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer
    )

    private fun posterHeaders() = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    /**
     * Canonicalize:
     * - fixUrl(url) ONE ARG
     * - Keep query
     * - Drop only fragment
     */
    private fun canonical(raw: String): String {
        val u = raw.trim()
        if (u.isBlank()) return u
        return fixUrl(u).substringBefore("#").trim()
    }

    /**
     * ✅ FIX: host is nullable -> always convert to non-null string BEFORE lowercase/contains
     */
    private fun isInternalUrl(u: String): Boolean {
        if (!u.startsWith("http")) return true
        val host = runCatching { (URI(u).host ?: "").lowercase() }.getOrDefault("")
        if (host.isBlank()) return true
        return host.contains("cfu") || host.contains("cima4u") || host.contains("cima4")
    }

    private fun guessType(title: String, url: String): TvType {
        val t = title.lowercase()
        val u = url.lowercase()
        if (t.contains("مسلسل") || t.contains("الحلقة") || t.contains("موسم") || u.contains("series") || u.contains("season"))
            return TvType.TvSeries
        if (t.contains("انمي") || u.contains("anime") || u.contains("انمي"))
            return TvType.Anime
        return TvType.Movie
    }

    private suspend fun fetchOgPosterCached(detailsUrl: String): String? {
        if (posterCache.containsKey(detailsUrl)) return posterCache[detailsUrl]
        val result = withTimeoutOrNull(4500L) {
            runCatching {
                val d = app.get(detailsUrl, headers = headersOf("$mainUrl/")).document
                d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { canonical(it) }
            }.getOrNull()
        }
        if (posterCache.size > 250) posterCache.remove(posterCache.keys.firstOrNull())
        posterCache[detailsUrl] = result
        return result
    }

    // ---------------------------
    // MainPage
    // ---------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الأحدث",
        "$mainUrl/category/movies/" to "أفلام",
        "$mainUrl/category/series/" to "مسلسلات",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url, headers = headersOf("$mainUrl/")).document
        val items = parseListing(doc)
        return newHomePageResponse(request.name, items)
    }

    // ---------------------------
    // Listing parser
    // ---------------------------
    private suspend fun parseListing(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val ph = posterHeaders()

        val cards = doc.select("article, .post, .item, .ml-item, .GridItem, .entry, .col-md-2, .col-sm-3, .col-xs-6")
        var ogCount = 0

        for (card in cards) {
            val a = card.selectFirst("a[href]") ?: continue
            val url = canonical(a.attr("href"))
            if (url.isBlank() || !isInternalUrl(url)) continue
            if (url.contains("/category/") || url.contains("/tag/") || url.contains("/page/")) continue

            val rawTitle =
                card.selectFirst("h3, h2, .title, .entry-title, .post-title")?.text()?.trim()
                    ?: a.attr("title").trim().ifBlank { a.text().trim() }

            val title = rawTitle.ifBlank { continue }

            val type = guessType(title, url)

            var poster: String? =
                card.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank {
                        img.attr("data-lazy-src").ifBlank {
                            img.attr("data-original").ifBlank {
                                img.attr("src")
                            }
                        }
                    }.trim().ifBlank { null }
                }

            if (poster.isNullOrBlank() && ogCount < 18) {
                ogCount++
                poster = fetchOgPosterCached(url)
            }

            out.putIfAbsent(
                url,
                newMovieSearchResponse(title, url, type) {
                    posterUrl = fixUrlNull(poster?.let { canonical(it) })
                    posterHeaders = ph
                }
            )

            if (out.size >= 80) break
        }

        return out.values.toList()
    }

    // ---------------------------
    // Search
    // ---------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val enc = URLEncoder.encode(q, "UTF-8")

        val urls = listOf(
            "$mainUrl/?s=$enc",
            "$mainUrl/search/$enc"
        )

        for (u in urls) {
            val doc = runCatching { app.get(u, headers = headersOf("$mainUrl/")).document }.getOrNull() ?: continue
            val parsed = parseListing(doc).take(MAX_SEARCH_RESULTS)
            if (parsed.isNotEmpty()) return parsed
        }

        return crawlSearchFallback(q)
    }

    private suspend fun crawlSearchFallback(q: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val sections = listOf(
            "$mainUrl/category/movies/",
            "$mainUrl/category/series/"
        )

        for (section in sections) {
            var p = 1
            while (p <= MAX_CRAWL_PAGES && out.size < MAX_SEARCH_RESULTS) {
                val pageUrl = if (p == 1) section else "${section.trimEnd('/')}/page/$p/"
                val doc = runCatching { app.get(pageUrl, headers = headersOf("$mainUrl/")).document }.getOrNull() ?: break
                val items = parseListing(doc)
                if (items.isEmpty()) break

                for (it in items) {
                    if (it.name.contains(q, ignoreCase = true)) out.putIfAbsent(it.url, it)
                    if (out.size >= MAX_SEARCH_RESULTS) break
                }
                p++
            }
        }
        return out.values.toList()
    }

    // ---------------------------
    // Load
    // ---------------------------
    override suspend fun load(url: String): LoadResponse {
        val pageUrl = canonical(url)
        val doc = app.get(pageUrl, headers = headersOf("$mainUrl/")).document

        val title =
            doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: doc.selectFirst("title")?.text()?.trim()
                ?: "Cima4U"

        val poster =
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?: doc.selectFirst("img[src]")?.attr("src")?.trim()

        val plot =
            doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()

        val type = guessType(title, pageUrl)
        val ph = posterHeaders()

        return if (type == TvType.TvSeries) {
            val episodes = doc.extractEpisodesSimple(pageUrl)
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = fixUrlNull(poster?.let { canonical(it) })
                posterHeaders = ph
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, pageUrl, type, pageUrl) {
                posterUrl = fixUrlNull(poster?.let { canonical(it) })
                posterHeaders = ph
                this.plot = plot
            }
        }
    }

    private fun Document.extractEpisodesSimple(seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()
        val epRegex = Regex("""الحلقة\s*(\d{1,4})""")

        select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach

            val link = canonical(href)
            if (!isInternalUrl(link)) return@forEach
            if (link == seriesUrl) return@forEach

            val txt = a.text().trim()
            val looksEpisode = txt.contains("الحلقة") || link.contains("الحلقة")
            if (!looksEpisode) return@forEach

            val epNum = epRegex.find(txt)?.groupValues?.getOrNull(1)?.toIntOrNull()

            found.putIfAbsent(link, newEpisode(link) {
                name = if (epNum != null) "الحلقة $epNum" else txt.ifBlank { "مشاهدة" }
                season = 1
                episode = epNum
            })
        }

        if (found.isEmpty()) {
            return listOf(newEpisode(seriesUrl) {
                name = "مشاهدة"
                season = 1
                episode = 1
            })
        }

        return found.values.toList().sortedBy { it.episode ?: 999999 }
    }

    // ---------------------------
    // Link discovery
    // ---------------------------
    private fun Document.findWatchUrl(detailsUrl: String): String? {
        val a = selectFirst("a:contains(مشاهدة الآن), a:contains(شاهد الآن), a[href*=/watch], a[href*=watch]")
        val href = a?.attr("href")?.trim().orEmpty()
        if (href.isNotBlank()) return canonical(href)

        val clean = if (detailsUrl.endsWith("/")) detailsUrl else "$detailsUrl/"
        return "${clean}watch/"
    }

    private fun Document.extractServerCandidates(): List<String> {
        val out = LinkedHashSet<String>()

        select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            if (href.isBlank()) return@forEach
            val u = href.substringBefore("#").trim()

            val low = u.lowercase()
            val looksUseful =
                low.contains("dood") ||
                    low.contains("filemoon") ||
                    low.contains("mixdrop") ||
                    low.contains("streamtape") ||
                    low.contains("ok.ru") ||
                    low.contains("uqload") ||
                    low.contains("embed") ||
                    low.contains("player") ||
                    low.contains("m3u8") ||
                    low.contains(".mp4")

            if (looksUseful) out.add(u)
        }

        select("[data-watch]").forEach {
            val v = it.attr("data-watch").trim()
            if (v.isNotBlank()) out.add(canonical(v))
        }

        val attrs = listOf("data-url", "data-href", "data-embed", "data-src", "data-link", "data-iframe")
        for (a in attrs) {
            select("[$a]").forEach { el ->
                val v = el.attr(a).trim()
                if (v.isNotBlank()) out.add(canonical(v))
            }
        }

        select("iframe[src]").forEach {
            val v = it.attr("src").trim()
            if (v.isNotBlank()) out.add(canonical(v))
        }

        select("[onclick]").forEach { el ->
            val oc = el.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(m.value.substringBefore("#").trim())
        }

        return out.toList()
    }

    private suspend fun resolveInternalOnce(url: String, referer: String): List<String> {
        val fixed = canonical(url)
        if (fixed.isBlank()) return emptyList()

        return withTimeoutOrNull(PER_REQ_TIMEOUT_MS) {
            runCatching {
                val doc = app.get(fixed, headers = headersOf(referer)).document
                doc.extractServerCandidates()
            }.getOrElse { emptyList() }
        } ?: emptyList()
    }

    @Suppress("DEPRECATION")
    private fun emitDirect(url: String, pageUrl: String, callback: (ExtractorLink) -> Unit) {
        val low = url.lowercase()
        val isM3u8 = low.contains(".m3u8")
        callback(
            ExtractorLink(
                source = name,
                name = "Cima4U Direct",
                url = url,
                referer = pageUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = isM3u8,
                headers = headersOf(pageUrl)
            )
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val detailsUrl = canonical(data)
        if (detailsUrl.isBlank()) return false

        val detailsDoc = app.get(detailsUrl, headers = headersOf("$mainUrl/")).document
        val watchUrl = detailsDoc.findWatchUrl(detailsUrl) ?: detailsUrl
        val watchDoc = app.get(watchUrl, headers = headersOf(detailsUrl)).document

        val candidates = LinkedHashSet<String>()
        watchDoc.extractServerCandidates().forEach { candidates.add(it) }
        if (candidates.isEmpty()) return false

        val expanded = LinkedHashSet<String>()
        expanded.addAll(candidates)

        var internalUsed = 0
        for (c in candidates.toList().take(min(candidates.size, MAX_CANDIDATES))) {
            if (internalUsed >= MAX_INTERNAL_RESOLVE) break
            val u = canonical(c)
            if (u.isBlank()) continue

            if (isInternalUrl(u)) {
                val more = resolveInternalOnce(u, watchUrl)
                more.forEach { expanded.add(it) }
                internalUsed++
            }
        }

        val finals = expanded.toList().take(MAX_FINAL_LINKS)
        if (finals.isEmpty()) return false

        var foundAny = false

        for (raw in finals) {
            val u = canonical(raw)
            if (u.isBlank()) continue
            val low = u.lowercase()

            if (low.contains(".mp4") || low.contains(".m3u8")) {
                emitDirect(u, watchUrl, callback)
                foundAny = true
                continue
            }

            if (!isInternalUrl(u)) {
                runCatching {
                    loadExtractor(u, watchUrl, subtitleCallback, callback)
                    foundAny = true
                }
            }
        }

        return foundAny
    }
}
