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

class WeCimaProvider : MainAPI() {

    // Domäner byts ofta; wecima.date verkar aktiv just nu.
    override var mainUrl = "https://wecima.date"
    override var name = "WeCima"
    override var lang = "ar"
    override var hasMainPage = true

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val safeHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private val posterHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    private val posterCache = LinkedHashMap<String, String?>()

    // Interna hosts (för att undvika "contains wecima" som kan ge falska träffar)
    private val internalHostSuffixes = setOf(
        "wecima.date",
        "wecima.show",
        "wecima.click",
        "wecimma.com",
        "mycima",     // ibland dyker bland-domäner upp
        "wca.cam",
        "wecema.media"
    )

    // Caps / Safety
    private val MAX_SEARCH_RESULTS = 30
    private val MAX_CRAWL_PAGES_PER_SECTION = 6
    private val MAX_CANDIDATES = 70
    private val MAX_FINAL_LINKS = 30
    private val MAX_INTERNAL_RESOLVE = 14
    private val PER_CANDIDATE_TIMEOUT_MS = 4500L

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أحدث الأفلام",
        "$mainUrl/series" to "أحدث المسلسلات",
        "$mainUrl/trending" to "الأكثر مشاهدة",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    // ---------------------------
    // Helpers
    // ---------------------------

    private fun normalizeUrl(u: String): String = u.substringBefore("?").substringBefore("#").trim()

    private fun Element.cleanUrl(u: String?): String? {
        val s = u?.trim().orEmpty()
        if (s.isBlank()) return null
        if (s.startsWith("data:")) return null
        return fixUrl(s)
    }

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
        val img = this.selectFirst("img")

        val style = this.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            cleanUrl(m?.groupValues?.getOrNull(2))?.let { return it }
        }

        val lazyAttrs = listOf(
            "data-src", "data-original", "data-lazy-src", "data-image",
            "data-thumb", "data-poster", "src"
        )

        val target = img ?: this
        for (a in lazyAttrs) {
            val v = target.attr(a).trim()
            if (v.isNotBlank()) {
                cleanUrl(v)?.let { return it }
            }
        }

        return null
    }

    private fun looksPlaceholder(poster: String?): Boolean {
        if (poster.isNullOrBlank()) return true
        val p = poster.lowercase()
        return p.contains("placeholder") || p.contains("noimage") || p.contains("default") || p.endsWith(".svg")
    }

    private fun guessTypeFrom(url: String, title: String): TvType {
        val u = url.lowercase()
        val t = title.lowercase()

        if (u.contains("/series/") || u.contains("/episodes/") || t.contains("الحلقة") || t.contains("موسم"))
            return TvType.TvSeries

        if (u.contains("انمي") || t.contains("انمي"))
            return TvType.Anime

        return TvType.Movie
    }

    private fun normalizeArabic(s: String): String =
        s.lowercase()
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ى", "ي").replace("ة", "ه")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isInternalUrl(url: String): Boolean {
        val fixed = fixUrl(url).trim()
        if (!fixed.startsWith("http")) return true
        val host = runCatching { URI(fixed).host?.lowercase() ?: "" }.getOrNull().orEmpty()
        if (host.isBlank()) return fixed.startsWith(mainUrl)
        return internalHostSuffixes.any { suf -> host.endsWith(suf) }
    }

    private suspend fun fetchOgPosterCached(detailsUrl: String): String? {
        if (posterCache.containsKey(detailsUrl)) return posterCache[detailsUrl]

        val result = try {
            val d = app.get(detailsUrl, headers = safeHeaders).document
            val og = d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            if (!og.isNullOrBlank()) fixUrl(og)
            else {
                val anyImg = d.selectFirst("img[src]")?.attr("src")?.trim()
                if (!anyImg.isNullOrBlank()) fixUrl(anyImg) else null
            }
        } catch (_: Throwable) {
            null
        }

        if (posterCache.size > 250) {
            val firstKey = posterCache.keys.firstOrNull()
            if (firstKey != null) posterCache.remove(firstKey)
        }
        posterCache[detailsUrl] = result
        return result
    }

    // ---------------------------
    // MainPage
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = safeHeaders).document

        val elements = doc.select(
            "div.Thumb--GridItem, div.GridItem, div.BlockItem, div.item, article, div.movie, li.item, .Thumb, .post-block"
        )

        val items = elements.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val link = normalizeUrl(fixUrl(a.attr("href").trim()))
            if (link.isBlank()) return@mapNotNull null

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)

            var poster = element.extractPosterFromCard()
            if (looksPlaceholder(poster)) poster = null

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    // ---------------------------
    // Search (multi-strategy + crawl fallback)
    // ---------------------------

    private fun parseCards(doc: Document): List<SearchResponse> {
        val elements = doc.select(
            "div.Thumb--GridItem, div.GridItem, div.BlockItem, div.item, article, div.movie, li.item, .Thumb, .post-block"
        )

        val list = elements.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val link = normalizeUrl(fixUrl(a.attr("href").trim()))
            if (link.isBlank()) return@mapNotNull null

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)
            val poster = element.extractPosterFromCard()

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders
            }
        }

        return list.distinctBy { it.url }
    }

    private fun filterByQuery(items: List<SearchResponse>, q: String): List<SearchResponse> {
        val needle = normalizeArabic(q)
        return items
            .filter { normalizeArabic(it.name).contains(needle) }
            .take(MAX_SEARCH_RESULTS)
    }

    private suspend fun crawlSearchFallback(q: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val sections = listOf(
            "$mainUrl/movies",
            "$mainUrl/series"
        )

        for (section in sections) {
            var page = 1
            while (page <= MAX_CRAWL_PAGES_PER_SECTION && out.size < MAX_SEARCH_RESULTS) {
                // WeCima layouts varierar: page/x eller ?page=x
                val pageUrl = when {
                    page == 1 -> section
                    else -> "$section/page/$page"
                }

                val doc = runCatching { app.get(pageUrl, headers = safeHeaders).document }.getOrNull() ?: break
                val cards = parseCards(doc)
                if (cards.isEmpty()) break

                for (c in filterByQuery(cards, q)) {
                    out.putIfAbsent(c.url, c)
                    if (out.size >= MAX_SEARCH_RESULTS) break
                }
                page++
            }
        }

        return out.values.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encPlus = q.trim().replace(" ", "+")
        val enc = URLEncoder.encode(q, "UTF-8")

        val strategies = listOf(
            "$mainUrl/search/$encPlus",
            "$mainUrl/?s=$enc",
            "$mainUrl/?search=$enc",
            "$mainUrl/search.php?q=$enc"
        )

        // Snabba försök
        for (u in strategies) {
            val doc = runCatching { app.get(u, headers = safeHeaders).document }.getOrNull() ?: continue
            val parsed = parseCards(doc)
            val filtered = filterByQuery(parsed, q)
            if (filtered.isNotEmpty()) return filtered
        }

        // Fallback crawl
        return crawlSearchFallback(q)
    }

    // ---------------------------
    // Link Logic
    // ---------------------------

    private fun Document.extractDirectMediaFromScripts(): List<String> {
        val out = LinkedHashSet<String>()
        val html = this.html()

        Regex("""https?://[^\s"'<>]+?\.(mp4|m3u8)(\?[^\s"'<>]+)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { out.add(it.value) }

        Regex("""file\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { out.add(it.groupValues[1]) }

        return out.toList()
    }

    private fun Document.extractServersFast(): List<String> {
        val out = LinkedHashSet<String>()
        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }
        val attrs = listOf("data-url", "data-href", "data-embed", "data-src", "data-link", "data-iframe")
        for (a in attrs) {
            this.select("[$a]").forEach {
                val s = it.attr(a).trim()
                if (s.isNotBlank()) out.add(fixUrl(s))
            }
        }
        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }
        // anchors: viktigt för WeCima ibland
        this.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            val h = href.lowercase()
            val ok = listOf("watch", "play", "download", "embed", "player", "go", "مشاهدة").any { h.contains(it) }
            if (ok && href.isNotBlank()) out.add(fixUrl(href))
        }
        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(fixUrl(m.value))
        }
        return out.toList()
    }

    private fun extractSlpWatchParam(url: String): String? {
        val u = url.trim()
        if (!u.contains("slp_watch=")) return null
        return try {
            val q = u.substringAfter("slp_watch=", "")
            if (q.isBlank()) null else q.substringBefore("&").trim()
        } catch (_: Throwable) { null }
    }

    private fun decodeSlpWatchUrl(encoded: String): String? {
        val raw = encoded.trim()
        if (raw.isBlank()) return null
        val normalized = raw.replace('-', '+').replace('_', '/').let { s ->
            val mod = s.length % 4
            if (mod == 0) s else s + "=".repeat(4 - mod)
        }
        return try {
            val bytes = Base64.getDecoder().decode(normalized)
            val decoded = String(bytes, Charsets.UTF_8).trim()
            if (decoded.startsWith("http")) decoded else null
        } catch (_: Throwable) { null }
    }

    private suspend fun expandDataWatchLink(dataWatchUrl: String, referer: String): List<String> {
        val out = LinkedHashSet<String>()
        val fixed = fixUrl(dataWatchUrl).trim()
        if (fixed.isBlank()) return emptyList()

        val slp = extractSlpWatchParam(fixed)
        val decodedUrl = slp?.let { decodeSlpWatchUrl(it) }
        val target = decodedUrl ?: fixed

        out.add(target)

        runCatching {
            val doc = app.get(target, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).document
            doc.extractServersFast().forEach { out.add(it) }
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }
        }
        return out.toList()
    }

    private suspend fun resolveInternalIfNeeded(link: String, referer: String): List<String> {
        val fixed = fixUrl(link).trim()
        if (fixed.isBlank()) return emptyList()

        // Endast intern parse om intern host
        if (!isInternalUrl(fixed)) return listOf(fixed)

        // Bara om det ser ut som gateway/watch/player/play
        val low = fixed.lowercase()
        val shouldResolve = (low.contains("watch") || low.contains("player") || low.contains("play") || low.contains("مشاهدة") || low.contains("download"))
        if (!shouldResolve) return listOf(fixed)

        return runCatching {
            val doc = app.get(fixed, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).document
            val out = LinkedHashSet<String>()
            out.addAll(doc.extractServersFast())
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }
            if (out.isEmpty()) listOf(fixed) else out.toList()
        }.getOrElse { listOf(fixed) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = safeHeaders).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "WeCima"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src]")?.attr("src")?.trim()
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val type = guessTypeFrom(url, title)

        if (type == TvType.TvSeries) {
            val episodes = doc.extractEpisodes(url)
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders
                this.plot = plot
            }
        }
        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = this@WeCimaProvider.posterHeaders
            this.plot = plot
        }
    }

    private fun Document.extractEpisodes(seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()
        val candidates = this.select(
            "a[href*=%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9],a:contains(الحلقة),a:contains(مشاهدة),a[href*=/episode],a[href*=/episodes],a[href*=%D9%85%D8%B4%D8%A7%D9%87%D8%AF%D8%A9-%D9%85%D8%B3%D9%84%D8%B3%D9%84]"
        )
        candidates.forEach { a ->
            val href = a.attr("href").trim()
            if (href.isNotBlank()) {
                val link = normalizeUrl(fixUrl(href))
                if (link.startsWith(mainUrl)) {
                    val rawName = a.text().trim().ifBlank { "حلقة" }
                    val epNum = Regex("""الحلقة\s*(\d{1,4})""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""[-/](\d{1,4})(?:/)?$""").find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""(\d{1,4})""").findAll(rawName).mapNotNull { it.value.toIntOrNull() }.lastOrNull()
                    val ep = newEpisode(link) {
                        this.name = rawName
                        this.season = 1
                        this.episode = epNum
                    }
                    found[link] = ep
                }
            }
        }
        val list = found.values.toList()
        if (list.isEmpty()) {
            return listOf(newEpisode(seriesUrl) {
                this.name = "مشاهدة"
                this.season = 1
                this.episode = 1
            })
        }
        return list.sortedWith(compareBy<Episode> { it.episode ?: 999999 })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.trim()
        if (pageUrl.isBlank()) return false

        val doc = app.get(pageUrl, headers = safeHeaders).document

        // Wrapper så vi bara returnerar true om vi faktiskt EMITTAR länkar
        var foundAny = false
        val safeCallback: (ExtractorLink) -> Unit = { link ->
            foundAny = true
            callback(link)
        }

        // 1) Aggregera kandidater först (dedupe + order)
        val candidates = LinkedHashSet<String>()

        // data-watch raw
        doc.select("[data-watch]").mapNotNull { it.attr("data-watch")?.trim() }
            .filter { it.isNotBlank() }
            .take(25)
            .forEach { candidates.add(fixUrl(it)) }

        // snabba extractions
        doc.extractServersFast().forEach { candidates.add(it) }
        doc.extractDirectMediaFromScripts().forEach { candidates.add(it) }

        // Om tomt: retry med minimal headers
        if (candidates.isEmpty()) {
            val retryDoc = app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).document
            retryDoc.select("[data-watch]").mapNotNull { it.attr("data-watch")?.trim() }
                .filter { it.isNotBlank() }
                .take(25)
                .forEach { candidates.add(fixUrl(it)) }

            retryDoc.extractServersFast().forEach { candidates.add(it) }
            retryDoc.extractDirectMediaFromScripts().forEach { candidates.add(it) }
        }

        if (candidates.isEmpty()) return false

        // 2) Expand data-watch försiktigt (bounded)
        val expanded = LinkedHashSet<String>()
        var expandedCount = 0
        for (c in candidates) {
            if (expandedCount >= 12) break
            if (c.contains("slp_watch=") || (isInternalUrl(c) && c.contains("watch", true))) {
                runCatching { expandDataWatchLink(c, pageUrl).forEach { expanded.add(it) } }
                expandedCount++
            }
        }
        expanded.forEach { candidates.add(it) }

        // 3) Resolva internt max 1 steg + dedupe final
        val finals = LinkedHashSet<String>()
        var internalCount = 0

        val takeN = min(candidates.size, MAX_CANDIDATES)
        val listCandidates = candidates.toList().take(takeN)

        for (cand in listCandidates) {
            if (finals.size >= MAX_FINAL_LINKS) break

            val done = withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) {
                val low = cand.lowercase()

                // Direkt media
                if (low.contains(".mp4") || low.contains(".m3u8")) {
                    finals.add(cand)
                    return@withTimeoutOrNull true
                }

                // Intern resolve (bounded)
                if (isInternalUrl(cand)) {
                    if (internalCount >= MAX_INTERNAL_RESOLVE) return@withTimeoutOrNull true
                    internalCount++
                    resolveInternalIfNeeded(cand, pageUrl).forEach { finals.add(it) }
                    return@withTimeoutOrNull true
                }

                // Extern kandidat -> lägg till som final direkt (loadExtractor sen)
                finals.add(cand)
                true
            }

            // timeout -> bara fortsätt
            if (done == null) continue
        }

        if (finals.isEmpty()) return false

        // 4) Emit länkar: direct media manuellt, annars loadExtractor på EXTERNA hosts
        finals.take(120).forEach { link ->
            val l = link.lowercase()

            val ok = withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) {
                if (l.contains(".mp4") || l.contains(".m3u8")) {
                    val el = newExtractorLink(
                        source = name,
                        name = "WeCima Direct",
                        url = link
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                        this.isM3u8 = l.contains(".m3u8")
                    }
                    safeCallback(el)
                    return@withTimeoutOrNull true
                }

                // IMPORTANT: loadExtractor bara på EXTERNA
                if (!isInternalUrl(link)) {
                    loadExtractor(link, pageUrl, subtitleCallback, safeCallback)
                }
                true
            }

            if (ok == null) {
                // timeout -> fortsätt
            }
        }

        return foundAny
    }
}
