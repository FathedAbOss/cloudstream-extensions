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

    // ===== Mirrors =====
    private val baseUrls = listOf(
        "https://wecima.date",
        "https://wecima.show",
        "https://wecima.click"
        // أضف مرايا أخرى إذا بدك
    )

    override var mainUrl = baseUrls.first()
    override var name = "WeCima"
    override var lang = "ar"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // ===== Safety caps =====
    private val MAX_SEARCH_RESULTS = 30
    private val MAX_CRAWL_PAGES_PER_SECTION = 6
    private val MAX_CANDIDATES = 110
    private val MAX_FINAL_LINKS = 55
    private val MAX_INTERNAL_RESOLVE = 18
    private val MAX_EXPAND_DATAWATCH = 14
    private val PER_CANDIDATE_TIMEOUT_MS = 6500L

    private val safeHeaders get() = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept-Language" to "ar,en-US;q=0.8,en;q=0.7"
    )

    private val posterHeaders get() = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    private val posterCache = LinkedHashMap<String, String?>()
    private var resolvedBaseUrl: String? = null

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أحدث الأفلام",
        "$mainUrl/series" to "أحدث المسلسلات",
        "$mainUrl/trending" to "الأكثر مشاهدة",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    // =========================
    // Base url auto-switch
    // =========================
    private suspend fun ensureWorkingMainUrl() {
        if (resolvedBaseUrl != null) return

        // جرّب الحالي
        val okCurrent = withTimeoutOrNull(2500L) {
            runCatching { app.get(mainUrl, headers = safeHeaders).text }.isSuccess
        } ?: false

        if (okCurrent) {
            resolvedBaseUrl = mainUrl
            return
        }

        // جرّب المرايا
        for (b in baseUrls) {
            val ok = withTimeoutOrNull(2500L) {
                runCatching { app.get(b, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$b/")).text }.isSuccess
            } ?: false

            if (ok) {
                mainUrl = b
                resolvedBaseUrl = b
                return
            }
        }

        // إذا ولا واحد شغّال، خلّيها على الحالي
        resolvedBaseUrl = mainUrl
    }

    // =========================
    // Helpers
    // =========================

    /**
     * مهم: ما نكسر روابط الحلقات أو slp_watch
     */
    private fun canonicalUrlKeepQuery(raw: String): String {
        val u = raw.trim()
        if (u.isBlank()) return u
        return fixUrl(u).trim()
    }

    /**
     * هذا فقط للبطاقات (قوائم) لتخفيف التكرار، وليس للحلقات.
     */
    private fun canonicalListUrl(raw: String): String {
        val u = raw.trim()
        if (u.isBlank()) return u
        val low = u.lowercase()
        return if (low.contains("slp_watch=") || low.contains("watch") || low.contains("episode") || low.contains("ep="))
            fixUrl(u).trim()
        else
            fixUrl(u.substringBefore("#").substringBefore("?").trim()).trim()
    }

    private fun normalizeArabic(s: String): String =
        s.lowercase()
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ى", "ي").replace("ة", "ه")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isInternalUrl(url: String): Boolean {
        val fixed = canonicalUrlKeepQuery(url)
        if (!fixed.startsWith("http")) return true
        val host = runCatching { URI(fixed).host?.lowercase() ?: "" }.getOrNull().orEmpty()
        if (host.isBlank()) return fixed.startsWith(mainUrl)
        return host.contains("wecima") || host.contains("wecimma") || host.endsWith("wecema.media") || host == "wca.cam"
    }

    private fun guessTypeFrom(url: String, title: String): TvType {
        val u = url.lowercase()
        val t = title.lowercase()
        if (u.contains("/series/") || u.contains("/episodes/") || u.contains("episode") || t.contains("الحلقة") || t.contains("موسم"))
            return TvType.TvSeries
        if (u.contains("انمي") || t.contains("انمي"))
            return TvType.Anime
        return TvType.Movie
    }

    private fun Element.extractTitleStrong(): String? {
        val h = this.selectFirst("h1,h2,h3,.title,.name,strong.title,.post-title")?.text()?.trim()
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
        // 1) background-image
        val bgEl = this.selectFirst("[style*=background-image]") ?: this
        val style = bgEl.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            val u = m?.groupValues?.getOrNull(2)?.trim()
            if (!u.isNullOrBlank() && !u.startsWith("data:")) return fixUrl(u)
        }

        // 2) data attrs على الكرت نفسه
        for (a in listOf("data-src", "data-original", "data-lazy-src", "data-image", "data-thumb", "data-poster", "data-bg", "data-background")) {
            val v = this.attr(a).trim()
            if (v.isNotBlank() && !v.startsWith("data:")) return fixUrl(v)
        }

        // 3) img
        val img = this.selectFirst("img")
        if (img != null) {
            for (a in listOf("data-src", "data-original", "data-lazy-src", "data-image", "src")) {
                val v = img.attr(a).trim()
                if (v.isNotBlank() && !v.startsWith("data:")) return fixUrl(v)
            }

            // srcset
            for (a in listOf("data-srcset", "srcset")) {
                val v = img.attr(a).trim()
                if (v.isNotBlank()) {
                    val first = v.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.trim()
                    if (!first.isNullOrBlank() && !first.startsWith("data:")) return fixUrl(first)
                }
            }
        }

        return null
    }

    private suspend fun fetchPosterFromDetails(detailsUrl: String): String? {
        if (posterCache.containsKey(detailsUrl)) return posterCache[detailsUrl]

        val result = withTimeoutOrNull(5500L) {
            runCatching {
                val d = app.get(detailsUrl, headers = safeHeaders).document
                val og = d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                val tw = d.selectFirst("meta[name=twitter:image]")?.attr("content")?.trim()
                val item = d.selectFirst("meta[itemprop=image]")?.attr("content")?.trim()
                val anyImg = d.selectFirst("img[src]")?.attr("src")?.trim()

                val p = listOf(og, tw, item, anyImg).firstOrNull { !it.isNullOrBlank() }
                p?.let { fixUrl(it) }
            }.getOrNull()
        }

        if (posterCache.size > 500) posterCache.remove(posterCache.keys.firstOrNull())
        posterCache[detailsUrl] = result
        return result
    }

    // =========================
    // MainPage
    // =========================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureWorkingMainUrl()

        val doc = app.get(request.data.replace(baseUrls.first(), mainUrl), headers = safeHeaders).document
        val elements = doc.select(
            "div.Thumb--GridItem, div.GridItem, div.BlockItem, div.item, article, div.movie, li.item, .Thumb, .post-block, .post, .item-box"
        )

        var detailsFetchCount = 0
        val items = elements.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val link = canonicalListUrl(a.attr("href"))
            if (link.isBlank()) return@mapNotNull null

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)

            var poster = element.extractPosterFromCard()
            if (poster.isNullOrBlank() && detailsFetchCount < 18) {
                detailsFetchCount++
                poster = fetchPosterFromDetails(link)
            }

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    // =========================
    // Search
    // =========================
    private suspend fun parseCards(doc: Document): List<SearchResponse> {
        val elements = doc.select(
            "div.Thumb--GridItem, div.GridItem, div.BlockItem, div.item, article, div.movie, li.item, .Thumb, .post-block, .post, .item-box"
        )

        var detailsFetchCount = 0
        val list = elements.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val link = canonicalListUrl(a.attr("href"))
            if (link.isBlank()) return@mapNotNull null

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)

            var poster = element.extractPosterFromCard()
            if (poster.isNullOrBlank() && detailsFetchCount < 18) {
                detailsFetchCount++
                poster = fetchPosterFromDetails(link)
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
        ensureWorkingMainUrl()

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

    // =========================
    // Link logic
    // =========================
    private fun Document.extractDirectMediaFromText(): List<String> {
        val out = LinkedHashSet<String>()
        val html = this.html()

        Regex("""https?://[^\s"'<>]+?\.(mp4|m3u8)(\?[^\s"'<>]+)?""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.value) }

        // jwplayer file: "..."
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.groupValues[1]) }

        // iframe/src داخل النص
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.groupValues[1]) }

        return out.toList()
    }

    private fun Document.extractServersFast(): List<String> {
        val out = LinkedHashSet<String>()

        // data-watch
        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(canonicalUrlKeepQuery(s))
        }

        // common data attrs
        val attrs = listOf("data-url", "data-href", "data-embed", "data-src", "data-link", "data-iframe")
        for (a in attrs) {
            this.select("[$a]").forEach {
                val s = it.attr(a).trim()
                if (s.isNotBlank()) out.add(canonicalUrlKeepQuery(s))
            }
        }

        // iframe src
        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(canonicalUrlKeepQuery(s))
        }

        // onclick urls
        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(canonicalUrlKeepQuery(m.value))
        }

        // anchors (watch/play/player/go/embed/download)
        this.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            val h = href.lowercase()
            val ok = listOf("embed", "player", "watch", "play", "download", "go", "slp_watch=", "source=").any { h.contains(it) }
            if (ok && href.isNotBlank()) out.add(canonicalUrlKeepQuery(href))
        }

        // plus: sniff inside scripts/text
        this.extractDirectMediaFromText().forEach { out.add(canonicalUrlKeepQuery(it)) }

        return out.toList()
    }

    private fun extractSlpWatchParam(url: String): String? {
        if (!url.contains("slp_watch=")) return null
        return runCatching {
            val q = url.substringAfter("slp_watch=", "")
            if (q.isBlank()) null else q.substringBefore("&").trim()
        }.getOrNull()
    }

    private fun decodeBase64UrlSafe(encoded: String): String? {
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

    private fun extractAtobStrings(html: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex("""atob\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { m ->
                val b64 = m.groupValues[1]
                decodeBase64UrlSafe(b64)?.let { out.add(it) }
            }
        return out.toList()
    }

    private suspend fun openInternalOnce(url: String, referer: String): List<String> {
        val fixed = canonicalUrlKeepQuery(url)
        if (fixed.isBlank()) return emptyList()

        val doc = runCatching {
            app.get(fixed, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).document
        }.getOrNull() ?: return emptyList()

        val out = LinkedHashSet<String>()
        out.addAll(doc.extractServersFast())

        // slp_watch decode
        doc.select("[data-watch]").forEach {
            val dw = it.attr("data-watch").trim()
            if (dw.contains("slp_watch=")) {
                extractSlpWatchParam(dw)?.let { decodeBase64UrlSafe(it) }?.let { out.add(it) }
            }
        }

        // atob links in scripts
        extractAtobStrings(doc.html()).forEach { out.add(it) }

        return out.toList()
    }

    private suspend fun emitDirect(u: String, pageUrl: String, callback: (ExtractorLink) -> Unit) {
        val url = canonicalUrlKeepQuery(u)
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

    // =========================
    // Load
    // =========================
    override suspend fun load(url: String): LoadResponse {
        ensureWorkingMainUrl()

        val pageUrl = canonicalUrlKeepQuery(url).replace(baseUrls.first(), mainUrl)
        val doc = app.get(pageUrl, headers = safeHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "WeCima"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src]")?.attr("src")?.trim()

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val type = guessTypeFrom(pageUrl, title)

        if (type == TvType.TvSeries) {
            val episodes = doc.extractEpisodes(pageUrl)
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, pageUrl, type, pageUrl) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = this@WeCimaProvider.posterHeaders
            this.plot = plot
        }
    }

    /**
     * Episodes: توسعة selectors + عدم دمج الروابط بالغلط
     */
    private fun Document.extractEpisodes(seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()

        val epArabic = Regex("""الحلقة\s*(\d{1,4})""")
        val sxe = Regex("""S\s*(\d{1,3})\s*[:\-]\s*E\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
        val numAny = Regex("""(\d{1,4})""")

        // selectors واسعة جداً
        val links = this.select(
            "a:matches((?i)episode|episodes|watch|player|play), " +
            "a[href*=%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9], a:contains(الحلقة), a:contains(مشاهدة), " +
            "a[href*=episode], a[href*=episodes], a[href*=watch], a[href*=play], a[href*=player], " +
            "[data-episode], [data-ep], [data-href], [data-url]"
        )

        // 1) anchors
        this.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach
            val link = canonicalUrlKeepQuery(href)
            if (!isInternalUrl(link)) return@forEach
            if (link == canonicalUrlKeepQuery(seriesUrl)) return@forEach

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

        // 2) fallback: data attrs (بعض الصفحات تحط الروابط هون)
        for (el in links) {
            val candidates = listOf(
                el.attr("data-episode"),
                el.attr("data-ep"),
                el.attr("data-href"),
                el.attr("data-url")
            ).map { it.trim() }.filter { it.isNotBlank() }

            for (c in candidates) {
                val link = canonicalUrlKeepQuery(c)
                if (!isInternalUrl(link)) continue
                if (link == canonicalUrlKeepQuery(seriesUrl)) continue
                if (found.containsKey(link)) continue

                val text = el.text().trim()
                val epNum =
                    epArabic.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""[?&]ep(?:isode)?=(\d{1,4})""", RegexOption.IGNORE_CASE)
                            .find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()

                val nm = if (text.isNotBlank()) text else (epNum?.let { "الحلقة $it" } ?: "مشاهدة")

                val epObj = newEpisode(link) {
                    this.name = nm
                    this.season = 1
                    this.episode = epNum
                }
                found.putIfAbsent(link, epObj)
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

    // =========================
    // loadLinks (StreamPlay style)
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureWorkingMainUrl()

        val pageUrl = canonicalUrlKeepQuery(data).replace(baseUrls.first(), mainUrl)
        val doc = app.get(pageUrl, headers = safeHeaders).document

        // 1) DISCOVERY
        val candidates = LinkedHashSet<String>()
        doc.extractServersFast().forEach { candidates.add(it) }

        // 2) EXPAND data-watch قليلاً
        var expandUsed = 0
        val expanded = LinkedHashSet<String>()
        expanded.addAll(candidates)

        for (c in candidates.toList().take(min(candidates.size, MAX_CANDIDATES))) {
            if (expandUsed >= MAX_EXPAND_DATAWATCH) break
            if (!isInternalUrl(c)) continue

            val low = c.lowercase()
            val shouldOpen =
                low.contains("watch") || low.contains("play") || low.contains("player") || low.contains("go") || low.contains("download") || low.contains("slp_watch=")

            if (!shouldOpen) continue

            val more = withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) { openInternalOnce(c, pageUrl) } ?: emptyList()
            more.forEach { expanded.add(it) }
            expandUsed++
        }

        // 3) FINAL LIST (dedupe)
        val finals = expanded.toList()
            .map { canonicalUrlKeepQuery(it) }
            .distinct()
            .take(MAX_FINAL_LINKS)

        if (finals.isEmpty()) return false

        // 4) EMIT
        var foundAny = false

        for (link in finals) {
            val u = canonicalUrlKeepQuery(link)
            val low = u.lowercase()

            // direct media
            if (low.contains(".mp4") || low.contains(".m3u8")) {
                runCatching {
                    emitDirect(u, pageUrl, callback)
                    foundAny = true
                }
                continue
            }

            // EXTERNAL ONLY -> loadExtractor
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
