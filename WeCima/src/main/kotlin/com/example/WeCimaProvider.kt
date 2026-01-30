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

    private val safeHeaders get() = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3"
    )

    private val posterHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3"
    )

    private val posterCache = LinkedHashMap<String, String?>()

    private val MAX_SEARCH_RESULTS = 30
    private val MAX_CRAWL_PAGES_PER_SECTION = 6

    private val MAX_CANDIDATES = 120
    private val MAX_FINAL_LINKS = 60
    private val MAX_INTERNAL_RESOLVE = 22
    private val PER_CANDIDATE_TIMEOUT_MS = 7000L

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

    /**
     * IMPORTANT:
     * - Don't strip query params if they look like player identifiers.
     * - Many internal pages rely on ?vid=, ?id=, ?server=, slp_watch= etc.
     */
    private fun canonicalUrl(raw: String): String {
        val u = raw.trim()
        if (u.isBlank()) return u
        val fixed = fixUrl(u).trim()

        val low = fixed.lowercase()
        val keepQueryKeys = listOf(
            "vid=", "id=", "server=", "ep=", "episode=", "season=", "slp_watch=", "watch="
        )
        val mustKeepQuery = keepQueryKeys.any { low.contains(it) } ||
            low.contains("watch.php?") ||
            low.contains("play.php?") ||
            low.contains("player.php?")

        if (mustKeepQuery) return fixed

        return fixed.substringBefore("#").trim()
    }

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

    private fun isInternalUrl(url: String): Boolean {
        val fixed = canonicalUrl(url)
        if (!fixed.startsWith("http")) return true
        val host = runCatching { URI(fixed).host?.lowercase() ?: "" }.getOrNull().orEmpty()
        if (host.isBlank()) return fixed.startsWith(mainUrl)
        return host.contains("wecima") || host.contains("wecimma") || host == "wca.cam" || host.endsWith("wecema.media")
    }

    private suspend fun fetchOgPosterCached(detailsUrl: String): String? {
        if (posterCache.containsKey(detailsUrl)) return posterCache[detailsUrl]

        val result = withTimeoutOrNull(4500L) {
            runCatching {
                val d = app.get(detailsUrl, headers = safeHeaders).document
                val og = d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                if (!og.isNullOrBlank()) fixUrl(og) else null
            }.getOrNull()
        }

        if (posterCache.size > 650) posterCache.remove(posterCache.keys.firstOrNull())
        posterCache[detailsUrl] = result
        return result
    }

    // ---------------------------
    // MainPage
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = safeHeaders).document

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
            // Keep this bounded; listing pages can be heavy
            if (poster.isNullOrBlank() && ogCount < 15) {
                ogCount++
                poster = fetchOgPosterCached(link)
            }

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    // ---------------------------
    // Search
    // ---------------------------

    private suspend fun parseCards(doc: Document): List<SearchResponse> {
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
            if (poster.isNullOrBlank() && ogCount < 15) {
                ogCount++
                poster = fetchOgPosterCached(link)
            }

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders
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
                val doc = runCatching { app.get(pageUrl, headers = safeHeaders).document }.getOrNull() ?: break
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
            val doc = runCatching { app.get(u, headers = safeHeaders).document }.getOrNull() ?: continue
            val parsed = parseCards(doc)
            val filtered = filterByQuery(parsed, q)
            if (filtered.isNotEmpty()) return filtered
        }

        return crawlSearchFallback(q)
    }

    // ---------------------------
    // Series: normalize episode -> series root, then crawl seasons -> episodes
    // ---------------------------

    private fun Document.findSeriesRootFromEpisodePage(): String? {
        // Episode pages usually contain links to series root + season pages.
        // Prefer a "مسلسل" link without "الموسم" and without "الحلقة".
        val links = this.select("a[href]")
            .mapNotNull { it.attr("href")?.trim() }
            .map { fixUrl(it) }
            .filter { it.contains("مسلسل") && !it.contains("الحلقة") }

        if (links.isEmpty()) return null

        return links.firstOrNull { !it.contains("الموسم") }
            ?: links.firstOrNull()
    }

    private fun parseSeasonNumberFromText(text: String): Int? {
        val t = text.trim()
        Regex("""(\d{1,3})""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        val map = mapOf(
            "الاول" to 1, "الأول" to 1,
            "الثاني" to 2,
            "الثالث" to 3,
            "الرابع" to 4,
            "الخامس" to 5,
            "السادس" to 6,
            "السابع" to 7,
            "الثامن" to 8,
            "التاسع" to 9,
            "العاشر" to 10,
            "الحادي عشر" to 11,
            "الثاني عشر" to 12
        )

        val low = t.lowercase()
        map.entries.firstOrNull { low.contains(it.key) }?.value?.let { return it }
        return null
    }

    private suspend fun Document.extractEpisodesDeep(seriesUrl: String): List<Episode> {
        // Step 1: collect season links (bounded)
        val seasonPairs = LinkedHashMap<String, Int?>()

        this.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach
            val u = canonicalUrl(href)
            if (!isInternalUrl(u)) return@forEach

            val txt = a.text().trim()
            val isSeason = (u.contains("الموسم") || txt.contains("الموسم")) &&
                u.contains("مسلسل") &&
                !u.contains("الحلقة")

            if (!isSeason) return@forEach

            val seasonNum = parseSeasonNumberFromText(txt) ?: parseSeasonNumberFromText(u)
            seasonPairs.putIfAbsent(u, seasonNum)
        }

        val seasons = seasonPairs.entries.toList().take(14) // safety cap

        // If no seasons found, fall back to local extraction
        if (seasons.isEmpty()) return this.extractEpisodes(seriesUrl)

        val out = LinkedHashMap<String, Episode>()

        // Step 2: crawl each season page and collect episode links
        for ((idx, entry) in seasons.withIndex()) {
            val sUrl = entry.key
            val seasonNumber = entry.value ?: (idx + 1)

            val sDoc = runCatching { app.get(sUrl, headers = safeHeaders).document }.getOrNull() ?: continue

            val epLinks = LinkedHashSet<String>()
            sDoc.select("a[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isBlank()) return@forEach
                val u = canonicalUrl(href)
                if (!isInternalUrl(u)) return@forEach
                if (!u.contains("الحلقة")) return@forEach
                epLinks.add(u)
            }

            for (epUrl in epLinks) {
                val epNum =
                    Regex("""الحلقة\s*(\d{1,4})""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""[-/](\d{1,4})(?:/)?$""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()

                val e = newEpisode(epUrl) {
                    this.season = seasonNumber
                    this.episode = epNum
                    this.name = if (epNum != null) "الحلقة $epNum" else "حلقة"
                }
                out.putIfAbsent(epUrl, e)
            }
        }

        val list = out.values.toList()
        return if (list.isEmpty()) {
            // fallback again, just in case
            this.extractEpisodes(seriesUrl)
        } else {
            list.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 999999 })
        }
    }

    // ---------------------------
    // Link Logic
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

    private fun Document.extractUrlsFromScriptsGeneral(): List<String> {
        val out = LinkedHashSet<String>()
        val html = this.html()

        Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val u = m.value
            val low = u.lowercase()
            // likely useful embed/player/watch endpoints
            if (low.contains("embed") || low.contains("player") || low.contains("watch") || low.contains("play")) {
                out.add(canonicalUrl(u))
            }
        }
        return out.toList()
    }

    private fun Document.extractServersFast(): List<String> {
        val out = LinkedHashSet<String>()

        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(canonicalUrl(s))
        }

        val attrs = listOf(
            "data-url", "data-href", "data-embed", "data-src", "data-link", "data-iframe",
            "data-load", "data-embed-url", "data-player", "data-link2", "data-video"
        )
        for (a in attrs) {
            this.select("[$a]").forEach {
                val s = it.attr(a).trim()
                if (s.isNotBlank()) out.add(canonicalUrl(s))
            }
        }

        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(canonicalUrl(s))
        }

        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(canonicalUrl(m.value))
        }

        // Looser than before: on WeCima some embed links don't contain obvious keywords.
        // Still add some filtering to avoid useless navigational links.
        this.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            if (href.isBlank()) return@forEach
            val h = href.lowercase()

            val skip = h.contains("javascript:") || h.contains("mailto:") || h.contains("#")
            if (skip) return@forEach

            val maybeUseful =
                h.contains("embed") || h.contains("player") || h.contains("watch") || h.contains("play") ||
                    h.contains("download") || h.contains("slp_watch=") || h.contains("go") ||
                    h.contains("watch.php") || h.contains("play.php") || h.contains("player.php")

            if (maybeUseful) out.add(canonicalUrl(href))
        }

        return out.toList()
    }

    private fun Document.extractDownloadServers(): List<String> {
        val out = LinkedHashSet<String>()
        val html = this.html().lowercase()

        // If a page contains common download wording, anchors inside it tend to be real external hosts.
        val pageHints = listOf("تحميل", "سيرفرات التحميل", "download")
        val hasDownloadSection = pageHints.any { html.contains(it) }

        this.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            if (href.isBlank()) return@forEach
            val h = href.lowercase()

            val isExternalHost =
                h.contains("filemoon") || h.contains("dood") || h.contains("mixdrop") || h.contains("ok.ru") ||
                    h.contains("streamtape") || h.contains("uqload") || h.contains("voe") || h.contains("vid") ||
                    h.contains("vembed") || h.contains("4shared") || h.contains("drop") || h.contains("upstream")

            if (isExternalHost) out.add(canonicalUrl(href))
            else if (hasDownloadSection && !isInternalUrl(href) && href.startsWith("http")) out.add(canonicalUrl(href))
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
            doc.extractUrlsFromScriptsGeneral().forEach { out.add(it) }
            doc.extractDownloadServers().forEach { out.add(it) }
        }

        return out.toList()
    }

    private suspend fun resolveInternalOnce(url: String, referer: String): List<String> {
        val fixed = canonicalUrl(url)
        if (fixed.isBlank()) return emptyList()

        return runCatching {
            val doc = app.get(fixed, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).document
            val out = LinkedHashSet<String>()

            out.addAll(doc.extractServersFast())
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }
            doc.extractUrlsFromScriptsGeneral().forEach { out.add(it) }
            doc.extractDownloadServers().forEach { out.add(it) }

            doc.select("[data-watch]").forEach {
                val dw = it.attr("data-watch").trim()
                if (dw.isNotBlank()) {
                    runCatching {
                        expandDataWatchLink(dw, fixed).forEach { out.add(it) }
                    }
                }
            }

            out.toList()
        }.getOrElse { emptyList() }
    }

    // ✅ Correct direct emitter for newer core (newExtractorLink is suspend + initializer)
    private suspend fun emitDirect(u: String, pageUrl: String, callback: (ExtractorLink) -> Unit) {
        val url = canonicalUrl(u)
        val low = url.lowercase()
        val type = if (low.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to pageUrl)

        val el = newExtractorLink(
            source = name,
            name = "WeCima Direct",
            url = url,
            type = type
        ) {
            this.referer = pageUrl
            this.quality = Qualities.Unknown.value
            this.headers = headers
        }

        callback(el)
    }

    // ---------------------------
    // Load
    // ---------------------------

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
            // Normalize: if we are on an episode page, jump to the real series root when possible
            val isEpisodePage = url.contains("الحلقة")
            val seriesUrl = if (isEpisodePage) doc.findSeriesRootFromEpisodePage() ?: url else url

            val seriesDoc = if (seriesUrl != url) {
                runCatching { app.get(seriesUrl, headers = safeHeaders).document }.getOrNull() ?: doc
            } else doc

            val episodes = seriesDoc.extractEpisodesDeep(seriesUrl)

            return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
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

    /**
     * Fallback episode extraction on a single doc.
     * Kept from your version, but used only when season crawling fails.
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
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach

            val link = canonicalUrl(href)
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

            val nm = when {
                text.isNotBlank() && text != "مشاهدة" -> text
                episode != null -> "الحلقة $episode"
                else -> "مشاهدة"
            }

            val epObj = newEpisode(link) {
                this.name = nm
                this.season = season
                this.episode = episode
            }

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

    // ---------------------------
    // loadLinks (StreamPlay-style aggregation + bounded internal resolve)
    // ---------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = canonicalUrl(data)
        if (pageUrl.isBlank()) return false

        val doc = app.get(pageUrl, headers = safeHeaders).document

        // 1) Discovery: collect EVERYTHING first
        val candidates = LinkedHashSet<String>()

        // Add the page itself (sometimes internal expansion from self yields the servers)
        if (isInternalUrl(pageUrl)) candidates.add(pageUrl)

        doc.extractServersFast().forEach { candidates.add(it) }
        doc.extractDirectMediaFromScripts().forEach { candidates.add(it) }
        doc.extractUrlsFromScriptsGeneral().forEach { candidates.add(it) }
        doc.extractDownloadServers().forEach { candidates.add(it) }

        // Expand explicit data-watch entries (limited, no deep recursion)
        doc.select("[data-watch]")
            .mapNotNull { it.attr("data-watch")?.trim() }
            .filter { it.isNotBlank() }
            .take(45)
            .forEach { candidates.add(canonicalUrl(it)) }

        if (candidates.isEmpty()) return false

        // 2) Expand internal once (bounded)
        val expanded = LinkedHashSet<String>()
        expanded.addAll(candidates)

        var internalUsed = 0
        for (c in candidates.toList().take(min(candidates.size, MAX_CANDIDATES))) {
            if (internalUsed >= MAX_INTERNAL_RESOLVE) break
            if (!isInternalUrl(c)) continue

            val more = withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) {
                resolveInternalOnce(c, pageUrl)
            } ?: emptyList()

            more.forEach { expanded.add(it) }
            internalUsed++
        }

        // 3) Final list (bounded)
        val finals = expanded.toList().take(MAX_FINAL_LINKS)
        if (finals.isEmpty()) return false

        // 4) Emit
        var foundAny = false

        for (link in finals) {
            val u = canonicalUrl(link)
            val low = u.lowercase()

            // Direct media
            if (low.contains(".mp4") || low.contains(".m3u8")) {
                runCatching {
                    emitDirect(u, pageUrl, callback)
                    foundAny = true
                }
                continue
            }

            // Only external hosts to loadExtractor
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
