package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
    // Extraction Helper Blocks
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

        // 1) data-watch (Primary mechanism)
        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // 2) common embed attributes
        val attrs = listOf("data-embed-url", "data-url", "data-href", "data-embed", "data-src", "data-link", "data-server")
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

        // 5) anchors with http
        this.select("a[href]").forEach {
            val s = it.attr("href").trim()
            if (s.startsWith("http")) out.add(fixUrl(s))
        }

        // 6) List items/Buttons used for server switching
        this.select("li[data-url], li[data-link], li[data-server], li[data-src], button[data-url], button[data-link]").forEach {
            val s = it.attr("data-url")
                .ifBlank { it.attr("data-link") }
                .ifBlank { it.attr("data-server") }
                .ifBlank { it.attr("data-src") }
                .trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        return out.toList()
    }

    // WeCima-specific key fix helpers
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
        val targetUrl = if (!decodedUrl.isNullOrBlank()) decodedUrl else fixed
        
        // Add the target itself (often an iframe wrapper like elif.news)
        out.add(targetUrl)

        // Fetch and scrape the target to reveal hidden iframes inside
        runCatching {
            val doc = app.get(
                targetUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            ).document
            doc.extractServersFast().forEach { out.add(it) }
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }
        }
        return out.toList()
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

        val link = newExtractorLink(
            source = name,
            name = "CimaLight Direct",
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
    // Main / Search
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = safeHeaders).document
        val items = document.select("h3 a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href").trim())
            if (title.isBlank() || link.isBlank()) return@mapNotNull null
            val poster = extractPosterFromAnchor(a)
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/search.php?keywords=$q&video-id=", headers = safeHeaders).document

        return document.select("h3 a").mapNotNull { a ->
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
    // Load
    // ---------------------------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.trim()
            ?.let { fixUrlNull(it) }
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val episodes = document.select("div.Episodes--Seasons--Episodes a, .episodes a, #episodes a, .list-parts a")
            .mapNotNull {
                val href = fixUrl(it.attr("href"))
                val name = it.text().trim()
                if (href.isBlank()) null else newEpisode(href) {
                    this.name = name
                }
            }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(
                name = title.ifBlank { "CimaLight Series" },
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(
            name = title.ifBlank { "CimaLight Movie" },
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------
    // LoadLinks (The Engine Room)
    // ---------------------------

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val watchUrl = data.trim()
        val collected = LinkedHashSet<String>()
        var foundAny = false

        // =========================================================
        // PIPELINE STEP 1: Fetch the Page & Scrape [data-watch]
        // =========================================================
        // As per WeCima briefing, the real links are often right here, hidden in data-watch/slp_watch
        
        val pageDoc = runCatching { 
            app.get(watchUrl, headers = safeHeaders).document 
        }.getOrNull()

        if (pageDoc != null) {
            // 1. Extract [data-watch] and decode them
            val dw = pageDoc.select("[data-watch]")
                .mapNotNull { it.attr("data-watch")?.trim() }
                .filter { it.isNotBlank() }
                .take(30)
            
            dw.forEach { link ->
                runCatching { 
                    expandDataWatchLink(link, watchUrl).forEach { collected.add(it) } 
                }
            }

            // 2. Extract standard items (iframes directly on page)
            pageDoc.extractServersFast().forEach { collected.add(it) }
            pageDoc.extractDirectMediaFromScripts().forEach { collected.add(it) }
        }

        // =========================================================
        // PIPELINE STEP 2: Fallback to play.php/downloads.php if needed
        // =========================================================
        // Only if we found absolutely nothing or for redundancy
        
        val vidRegex = Regex("vid=([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
        var vid = vidRegex.find(watchUrl)?.groupValues?.getOrNull(1)

        if (vid == null && pageDoc != null) {
            // Try to find vid in the HTML we just fetched
            val watchLink = pageDoc.select("a[href*='vid=']").firstOrNull()
            if (watchLink != null) {
                vid = vidRegex.find(watchLink.attr("href"))?.groupValues?.getOrNull(1)
            }
        }

        if (vid != null) {
            val endpoints = listOf(
                "$mainUrl/play.php?vid=$vid",
                "$mainUrl/downloads.php?vid=$vid"
            )

            endpoints.forEach { endpoint ->
                runCatching {
                    val doc = app.get(endpoint, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)).document
                    
                    // Standard Extraction
                    doc.extractServersFast().forEach { collected.add(it) }
                    
                    // Data-Watch Extraction
                    val dwEndpoint = doc.select("[data-watch]")
                        .mapNotNull { it.attr("data-watch")?.trim() }
                        .filter { it.isNotBlank() }
                    
                    dwEndpoint.forEach { link ->
                        expandDataWatchLink(link, endpoint).forEach { collected.add(it) }
                    }
                }.onFailure { 
                    // Ignore errors on fallbacks
                }
            }
        }

        // =========================================================
        // PIPELINE STEP 3: Resolve & Emit
        // =========================================================

        collected.toList().distinct().forEach { link ->
            // A) Direct Media (mp4/m3u8)
            if (emitDirectMedia(link, watchUrl, callback)) {
                foundAny = true
                return@forEach
            }

            // B) Standard Extractor
            if (link.startsWith("http") && !link.contains("cimalight", ignoreCase = true)) {
                runCatching { 
                    // Some internal wrappers might need one more hop, but standard extractors handle their own resolution
                    loadExtractor(link, watchUrl, subtitleCallback, callback) 
                }
                foundAny = true
            }
        }

        return foundAny
    }
}
