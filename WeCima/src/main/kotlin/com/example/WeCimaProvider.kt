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

    private val MAX_CANDIDATES = 140
    private val MAX_FINAL_LINKS = 70
    private val MAX_INTERNAL_RESOLVE = 26
    private val PER_CANDIDATE_TIMEOUT_MS = 7500L

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أحدث الأفلام",
        "$mainUrl/series" to "أحدث المسلسلات",
        "$mainUrl/trending" to "الأكثر مشاهدة",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    // ---------------------------
    // URL helpers
    // ---------------------------

    private fun fixUrlPlus(u: String?): String? {
        val s = u?.trim().orEmpty()
        if (s.isBlank()) return null
        if (s.startsWith("data:")) return null
        // protocol-relative //cdn...
        if (s.startsWith("//")) return "https:$s"
        return fixUrl(s)
    }

    /**
     * IMPORTANT:
     * don't strip query params if they look like player identifiers
     */
    private fun canonicalUrl(raw: String): String {
        val u = raw.trim()
        if (u.isBlank()) return u
        val fixed = fixUrl(u).trim()

        val low = fixed.lowercase()
        val keepQueryKeys = listOf(
            "vid=", "id=", "server=", "ep=", "episode=", "season=", "slp_watch=", "watch=", "src="
        )

        val mustKeepQuery = keepQueryKeys.any { low.contains(it) } ||
            low.contains("watch.php?") ||
            low.contains("play.php?") ||
            low.contains("player.php?")

        if (mustKeepQuery) return fixed
        return fixed.substringBefore("#").trim()
    }

    private fun isInternalUrl(url: String): Boolean {
        val fixed = canonicalUrl(url)
        if (!fixed.startsWith("http")) return true
        val host = runCatching { URI(fixed).host?.lowercase() ?: "" }.getOrNull().orEmpty()
        if (host.isBlank()) return fixed.startsWith(mainUrl)
        return host.contains("wecima") ||
            host.contains("wecimma") ||
            host == "wca.cam" ||
            host.endsWith("wecema.media")
    }

    private fun guessTypeFrom(url: String, title: String): TvType {
        val u = url.lowercase()
        val t = title.lowercase()
        if (u.contains("/series/") || u.contains("/episodes/") || t.contains("الحلقة") || t.contains("موسم"))
            return TvType.TvSeries
        if (u.contains("انمي") || t.contains("انمي") || u.contains("anime"))
            return TvType.Anime
        return TvType.Movie
    }

    // ---------------------------
    // Card helpers
    // ---------------------------

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
            fixUrlPlus(m?.groupValues?.getOrNull(2))?.let { return it }
        }

        val img = this.selectFirst("img")
        if (img != null) {
            for (a in listOf("data-srcset", "srcset")) {
                val v = img.attr(a).trim()
                if (v.isNotBlank()) {
                    val first = v.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.trim()
                    fixUrlPlus(first)?.let { return it }
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
            if (v.isNotBlank()) fixUrlPlus(v)?.let { return it }
        }

        return null
    }

    private suspend fun fetchOgPosterCached(detailsUrl: String): String? {
        if (posterCache.containsKey(detailsUrl)) return posterCache[detailsUrl]

        val result = withTimeoutOrNull(4500L) {
            runCatching {
                val d = app.get(detailsUrl, headers = safeHeaders).document
                val og = d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                fixUrlPlus(og)
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
            // listing pages on wecima often have no real img in HTML
            if (poster.isNullOrBlank() && ogCount < 18) {
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

    private fun normalizeArabic(s: String): String =
        s.lowercase()
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ى", "ي").replace("ة", "ه")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun filterByQuery(items: List<SearchResponse>, q: String): List<SearchResponse> {
        val needle = normalizeArabic(q)
        return items.filter { normalizeArabic(it.name).contains(needle) }.take(MAX_SEARCH_RESULTS)
    }

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
            if (poster.isNullOrBlank() && ogCount < 18) {
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
    // Series fixing: episode page -> season page (best)
    // ---------------------------

    private fun Document.findBestSeriesOrSeasonFromEpisodePage(): String? {
        // WeCima episode page عادة يحتوي أزرار في الأسفل:
        // "الحلقة 14" + "الموسم الثالث مترجم" (وهذا هو الأفضل لاستخراج كل الحلقات)
        val anchors = this.select("a[href]").toList()

        fun abs(a: Element): String {
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            return canonicalUrl(href)
        }

        val season = anchors.firstOrNull { a ->
            val t = a.text().trim()
            val u = abs(a)
            (t.contains("الموسم") || u.contains("الموسم")) &&
                !u.contains("الحلقة")
        }?.let { abs(it) }?.takeIf { it.isNotBlank() && isInternalUrl(it) }

        if (!season.isNullOrBlank()) return season

        // fallback: series root (مسلسل أو انمي) بدون حلقة
        val series = anchors.firstOrNull { a ->
            val t = a.text().trim()
            val u = abs(a)
            (u.contains("مسلسل") || u.contains("انمي") || u.contains("anime") || t.contains("مسلسل") || t.contains("انمي")) &&
                !u.contains("الحلقة")
        }?.let { abs(it) }?.takeIf { it.isNotBlank() && isInternalUrl(it) }

        return series
    }

    private suspend fun Document.extractEpisodesFromThisDocIfPresent(baseUrl: String): List<Episode> {
        val out = LinkedHashMap<String, Episode>()

        // إذا الصفحة هي موسم، غالباً فيها روابط كثيرة للحلقات.
        val epLinks = this.select("a[href]")
            .mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
                val u = canonicalUrl(href)
                if (u.isBlank()) null else u
            }
            .filter { isInternalUrl(it) && (it.contains("الحلقة") || it.contains("episode", true)) }
            .distinct()

        if (epLinks.size < 5) return emptyList() // مش قائمة كاملة

        for (u in epLinks) {
            val epNum =
                Regex("""الحلقة\s*(\d{1,4})""").find(u)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""[-/](\d{1,4})(?:/)?$""").find(u)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val e = newEpisode(u) {
                this.season = 1
                this.episode = epNum
                this.name = if (epNum != null) "الحلقة $epNum" else "حلقة"
            }
            out.putIfAbsent(u, e)
        }

        return out.values.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 999999 })
    }

    /**
     * Deep extraction:
     * - If current doc already has many episode links, use them.
     * - Else: collect season links -> fetch each season -> collect episodes.
     */
    private suspend fun Document.extractEpisodesDeep(seriesOrSeasonUrl: String): List<Episode> {
        // 0) direct episodes list in current doc
        val direct = extractEpisodesFromThisDocIfPresent(seriesOrSeasonUrl)
        if (direct.isNotEmpty()) return direct

        // 1) gather season links
        val seasonLinks = LinkedHashMap<String, String>() // url -> text

        this.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            val u = canonicalUrl(href)
            if (u.isBlank() || !isInternalUrl(u)) return@forEach

            val txt = a.text().trim()
            val isSeason = (txt.contains("الموسم") || u.contains("الموسم")) &&
                !u.contains("الحلقة")

            if (isSeason) seasonLinks.putIfAbsent(u, txt)
        }

        val seasons = seasonLinks.entries.toList().take(14)
        if (seasons.isEmpty()) {
            // fallback: old simple extraction from the same doc
            return this.extractEpisodesFallback(seriesOrSeasonUrl)
        }

        val out = LinkedHashMap<String, Episode>()

        for ((idx, entry) in seasons.withIndex()) {
            val sUrl = entry.key
            val sDoc = runCatching { app.get(sUrl, headers = safeHeaders).document }.getOrNull() ?: continue

            // try direct episodes list on season page
            val fromSeasonDoc = sDoc.extractEpisodesFromThisDocIfPresent(sUrl)
            if (fromSeasonDoc.isNotEmpty()) {
                // assign season number loosely (idx+1) if site has multiple seasons
                for (ep in fromSeasonDoc) {
                    val fixed = ep.copy(season = idx + 1)
                    out.putIfAbsent(fixed.data, fixed)
                }
                continue
            }

            // otherwise collect episode links
            val epLinks = sDoc.select("a[href]")
                .mapNotNull { a ->
                    val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
                    val u = canonicalUrl(href)
                    if (u.isBlank()) null else u
                }
                .filter { isInternalUrl(it) && it.contains("الحلقة") }
                .distinct()

            for (epUrl in epLinks) {
                val epNum =
                    Regex("""الحلقة\s*(\d{1,4})""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""[-/](\d{1,4})(?:/)?$""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()

                val e = newEpisode(epUrl) {
                    this.season = idx + 1
                    this.episode = epNum
                    this.name = if (epNum != null) "الحلقة $epNum" else "حلقة"
                }
                out.putIfAbsent(epUrl, e)
            }
        }

        val list = out.values.toList()
        return if (list.isEmpty()) this.extractEpisodesFallback(seriesOrSeasonUrl)
        else list.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 999999 })
    }

    /**
     * Fallback old extraction on a single document.
     */
    private fun Document.extractEpisodesFallback(seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()

        val epArabic = Regex("""الحلقة\s*(\d{1,4})""")
        val numAny = Regex("""(\d{1,4})""")

        val links = this.select(
            "a[href*=%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9], a:contains(الحلقة), a[href*=episode], a[href*=watch]"
        )

        links.forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            if (href.isBlank()) return@forEach

            val link = canonicalUrl(href)
            if (!isInternalUrl(link)) return@forEach
            if (canonicalUrl(seriesUrl) == canonicalUrl(link)) return@forEach

            val text = a.text().trim()

            val looksEpisode =
                link.contains("episode", true) ||
                    link.contains("الحلقة", true) ||
                    text.contains("الحلقة")

            if (!looksEpisode) return@forEach

            val episode =
                epArabic.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""[-/](\d{1,4})(?:/)?$""").find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: numAny.findAll(text).mapNotNull { it.value.toIntOrNull() }.lastOrNull()

            val nm = if (episode != null) "الحلقة $episode" else (if (text.isNotBlank()) text else "مشاهدة")

            val epObj = newEpisode(link) {
                this.name = nm
                this.season = 1
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

    private fun tryDecodeAnyBase64UrlsFromHtml(html: String): List<String> {
        val out = LinkedHashSet<String>()
        // find long base64-ish tokens and attempt decode (urlsafe too)
        val rx = Regex("""([A-Za-z0-9_\-+/=]{40,})""")
        rx.findAll(html).forEach { m ->
            val token = m.groupValues[1].trim()
            val decoded = decodeBase64UrlSafe(token) ?: return@forEach
            if (decoded.startsWith("http")) out.add(canonicalUrl(decoded))
        }
        return out.toList()
    }

    private fun decodeBase64UrlSafe(raw: String): String? {
        val s0 = raw.trim()
        if (s0.length < 12) return null
        val s = s0.replace('-', '+').replace('_', '/').let { x ->
            val mod = x.length % 4
            if (mod == 0) x else x + "=".repeat(4 - mod)
        }
        return runCatching {
            val bytes = Base64.getDecoder().decode(s)
            String(bytes, Charsets.UTF_8).trim()
        }.getOrNull()
    }

    private fun Document.extractServersFromWatchSection(): List<String> {
        val out = LinkedHashSet<String>()

        // find the node that contains "سيرفرات المشاهدة" then scan around it
        val header = this.select("*:matchesOwn(سيرفرات\\s+المشاهدة)").firstOrNull()
        val container = header?.parent()?.parent() ?: header?.parent() ?: this

        // any elements with data-* or onclick or iframe/src or a/href within that container
        container.select("[data-watch],[data-url],[data-href],[data-embed],[data-src],[data-link],[data-iframe],[onclick],iframe[src],a[href],button").forEach { el ->
            // attrs
            listOf("data-watch","data-url","data-href","data-embed","data-src","data-link","data-iframe","data-load","data-player").forEach { a ->
                val v = el.attr(a).trim()
                if (v.isNotBlank()) out.add(canonicalUrl(v))
            }

            // iframe src
            val iframe = if (el.tagName().equals("iframe", true)) el else null
            iframe?.attr("src")?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(canonicalUrl(it)) }

            // onclick URL
            val oc = el.attr("onclick").trim()
            if (oc.isNotBlank()) {
                Regex("""https?://[^"'\s<>]+""").find(oc)?.value?.let { out.add(canonicalUrl(it)) }
            }

            // anchor href
            if (el.tagName().equals("a", true)) {
                val href = el.absUrl("href").ifBlank { el.attr("href").trim() }
                if (href.isNotBlank()) out.add(canonicalUrl(href))
            }
        }

        return out.toList()
    }

    private fun Document.extractServersFast(): List<String> {
        val out = LinkedHashSet<String>()

        // from watch section first (higher success rate for wecima)
        extractServersFromWatchSection().forEach { out.add(it) }

        // generic attributes
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

        // iframes
        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(canonicalUrl(s))
        }

        // onclick
        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            Regex("""https?://[^"'\s<>]+""").find(oc)?.value?.let { u -> out.add(canonicalUrl(u)) }
        }

        // anchors (loose but still avoids junk)
        this.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            if (href.isBlank()) return@forEach
            val h = href.lowercase()
            if (h.contains("javascript:") || h.contains("mailto:") || href == "#") return@forEach

            val maybeUseful =
                h.contains("embed") || h.contains("player") || h.contains("watch") || h.contains("play") ||
                    h.contains("download") || h.contains("slp_watch=") ||
                    h.contains("watch.php") || h.contains("play.php") || h.contains("player.php")

            if (maybeUseful) out.add(canonicalUrl(href))
        }

        // base64 urls in scripts/html
        tryDecodeAnyBase64UrlsFromHtml(this.html()).forEach { out.add(it) }

        return out.toList()
    }

    private fun Document.extractDownloadServers(): List<String> {
        val out = LinkedHashSet<String>()
        val html = this.html().lowercase()

        val pageHints = listOf("تحميل", "سيرفرات التحميل", "download")
        val hasDownloadSection = pageHints.any { html.contains(it) }

        this.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            if (href.isBlank()) return@forEach
            val h = href.lowercase()

            val isExternalHost =
                h.contains("filemoon") || h.contains("dood") || h.contains("mixdrop") || h.contains("ok.ru") ||
                    h.contains("streamtape") || h.contains("uqload") || h.contains("voe") || h.contains("vid") ||
                    h.contains("vimeo") || h.contains("vk") || h.contains("vks") || h.contains("vinovo")

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
            doc.extractDownloadServers().forEach { out.add(it) }

            doc.select("[data-watch]").forEach {
                val dw = it.attr("data-watch").trim()
                if (dw.isNotBlank()) {
                    runCatching { expandDataWatchLink(dw, fixed).forEach { out.add(it) } }
                }
            }

            out.toList()
        }.getOrElse { emptyList() }
    }

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

        val poster = fixUrlPlus(doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim())
            ?: fixUrlPlus(doc.selectFirst("img[src]")?.attr("src")?.trim())

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val type = guessTypeFrom(url, title)

        if (type == TvType.TvSeries) {
            // IMPORTANT: if this is an episode page, jump to season page (best) or series root
            val isEpisodePage = url.contains("الحلقة") || title.contains("الحلقة")
            val targetUrl = if (isEpisodePage) doc.findBestSeriesOrSeasonFromEpisodePage() ?: url else url

            val targetDoc = if (targetUrl != url) {
                runCatching { app.get(targetUrl, headers = safeHeaders).document }.getOrNull() ?: doc
            } else doc

            val episodes = targetDoc.extractEpisodesDeep(targetUrl)

            return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes) {
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

    // ---------------------------
    // loadLinks
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

        // 1) Discovery
        val candidates = LinkedHashSet<String>()

        if (isInternalUrl(pageUrl)) candidates.add(pageUrl)

        doc.extractServersFast().forEach { candidates.add(it) }
        doc.extractDirectMediaFromScripts().forEach { candidates.add(it) }
        doc.extractDownloadServers().forEach { candidates.add(it) }

        // data-watch expansion seeds
        doc.select("[data-watch]").asSequence()
    .map { it.attr("data-watch").trim() }
    .filter { it.isNotBlank() }
    .take(55)
    .forEach { candidates.add(canonicalUrl(it)) }

        if (candidates.isEmpty()) return false

        // 2) Expand internal once (bounded)
        val expanded = LinkedHashSet<String>()
        expanded.addAll(candidates)

        var internalUsed = 0
        for (c in candidates.toList().take(min(candidates.size, MAX_CANDIDATES))) {
            if (internalUsed >= MAX_INTERNAL_RESOLVE) break
            if (!isInternalUrl(c)) continue

            val more = withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) { resolveInternalOnce(c, pageUrl) } ?: emptyList()
            more.forEach { expanded.add(it) }
            internalUsed++
        }

        // 3) Final list
        val finals = expanded.toList().take(MAX_FINAL_LINKS)
        if (finals.isEmpty()) return false

        // 4) Emit
        var foundAny = false

        for (link in finals) {
            val u = canonicalUrl(link)
            val low = u.lowercase()

            if (low.contains(".mp4") || low.contains(".m3u8")) {
                runCatching {
                    emitDirect(u, pageUrl, callback)
                    foundAny = true
                }
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
