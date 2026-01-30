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

    override var mainUrl = "https://wecima.date"
    override var name = "WeCima"
    override var lang = "ar"
    override var hasMainPage = true

    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // Mirrors (auto fallback)
    private val mirrors = listOf(
        "https://wecima.date",
        "https://wecima.show",
        "https://wecima.click",
        "https://wecima.tube"
    )

    @Volatile private var activeBase: String? = null

    // Limits
    private val MAX_SEARCH_RESULTS = 30
    private val MAX_CRAWL_PAGES_PER_SECTION = 6
    private val MAX_CANDIDATES = 90
    private val MAX_FINAL_LINKS = 50
    private val MAX_INTERNAL_RESOLVE = 16
    private val MAX_EXPAND_DATAWATCH = 12
    private val PER_REQ_TIMEOUT_MS = 6500L

    private val posterCache = LinkedHashMap<String, String?>()

    // mainPage MUST be static strings
    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أحدث الأفلام",
        "$mainUrl/series" to "أحدث المسلسلات",
        "$mainUrl/trending" to "الأكثر مشاهدة",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    private fun headersOf(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer
    )

    private fun posterHeadersOf(base: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$base/",
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    /**
     * Your Cloudstream build doesn't support fixUrl(url, base)
     * so we implement absolute URL ourselves.
     */
    private fun absUrl(base: String, raw: String): String {
        val u = raw.trim()
        if (u.isBlank()) return u
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        return try {
            URI(base).resolve(u).toString()
        } catch (_: Throwable) {
            // fallback join
            val b = base.trimEnd('/')
            val p = u.trimStart('/')
            "$b/$p"
        }
    }

    /**
     * Keep query for episodes/watch links. Only remove fragments.
     */
    private fun canonicalUrl(base: String, raw: String): String {
        val fixed = absUrl(base, raw)
        return fixed.substringBefore("#").trim()
    }

    private suspend fun base(): String {
        activeBase?.let { return it }

        val candidates = (listOf(mainUrl) + mirrors).distinct()
        for (b in candidates) {
            val ok = withTimeoutOrNull(2500L) {
                runCatching { app.get("$b/", headers = headersOf("$b/")).code == 200 }.getOrDefault(false)
            } ?: false
            if (ok) {
                activeBase = b
                // keep mainUrl for UI, but internally we use activeBase
                return b
            }
        }

        activeBase = mainUrl
        return mainUrl
    }

    /**
     * If mainPage contains old domain, replace host with active base.
     */
    private fun swapToBase(active: String, url: String): String {
        return if (url.startsWith("http")) {
            url.replace(Regex("^https?://[^/]+"), active)
        } else {
            absUrl(active, url)
        }
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

    private fun Element.extractPosterFromCard(base: String): String? {
        val bgEl = this.selectFirst("[style*=background-image]") ?: this
        val style = bgEl.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            val raw = m?.groupValues?.getOrNull(2)?.trim()
            if (!raw.isNullOrBlank() && !raw.startsWith("data:")) return absUrl(base, raw)
        }

        val img = this.selectFirst("img")
        if (img != null) {
            for (a in listOf("data-srcset", "srcset")) {
                val v = img.attr(a).trim()
                if (v.isNotBlank()) {
                    val first = v.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.trim()
                    if (!first.isNullOrBlank()) return absUrl(base, first)
                }
            }
        }

        for (a in listOf("data-src", "data-original", "data-lazy-src", "data-image", "data-thumb", "data-poster", "src")) {
            val v = (img ?: this).attr(a).trim()
            if (v.isNotBlank() && !v.startsWith("data:")) return absUrl(base, v)
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

    private fun isInternalUrl(u: String): Boolean {
        if (!u.startsWith("http")) return true
        val host = runCatching { URI(u).host?.lowercase() ?: "" }.getOrNull().orEmpty()
        if (host.isBlank()) return true
        return host.contains("wecima") || host.contains("wecimma")
    }

    private suspend fun fetchOgPosterCached(base: String, detailsUrl: String): String? {
        if (posterCache.containsKey(detailsUrl)) return posterCache[detailsUrl]

        val result = withTimeoutOrNull(4500L) {
            runCatching {
                val d = app.get(detailsUrl, headers = headersOf("$base/")).document
                val og = d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                if (!og.isNullOrBlank()) absUrl(base, og) else null
            }.getOrNull()
        }

        if (posterCache.size > 350) posterCache.remove(posterCache.keys.firstOrNull())
        posterCache[detailsUrl] = result
        return result
    }

    private fun selectCardElements(doc: Document) =
        doc.select("div.Thumb--GridItem, div.GridItem, div.BlockItem, div.item, article, div.movie, li.item, .Thumb, .post-block, .post, .item-box")

    private suspend fun parseCards(base: String, doc: Document): List<SearchResponse> {
        var ogCount = 0
        val ph = posterHeadersOf(base)

        val list = selectCardElements(doc).mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val link = canonicalUrl(base, href)
            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)

            var poster = element.extractPosterFromCard(base)
            if (poster.isNullOrBlank() && ogCount < 20) {
                ogCount++
                poster = fetchOgPosterCached(base, link)
            }

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = ph
            }
        }

        return list.distinctBy { it.url }
    }

    // ---------------------------
    // MainPage / Search
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val b = base()
        val url = swapToBase(b, request.data)
        val doc = app.get(url, headers = headersOf("$b/")).document
        val items = parseCards(b, doc)
        return newHomePageResponse(request.name, items)
    }

    private fun filterByQuery(items: List<SearchResponse>, q: String): List<SearchResponse> {
        val needle = normalizeArabic(q)
        return items.filter { normalizeArabic(it.name).contains(needle) }.take(MAX_SEARCH_RESULTS)
    }

    private suspend fun crawlSearchFallback(base: String, q: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val sections = listOf("$base/movies", "$base/series")

        for (section in sections) {
            var p = 1
            while (p <= MAX_CRAWL_PAGES_PER_SECTION && out.size < MAX_SEARCH_RESULTS) {
                val pageUrl = if (p == 1) section else "$section/page/$p"
                val doc = runCatching { app.get(pageUrl, headers = headersOf("$base/")).document }.getOrNull() ?: break
                val cards = parseCards(base, doc)
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
        val b = base()
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encPlus = q.replace(" ", "+")
        val enc = URLEncoder.encode(q, "UTF-8")

        val urls = listOf(
            "$b/search/$encPlus",
            "$b/?s=$enc",
            "$b/?search=$enc",
            "$b/search.php?q=$enc"
        )

        for (u in urls) {
            val doc = runCatching { app.get(u, headers = headersOf("$b/")).document }.getOrNull() ?: continue
            val parsed = parseCards(b, doc)
            val filtered = filterByQuery(parsed, q)
            if (filtered.isNotEmpty()) return filtered
        }

        return crawlSearchFallback(b, q)
    }

    // ---------------------------
    // Episodes
    // ---------------------------

    private fun Document.extractEpisodes(base: String, seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()

        val epArabic = Regex("""الحلقة\s*(\d{1,4})""")
        val sxe = Regex("""S\s*(\d{1,3})\s*:\s*E\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
        val numAny = Regex("""(\d{1,4})""")

        val links = this.select(
            "a[href*=%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9], a:contains(الحلقة), a:contains(مشاهدة), " +
                "a[href*=episode], a[href*=episodes], a[href*=watch], a[href*=play], a[href*=player]"
        )

        links.forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach

            val link = canonicalUrl(base, href) // keep query
            if (!isInternalUrl(link)) return@forEach
            if (canonicalUrl(base, seriesUrl) == link) return@forEach

            val text = a.text().trim()

            val looksEpisode =
                link.contains("episode", true) ||
                link.contains("episodes", true) ||
                link.contains("الحلقة", true) ||
                text.contains("الحلقة") ||
                sxe.containsMatchIn(text) ||
                (text == "مشاهدة" && (link.contains("watch", true) || link.contains("episode", true)))

            if (!looksEpisode) return@forEach

            val (season, episode) = run {
                val m = sxe.find(text)
                if (m != null) {
                    (m.groupValues[1].toIntOrNull() ?: 1) to m.groupValues[2].toIntOrNull()
                } else {
                    1 to (
                        epArabic.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: Regex("""[?&]ep(?:isode)?=(\d{1,4})""", RegexOption.IGNORE_CASE)
                                .find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: Regex("""[-/](\d{1,4})(?:/)?$""").find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: numAny.findAll(text).mapNotNull { it.value.toIntOrNull() }.lastOrNull()
                    )
                }
            }

            val name = when {
                text.isNotBlank() && text != "مشاهدة" -> text
                episode != null -> "الحلقة $episode"
                else -> "مشاهدة"
            }

            val epObj = newEpisode(link) {
                this.name = name
                this.season = season
                this.episode = episode
            }

            found.putIfAbsent(link, epObj) // key = full url
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
    // Link logic (StreamPlay style)
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

    private fun Document.extractServersFast(base: String): List<String> {
        val out = LinkedHashSet<String>()

        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(canonicalUrl(base, s))
        }

        val attrs = listOf("data-url", "data-href", "data-embed", "data-src", "data-link", "data-iframe")
        for (a in attrs) {
            this.select("[$a]").forEach {
                val s = it.attr(a).trim()
                if (s.isNotBlank()) out.add(canonicalUrl(base, s))
            }
        }

        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(canonicalUrl(base, s))
        }

        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(canonicalUrl(base, m.value))
        }

        this.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            val low = href.lowercase()
            val ok = listOf("embed", "player", "watch", "play", "download", "go", "slp_watch=").any { low.contains(it) }
            if (ok && href.isNotBlank()) out.add(canonicalUrl(base, href))
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

    private suspend fun resolveInternalOnce(base: String, url: String, referer: String): List<String> {
        val fixed = canonicalUrl(base, url)
        if (fixed.isBlank()) return emptyList()

        return withTimeoutOrNull(PER_REQ_TIMEOUT_MS) {
            runCatching {
                val doc = app.get(fixed, headers = headersOf(referer)).document
                val out = LinkedHashSet<String>()
                out.addAll(doc.extractServersFast(base))
                doc.extractDirectMediaFromScripts().forEach { out.add(it) }

                // decode slp_watch if present in url itself
                extractSlpWatchParam(fixed)?.let { enc ->
                    decodeSlpWatchUrl(enc)?.let { out.add(it) }
                }

                // decode slp_watch inside data-watch
                var count = 0
                doc.select("[data-watch]").forEach {
                    if (count >= 6) return@forEach
                    val dw = it.attr("data-watch").trim()
                    if (dw.isBlank()) return@forEach
                    count++

                    val cand = canonicalUrl(base, dw)
                    out.add(cand)
                    extractSlpWatchParam(cand)?.let { enc ->
                        decodeSlpWatchUrl(enc)?.let { out.add(it) }
                    }
                }

                out.toList()
            }.getOrElse { emptyList() }
        } ?: emptyList()
    }

    // ---------------------------
    // Load
    // ---------------------------

    override suspend fun load(url: String): LoadResponse {
        val b = base()
        val pageUrl = canonicalUrl(b, url)

        val doc = app.get(pageUrl, headers = headersOf("$b/")).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "WeCima"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src]")?.attr("src")?.trim()

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val type = guessTypeFrom(pageUrl, title)
        val ph = posterHeadersOf(b)

        if (type == TvType.TvSeries) {
            val episodes = doc.extractEpisodes(b, pageUrl)
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster?.let { absUrl(b, it) })
                this.posterHeaders = ph
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, pageUrl, type, pageUrl) {
            this.posterUrl = fixUrlNull(poster?.let { absUrl(b, it) })
            this.posterHeaders = ph
            this.plot = plot
        }
    }

    // ---------------------------
    // loadLinks (NO deprecated constructor)
    // ---------------------------

    private suspend fun emitDirect(url: String, pageUrl: String, callback: (ExtractorLink) -> Unit) {
        val low = url.lowercase()
        val t = if (low.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        val link = newExtractorLink(
            source = name,
            name = "WeCima Direct",
            url = url,
            type = t
        ) {
            referer = pageUrl
            quality = Qualities.Unknown.value
            headers = headersOf(pageUrl)
        }

        callback(link)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val b = base()
        val pageUrl = canonicalUrl(b, data)
        if (pageUrl.isBlank()) return false

        val doc = app.get(pageUrl, headers = headersOf("$b/")).document

        // 1) Discover
        val candidates = LinkedHashSet<String>()
        doc.extractServersFast(b).forEach { candidates.add(it) }
        doc.extractDirectMediaFromScripts().forEach { candidates.add(it) }

        if (candidates.isEmpty()) return false

        // 2) Expand internal once (bounded)
        val expanded = LinkedHashSet<String>()
        expanded.addAll(candidates)

        var internalUsed = 0
        var expandedDw = 0

        val candList = candidates.toList().take(min(candidates.size, MAX_CANDIDATES))
        for (c in candList) {
            if (internalUsed >= MAX_INTERNAL_RESOLVE) break

            val low = c.lowercase()
            val looksGateway = low.contains("watch") || low.contains("player") || low.contains("play") || low.contains("embed") || low.contains("slp_watch=")

            if (looksGateway && expandedDw < MAX_EXPAND_DATAWATCH) {
                extractSlpWatchParam(c)?.let { enc ->
                    decodeSlpWatchUrl(enc)?.let { expanded.add(it) }
                }
                expandedDw++
            }

            if (isInternalUrl(c) && looksGateway) {
                val more = resolveInternalOnce(b, c, pageUrl)
                more.forEach { expanded.add(it) }
                internalUsed++
            }
        }

        // 3) Final list
        val finals = expanded.toList().take(MAX_FINAL_LINKS)
        if (finals.isEmpty()) return false

        // 4) Emit
        var foundAny = false

        for (raw in finals) {
            val u = canonicalUrl(b, raw)
            if (u.isBlank()) continue

            val low = u.lowercase()

            if (low.contains(".mp4") || low.contains(".m3u8")) {
                emitDirect(u, pageUrl, callback)
                foundAny = true
                continue
            }

            // external only
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
