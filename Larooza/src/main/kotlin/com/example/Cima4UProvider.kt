package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
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
     * - fixUrl(url) one arg only
     * - keep query
     * - drop fragment only
     */
    private fun canonical(raw: String): String {
        val u = raw.trim()
        if (u.isBlank()) return u
        return fixUrl(u).substringBefore("#").trim()
    }

    private fun isInternalUrl(u: String): Boolean {
        if (!u.startsWith("http")) return true
        val host = runCatching { URI(u).host?.lowercase().orEmpty() }.getOrDefault("")
        if (host.isBlank()) return true
        return host.contains("cfu") || host.contains("cima4u") || host.contains("egybests") || host.contains("cima4")
    }

    private fun isListingUrl(u: String): Boolean {
        val low = u.lowercase()
        return low.contains("/category/") ||
            low.contains("/tag/") ||
            low.contains("/page/") ||
            low.contains("/wp-admin") ||
            low.contains("/wp-content") ||
            low.endsWith("/") && (low == mainUrl.lowercase() + "/")
    }

    /**
     * CFU posts usually have Arabic "مشاهدة" slug (often urlencoded).
     * We treat these as real media posts (movie/series/episode pages).
     */
    private fun looksLikePostUrl(u: String): Boolean {
        val low = u.lowercase()
        if (!isInternalUrl(u)) return false
        if (isListingUrl(u)) return false
        return low.contains("%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9") || // مشاهدة
            low.contains("/مشاهدة") ||
            low.contains("/watch") ||
            low.contains("/movie") ||
            low.contains("/series") ||
            low.contains("/show") ||
            low.contains("episode") ||
            low.contains("الحلقة")
    }

    private fun guessType(title: String, url: String): TvType {
        val t = title.lowercase()
        val u = url.lowercase()
        if (t.contains("مسلسل") || t.contains("الحلقة") || t.contains("موسم") || u.contains("series") || u.contains("episode"))
            return TvType.TvSeries
        if (t.contains("انمي") || u.contains("انمي"))
            return TvType.Anime
        return TvType.Movie
    }

    private fun sanitizeTitle(raw: String): String {
        var t = raw.trim()

        // Remove extremely noisy separators (keep the first meaningful chunk)
        listOf("|", " - ", " — ", " – ").forEach { sep ->
            if (t.contains(sep)) t = t.substringBefore(sep).trim()
        }

        // Collapse spaces
        t = t.replace(Regex("\\s+"), " ").trim()

        // Reject pure numbers or very short junk
        if (t.matches(Regex("^\\d{1,6}$"))) return ""
        if (t.length < 3) return ""

        // If the title is still huge and SEO-like, keep only the first 80 chars before repeating keywords
        if (t.length > 120) {
            // Try cut before common SEO repeats
            val cutKeywords = listOf("مشاهدة", "تحميل", "موقع", "حصريا", "أحدث", "اون لاين", "افلام", "مسلسلات", "Cima", "cima", "cfu")
            val idx = cutKeywords.map { k -> t.indexOf(k, startIndex = 10) }.filter { it >= 0 }.minOrNull()
            if (idx != null && idx in 10..120) t = t.substring(0, idx).trim()
            if (t.length > 120) t = t.take(120).trim()
        }

        return t
    }

    private fun titleFromUrlFallback(url: String): String {
        val path = runCatching { URI(url).path ?: "" }.getOrDefault("")
        if (path.isBlank()) return ""
        val slug = path.trim().trim('/').substringAfterLast('/')
        if (slug.isBlank()) return ""

        val decoded = runCatching { URLDecoder.decode(slug, "UTF-8") }.getOrDefault(slug)
        var t = decoded.replace('-', ' ').replace('_', ' ').trim()

        // Remove common CFU slug junk
        val junk = listOf(
            "مشاهدة", "تحميل", "فيلم", "مسلسل", "مترجم", "اون لاين", "بجودة", "جودة", "HD", "WEB", "BluRay",
            "موقع", "سيما", "فور", "يو", "Cima4u", "cima4u", "cfu", "الاصلي", "حصريا", "الجميع", "السينما"
        )
        junk.forEach { j ->
            t = t.replace(Regex("\\b" + Regex.escape(j) + "\\b", RegexOption.IGNORE_CASE), " ")
        }

        // Remove years and stray digits
        t = t.replace(Regex("\\b(19\\d{2}|20\\d{2})\\b"), " ")
        t = t.replace(Regex("\\b\\d+\\b"), " ")

        t = t.replace(Regex("\\s+"), " ").trim()
        return sanitizeTitle(t)
    }

    private fun Element.extractPosterFromCard(): String? {
        // background-image
        val bgEl = this.selectFirst("[style*=background-image]") ?: this
        val style = bgEl.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            val raw = m?.groupValues?.getOrNull(2)?.trim()
            if (!raw.isNullOrBlank() && !raw.startsWith("data:")) return canonical(raw)
        }

        val img = this.selectFirst("img")
        if (img != null) {
            // srcset / data-srcset
            for (a in listOf("data-srcset", "srcset")) {
                val v = img.attr(a).trim()
                if (v.isNotBlank()) {
                    val first = v.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.trim()
                    if (!first.isNullOrBlank() && !first.startsWith("data:")) return canonical(first)
                }
            }
            // common lazy attributes
            for (a in listOf("data-src", "data-original", "data-lazy-src", "data-image", "data-thumb", "data-poster", "src")) {
                val v = img.attr(a).trim()
                if (v.isNotBlank() && !v.startsWith("data:")) return canonical(v)
            }
        }
        return null
    }

    private fun Element.extractTitleFromCard(): String? {
        // Prefer explicit headings inside a card
        val h = this.selectFirst("h1,h2,h3,h4,.title,.name,.post-title,.entry-title")?.text()?.trim()
        val sh = if (!h.isNullOrBlank()) sanitizeTitle(h) else ""
        if (sh.isNotBlank()) return sh

        // Prefer anchor title attribute
        val a = this.selectFirst("a[href]")
        val at = a?.attr("title")?.trim().orEmpty()
        val sat = if (at.isNotBlank()) sanitizeTitle(at) else ""
        if (sat.isNotBlank()) return sat

        // Prefer img alt
        val alt = this.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
        val salt = if (alt.isNotBlank()) sanitizeTitle(alt) else ""
        if (salt.isNotBlank()) return salt

        // Last resort: anchor text (often noisy)
        val txt = a?.text()?.trim().orEmpty()
        val stxt = if (txt.isNotBlank()) sanitizeTitle(txt) else ""
        if (stxt.isNotBlank()) return stxt

        return null
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
        val doc = app.get(request.data, headers = headersOf("$mainUrl/")).document
        val items = parseListing(doc)
        return newHomePageResponse(request.name, items)
    }

    // ---------------------------
    // Listing parser (FIXED: card-based, not all anchors)
    // ---------------------------
    private fun selectCardElements(doc: Document) =
        doc.select(
            "article, " +
                "div.post, div.post-item, div.post-box, div.post-block, " +
                "div.entry, div.entry-content, div.blog-item, " +
                "div.Thumb--GridItem, div.GridItem, div.BlockItem, " +
                "div.item, li.item, .Thumb, .item-box, .post-card"
        )

    private suspend fun parseListing(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val ph = posterHeaders()

        val cards = selectCardElements(doc)
        var ogCount = 0

        for (card in cards) {
            // Find a meaningful link in the card
            val a = card.selectFirst("a[href]") ?: continue
            val href = a.attr("href").trim()
            if (href.isBlank()) continue

            val url = canonical(href)
            if (!looksLikePostUrl(url)) continue

            // Extract title properly (avoid numbers / SEO)
            var title = card.extractTitleFromCard().orEmpty()

            // If title still bad, fallback to URL slug
            if (title.isBlank() || title.matches(Regex("^\\d{1,6}$")) || title.length < 3) {
                title = titleFromUrlFallback(url)
            }
            if (title.isBlank()) continue

            // Poster: prefer card poster, then limited OG fallback
            var poster = card.extractPosterFromCard()
            if (poster.isNullOrBlank() && ogCount < 18) {
                ogCount++
                poster = fetchOgPosterCached(url)
            }

            val type = guessType(title, url)

            val sr = newMovieSearchResponse(title, url, type) {
                posterUrl = fixUrlNull(poster)
                posterHeaders = ph
            }

            out.putIfAbsent(url, sr)
            if (out.size >= 80) break
        }

        // Fallback: if the site renders cards differently on some pages, do a safe filtered anchor scan
        if (out.isEmpty()) {
            val anchors = doc.select("a[href]")
            for (a in anchors) {
                val href = a.attr("href").trim()
                if (href.isBlank()) continue
                val url = canonical(href)
                if (!looksLikePostUrl(url)) continue

                var title = sanitizeTitle(a.attr("title").ifBlank { a.text() })
                if (title.isBlank() || title.matches(Regex("^\\d{1,6}$"))) {
                    title = titleFromUrlFallback(url)
                }
                if (title.isBlank()) continue

                val type = guessType(title, url)

                val sr = newMovieSearchResponse(title, url, type) {
                    posterUrl = null
                    posterHeaders = ph
                }

                out.putIfAbsent(url, sr)
                if (out.size >= 60) break
            }
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
            val parsed = parseListing(doc)
            val filtered = parsed.filter { it.name.contains(q, ignoreCase = true) }.take(MAX_SEARCH_RESULTS)
            if (filtered.isNotEmpty()) return filtered
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
                val pageUrl = if (p == 1) section else "${section}page/$p/"
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
    // Load (details)
    // ---------------------------
    override suspend fun load(url: String): LoadResponse {
        val pageUrl = canonical(url)
        val doc = app.get(pageUrl, headers = headersOf("$mainUrl/")).document

        val title =
            doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: titleFromUrlFallback(pageUrl).ifBlank { "Cima4U" }

        val poster =
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?: doc.selectFirst("img[src]")?.attr("src")?.trim()

        val plot =
            doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

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
            val looksEpisode =
                txt.contains("الحلقة") ||
                    link.contains("episode", true) ||
                    link.contains("الحلقة", true)

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
        val a = selectFirst("a:contains(مشاهدة الآن), a[href*=/watch], a[href*=watch]")
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

    /**
     * newExtractorLink usage:
     * - (source, name, url, type)
     * - set referer/quality/headers inside initializer
     */
    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val low = url.lowercase()
        val isM3u8 = low.contains(".m3u8")
        val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback(
            newExtractorLink(
                source = name,
                name = "Cima4U Direct",
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = headersOf(referer)
            }
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
        val watchUrl = detailsDoc.findWatchUrl(detailsUrl) ?: return false

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
