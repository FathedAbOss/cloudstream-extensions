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

    // Default; will be replaced automatically by ensureBase() if dead.
    override var mainUrl = "https://wecima.date"
    override var name = "WeCima"
    override var lang = "ar"
    override var hasMainPage = true

    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // ---- Mirrors (if wecima.date dies tomorrow) ----
    // Keep this list short and real. Add/remove as needed.
    private val mirrors = listOf(
        "https://wecima.date",
        "https://wecima.show",
        "https://wecima.click"
        // add more only if confirmed working
    )

    // ---- Safety caps ----
    private val MAX_SEARCH_RESULTS = 30
    private val MAX_CRAWL_PAGES_PER_SECTION = 6
    private val MAX_CANDIDATES = 90
    private val MAX_FINAL_LINKS = 45
    private val MAX_INTERNAL_RESOLVE = 18
    private val PER_CANDIDATE_TIMEOUT_MS = 6500L

    // Posters cache
    private val posterCache = LinkedHashMap<String, String?>()

    // ---------- Headers ----------
    private fun safeHeaders(base: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$base/"
    )

    private fun posterHeaders(base: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$base/",
        "Origin" to base,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    // ---------- Main Page ----------
    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أحدث الأفلام",
        "$mainUrl/series" to "أحدث المسلسلات",
        "$mainUrl/trending" to "الأكثر مشاهدة",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    // ============================================================
    // Base / Mirror selection
    // ============================================================

    private suspend fun ensureBase(): String {
        // If current mainUrl responds quickly, keep it.
        val current = mainUrl
        val ok = withTimeoutOrNull(3500L) {
            runCatching { app.get("$current/", headers = safeHeaders(current)).code }.getOrNull() == 200
        } == true
        if (ok) return current

        // Try mirrors
        for (m in mirrors) {
            val mOk = withTimeoutOrNull(3500L) {
                runCatching { app.get("$m/", headers = safeHeaders(m)).code }.getOrNull() == 200
            } == true
            if (mOk) {
                mainUrl = m
                return m
            }
        }

        // fallback
        return current
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * IMPORTANT: do NOT strip query for episode/watch links.
     * But for plain details pages, stripping helps dedupe.
     */
    private fun canonicalUrl(raw: String): String {
        val u = raw.trim()
        if (u.isBlank()) return u
        val low = u.lowercase()

        return if (
            low.contains("slp_watch=") ||
            low.contains("episode") ||
            low.contains("episodes") ||
            low.contains("ep=") ||
            low.contains("watch") ||
            low.contains("player") ||
            low.contains("play")
        ) fixUrl(u).trim()
        else fixUrl(u.substringBefore("#").substringBefore("?").trim()).trim()
    }

    private fun normalizeArabic(s: String): String =
        s.lowercase()
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ى", "ي").replace("ة", "ه")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isInternalUrl(url: String): Boolean {
        val fixed = canonicalUrl(url)
        if (!fixed.startsWith("http")) return true
        val host = runCatching { URI(fixed).host?.lowercase() ?: "" }.getOrNull().orEmpty()
        if (host.isBlank()) return fixed.startsWith(mainUrl)

        // Only true WeCima hosts
        return host.contains("wecima") || host.contains("wecimma")
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
        // background-image
        val bgEl = this.selectFirst("[style*=background-image]") ?: this
        val style = bgEl.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            val url = m?.groupValues?.getOrNull(2)?.trim()
            if (!url.isNullOrBlank() && !url.startsWith("data:")) return fixUrl(url)
        }

        val img = this.selectFirst("img")
        if (img != null) {
            // srcset
            for (a in listOf("data-srcset", "srcset")) {
                val v = img.attr(a).trim()
                if (v.isNotBlank()) {
                    val first = v.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.trim()
                    if (!first.isNullOrBlank() && !first.startsWith("data:")) return fixUrl(first)
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
            if (v.isNotBlank() && !v.startsWith("data:")) return fixUrl(v)
        }

        return null
    }

    private suspend fun fetchOgPosterCached(detailsUrl: String, base: String): String? {
        if (posterCache.containsKey(detailsUrl)) return posterCache[detailsUrl]

        val result = withTimeoutOrNull(4500L) {
            runCatching {
                val d = app.get(detailsUrl, headers = safeHeaders(base)).document
                d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.let { fixUrl(it) }
            }.getOrNull()
        }

        if (posterCache.size > 400) posterCache.remove(posterCache.keys.firstOrNull())
        posterCache[detailsUrl] = result
        return result
    }

    // ============================================================
    // MainPage / Search
    // ============================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = ensureBase()
        val reqUrl = request.data.replace(mirrors.first(), base).replace(mainUrl, base)
        val doc = app.get(reqUrl, headers = safeHeaders(base)).document

        val elements = doc.select(
            "div.Thumb--GridItem, div.GridItem, div.BlockItem, div.item, article, div.movie, li.item, .Thumb, .post-block, .post, .item-box"
        )

        var ogCount = 0
        val items = elements.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val link = canonicalUrl(a.attr("href"))
            if (link.isBlank()) return@mapNotNull null

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)

            var poster = element.extractPosterFromCard()
            if (poster.isNullOrBlank() && ogCount < 25) {
                ogCount++
                poster = fetchOgPosterCached(link, base)
            }

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = posterHeaders(base)
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private suspend fun parseCards(doc: Document, base: String): List<SearchResponse> {
        val elements = doc.select(
            "div.Thumb--GridItem, div.GridItem, div.BlockItem, div.item, article, div.movie, li.item, .Thumb, .post-block, .post, .item-box"
        )

        var ogCount = 0
        val list = elements.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val link = canonicalUrl(a.attr("href"))
            if (link.isBlank()) return@mapNotNull null

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)

            var poster = element.extractPosterFromCard()
            if (poster.isNullOrBlank() && ogCount < 25) {
                ogCount++
                poster = fetchOgPosterCached(link, base)
            }

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = posterHeaders(base)
            }
        }

        return list.distinctBy { it.url }
    }

    private fun filterByQuery(items: List<SearchResponse>, q: String): List<SearchResponse> {
        val needle = normalizeArabic(q)
        return items.filter { normalizeArabic(it.name).contains(needle) }.take(MAX_SEARCH_RESULTS)
    }

    private suspend fun crawlSearchFallback(q: String, base: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val sections = listOf("$base/movies", "$base/series")

        for (section in sections) {
            var p = 1
            while (p <= MAX_CRAWL_PAGES_PER_SECTION && out.size < MAX_SEARCH_RESULTS) {
                val pageUrl = if (p == 1) section else "$section/page/$p"
                val doc = runCatching { app.get(pageUrl, headers = safeHeaders(base)).document }.getOrNull() ?: break
                val cards = parseCards(doc, base)
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
        val base = ensureBase()
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encPlus = q.replace(" ", "+")
        val enc = URLEncoder.encode(q, "UTF-8")

        val urls = listOf(
            "$base/search/$encPlus",
            "$base/?s=$enc",
            "$base/?search=$enc",
            "$base/search.php?q=$enc"
        )

        for (u in urls) {
            val doc = runCatching { app.get(u, headers = safeHeaders(base)).document }.getOrNull() ?: continue
            val parsed = parseCards(doc, base)
            val filtered = filterByQuery(parsed, q)
            if (filtered.isNotEmpty()) return filtered
        }

        return crawlSearchFallback(q, base)
    }

    // ============================================================
    // Link Logic (StreamPlay style)
    // ============================================================

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

        // data-watch
        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(canonicalUrl(s))
        }

        // data-*
        val attrs = listOf("data-url", "data-href", "data-embed", "data-src", "data-link", "data-iframe")
        for (a in attrs) {
            this.select("[$a]").forEach {
                val s = it.attr(a).trim()
                if (s.isNotBlank()) out.add(canonicalUrl(s))
            }
        }

        // iframe
        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(canonicalUrl(s))
        }

        // onclick url
        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(canonicalUrl(m.value))
        }

        // anchors
        this.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            val h = href.lowercase()
            val ok = listOf("embed", "player", "watch", "play", "download", "go", "slp_watch=").any { h.contains(it) }
            if (ok && href.isNotBlank()) out.add(canonicalUrl(href))
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

    private suspend fun expandDataWatchLink(dataWatchUrl: String, referer: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        val fixed = canonicalUrl(dataWatchUrl)
        if (fixed.isBlank()) return emptyList()

        val slp = extractSlpWatchParam(fixed)
        val decodedUrl = slp?.let { decodeSlpWatchUrl(it) }
        val target = decodedUrl ?: fixed

        out.add(canonicalUrl(target))

        runCatching {
            val doc = app.get(target, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).document
            doc.extractServersFast().forEach { out.add(it) }
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }
        }

        // NOTE: If WeCima uses XHR (like /p) you must replicate it here (we’ll finish once you copy the request)
        // out.addAll(tryWeCimaAjaxPlayer(doc, referer, base))

        return out.toList()
    }

    private suspend fun resolveInternalOnce(url: String, referer: String, base: String): List<String> {
        val fixed = canonicalUrl(url)
        if (fixed.isBlank()) return emptyList()

        return runCatching {
            val doc = app.get(fixed, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).document
            val out = LinkedHashSet<String>()

            out.addAll(doc.extractServersFast())
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }

            // expand data-watch inside internal page (bounded upstream)
            doc.select("[data-watch]").forEach {
                val dw = it.attr("data-watch").trim()
                if (dw.isNotBlank()) runCatching { expandDataWatchLink(dw, fixed, base).forEach { out.add(it) } }
            }

            out.toList()
        }.getOrElse { emptyList() }
    }

    // ============================================================
    // Load
    // ============================================================

    override suspend fun load(url: String): LoadResponse {
        val base = ensureBase()
        val u = canonicalUrl(url)
        val doc = app.get(u, headers = safeHeaders(base)).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "WeCima"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src]")?.attr("src")?.trim()

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val type = guessTypeFrom(u, title)

        if (type == TvType.TvSeries) {
            // Try extract episodes from current doc
            var episodes = doc.extractEpisodes(u)

            // If too few episodes, attempt a “series hub” page link (often text contains "مترجم" without "الحلقة")
            if (episodes.size < 4) {
                val seriesHub = doc.select("a[href]").mapNotNull { a ->
                    val href = canonicalUrl(a.attr("href"))
                    val txt = a.text().trim()
                    val okTxt = txt.isNotBlank() && txt.contains("مترجم") && !txt.contains("الحلقة")
                    val okHref = href.isNotBlank() && isInternalUrl(href) && href != u
                    if (okTxt && okHref) href else null
                }.firstOrNull()

                if (!seriesHub.isNullOrBlank()) {
                    val hubDoc = runCatching { app.get(seriesHub, headers = safeHeaders(base)).document }.getOrNull()
                    if (hubDoc != null) {
                        val more = hubDoc.extractEpisodes(seriesHub)
                        if (more.size > episodes.size) episodes = more
                    }
                }
            }

            return newTvSeriesLoadResponse(title, u, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = posterHeaders(base)
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, u, type, u) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = posterHeaders(base)
            this.plot = plot
        }
    }

    /**
     * Episode extraction:
     * - do NOT strip query
     * - do NOT overwrite episodes by using normalized URL
     */
    private fun Document.extractEpisodes(seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()

        val epArabic = Regex("""الحلقة\s*(\d{1,4})""")
        val sxe = Regex("""S\s*(\d{1,3})\s*:\s*E\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
        val numAny = Regex("""(\d{1,4})""")

        val links = this.select(
            "a[href*=%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9], a:contains(الحلقة), a:contains(مشاهدة), " +
                "a[href*=episode], a[href*=episodes], a[href*=watch], a[href*=play], a[href*=player]"
        )

        links.forEach { a ->
            val hrefRaw = a.attr("href").trim()
            if (hrefRaw.isBlank()) return@forEach

            val link = canonicalUrl(hrefRaw)
            if (!isInternalUrl(link)) return@forEach
            if (canonicalUrl(seriesUrl) == canonicalUrl(link)) return@forEach

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

            // key is full URL (with query) to avoid collapsing
            found.putIfAbsent(link, epObj)
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

    // ============================================================
    // loadLinks
    // ============================================================

    private suspend fun emitDirect(u: String, pageUrl: String, callback: (ExtractorLink) -> Unit) {
        val low = u.lowercase()
        val type = if (low.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        val el = newExtractorLink(
            source = name,
            name = "WeCima Direct",
            url = u,
            type = type
        ) {
            this.referer = pageUrl
            this.quality = Qualities.Unknown.value
            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to pageUrl)
        }

        callback(el)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val base = ensureBase()
        val pageUrl = canonicalUrl(data).replace(mirrors.first(), base).replace(mainUrl, base)
        if (pageUrl.isBlank()) return false

        val doc = app.get(pageUrl, headers = safeHeaders(base)).document

        // 1) DISCOVERY: collect everything first
        val candidates = LinkedHashSet<String>()
        doc.extractServersFast().forEach { candidates.add(it) }
        doc.extractDirectMediaFromScripts().forEach { candidates.add(it) }

        doc.select("[data-watch]").mapNotNull { it.attr("data-watch")?.trim() }
            .filter { it.isNotBlank() }
            .take(35)
            .forEach { candidates.add(canonicalUrl(it)) }

        if (candidates.isEmpty()) return false

        // 2) EXPAND/RESOLVE internal ONCE (bounded)
        val expanded = LinkedHashSet<String>()
        expanded.addAll(candidates)

        var internalUsed = 0
        for (c in candidates.toList().take(min(candidates.size, MAX_CANDIDATES))) {
            if (internalUsed >= MAX_INTERNAL_RESOLVE) break
            if (!isInternalUrl(c)) continue

            val more = withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) {
                resolveInternalOnce(c, pageUrl, base)
            } ?: emptyList()

            more.forEach { expanded.add(it) }
            internalUsed++
        }

        // 3) FINAL list
        val finals = expanded.toList().take(MAX_FINAL_LINKS)
        if (finals.isEmpty()) return false

        // 4) EMIT
        var foundAny = false

        for (link in finals) {
            val u = canonicalUrl(link)
            val low = u.lowercase()

            if (low.contains(".mp4") || low.contains(".m3u8")) {
                runCatching { emitDirect(u, pageUrl, callback) }
                foundAny = true
                continue
            }

            // loadExtractor ONLY on external
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
