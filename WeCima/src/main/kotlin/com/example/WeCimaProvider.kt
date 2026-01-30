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

    // Default (will auto-update if domain fails)
    override var mainUrl = "https://wecima.date"
    override var name = "WeCima"
    override var lang = "ar"
    override var hasMainPage = true

    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // -------- Mirrors (Fallback) --------
    private val mirrors = listOf(
        "https://wecima.date",
        "https://wecima.show",
        "https://wecima.click",
        "https://wecimma.com"
    )
    private var baseResolved = false

    // Safety caps
    private val MAX_SEARCH_RESULTS = 30
    private val MAX_CRAWL_PAGES_PER_SECTION = 6

    private val MAX_CANDIDATES = 90
    private val MAX_FINAL_LINKS = 45
    private val MAX_INTERNAL_GATEWAYS = 16      // How many internal gates to open "once"
    private val MAX_DW_EXPAND = 14              // How many data-watch to expand
    private val PER_CANDIDATE_TIMEOUT_MS = 5500L

    private val posterCache = LinkedHashMap<String, String?>()

    private val internalHostSuffixesBase = setOf(
        "wecima.date", "wecima.show", "wecima.click", "wecimma.com",
        "mycima", "wca.cam", "wecema.media"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أحدث الأفلام",
        "$mainUrl/series" to "أحدث المسلسلات",
        "$mainUrl/trending" to "الأكثر مشاهدة",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    // ---------------------------
    // Dynamic headers (must follow mainUrl)
    // ---------------------------

    private fun safeHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private fun posterHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl, // Kept for image fix
        "Sec-Fetch-Dest" to "image", // Kept for image fix
        "Sec-Fetch-Mode" to "no-cors", // Kept for image fix
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    // ---------------------------
    // Ensure baseUrl (mirror fallback)
    // ---------------------------

    private suspend fun ensureBaseUrl() {
        if (baseResolved) return
        baseResolved = true

        // Try mirrors quickly, stick to first success
        for (m in mirrors) {
            val ok = withTimeoutOrNull(2500L) {
                runCatching { app.get(m, headers = mapOf("User-Agent" to USER_AGENT)).document }.isSuccess
            } ?: false
            if (ok) {
                mainUrl = m
                return
            }
        }
        // If all fail, keep default
    }

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
        val bgEl = this.selectFirst("[style*=background-image]") ?: this
        val style = bgEl.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            cleanUrl(m?.groupValues?.getOrNull(2))?.let { return it }
        }

        val img = this.selectFirst("img")
        if (img != null) {
            for (a in listOf("data-srcset", "srcset")) {
                val v = img.attr(a).trim()
                if (v.isNotBlank()) {
                    val first = v.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.trim()
                    cleanUrl(first)?.let { return it }
                }
            }
        }

        val lazyAttrs = listOf(
            "data-src", "data-original", "data-lazy-src", "data-image",
            "data-thumb", "data-poster", "data-bg", "data-background",
            "src"
        )
        val target = img ?: this
        for (a in lazyAttrs) {
            val v = target.attr(a).trim()
            if (v.isNotBlank()) cleanUrl(v)?.let { return it }
        }
        return null
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

    private fun currentHostSuffixes(): Set<String> {
        val host = runCatching { URI(mainUrl).host?.lowercase() ?: "" }.getOrNull().orEmpty()
        return if (host.isBlank()) internalHostSuffixesBase else internalHostSuffixesBase + host
    }

    private fun isInternalUrl(url: String): Boolean {
        val fixed = fixUrl(url).trim()
        if (!fixed.startsWith("http")) return true
        val host = runCatching { URI(fixed).host?.lowercase() ?: "" }.getOrNull().orEmpty()
        if (host.isBlank()) return fixed.startsWith(mainUrl)
        return currentHostSuffixes().any { suf -> host.endsWith(suf) }
    }

    private fun isGatewayInternal(u: String): Boolean {
        val x = u.lowercase()
        return isInternalUrl(u) && (
                x.contains("watch") || x.contains("play") || x.contains("player") ||
                        x.contains("go") || x.contains("download") || x.contains("embed") ||
                        x.contains("مشاهدة")
                )
    }

    private suspend fun fetchOgPosterCached(detailsUrl: String): String? {
        if (posterCache.containsKey(detailsUrl)) return posterCache[detailsUrl]
        val result = runCatching {
            val d = app.get(detailsUrl, headers = safeHeaders()).document
            val og = d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            if (!og.isNullOrBlank()) fixUrl(og) else null
        }.getOrNull()

        if (posterCache.size > 300) posterCache.remove(posterCache.keys.firstOrNull())
        posterCache[detailsUrl] = result
        return result
    }

    // ---------------------------
    // MainPage / Search
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureBaseUrl()

        // Handle mirror replacement logic
        val baseUrl = request.data.replace(mirrors.first(), mainUrl)

        // Handle pagination correctly
        val url = if (page > 1) {
            val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            "${base}page/$page"
        } else {
            baseUrl
        }

        val doc = app.get(url, headers = safeHeaders()).document
        val elements = doc.select(
            "div.Thumb--GridItem, div.GridItem, div.BlockItem, div.item, article, div.movie, li.item, .Thumb, .post-block, .post, .item-box"
        )

        var fallbackCount = 0
        val items = elements.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val link = normalizeUrl(fixUrl(a.attr("href").trim()))
            if (link.isBlank()) return@mapNotNull null

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)

            var poster = element.extractPosterFromCard()
            if (poster.isNullOrBlank() && fallbackCount < 10) {
                fallbackCount++
                poster = fetchOgPosterCached(link)
            }

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders()
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private suspend fun parseCards(doc: Document): List<SearchResponse> {
        val elements = doc.select(
            "div.Thumb--GridItem, div.GridItem, div.BlockItem, div.item, article, div.movie, li.item, .Thumb, .post-block, .post, .item-box"
        )

        var fallbackCount = 0
        val list = elements.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val link = normalizeUrl(fixUrl(a.attr("href").trim()))
            if (link.isBlank()) return@mapNotNull null

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)

            var poster = element.extractPosterFromCard()
            if (poster.isNullOrBlank() && fallbackCount < 10) {
                fallbackCount++
                poster = fetchOgPosterCached(link)
            }

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders()
            }
        }

        return list.distinctBy { it.url }
    }

    private fun filterByQuery(items: List<SearchResponse>, q: String): List<SearchResponse> {
        val needle = normalizeArabic(q)
        return items.filter { normalizeArabic(it.name).contains(needle) }.take(MAX_SEARCH_RESULTS)
    }

    private suspend fun crawlSearchFallback(q: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val sections = listOf("$mainUrl/movies", "$mainUrl/series")

        for (section in sections) {
            var p = 1
            while (p <= MAX_CRAWL_PAGES_PER_SECTION && out.size < MAX_SEARCH_RESULTS) {
                val pageUrl = if (p == 1) section else "$section/page/$p"
                val doc = runCatching { app.get(pageUrl, headers = safeHeaders()).document }.getOrNull() ?: break
                val cards = parseCards(doc)
                if (cards.isEmpty()) break
                for (c in filterByQuery(cards, q)) {
                    out.putIfAbsent(c.url, c)
                    if (out.size >= MAX_SEARCH_RESULTS) break
                }
                p++
            }
        }
        return out.values.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureBaseUrl()

        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encPlus = q.replace(" ", "+")
        val enc = URLEncoder.encode(q, "UTF-8")

        val urls = listOf(
            "$mainUrl/search/$encPlus",
            "$mainUrl/?s=$enc",
            "$mainUrl/?search=$enc",
            "$mainUrl/search.php?q=$enc"
        )

        for (u in urls) {
            val doc = runCatching { app.get(u, headers = safeHeaders()).document }.getOrNull() ?: continue
            val parsed = parseCards(doc)
            val filtered = filterByQuery(parsed, q)
            if (filtered.isNotEmpty()) return filtered
        }

        return crawlSearchFallback(q)
    }

    // ---------------------------
    // Link Discovery (StreamPlay style)
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

        this.select("a[href]").forEach { a ->
            val hrefRaw = a.attr("href").trim()
            val href = a.absUrl("href").ifBlank { fixUrl(hrefRaw) }
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
        return runCatching {
            val q = u.substringAfter("slp_watch=", "")
            if (q.isBlank()) null else q.substringBefore("&").trim()
        }.getOrNull()
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

    /**
     * ✅ StreamPlay rule:
     * Open internal link "ONCE", extract iframe/data/mp4/m3u8/external, return.
     * No recursion.
     */
    private suspend fun resolveInternalOnce(url: String, referer: String): List<String> {
        val fixed = fixUrl(url).trim()
        if (fixed.isBlank()) return emptyList()

        return runCatching {
            val doc = app.get(fixed, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).document
            val out = LinkedHashSet<String>()

            // Same discovery tools (but inside internal page)
            doc.extractServersFast().forEach { out.add(it) }
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }

            // Light data-watch expansion
            var dwCount = 0
            doc.select("[data-watch]").forEach {
                if (dwCount >= 6) return@forEach
                val dw = it.attr("data-watch").trim()
                if (dw.isNotBlank()) {
                    runCatching { expandDataWatchLink(dw, fixed).forEach { out.add(it) } }
                    dwCount++
                }
            }

            out.toList()
        }.getOrElse { emptyList() }
    }

    private suspend fun expandInternalGateways(
        candidates: LinkedHashSet<String>,
        pageUrl: String
    ): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        out.addAll(candidates)

        var used = 0
        for (c in candidates) {
            if (used >= MAX_INTERNAL_GATEWAYS) break
            if (!isGatewayInternal(c) && !(c.contains("slp_watch="))) continue

            val more = withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) {
                // If data-watch has slp_watch, expand it first
                if (c.contains("slp_watch=")) {
                    expandDataWatchLink(c, pageUrl)
                } else {
                    resolveInternalOnce(c, pageUrl)
                }
            } ?: emptyList()

            more.forEach { out.add(it) }
            used++
        }

        return out
    }

    // ---------------------------
    // Load
    // ---------------------------

    override suspend fun load(url: String): LoadResponse {
        ensureBaseUrl()

        val doc = app.get(url, headers = safeHeaders()).document
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
                this.posterHeaders = this@WeCimaProvider.posterHeaders()
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = this@WeCimaProvider.posterHeaders()
            this.plot = plot
        }
    }

    // Episode extraction (keep yours, but avoid catching only one)
    private fun Document.extractEpisodes(seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()

        val containers = this.select(
            ".episodes, #episodes, .episode-list, .eps, .ep, .episodes-list, " +
                    "div:has(a:matchesOwn(S\\d+\\s*:\\s*E\\d+)), " +
                    "div:has(a[href*=episode]), ul:has(a[href*=episode])"
        )
        val scope = if (containers.isNotEmpty()) containers else listOf(this)

        val epArabic = Regex("""الحلقة\s*(\d{1,4})""")
        val sxe = Regex("""S\s*(\d{1,3})\s*:\s*E\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
        val numAny = Regex("""(\d{1,4})""")

        scope.flatMap { it.select("a[href]") }.forEach { a ->
            val hrefRaw = a.attr("href").trim()
            if (hrefRaw.isBlank()) return@forEach

            val link = normalizeUrl(fixUrl(hrefRaw))
            if (!link.startsWith(mainUrl)) return@forEach
            if (link == seriesUrl) return@forEach

            val text = a.text().trim()

            // ignore navbar junk
            val bad = text.contains("WECIMA", true) ||
                    text.contains("WeCima", true) ||
                    text.contains("افلام", true) ||
                    text.contains("مسلسلات", true) ||
                    text.contains("الرئيسية", true)
            if (bad) return@forEach

            val looksLikeEpisode =
                link.contains("/episode", true) ||
                        link.contains("/episodes", true) ||
                        link.contains("الحلقة", true) ||
                        text.contains("الحلقة") ||
                        sxe.containsMatchIn(text) ||
                        // allow "مشاهدة" buttons if link looks episode-ish
                        (text == "مشاهدة" && (link.contains("episode", true) || link.contains("الحلقة", true)))

            if (!looksLikeEpisode) return@forEach

            val (season, episode) = run {
                val m = sxe.find(text)
                if (m != null) {
                    val s = m.groupValues[1].toIntOrNull() ?: 1
                    val e = m.groupValues[2].toIntOrNull()
                    s to e
                } else {
                    1 to (epArabic.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""[-/](\d{1,4})(?:/)?$""").find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: numAny.findAll(text).mapNotNull { it.value.toIntOrNull() }.lastOrNull())
                }
            }

            val name = if (text.isNotBlank() && text != "مشاهدة") text
            else if (episode != null) "الحلقة $episode" else "مشاهدة"

            found[link] = newEpisode(link) {
                this.name = name
                this.season = season
                this.episode = episode
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

        return list.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 999999 })
    }

    // ---------------------------
    // LoadLinks (StreamPlay pipeline)
    // ---------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureBaseUrl()

        val pageUrl = data.trim()
        if (pageUrl.isBlank()) return false

        val doc = app.get(pageUrl, headers = safeHeaders()).document

        var foundAny = false
        val safeCallback: (ExtractorLink) -> Unit = { link ->
            foundAny = true
            callback(link)
        }

        // 1) DISCOVERY: collect everything first
        val candidates = LinkedHashSet<String>()

        // raw data-watch
        doc.select("[data-watch]")
            .mapNotNull { it.attr("data-watch")?.trim() }
            .filter { it.isNotBlank() }
            .take(40)
            .forEach { candidates.add(fixUrl(it)) }

        // general fast selectors
        doc.extractServersFast().forEach { candidates.add(it) }

        // direct links inside scripts
        doc.extractDirectMediaFromScripts().forEach { candidates.add(it) }

        // retry minimal headers if empty
        if (candidates.isEmpty()) {
            val retry = app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).document
            retry.select("[data-watch]").mapNotNull { it.attr("data-watch")?.trim() }
                .filter { it.isNotBlank() }.take(40).forEach { candidates.add(fixUrl(it)) }
            retry.extractServersFast().forEach { candidates.add(it) }
            retry.extractDirectMediaFromScripts().forEach { candidates.add(it) }
        }

        if (candidates.isEmpty()) return false

        // 2) EXPAND data-watch (bounded)
        val dwExpanded = LinkedHashSet<String>()
        var dwCount = 0
        for (c in candidates) {
            if (dwCount >= MAX_DW_EXPAND) break
            if (c.contains("slp_watch=")) {
                val more = withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) { expandDataWatchLink(c, pageUrl) } ?: emptyList()
                more.forEach { dwExpanded.add(it) }
                dwCount++
            }
        }
        dwExpanded.forEach { candidates.add(it) }

        // 3) INTERNAL EXPANSION (the StreamPlay 핵심): open internal gateways ONCE
        val expandedAll = expandInternalGateways(candidates, pageUrl)

        // 4) FINALIZE (dedupe + caps)
        val finals = LinkedHashSet<String>()
        expandedAll.toList()
            .take(min(expandedAll.size, MAX_CANDIDATES))
            .forEach {
                if (finals.size < MAX_FINAL_LINKS) finals.add(it)
            }

        if (finals.isEmpty()) return false

        // 5) EMIT: direct media with headers+referer, external -> loadExtractor ONLY
        val directHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to pageUrl)

        finals.take(160).forEach { link ->
            val ok = withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) {
                // ✅ START OF CHANGE
                val l = link.lowercase()
                if (l.contains(".mp4") || l.contains(".m3u8")) {
                    val el = newExtractorLink(
                        name,
                        "WeCima Direct",
                        link,
                        pageUrl,
                        Qualities.Unknown.value,
                        l.contains(".m3u8"),
                        directHeaders
                    )
                    safeCallback(el)
                    return@withTimeoutOrNull true
                }
                // ✅ END OF CHANGE

                // ✅ IMPORTANT: do NOT call loadExtractor on internal
                if (!isInternalUrl(link)) {
                    loadExtractor(link, pageUrl, subtitleCallback, safeCallback)
                }
                true
            }
            if (ok == null) {
                // timeout -> continue
            }
        }

        return foundAny
    }
}
