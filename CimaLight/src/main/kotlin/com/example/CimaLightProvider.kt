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

    private fun Document.extractServersFast(): List<String> {
        val out = LinkedHashSet<String>()

        // data-watch
        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // common data-* embeds
        val attrs = listOf("data-embed-url", "data-url", "data-href", "data-embed", "data-src", "data-link")
        for (a in attrs) {
            this.select("[$a]").forEach {
                val s = it.attr(a).trim()
                if (s.isNotBlank()) out.add(fixUrl(s))
            }
        }

        // iframes
        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // onclick URLs
        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(fixUrl(m.value))
        }

        // anchors
        this.select("a[href]").forEach {
            val s = it.attr("href").trim()
            if (s.startsWith("http")) out.add(fixUrl(s))
        }

        return out.toList()
    }

    // ---------------------------
    // JS/meta redirect helpers
    // ---------------------------
    private fun Document.extractJsOrMetaRedirect(): String? {
        // meta refresh
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

    private fun needsOneStepFollow(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("cimalight") || u.contains("elif.news") || u.contains("alhakekanet.net")
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
            doc.extractServersFast().forEach { out.add(it) }
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }
        }

        return out.toList()
    }

    private suspend fun scrapeOneStepServers(url: String, referer: String): List<String> {
        val out = LinkedHashSet<String>()
        val fixed = fixUrl(url).trim()
        if (fixed.isBlank()) return emptyList()

        val (finalUrl, doc) = getDocFollowRedirectOnce(fixed, referer) ?: return emptyList()
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
                title.contains("فيلم") -> TvType.Movie
                else -> TvType.Movie
            }

            newMovieSearchResponse(title, link, tvType) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    // ---------------------------
    // Search (FIXED endpoint)
    // ---------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/search.php?keywords=$q&video-id="

        val document = app.get(url, headers = safeHeaders).document

        val anchors = document.select("h3 a, h2 a, .Thumb--GridItem a, .Thumb--Grid a, a[href]")
        return anchors.mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            if (title.isBlank() || link.isBlank()) return@mapNotNull null
            if (!link.startsWith("http")) return@mapNotNull null
            if (!link.contains("cimalight", ignoreCase = true)) return@mapNotNull null

            val poster = extractPosterFromAnchor(a)

            val tvType = when {
                title.contains("مسلسل") || title.contains("حلق") || title.contains("موسم") -> TvType.TvSeries
                title.contains("فيلم") -> TvType.Movie
                else -> TvType.Movie
            }

            newMovieSearchResponse(title, link, tvType) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    // ---------------------------
    // Load (FIX: لا مسلسل إلا لو "الحلقة" فعلاً)
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

            // ✅ فقط إذا مكتوب "الحلقة" (مو جودات مثل BluRay / WEB-DL)
            val looksLikeEpisode = text.contains("الحلقة") ||
                    Regex("""\bEpisode\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

            if (!looksLikeEpisode) return@mapNotNull null
            Pair(text, eUrl)
        }.distinctBy { it.second }

        if (episodeLinks.size >= 2) {
            val episodes = episodeLinks.map { (text, eUrl) ->
                val epNum = Regex("""الحلقة\s+(\d+)""")
                    .find(text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

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

        // ✅ افتراضياً فيلم
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
    // LoadLinks (DEBUG + play + downloads)
    // ---------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val watchUrl = data.trim()
        val vid = Regex("""vid=([A-Za-z0-9]+)""")
            .find(watchUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        // ✅ DEBUG
        println("CimaLight loadLinks: watchUrl=$watchUrl vid=$vid")

        var emitted = false
        val cb: (ExtractorLink) -> Unit = {
            emitted = true
            callback(it)
        }

        suspend fun processCandidate(url: String, referer: String) {
            val u = url.trim()
            if (u.isBlank() || !u.startsWith("http")) return

            if (emitDirectMedia(u, referer, cb)) return

            val lower = u.lowercase()

            if (needsOneStepFollow(u)) {
                val inner = runCatching { scrapeOneStepServers(u, referer) }.getOrNull().orEmpty()
                println("CimaLight processCandidate: oneStep host=$u -> innerCount=${inner.size}")
                inner.distinct().take(150).forEach { x ->
                    if (emitDirectMedia(x, u, cb)) return@forEach
                    if (x.startsWith("http") && !x.lowercase().contains("cimalight")) {
                        runCatching { loadExtractor(x, u, subtitleCallback, cb) }
                    }
                }
                return
            }

            if (lower.contains("multiup.io")) {
                runCatching {
                    val doc = app.get(u, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).document
                    val mirrors = doc.select("a[href]")
                        .mapNotNull { fixUrlNull(it.attr("href").trim()) }
                        .filter { it.startsWith("http") }
                        .filter { !it.contains("multiup.io", ignoreCase = true) }
                        .distinct()
                        .take(25)

                    println("CimaLight processCandidate: multiup mirrors=${mirrors.size}")

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

            runCatching { loadExtractor(u, referer, subtitleCallback, cb) }
        }

        // 1) play.php
        val playUrl = "$mainUrl/play.php?vid=$vid"
        println("CimaLight loadLinks: playUrl=$playUrl")

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

        println("CimaLight loadLinks: playCandidates=${playCandidates.size}")

        playCandidates.toList().distinct().take(200).forEach { link ->
            processCandidate(link, playUrl)
        }

        // 2) downloads.php (DO NOT SKIP)
        val downloadsUrl = "$mainUrl/downloads.php?vid=$vid"
        println("CimaLight loadLinks: downloadsUrl=$downloadsUrl")

        val downloadsDoc = runCatching {
            app.get(downloadsUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)).document
        }.getOrNull()

        val dlCandidates = LinkedHashSet<String>()
        if (downloadsDoc != null) {
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

        println("CimaLight loadLinks: downloadsCandidates=${dlCandidates.size}")

        dlCandidates.toList().distinct().take(300).forEach { link ->
            processCandidate(link, downloadsUrl)
        }

        println("CimaLight loadLinks: emitted=$emitted")
        return emitted
    }
}
