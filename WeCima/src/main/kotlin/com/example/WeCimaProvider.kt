package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

class WeCimaProvider : MainAPI() {

    // Note: This domain changes frequently (e.g., .show, .tube, .date).
    // If the provider stops working, check the website URL.
    override var mainUrl = "https://wecima.date"
    override var name = "WeCima"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    private val safeHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    // ---------- Performance Caps ----------
    // Limits to prevent the app from freezing on slow scraping
    private val MAX_FINAL_LINKS = 12
    private val MAX_TOTAL_CANDIDATES = 140
    private val MAX_INTERNAL_RESOLVES = 18
    private val PER_CANDIDATE_TIMEOUT_MS = 9000L

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أحدث الأفلام",
        "$mainUrl/series" to "أحدث المسلسلات",
        "$mainUrl/trending" to "الأكثر مشاهدة"
    )

    // ---------------------------
    // Poster Extractor
    // ---------------------------
    private fun extractPosterFromAnchor(a: Element): String? {
        // Attempts to find the closest container to the link, then the image within it
        val container = a.closest("article, li, .movie, .post, .item, .Thumb--GridItem, .Thumb--Grid, .poster, .Thumb")
        val img = (container ?: a).selectFirst("img") ?: return null

        // Tries standard src, then lazy-loading attributes
        val posterRaw = img.attr("src").trim().ifBlank {
            img.attr("data-src").trim().ifBlank {
                img.attr("data-original").trim().ifBlank {
                    img.attr("data-lazy-src").trim().ifBlank {
                        img.attr("srcset").trim()
                            .split(" ")
                            .firstOrNull { it.startsWith("http") || it.startsWith("/") }
                            ?.trim()
                            .orEmpty()
                    }
                }
            }
        }

        return fixUrlNull(posterRaw)
    }

    // ---------------------------
    // Helpers: Extract Candidates
    // ---------------------------
    private fun Document.extractDirectMediaFromHtml(): List<String> {
        val out = LinkedHashSet<String>()
        val html = this.html()

        // Regex to find raw m3u8 or mp4 links in the source code
        Regex("""https?://[^\s"'<>]+?\.(m3u8|mp4)(\?[^\s"'<>]+)?""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.value) }

        Regex("""source\s+src\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.groupValues[1]) }

        Regex("""file\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.groupValues[1]) }

        return out.toList()
    }

    private fun Document.extractServersSmart(): List<String> {
        val out = LinkedHashSet<String>()

        // Check data-watch attributes
        select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // Check common embed data attributes
        val attrs = listOf("data-embed-url", "data-url", "data-href", "data-embed", "data-src", "data-link")
        for (a in attrs) {
            select("[$a]").forEach {
                val s = it.attr(a).trim()
                if (s.isNotBlank()) out.add(fixUrl(s))
            }
        }

        // Check iframes
        select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // Check onclick events for URLs
        select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(fixUrl(m.value))
        }

        // Check standard anchors with keywords indicating a player/stream
        select("a[href]").forEach {
            val href = it.attr("href").trim()
            if (href.isBlank()) return@forEach
            val u = fixUrl(href)
            val l = u.lowercase()
            val useful = l.contains("watch") || l.contains("player") || l.contains("embed") ||
                    l.contains("stream") || l.contains("dood") || l.contains("mixdrop") ||
                    l.contains("ok.ru") || l.contains("uqload") || l.contains("filemoon") ||
                    l.contains("streamtape") || l.contains("vidoza") ||
                    l.contains(".m3u8") || l.contains(".mp4")
            if (useful) out.add(u)
        }

        return out.toList()
    }

    // ---------------------------
    // Redirect Helper
    // ---------------------------
    private fun Document.extractJsOrMetaRedirect(): String? {
        // Meta refresh
        selectFirst("meta[http-equiv~=(?i)refresh]")?.attr("content")?.let { c ->
            val u = c.substringAfter("url=", "").trim()
            if (u.startsWith("http")) return u
        }

        val html = this.html()
        // JS location.replace
        Regex("""location\.replace\(\s*['"]([^'"]+)['"]\s*\)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.let { if (it.startsWith("http")) return it }

        // JS window.location
        Regex("""window\.location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.let { if (it.startsWith("http")) return it }

        return null
    }

    private suspend fun getDocFollowRedirectOnce(url: String, referer: String): Pair<String, Document>? {
        val doc = runCatching {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).document
        }.getOrNull() ?: return null

        val redir = doc.extractJsOrMetaRedirect()
        if (!redir.isNullOrBlank() && redir.startsWith("http")) {
            val doc2 = runCatching {
                app.get(redir, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url)).document
            }.getOrNull()
            if (doc2 != null) return redir to doc2
        }
        return url to doc
    }

    // ---------------------------
    // SLP_WATCH Base64 URL-safe Decode
    // ---------------------------
    private fun extractSlpWatchParam(url: String): String? {
        val u = url.trim()
        if (!u.contains("slp_watch=")) return null
        return u.substringAfter("slp_watch=").substringBefore("&").trim().ifBlank { null }
    }

    private fun decodeSlpWatchUrl(encoded: String): String? {
        val raw = encoded.trim()
        if (raw.isBlank()) return null

        // Standardize Base64 string
        val normalized = raw
            .replace('-', '+')
            .replace('_', '/')
            .let { s ->
                val mod = s.length % 4
                if (mod == 0) s else s + "=".repeat(4 - mod)
            }

        return try {
            val bytes = Base64.getDecoder().decode(normalized)
            val decoded = String(bytes, Charsets.UTF_8).trim()
            if (decoded.startsWith("http")) decoded else null
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun expandDataWatchLink(dataWatchUrl: String, referer: String): List<String> {
        val out = LinkedHashSet<String>()
        val fixed = fixUrl(dataWatchUrl).trim()
        if (fixed.isBlank()) return emptyList()

        // 1. Check if it is a Base64 encoded slp_watch link
        val slp = extractSlpWatchParam(fixed)
        val decodedUrl = slp?.let { decodeSlpWatchUrl(it) }
        val target = decodedUrl ?: fixed

        out.add(target)

        // 2. Visit the target and extract video links/servers
        runCatching {
            val (finalUrl, doc) = getDocFollowRedirectOnce(target, referer) ?: return@runCatching
            out.add(finalUrl)
            doc.extractServersSmart().forEach { out.add(it) }
            doc.extractDirectMediaFromHtml().forEach { out.add(it) }
        }

        return out.toList()
    }

    // ---------------------------
    // Internal Logic
    // ---------------------------
    private fun isInternal(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("wecima") || url.startsWith(mainUrl)
    }

    private fun looksLikeBadLink(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("trailer") || u.contains("preview") || u.contains("promo") || u.contains("ads")
    }

    private fun hostLabel(url: String): String =
        runCatching { URI(url).host ?: "direct" }.getOrNull() ?: "direct"

    private fun isDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.contains(".mp4")
    }

    private suspend fun emitDirectMedia(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("http")) return false
        if (!isDirectMedia(clean)) return false
        if (looksLikeBadLink(clean)) return false

        val l = clean.lowercase()
        val type = if (l.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val host = hostLabel(clean)

        callback(
            newExtractorLink(
                source = name,
                name = "Direct ($host)",
                url = clean,
                type = type
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            }
        )
        return true
    }

    private suspend fun resolveInternalOnce(url: String, referer: String): List<String> {
        val out = LinkedHashSet<String>()
        val fixed = url.trim()
        if (fixed.isBlank() || !fixed.startsWith("http")) return emptyList()

        out.add(fixed)

        val (finalUrl, doc) = getDocFollowRedirectOnce(fixed, referer) ?: return out.toList()
        out.add(finalUrl)

        doc.extractServersSmart().forEach { out.add(it) }
        doc.extractDirectMediaFromHtml().forEach { out.add(it) }

        // Expand some data-watch attributes if found
        doc.select("[data-watch]")
            .mapNotNull { it.attr("data-watch").trim().ifBlank { null } }
            .take(12)
            .forEach { dw ->
                runCatching { expandDataWatchLink(dw, finalUrl).forEach { out.add(it) } }
            }

        return out.toList()
    }

    // ---------------------------
    // Main Page
    // ---------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = safeHeaders).document

        val items = doc.select("h3 a, h2 a, a[href*=\"/watch/\"]").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = extractPosterFromAnchor(a)
            val lower = (title + " " + link).lowercase()
            val tvType = if (lower.contains("مسلسل") || lower.contains("episode") || lower.contains("season"))
                TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, link, tvType) { this.posterUrl = poster }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    // ---------------------------
    // Search (GET + Fallback Crawl)
    // ---------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val qRaw = query.trim()
        if (qRaw.isBlank()) return emptyList()

        // Seed cookies/session
        runCatching { app.get("$mainUrl/", headers = safeHeaders) }

        // Try standard site search
        val enc = URLEncoder.encode(qRaw, "UTF-8")
        val url = "$mainUrl/search?keyword=$enc"

        val doc = runCatching { app.get(url, headers = safeHeaders).document }.getOrNull()
        val results = doc?.select("a[href]")?.mapNotNull { a ->
            val title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val link = fixUrl(a.attr("href").trim())
            if (!link.startsWith("http")) return@mapNotNull null
            if (!isInternal(link)) return@mapNotNull null

            val poster = extractPosterFromAnchor(a)
            val lower = (title + " " + link).lowercase()
            val tvType = if (lower.contains("مسلسل") || lower.contains("episode") || lower.contains("season"))
                TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, link, tvType) { this.posterUrl = poster }
        }?.distinctBy { it.url }.orEmpty()

        if (results.size >= 5) return results

        // Fallback: manually crawl main pages if search fails or returns few results
        val needle = qRaw.lowercase()
        val out = LinkedHashSet<SearchResponse>()
        val pages = listOf("$mainUrl/movies", "$mainUrl/series", "$mainUrl/trending")

        suspend fun crawlPage(p: String) {
            val d = runCatching { app.get(p, headers = safeHeaders).document }.getOrNull() ?: return
            val anchors = d.select("h3 a, h2 a, a[href]")
            for (a in anchors) {
                if (out.size >= 30) return
                val title = a.text().trim()
                if (title.isBlank()) continue
                val link = fixUrl(a.attr("href").trim())
                val hay = (title + " " + link).lowercase()
                if (!hay.contains(needle)) continue
                if (!link.startsWith("http")) continue
                if (!isInternal(link)) continue

                val poster = extractPosterFromAnchor(a)
                val tvType = if (hay.contains("مسلسل") || hay.contains("episode") || hay.contains("season"))
                    TvType.TvSeries else TvType.Movie

                out.add(newMovieSearchResponse(title, link, tvType) { this.posterUrl = poster })
            }
        }

        for (p in pages) crawlPage(p)
        return out.toList()
    }

    // ---------------------------
    // Load (Movie vs Series)
    // ---------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = safeHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.let { fixUrlNull(it) }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // Detect episodes
        val eps = doc.select("a[href*=\"watch\"], a[href*=\"episode\"], a[href*=\"/ep/\"]")
            .mapNotNull { a ->
                val text = a.text().trim()
                val link = fixUrl(a.attr("href").trim())
                if (text.isBlank() || link.isBlank()) return@mapNotNull null

                val looksEpisode = text.contains("الحلقة") ||
                        Regex("""\bEpisode\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

                if (!looksEpisode) return@mapNotNull null

                val epNum = Regex("""الحلقة\s+(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(link) {
                    this.name = text
                    this.season = 1
                    this.episode = epNum
                }
            }.distinctBy { it.data }

        if (eps.size >= 2) {
            return newTvSeriesLoadResponse(
                name = title.ifBlank { "WeCima" },
                url = url,
                type = TvType.TvSeries,
                episodes = eps
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(
            name = title.ifBlank { "WeCima" },
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------
    // LoadLinks (Fast + Multi)
    // ---------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val watchUrl = data.trim()
        if (watchUrl.isBlank()) return false

        var emittedCount = 0
        var internalResolveCount = 0
        val visited = HashSet<String>()

        val cb: (ExtractorLink) -> Unit = {
            emittedCount++
            callback(it)
        }

        // Recursive-like function to process a candidate URL
        suspend fun tryCandidate(u: String, referer: String) {
            if (emittedCount >= MAX_FINAL_LINKS) return
            val url = u.trim()
            if (url.isBlank() || !url.startsWith("http")) return
            if (looksLikeBadLink(url)) return

            val key = (url + "|" + referer).lowercase()
            if (!visited.add(key)) return

            // direct
            if (emitDirectMedia(url, referer, cb)) return

            // external => extractor
            if (!isInternal(url)) {
                runCatching { loadExtractor(url, referer, subtitleCallback, cb) }
                return
            }

            // internal resolve bounded
            if (internalResolveCount >= MAX_INTERNAL_RESOLVES) return
            internalResolveCount++

            val expanded = resolveInternalOnce(url, referer)
            expanded.distinct().take(50).forEach { x ->
                if (emittedCount >= MAX_FINAL_LINKS) return
                if (looksLikeBadLink(x)) return@forEach
                if (emitDirectMedia(x, url, cb)) return@forEach
                if (x.startsWith("http") && !isInternal(x)) {
                    runCatching { loadExtractor(x, url, subtitleCallback, cb) }
                }
            }
        }

        fun addCandidates(doc: Document, out: LinkedHashSet<String>) {
            doc.extractServersSmart().forEach { out.add(it) }
            doc.extractDirectMediaFromHtml().forEach { out.add(it) }

            doc.select("[data-watch]")
                .mapNotNull { it.attr("data-watch").trim().ifBlank { null } }
                .take(15)
                .forEach { out.add(fixUrl(it)) }
        }

        val candidates = LinkedHashSet<String>()

        // 1) watch page
        runCatching {
            val d = app.get(watchUrl, headers = safeHeaders).document
            addCandidates(d, candidates)
        }

        // 2) sometimes there is an internal player or download page linked
        // try to discover extra internal pages:
        val internalMore = candidates.filter { isInternal(it) }.take(8)
        for (x in internalMore) {
            runCatching {
                val d = app.get(x, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)).document
                addCandidates(d, candidates)
            }
        }

        // expand data-watch (limited)
        val dataWatch = candidates.filter { it.contains("data-watch", ignoreCase = true) || it.contains("slp_watch=", ignoreCase = true) }
        // (the above might be empty; we also expand anything that has slp_watch)
        candidates.filter { it.contains("slp_watch=", ignoreCase = true) }
            .take(12)
            .forEach { dw ->
                runCatching { expandDataWatchLink(dw, watchUrl).forEach { candidates.add(it) } }
            }

        // process candidates (bounded + timeout)
        val finalList = candidates.toList().distinct().take(MAX_TOTAL_CANDIDATES)
        for (link in finalList) {
            if (emittedCount >= MAX_FINAL_LINKS) break
            withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) {
                tryCandidate(link, watchUrl)
            }
        }

        return emittedCount > 0
    }
}
