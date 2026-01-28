package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

class CimaLightProvider : MainAPI() {

    override var mainUrl = "https://w.cimalight.co"
    override var name = "CimaLight"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    private val safeHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    // ✅ Performance limits (IMPORTANT)
    private val MAX_FINAL_LINKS = 12           // stop after finding enough playable links
    private val MAX_TOTAL_CANDIDATES = 120     // hard cap overall candidates processed
    private val MAX_INTERNAL_RESOLVES = 18     // hard cap internal page resolves
    private val PER_CANDIDATE_TIMEOUT_MS = 9000L

    // ✅ NEW: elif.news is expensive + can 403
    private val MAX_ELIF_RESOLVES = 4
    private val ELIF_TIMEOUT_MS = 9000L

    override val mainPage = mainPageOf(
        "$mainUrl/movies.php" to "أحدث الأفلام",
        "$mainUrl/main15" to "جديد الموقع",
        "$mainUrl/most.php" to "الأكثر مشاهدة"
    )

    // ---------------------------
    // Poster extractor
    // ---------------------------
    private fun extractPosterFromAnchor(a: Element): String? {
        val container = a.closest("article, li, .movie, .post, .item, .Thumb--GridItem, .Thumb--Grid")
        val img = (container ?: a).selectFirst("img") ?: return null

        val posterRaw =
            img.attr("src").trim().ifBlank {
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
    // Extract helpers
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

    private fun Document.extractServersSmart(): List<String> {
        val out = LinkedHashSet<String>()

        // 1) data-watch
        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // 2) data-* embeds
        val attrs = listOf("data-embed-url", "data-url", "data-href", "data-embed", "data-src", "data-link")
        for (a in attrs) {
            this.select("[$a]").forEach {
                val s = it.attr(a).trim()
                if (s.isNotBlank()) out.add(fixUrl(s))
            }
        }

        // 3) iframes
        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // 4) onclick URLs
        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(fixUrl(m.value))
        }

        // 5) anchors (smart)
        this.select("a[href]").forEach {
            val hrefRaw = it.attr("href").trim()
            if (hrefRaw.isBlank()) return@forEach

            val href = fixUrl(hrefRaw)
            val h = href.lowercase()

            val looksUseful =
                h.contains(".m3u8") ||
                    h.contains(".mp4") ||
                    h.contains("multiup") ||
                    h.contains("embed") ||
                    h.contains("player") ||
                    h.contains("stream") ||
                    h.contains("ok.ru") ||
                    h.contains("uqload") ||
                    h.contains("vidoza") ||
                    h.contains("filemoon") ||
                    h.contains("dood") ||
                    h.contains("mixdrop") ||
                    h.contains("streamtape") ||
                    h.contains("cimalight") ||
                    h.contains("elif.news")

            if (looksUseful) out.add(href)
        }

        return out.toList()
    }

    // ---------------------------
    // JS/meta redirect helpers
    // ---------------------------
    private fun Document.extractJsOrMetaRedirect(): String? {
        this.selectFirst("meta[http-equiv~=(?i)refresh]")?.attr("content")?.let { c ->
            val u = c.substringAfter("url=", "").trim()
            if (u.startsWith("http")) return u
        }

        val html = this.html()

        Regex("""redirectUrl\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.let { if (it.startsWith("http")) return it }

        Regex("""location\.replace\(\s*['"]([^'"]+)['"]\s*\)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.let { if (it.startsWith("http")) return it }

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
    // slp_watch helpers
    // ---------------------------
    private fun extractSlpWatchParam(url: String): String? {
        val u = url.trim()
        if (!u.contains("slp_watch=")) return null
        return try {
            val q = u.substringAfter("slp_watch=", "")
            if (q.isBlank()) null else q.substringBefore("&").trim()
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeSlpWatchUrl(encoded: String): String? {
        val raw = encoded.trim()
        if (raw.isBlank()) return null

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

        val slp = extractSlpWatchParam(fixed)
        val decodedUrl = slp?.let { decodeSlpWatchUrl(it) }
        val target = decodedUrl ?: fixed

        out.add(target)

        runCatching {
            val (finalUrl, doc) = getDocFollowRedirectOnce(target, referer) ?: return@runCatching
            out.add(finalUrl)
            doc.extractServersSmart().forEach { out.add(it) }
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }
        }

        return out.toList()
    }

    // ---------------------------
    // elif.news resolver
    // ---------------------------
    private fun isElif(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("elif.news")
    }

    private suspend fun resolveElifOnce(url: String, referer: String): List<String> {
        val out = LinkedHashSet<String>()
        val fixed = url.trim()
        if (fixed.isBlank() || !fixed.startsWith("http")) return emptyList()

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        val doc = runCatching { app.get(fixed, headers = headers).document }.getOrNull() ?: return emptyList()

        doc.extractDirectMediaFromScripts().forEach { out.add(it) }
        doc.select("iframe[src]").forEach { out.add(fixUrl(it.attr("src").trim())) }
        doc.extractServersSmart().forEach { out.add(it) }

        return out.toList()
    }

    // ---------------------------
    // Internal resolver (bounded)
    // ---------------------------
    private fun isInternal(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("cimalight") || url.startsWith(mainUrl)
    }

    private fun looksLikeInternalPlayer(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("player") || u.contains("embed") || u.contains("play") || u.contains("watch")
    }

    private suspend fun resolveInternalCimaOnce(url: String, referer: String): List<String> {
        val fixed = url.trim()
        if (fixed.isBlank() || !fixed.startsWith("http")) return emptyList()
        if (!isInternal(fixed)) return listOf(fixed)
        if (!looksLikeInternalPlayer(fixed)) return listOf(fixed)

        val out = LinkedHashSet<String>()
        out.add(fixed)

        runCatching {
            val (finalUrl, doc) = getDocFollowRedirectOnce(fixed, referer) ?: return@runCatching
            out.add(finalUrl)

            doc.extractServersSmart().forEach { out.add(it) }
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }

            doc.select("[data-watch]")
                .mapNotNull { it.attr("data-watch")?.trim() }
                .filter { it.isNotBlank() }
                .take(10)
                .forEach { dw ->
                    runCatching { expandDataWatchLink(dw, finalUrl).forEach { out.add(it) } }
                }
        }

        return out.toList()
    }

    // ---------------------------
    // Utils
    // ---------------------------
    private fun hostLabel(url: String): String =
        runCatching { URI(url).host ?: "direct" }.getOrNull() ?: "direct"

    private fun looksLikeBadLink(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("trailer") || u.contains("preview") || u.contains("promo") || u.contains("sample") || u.contains("ads")
    }

    private fun isDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.contains(".mp4")
    }

    private suspend fun emitDirectMedia(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = url.trim()
        if (clean.isBlank() || !clean.startsWith("http")) return false
        if (looksLikeBadLink(clean)) return false
        if (!isDirectMedia(clean)) return false

        val l = clean.lowercase()
        val isM3u8 = l.contains(".m3u8")
        val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            }
        )
        return true
    }

    // ---------------------------
    // Main page
    // ---------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = safeHeaders).document

        val items = document.select("h3 a, h2 a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = extractPosterFromAnchor(a)
            val tvType = when {
                title.contains("مسلسل") || title.contains("حلق") || title.contains("موسم") -> TvType.TvSeries
                else -> TvType.Movie
            }

            newMovieSearchResponse(title, link, tvType) { this.posterUrl = poster }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    // ---------------------------
    // Search (FIXED)
    // ---------------------------
override suspend fun search(query: String): List<SearchResponse> {
    val q = query.trim()
    if (q.isBlank()) return emptyList()

    val headers = safeHeaders + mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
    )

    fun parseSearchDoc(doc: Document): List<SearchResponse> {
        val anchors = doc.select("a[href]")
        return anchors.mapNotNull { a ->
            val title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null

            val hrefRaw = a.attr("href").trim()
            if (hrefRaw.isBlank()) return@mapNotNull null

            val link = fixUrl(hrefRaw)
            if (!link.startsWith("http")) return@mapNotNull null

            val internal = link.startsWith(mainUrl) || link.contains("cimalight", ignoreCase = true)
            if (!internal) return@mapNotNull null

            // Important: keep only “content-like” pages to avoid random nav/footer links
            val l = link.lowercase()
            val looksLikeContent =
                l.contains("watch.php?vid=") ||
                l.contains("/movie") ||
                l.contains("/series") ||
                l.contains("/show") ||
                l.contains("/video") ||
                l.contains("details") ||
                l.contains("film") ||
                l.contains("episode") ||
                l.contains("season")

            if (!looksLikeContent) return@mapNotNull null

            val lower = (title + " " + link).lowercase()
            val tvType = if (
                lower.contains("مسلسل") || lower.contains("الحلقة") ||
                lower.contains("season") || lower.contains("episode")
            ) TvType.TvSeries else TvType.Movie

            val poster = extractPosterFromAnchor(a)
            newMovieSearchResponse(title, link, tvType) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    // ---- 0) Seed cookies/session (helps some WAF setups)
    runCatching { app.get("$mainUrl/main15", headers = headers) }

    // ---- 1) Try GET search
    val getDoc = runCatching {
        val enc = URLEncoder.encode(q, "UTF-8")
        app.get("$mainUrl/search.php?keywords=$enc&video-id mocking=", headers = headers).document
    }.getOrNull()

    val getResults = getDoc?.let { parseSearchDoc(it) }.orEmpty()
    if (getResults.isNotEmpty()) return getResults

    // ---- 2) Try POST search (many PHP sites require POST)
    val postDoc = runCatching {
        app.post(
            "$mainUrl/search.php",
            data = mapOf(
                "keywords" to q,
                "video-id" to ""
            ),
            headers = headers
        ).document
    }.getOrNull()

    val postResults = postDoc?.let { parseSearchDoc(it) }.orEmpty()
    if (postResults.isNotEmpty()) return postResults

    // ---- 3) Fallback search (works even if search.php is blocked)
    // Scrape a few pages and filter titles locally
    val fallbackPages = listOf(
        "$mainUrl/movies.php",
        "$mainUrl/main15",
        "$mainUrl/most.php"
    )

    val needle = q.lowercase()
    val fallbackOut = ArrayList<SearchResponse>()

    for (p in fallbackPages) {
        val doc = runCatching { app.get(p, headers = headers).document }.getOrNull() ?: continue

        // reuse your main-page-ish selector style but broader
        val anchors = doc.select("h3 a, h2 a, .Thumb--GridItem a, .Thumb--Grid a, a[href*=\"watch.php?vid=\"]")
        for (a in anchors) {
            val title = a.text().trim()
            if (title.isBlank()) continue
            if (!title.lowercase().contains(needle)) continue

            val link = fixUrl(a.attr("href").trim())
            if (!link.startsWith("http")) continue

            val poster = extractPosterFromAnchor(a)
            val lower = (title + " " + link).lowercase()
            val tvType = if (
                lower.contains("مسلسل") || lower.contains("الحلقة") ||
                lower.contains("season") || lower.contains("episode")
            ) TvType.TvSeries else TvType.Movie

            fallbackOut.add(newMovieSearchResponse(title, link, tvType) { this.posterUrl = poster })
        }

        // stop early if we already found enough
        if (fallbackOut.size >= 25) break
    }

    return fallbackOut.distinctBy { it.url }
}

    // ---------------------------
    // Load (Movie vs Series)
    // ---------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.trim()
            ?.let { fixUrlNull(it) }
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val episodeAnchors = document.select("a[href*=watch.php?vid=]")
        val episodeLinks = episodeAnchors.mapNotNull { a ->
            val text = a.text().trim()
            val eUrl = fixUrl(a.attr("href").trim())
            if (eUrl.isBlank()) return@mapNotNull null

            val looksLikeEpisode = text.contains("الحلقة") ||
                Regex("""\bEpisode\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

            if (!looksLikeEpisode) return@mapNotNull null
            Pair(text, eUrl)
        }.distinctBy { it.second }

        if (episodeLinks.size >= 2) {
            val episodes = episodeLinks.map { (text, eUrl) ->
                val epNum = Regex("""الحلقة\s+(\d+)""")
                    .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(eUrl) {
                    this.name = text.ifBlank { "Episode" }
                    this.season = 1
                    this.episode = epNum
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(
                name = title.ifBlank { "CimaLight" },
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(
            name = title.ifBlank { "CimaLight" },
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------
    // LoadLinks (FAST + MULTI)
    // ---------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var watchUrl = data.trim()

        // If user gave details page, locate watch.php?vid=...
        var vid = Regex("""vid=([A-Za-z0-9]+)""").find(watchUrl)?.groupValues?.getOrNull(1)
        if (vid.isNullOrBlank()) {
            val doc = runCatching {
                app.get(watchUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).document
            }.getOrNull()

            val extractedWatch = doc?.selectFirst("""a[href*="watch.php?vid="]""")
                ?.attr("href")?.trim()?.let { fixUrl(it) }

            if (!extractedWatch.isNullOrBlank()) {
                watchUrl = extractedWatch
                vid = Regex("""vid=([A-Za-z0-9]+)""").find(watchUrl)?.groupValues?.getOrNull(1)
            }
        }
        if (vid.isNullOrBlank()) return false

        var emittedCount = 0
        var internalResolveCount = 0
        var elifResolveCount = 0
        val visited = HashSet<String>()

        val cb: (ExtractorLink) -> Unit = {
            emittedCount++
            callback(it)
        }

        suspend fun tryExtractor(u: String, referer: String) {
            if (emittedCount >= MAX_FINAL_LINKS) return
            val url = u.trim()
            if (url.isBlank() || !url.startsWith("http")) return
            if (looksLikeBadLink(url)) return

            val key = (url + "|" + referer).lowercase()
            if (!visited.add(key)) return

            if (emitDirectMedia(url, referer, cb)) return

            // elif.news gateway
            if (isElif(url)) {
                if (elifResolveCount >= MAX_ELIF_RESOLVES) return
                elifResolveCount++

                withTimeoutOrNull(ELIF_TIMEOUT_MS) {
                    val realLinks = resolveElifOnce(url, referer)
                        .distinct()
                        .filter { it.startsWith("http") }
                        .filter { !looksLikeBadLink(it) }
                        .take(30)

                    for (x in realLinks) {
                        if (emittedCount >= MAX_FINAL_LINKS) return@withTimeoutOrNull
                        if (emitDirectMedia(x, url, cb)) continue
                        if (!isInternal(x)) {
                            runCatching { loadExtractor(x, url, subtitleCallback, cb) }
                        } else {
                            if (internalResolveCount < MAX_INTERNAL_RESOLVES && looksLikeInternalPlayer(x)) {
                                internalResolveCount++
                                val expanded = resolveInternalCimaOnce(x, url)
                                expanded.distinct().take(25).forEach { z ->
                                    if (emittedCount >= MAX_FINAL_LINKS) return@withTimeoutOrNull
                                    if (emitDirectMedia(z, x, cb)) return@forEach
                                    if (z.startsWith("http") && !isInternal(z)) {
                                        runCatching { loadExtractor(z, x, subtitleCallback, cb) }
                                    }
                                }
                            }
                        }
                    }
                }
                return
            }

            if (!isInternal(url)) {
                runCatching { loadExtractor(url, referer, subtitleCallback, cb) }
                return
            }

            if (internalResolveCount >= MAX_INTERNAL_RESOLVES) return
            if (!looksLikeInternalPlayer(url)) return

            internalResolveCount++
            val expanded = resolveInternalCimaOnce(url, referer)
            expanded.distinct().take(40).forEach { x ->
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
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }

            doc.select("[data-watch]")
                .mapNotNull { it.attr("data-watch")?.trim() }
                .filter { it.isNotBlank() }
                .take(10)
                .forEach { out.add(fixUrl(it)) }

            doc.select("a[href*=\"elif.news\"]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isNotBlank()) out.add(fixUrl(href))
            }
        }

        val candidates = LinkedHashSet<String>()

        // watch page FIRST
        runCatching {
            val watchDoc = app.get(watchUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).document
            addCandidates(watchDoc, candidates)
        }

        // play.php
        val playUrl = "$mainUrl/play.php?vid=$vid"
        runCatching {
            val playDoc = app.get(playUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)).document
            addCandidates(playDoc, candidates)
        }

        // downloads.php
        val downloadsUrl = "$mainUrl/downloads.php?vid=$vid"
        runCatching {
            val dlDoc = app.get(downloadsUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)).document
            addCandidates(dlDoc, candidates)

            dlDoc.select("a[href]")
                .mapNotNull { fixUrlNull(it.attr("href").trim()) }
                .filter { it.startsWith("http") }
                .filter { !it.contains(mainUrl, ignoreCase = true) }
                .distinct()
                .take(35)
                .forEach { candidates.add(it) }
        }

        val finalList = candidates.toList().distinct().take(MAX_TOTAL_CANDIDATES)

        for (link in finalList) {
            if (emittedCount >= MAX_FINAL_LINKS) break
            withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) {
                tryExtractor(link, watchUrl)
            }
        }

        return emittedCount > 0
    }
}
