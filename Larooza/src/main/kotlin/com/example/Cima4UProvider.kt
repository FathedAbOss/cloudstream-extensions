package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
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
    private val MAX_CANDIDATES = 110
    private val MAX_FINAL_LINKS = 70
    private val MAX_INTERNAL_RESOLVE = 16
    private val MAX_EXPAND_DATAWATCH = 14
    private val PER_REQ_TIMEOUT_MS = 6500L

    // Poster cache
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
     * - Keep query params
     * - Drop only fragment (#...)
     * - fixUrl(url) ONE ARG
     */
    private fun canonical(raw: String): String {
        val u = raw.trim()
        if (u.isBlank()) return u
        return fixUrl(u).substringBefore("#").trim()
    }

    private fun absFixUrl(u: String): String = fixUrl(u)

    private fun isInternalUrl(u: String): Boolean {
        if (!u.startsWith("http")) return true
        val host = runCatching { URI(u).host?.lowercase().orEmpty() }.getOrDefault("")
        if (host.isBlank()) return true
        return host.contains("cfu") || host.contains("cima4u") || host.contains("cima") || host.contains("egybests")
    }

    private fun guessTypeFrom(url: String, title: String): TvType {
        val u = url.lowercase()
        val t = title.lowercase()
        if (u.contains("series") || u.contains("season") || u.contains("episode") || t.contains("مسلسل") || t.contains("الحلقة") || t.contains("موسم"))
            return TvType.TvSeries
        if (u.contains("anime") || t.contains("انمي") || t.contains("أنمي"))
            return TvType.Anime
        return TvType.Movie
    }

    // ---------------------------
    // MainPage
    // ---------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الأحدث",
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/anime" to "أنمي",
        "$mainUrl/category/movies/" to "أفلام (تصنيف)",
        "$mainUrl/category/series/" to "مسلسلات (تصنيف)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val doc = runCatching { app.get(url, headers = headersOf("$mainUrl/")).document }.getOrNull()
            ?: runCatching { app.get("$mainUrl/", headers = headersOf("$mainUrl/")).document }.getOrNull()
            ?: throw ErrorLoadingException("Failed to load main page")

        val items = parseCards(doc)
        return newHomePageResponse(request.name, items)
    }

    // ---------------------------
    // Cards parsing
    // ---------------------------
    private fun selectCardElements(doc: Document) =
        doc.select(
            "article, div.Thumb--GridItem, div.GridItem, div.BlockItem, div.item, div.movie, li.item, .Thumb, .post, .post-block, .item-box, .Grid--Item"
        )

    private fun Element.extractTitleStrong(): String? {
        val h = this.selectFirst("h1,h2,h3,.title,.name,strong.title")?.text()?.trim()
        if (!h.isNullOrBlank()) return h
        val a = this.selectFirst("a")
        val t = a?.attr("title")?.trim()
        if (!t.isNullOrBlank()) return t
        val imgAlt = this.selectFirst("img")?.attr("alt")?.trim()
        if (!imgAlt.isNullOrBlank()) return imgAlt
        val at = a?.text()?.trim()
        if (!at.isNullOrBlank() && at.length > 2) return at
        return null
    }

    private fun Element.extractPosterFromCard(): String? {
        val bgEl = this.selectFirst("[style*=background-image]") ?: this
        val style = bgEl.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            val raw = m?.groupValues?.getOrNull(2)?.trim()
            if (!raw.isNullOrBlank() && !raw.startsWith("data:")) return absFixUrl(raw)
        }

        val img = this.selectFirst("img")
        if (img != null) {
            for (a in listOf("data-srcset", "srcset")) {
                val v = img.attr(a).trim()
                if (v.isNotBlank()) {
                    val first = v.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.trim()
                    if (!first.isNullOrBlank()) return absFixUrl(first)
                }
            }
        }

        for (a in listOf("data-src", "data-original", "data-lazy-src", "data-image", "data-thumb", "data-poster", "src")) {
            val v = (img ?: this).attr(a).trim()
            if (v.isNotBlank() && !v.startsWith("data:")) return absFixUrl(v)
        }

        return null
    }

    private suspend fun fetchOgPosterCached(detailsUrl: String): String? {
        if (posterCache.containsKey(detailsUrl)) return posterCache[detailsUrl]
        val result = withTimeoutOrNull(4500L) {
            runCatching {
                val d = app.get(detailsUrl, headers = headersOf("$mainUrl/")).document
                val og = d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                if (!og.isNullOrBlank()) absFixUrl(og) else null
            }.getOrNull()
        }
        if (posterCache.size > 350) posterCache.remove(posterCache.keys.firstOrNull())
        posterCache[detailsUrl] = result
        return result
    }

    private suspend fun parseCards(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val ph = posterHeaders()
        var ogCount = 0

        val cards = selectCardElements(doc)
        if (cards.isNotEmpty()) {
            for (el in cards) {
                val a = el.selectFirst("a[href]") ?: continue
                val href = a.attr("href").trim()
                if (href.isBlank()) continue

                val link = canonical(href)
                if (!isInternalUrl(link)) continue

                val title = el.extractTitleStrong() ?: continue
                val type = guessTypeFrom(link, title)

                var poster = el.extractPosterFromCard()
                if (poster.isNullOrBlank() && ogCount < 20) {
                    ogCount++
                    poster = fetchOgPosterCached(link)
                }

                val sr = newMovieSearchResponse(title, link, type) {
                    posterUrl = fixUrlNull(poster)
                    posterHeaders = ph
                }

                out.putIfAbsent(link, sr)
                if (out.size >= 120) break
            }
        } else {
            for (a in doc.select("a[href]")) {
                val href = a.attr("href").trim()
                if (href.isBlank()) continue
                val link = canonical(href)
                if (!isInternalUrl(link)) continue
                if (link.contains("/watch", true)) continue

                val title = a.attr("title").trim().ifBlank { a.text().trim() }
                if (title.length < 3) continue

                val type = guessTypeFrom(link, title)
                val sr = newMovieSearchResponse(title, link, type) {
                    posterHeaders = ph
                }
                out.putIfAbsent(link, sr)
                if (out.size >= 100) break
            }
        }

        return out.values.toList().distinctBy { it.url }
    }

    // ---------------------------
    // Search
    // ---------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val enc = URLEncoder.encode(q, "UTF-8")
        val encPlus = q.replace(" ", "+")

        val urls = listOf(
            "$mainUrl/search/$encPlus",
            "$mainUrl/?s=$enc",
            "$mainUrl/?search=$enc",
            "$mainUrl/search.php?q=$enc"
        )

        for (u in urls) {
            val doc = runCatching { app.get(u, headers = headersOf("$mainUrl/")).document }.getOrNull() ?: continue
            val parsed = parseCards(doc)
            val filtered = parsed.filter { it.name.contains(q, ignoreCase = true) }.take(MAX_SEARCH_RESULTS)
            if (filtered.isNotEmpty()) return filtered
        }

        return crawlSearchFallback(q)
    }

    private suspend fun crawlSearchFallback(q: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val sections = listOf(
            "$mainUrl/movies",
            "$mainUrl/series",
            "$mainUrl/anime",
            "$mainUrl/category/movies/",
            "$mainUrl/category/series/"
        )

        for (section in sections) {
            var p = 1
            while (p <= MAX_CRAWL_PAGES && out.size < MAX_SEARCH_RESULTS) {
                val pageUrl = when {
                    section.contains("/category/") && p > 1 -> "${section}page/$p/"
                    !section.contains("/category/") && p > 1 -> "$section/page/$p"
                    else -> section
                }

                val doc = runCatching { app.get(pageUrl, headers = headersOf("$mainUrl/")).document }.getOrNull() ?: break
                val cards = parseCards(doc)
                if (cards.isEmpty()) break

                for (c in cards) {
                    if (c.name.contains(q, ignoreCase = true)) out.putIfAbsent(c.url, c)
                    if (out.size >= MAX_SEARCH_RESULTS) break
                }
                p++
            }
        }

        return out.values.toList()
    }

    // ---------------------------
    // Load details
    // ---------------------------
    override suspend fun load(url: String): LoadResponse {
        val pageUrl = canonical(url)
        val doc = app.get(pageUrl, headers = headersOf("$mainUrl/")).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Cima4U"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src]")?.attr("src")?.trim()

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val type = guessTypeFrom(pageUrl, title)
        val ph = posterHeaders()

        return if (type == TvType.TvSeries) {
            val episodes = doc.extractEpisodes(pageUrl)
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = fixUrlNull(poster?.let { absFixUrl(it) })
                posterHeaders = ph
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, pageUrl, type, pageUrl) {
                posterUrl = fixUrlNull(poster?.let { absFixUrl(it) })
                posterHeaders = ph
                this.plot = plot
            }
        }
    }

    private fun Document.extractEpisodes(seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()
        val epArabic = Regex("""الحلقة\s*(\d{1,4})""")

        val links = this.select(
            "a[href*=%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9], a:contains(الحلقة), a[href*=episode], a[href*=episodes], a[href*=watch], a[href*=play], a[href*=player]"
        )

        for (a in links) {
            val href = a.attr("href").trim()
            if (href.isBlank()) continue

            val link = canonical(href)
            if (!isInternalUrl(link)) continue
            if (canonical(seriesUrl) == link) continue

            val text = a.text().trim()
            val looksEpisode = text.contains("الحلقة") || link.contains("episode", true) || link.contains("الحلقة", true)
            if (!looksEpisode) continue

            val epNum = epArabic.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""[?&]ep(?:isode)?=(\d{1,4})""", RegexOption.IGNORE_CASE).find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val name = when {
                text.isNotBlank() -> text
                epNum != null -> "الحلقة $epNum"
                else -> "مشاهدة"
            }

            found.putIfAbsent(link, newEpisode(link) {
                this.name = name
                this.season = 1
                this.episode = epNum
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
    // Discovery (StreamPlay-ish)
    // ---------------------------
    private fun Document.extractDirectMediaFromScripts(): List<String> {
        val out = LinkedHashSet<String>()
        val html = this.html()

        Regex("""https?://[^\s"'<>]+?\.(mp4|m3u8)(\?[^\s"'<>]+)?""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.value) }

        Regex("""file\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.groupValues[1]) }

        return out.toList()
    }

    private fun Document.extractServersFast(): List<String> {
        val out = LinkedHashSet<String>()

        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(canonical(s))
        }

        val attrs = listOf("data-url", "data-href", "data-embed", "data-src", "data-link", "data-iframe")
        for (a in attrs) {
            this.select("[$a]").forEach {
                val s = it.attr(a).trim()
                if (s.isNotBlank()) out.add(canonical(s))
            }
        }

        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(canonical(s))
        }

        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(canonical(m.value))
        }

        this.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            if (href.isBlank()) return@forEach
            val low = href.lowercase()

            val ok = listOf(
                "embed", "player", "watch", "play", "download",
                "dood", "filemoon", "mixdrop", "streamtape", "ok.ru", "uqload",
                "m3u8", ".mp4", "slp_watch="
            ).any { low.contains(it) }

            if (ok) out.add(canonical(href))
        }

        return out.toList()
    }

    private fun extractSlpWatchParam(url: String): String? {
        val u = url.trim()
        if (!u.contains("slp_watch=")) return null
        val q = u.substringAfter("slp_watch=", "")
        return if (q.isBlank()) null else q.substringBefore("&").trim()
    }

    private fun decodeSlpWatchUrl(encoded: String): String? {
        val raw = encoded.trim()
        if (raw.isBlank()) return null
        val normalized = raw.replace('-', '+').replace('_', '/').let { s ->
            val mod = s.length % 4
            if (mod == 0) s else s + "=".repeat(4 - mod)
        }
        return runCatching {
            val bytes = Base64.getDecoder().decode(normalized)
            val decoded = String(bytes, Charsets.UTF_8).trim()
            if (decoded.startsWith("http")) decoded else null
        }.getOrNull()
    }

    private suspend fun resolveInternalOnce(url: String, referer: String): List<String> {
        val fixed = canonical(url)
        if (fixed.isBlank()) return emptyList()

        return withTimeoutOrNull(PER_REQ_TIMEOUT_MS) {
            runCatching {
                val doc = app.get(fixed, headers = headersOf(referer)).document
                val out = LinkedHashSet<String>()
                out.addAll(doc.extractServersFast())
                doc.extractDirectMediaFromScripts().forEach { out.add(it) }

                var dwCount = 0
                doc.select("[data-watch]").forEach {
                    if (dwCount >= 6) return@forEach
                    dwCount++

                    val dw = it.attr("data-watch").trim()
                    if (dw.isBlank()) return@forEach

                    val cand = canonical(dw)
                    out.add(cand)

                    val slp = extractSlpWatchParam(cand)
                    val decoded = slp?.let { decodeSlpWatchUrl(it) }
                    if (!decoded.isNullOrBlank()) out.add(decoded)
                }

                out.toList()
            }.getOrElse { emptyList() }
        } ?: emptyList()
    }

    // ✅ FIXED: newExtractorLink signature (type + initializer)
    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = url.lowercase().contains(".m3u8")

        val link = newExtractorLink(
            source = name,
            name = "Cima4U Direct",
            url = url,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = headersOf(referer)
        }

        callback(link)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = canonical(data)
        if (pageUrl.isBlank()) return false

        val doc = app.get(pageUrl, headers = headersOf("$mainUrl/")).document

        // 1) Discover candidates (dedupe + order)
        val candidates = LinkedHashSet<String>()
        doc.extractServersFast().forEach { candidates.add(it) }
        doc.extractDirectMediaFromScripts().forEach { candidates.add(it) }
        if (candidates.isEmpty()) return false

        // 2) Expand internal once (bounded)
        val expanded = LinkedHashSet<String>()
        expanded.addAll(candidates)

        var internalUsed = 0
        var dataWatchExpanded = 0

        for (c in candidates.toList().take(min(candidates.size, MAX_CANDIDATES))) {
            if (internalUsed >= MAX_INTERNAL_RESOLVE) break

            val low = c.lowercase()
            val looksGateway =
                low.contains("watch") || low.contains("player") || low.contains("play") || low.contains("embed") || low.contains("slp_watch=")

            if (looksGateway && dataWatchExpanded < MAX_EXPAND_DATAWATCH) {
                val slp = extractSlpWatchParam(c)
                val decoded = slp?.let { decodeSlpWatchUrl(it) }
                if (!decoded.isNullOrBlank()) expanded.add(decoded)
                dataWatchExpanded++
            }

            if (isInternalUrl(c)) {
                val more = resolveInternalOnce(c, pageUrl)
                more.forEach { expanded.add(it) }
                internalUsed++
            }
        }

        // 3) Final list
        val finals = expanded.toList().take(MAX_FINAL_LINKS)
        if (finals.isEmpty()) return false

        // 4) Emit direct + external extractors
        var foundAny = false

        for (raw in finals) {
            val u = canonical(raw)
            if (u.isBlank()) continue
            val low = u.lowercase()

            if (low.contains(".mp4") || low.contains(".m3u8")) {
                emitDirect(u, pageUrl, callback)
                foundAny = true
                continue
            }

            if (!isInternalUrl(u)) {
                runCatching {
                    loadExtractor(u, pageUrl, subtitleCallback, callback)
                    foundAny = true
                }
            }
        }

        return foundAny
    }
}
