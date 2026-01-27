package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
    // WeCima-style helpers
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

        // 1) data-watch
        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // 2) common embed attributes
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

        // 4) onclick urls
        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(fixUrl(m.value))
        }

        // 5) anchors
        this.select("a[href]").forEach {
            val s = it.attr("href").trim()
            if (s.startsWith("http")) out.add(fixUrl(s))
        }

        return out.toList()
    }

    // ---------------------------
    // Redirect helpers (JS/meta)
    // ---------------------------

    private fun Document.extractJsOrMetaRedirect(): String? {
        // meta refresh
        this.selectFirst("meta[http-equiv~=(?i)refresh]")?.attr("content")?.let { c ->
            val u = c.substringAfter("url=", "").trim()
            if (u.startsWith("http")) return u
        }

        val html = this.html()

        // redirectUrl='https://...';
        Regex("""redirectUrl\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.let { if (it.startsWith("http")) return it }

        // location.replace("https://...")
        Regex("""location\.replace\(\s*['"]([^'"]+)['"]\s*\)""", RegexOption.IGNORE_CASE)
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

    private fun needsOneStepFollow(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("cimalight") || u.contains("elif.news") || u.contains("alhakekanet.net")
    }

    // ---------------------------
    // slp_watch decode
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
            val pair = getDocFollowRedirectOnce(target, referer) ?: return@runCatching
            val finalUrl = pair.first
            val doc = pair.second
            out.add(finalUrl)
            doc.extractServersFast().forEach { out.add(it) }
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }
        }

        return out.toList()
    }

    private suspend fun scrapeOneStepServers(url: String, referer: String): List<String> {
        val out = LinkedHashSet<String>()
        val fixed = fixUrl(url).trim()
        if (fixed.isBlank()) return emptyList()

        val pair = getDocFollowRedirectOnce(fixed, referer) ?: return emptyList()
        val finalUrl = pair.first
        val doc = pair.second

        out.add(finalUrl)
        doc.extractServersFast().forEach { out.add(it) }
        doc.extractDirectMediaFromScripts().forEach { out.add(it) }

        val dw = doc.select("[data-watch]")
            .mapNotNull { it.attr("data-watch")?.trim() }
            .filter { it.isNotBlank() }
            .take(25)

        dw.forEach { link ->
            runCatching { expandDataWatchLink(link, finalUrl).forEach { out.add(it) } }
        }

        return out.toList()
    }

    private fun hostLabel(url: String): String {
        return runCatching { URI(url).host ?: "direct" }.getOrNull() ?: "direct"
    }

    private suspend fun emitDirectMedia(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val l = url.lowercase()
        val isM3u8 = l.contains(".m3u8")
        val isMp4 = l.contains(".mp4")
        if (!isM3u8 && !isMp4) return false

        val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val host = hostLabel(url)

        val link = newExtractorLink(
            source = name,
            name = "Direct ($host)",
            url = url,
            type = type
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer
            )
        }

        callback.invoke(link)
        return true
    }

    // ---------------------------
    // Main page / Search
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = safeHeaders).document

        val items = document.select("h3 a, h2 a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = extractPosterFromAnchor(a)
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/search.php?keywords=$q&video-id="

        val document = app.get(url, headers = safeHeaders).document

        val anchors = document.select("h3 a, h2 a, .Thumb--GridItem a, .Thumb--Grid a")
        return anchors.mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = extractPosterFromAnchor(a)
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    // ---------------------------
    // Load (detect Movie vs Series)
    // ---------------------------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.trim()
            ?.let { fixUrlNull(it) }
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // Episode links are usually inside "المواسم و الحلقات" and contain watch.php?vid=
        val episodeAnchors = document.select("a[href*=watch.php?vid=]")
            .filter { it.text().contains("الحلقة") }

        if (episodeAnchors.size >= 2) {
            // Treat as series page
val episodes = episodeAnchors.mapNotNull { a ->
    val eUrl = fixUrl(a.attr("href").trim())
    if (eUrl.isBlank()) return@mapNotNull null

    val t = a.text().trim()
    val epNum = Regex("""الحلقة\s+(\d+)""")
        .find(t)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    newEpisode(
        data = eUrl,
        name = t.ifBlank { "Episode" },
        season = 1,
        episode = epNum
    )
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

        // Otherwise treat as a single movie/episode page (watch.php?vid=...)
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
    // LoadLinks (play + downloads, many servers)
    // ---------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val watchUrl = data.trim()

        // Accept direct watch.php?vid=...
        val vid = Regex("""vid=([A-Za-z0-9]+)""")
            .find(watchUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        // emitted=true only if we actually sent at least one playable link
        var emitted = false
        val cb: (ExtractorLink) -> Unit = {
            emitted = true
            callback(it)
        }

        suspend fun processCandidate(url: String, referer: String) {
            val u = url.trim()
            if (u.isBlank() || !u.startsWith("http")) return

            // direct media first
            if (emitDirectMedia(u, referer, cb)) return

            val lower = u.lowercase()

            // multiup mirrors
            if (lower.contains("multiup.io")) {
                runCatching {
                    val doc = app.get(u, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).document
                    val mirrors = doc.select("a[href]")
                        .mapNotNull { fixUrlNull(it.attr("href").trim()) }
                        .filter { it.startsWith("http") }
                        .filter { !it.contains("multiup.io", ignoreCase = true) }
                        .distinct()
                        .take(25)

                    mirrors.forEach { m ->
                        runCatching {
                            if (!emitDirectMedia(m, u, cb)) {
                                loadExtractor(m, u, subtitleCallback, cb)
                            }
                        }
                    }
                }
                return
            }

            // follow internal/gateway pages one step (elif/alhakekanet/cimalight)
            if (needsOneStepFollow(u)) {
                val inner = runCatching { scrapeOneStepServers(u, referer) }.getOrNull().orEmpty()
                inner.distinct().take(120).forEach { x ->
                    if (emitDirectMedia(x, u, cb)) return@forEach
                    if (x.startsWith("http") && !x.lowercase().contains("cimalight")) {
                        runCatching { loadExtractor(x, u, subtitleCallback, cb) }
                    }
                }
                return
            }

            // normal external extractor
            runCatching { loadExtractor(u, referer, subtitleCallback, cb) }
        }

        // ---------
        // 1) play.php (streaming)
        // ---------
        val playUrl = "$mainUrl/play.php?vid=$vid"
        val playDoc = runCatching {
            app.get(playUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)).document
        }.getOrNull()

        val playCandidates = LinkedHashSet<String>()
        if (playDoc != null) {
            playDoc.extractServersFast().forEach { playCandidates.add(it) }
            playDoc.extractDirectMediaFromScripts().forEach { playCandidates.add(it) }

            val dw = playDoc.select("[data-watch]")
                .mapNotNull { it.attr("data-watch")?.trim() }
                .filter { it.isNotBlank() }
                .take(25)

            dw.forEach { link ->
                runCatching { expandDataWatchLink(link, playUrl).forEach { playCandidates.add(it) } }
            }
        }

        playCandidates.toList().distinct().take(150).forEach { link ->
            processCandidate(link, playUrl)
        }

        // ---------
        // 2) downloads.php (many mirrors)  <-- DO NOT SKIP THIS EVEN IF PLAY WORKS
        // ---------
        val downloadsUrl = "$mainUrl/downloads.php?vid=$vid"
        val downloadsDoc = runCatching {
            app.get(downloadsUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)).document
        }.getOrNull()

        val dlCandidates = LinkedHashSet<String>()
        if (downloadsDoc != null) {
            // Here the page contains many external anchors (Vikingfile, Krakenfiles, Vidnest, etc.)
            downloadsDoc.select("a[href]").forEach { a ->
                val href = a.attr("href").trim()
                val fixed = fixUrlNull(href) ?: return@forEach
                if (fixed.startsWith("http")) dlCandidates.add(fixed)
            }

            downloadsDoc.extractServersFast().forEach { dlCandidates.add(it) }
            downloadsDoc.extractDirectMediaFromScripts().forEach { dlCandidates.add(it) }

            val dw2 = downloadsDoc.select("[data-watch]")
                .mapNotNull { it.attr("data-watch")?.trim() }
                .filter { it.isNotBlank() }
                .take(25)

            dw2.forEach { link ->
                runCatching { expandDataWatchLink(link, downloadsUrl).forEach { dlCandidates.add(it) } }
            }
        }

        dlCandidates.toList().distinct().take(250).forEach { link ->
            processCandidate(link, downloadsUrl)
        }

        return emitted
    }
}
