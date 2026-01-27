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

    // ✅ Performance limits
    private val MAX_FINAL_LINKS = 12
    private val MAX_TOTAL_CANDIDATES = 120   // keep collection large, but...
    private val MAX_TRIES = 35               // ✅ hard cap of actual attempts (big speed win)
    private val MAX_INTERNAL_RESOLVES = 10
    private val PER_CANDIDATE_TIMEOUT_MS = 8500L

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
                    h.contains("cimalight")

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

    /**
     * ✅ FAST: decode slp_watch without any extra network requests.
     * If url contains slp_watch, returns decoded real url. Otherwise null.
     */
    private fun maybeDecodeSlpWatchFast(url: String): String? {
        val slp = extractSlpWatchParam(url) ?: return null
        return decodeSlpWatchUrl(slp)
    }

    // ---------------------------
    // Internal resolver (LIGHT)
    // ---------------------------
    private fun isInternal(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("cimalight") || url.startsWith(mainUrl)
    }

    private fun looksLikeInternalPlayer(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("player") || u.contains("embed") || u.contains("play") || u.contains("watch")
    }

    private suspend fun resolveInternalCimaLightOnce(url: String, referer: String): List<String> {
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

            // keep extremely small
            doc.select("[data-watch]")
                .mapNotNull { it.attr("data-watch")?.trim() }
                .filter { it.isNotBlank() }
                .take(2)
                .forEach { out.add(fixUrl(it)) }
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

    private fun priorityScore(url: String): Int {
        val u = url.lowercase()
        return when {
            u.contains("slp_watch=") -> 0          // ✅ decode-first candidates at top
            u.contains(".m3u8") -> 1
            u.contains(".mp4") -> 2
            u.contains("voe") -> 3
            u.contains("streamwish") || u.contains("wish") -> 4
            u.contains("mixdrop") -> 5
            u.contains("filemoon") -> 6
            u.contains("dood") -> 7
            u.contains("streamtape") -> 8
            isInternal(url) && looksLikeInternalPlayer(url) -> 50
            else -> 20
        }
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
    // Search
    // ---------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/search.php?keywords=$q&video-id="
        val document = app.get(url, headers = safeHeaders).document

        val anchors = document.select("h3 a, h2 a, .Thumb--GridItem a, .Thumb--Grid a")
        return anchors.mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            if (title.isBlank() || link.isBlank()) return@mapNotNull null
            if (!link.contains("cimalight", ignoreCase = true)) return@mapNotNull null

            val poster = extractPosterFromAnchor(a)
            newMovieSearchResponse(title, link, TvType.Movie) { this.posterUrl = poster }
        }.distinctBy { it.url }
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
    // LoadLinks (DECODE-FIRST + HARD TRY LIMIT)
    // ---------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var watchUrl = data.trim()

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
        val visited = HashSet<String>()

        val cb: (ExtractorLink) -> Unit = {
            emittedCount++
            callback(it)
        }

        fun addCandidates(doc: Document, out: LinkedHashSet<String>) {
            doc.extractServersSmart().forEach { out.add(it) }
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }

            // collect data-watch as-is (we decode-first later)
            doc.select("[data-watch]")
                .mapNotNull { it.attr("data-watch")?.trim() }
                .filter { it.isNotBlank() }
                .take(16)
                .forEach { out.add(fixUrl(it)) }
        }

        suspend fun tryOneCandidate(u: String, referer: String) {
            if (emittedCount >= MAX_FINAL_LINKS) return
            val url = u.trim()
            if (url.isBlank() || !url.startsWith("http")) return
            if (looksLikeBadLink(url)) return

            // ✅ DECODE-FIRST
            val decoded = maybeDecodeSlpWatchFast(url)
            if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                // try decoded first (no extra request)
                val keyDec = (decoded + "|" + referer).lowercase()
                if (visited.add(keyDec)) {
                    if (emitDirectMedia(decoded, referer, cb)) return
                    if (!isInternal(decoded)) {
                        runCatching { loadExtractor(decoded, referer, subtitleCallback, cb) }
                        if (emittedCount >= MAX_FINAL_LINKS) return
                    }
                }
            }

            val key = (url + "|" + referer).lowercase()
            if (!visited.add(key)) return

            if (emitDirectMedia(url, referer, cb)) return

            // external
            if (!isInternal(url)) {
                runCatching { loadExtractor(url, referer, subtitleCallback, cb) }
                return
            }

            // internal resolve (light + bounded)
            if (!looksLikeInternalPlayer(url)) return
            if (internalResolveCount >= MAX_INTERNAL_RESOLVES) return
            internalResolveCount++

            val expanded = resolveInternalCimaLightOnce(url, referer)
                .distinct()
                .filter { it.startsWith("http") }
                .filter { !looksLikeBadLink(it) }
                .take(25)

            for (x in expanded) {
                if (emittedCount >= MAX_FINAL_LINKS) return
                if (emitDirectMedia(x, url, cb)) continue
                if (!isInternal(x)) {
                    runCatching { loadExtractor(x, url, subtitleCallback, cb) }
                }
            }
        }

        val candidates = LinkedHashSet<String>()

        val playUrl = "$mainUrl/play.php?vid=$vid"
        runCatching {
            val playDoc = app.get(playUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)).document
            addCandidates(playDoc, candidates)
        }

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

        // ✅ aggregate: dedupe + priority + hard try limit
        val finalList = candidates
            .asSequence()
            .distinct()
            .filter { it.startsWith("http") }
            .filter { !looksLikeBadLink(it) }
            .sortedWith(compareBy<String> { priorityScore(it) }.thenBy { it.length })
            .take(MAX_TOTAL_CANDIDATES)
            .toList()

        var tries = 0
        for (link in finalList) {
            if (emittedCount >= MAX_FINAL_LINKS) break
            if (tries >= MAX_TRIES) break
            tries++

            withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MS) {
                tryOneCandidate(link, playUrl)
            }
        }

        return emittedCount > 0
    }
}
