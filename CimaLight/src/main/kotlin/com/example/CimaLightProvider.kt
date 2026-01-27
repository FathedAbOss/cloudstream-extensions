package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64
import java.net.URLEncoder

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
    // Helper Methods
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

        // 6) List items used for server switching
        this.select("li[data-url], li[data-link], li[data-server], li[data-src]").forEach {
            val s = it.attr("data-url")
                .ifBlank { it.attr("data-link") }
                .ifBlank { it.attr("data-server") }
                .ifBlank { it.attr("data-src") }
                .trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        return out.toList()
    }

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
        
        out.add(targetUrl)

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
        // Fixed: Proper encoding for Arabic queries
        val q = URLEncoder.encode(query.trim(), "UTF-8")
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

        // Attempt to parse Episodes
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
    // LoadLinks
    // ---------------------------

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var watchUrl = data.trim()
        var vid: String? = null

        // ✅ 1. Try to extract 'vid' from the URL directly
        val vidRegex = Regex("vid=([A-Za-z0-9_-]+)")
        vid = vidRegex.find(watchUrl)?.groupValues?.getOrNull(1)

        // ✅ 2. If no 'vid' in URL, fetch the page to find it (Necessary for Movies)
        if (vid == null) {
            val doc = runCatching { app.get(watchUrl, headers = safeHeaders).document }.getOrNull()
            if (doc != null) {
                // Search the full HTML for the ID pattern (more robust than selecting 'a' tags)
                vid = vidRegex.find(doc.html())?.groupValues?.getOrNull(1)
            }
        }

        // If we still don't have a vid, we can't proceed
        if (vid == null) return false

        val collected = LinkedHashSet<String>()
        var foundAny = false

        // ✅ STEP 1: Scrape play.php
        val playUrl = "$mainUrl/play.php?vid=$vid"
        val playDoc = runCatching {
            app.get(playUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)).document
        }.getOrNull()

        if (playDoc != null) {
            val initialLinks = LinkedHashSet<String>()
            playDoc.extractServersFast().forEach { initialLinks.add(it) }
            playDoc.extractDirectMediaFromScripts().forEach { initialLinks.add(it) }

            val dw = playDoc.select("[data-watch]")
                .mapNotNull { it.attr("data-watch")?.trim() }
                .filter { it.isNotBlank() }
                .take(25)

            dw.forEach { link ->
                runCatching { expandDataWatchLink(link, playUrl).forEach { initialLinks.add(it) } }
            }

            // 🔥 DEEP SCAN: Visit found iframes/links to find real servers (e.g. elif.news)
            initialLinks.forEach { link ->
                collected.add(link)
                // Filter out obviously direct files to save time
                if (!link.contains(".mp4") && !link.contains(".m3u8")) {
                    runCatching {
                        val embedDoc = app.get(link, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to playUrl)).document
                        embedDoc.extractServersFast().forEach { collected.add(it) }
                        embedDoc.extractDirectMediaFromScripts().forEach { collected.add(it) }
                    }
                }
            }
        }

        // Emit discovered links
        collected.toList().distinct().forEach { link ->
            if (emitDirectMedia(link, playUrl, callback)) {
                foundAny = true
                return@forEach
            }
            if (link.startsWith("http") && !link.contains("cimalight", ignoreCase = true)) {
                runCatching { loadExtractor(link, playUrl, subtitleCallback, callback) }
                foundAny = true
            }
        }

        if (foundAny) return true

        // ✅ STEP 2: Fallback to downloads.php
        val downloadsUrl = "$mainUrl/downloads.php?vid=$vid"
        val downloadsDoc = runCatching {
            app.get(downloadsUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)).document
        }.getOrNull() ?: return false

        val dlCollected = LinkedHashSet<String>()
        downloadsDoc.extractServersFast().forEach { dlCollected.add(it) }
        downloadsDoc.extractDirectMediaFromScripts().forEach { dlCollected.add(it) }

        val dw2 = downloadsDoc.select("[data-watch]")
            .mapNotNull { it.attr("data-watch")?.trim() }
            .filter { it.isNotBlank() }
            .take(25)

        dw2.forEach { link ->
            runCatching { expandDataWatchLink(link, downloadsUrl).forEach { dlCollected.add(it) } }
        }

        // Expand MultiUp mirrors + emit
        dlCollected.toList().distinct().take(80).forEach { link ->
            if (emitDirectMedia(link, downloadsUrl, callback)) {
                foundAny = true
                return@forEach
            }
            if (!link.startsWith("http")) return@forEach
            if (link.contains("cimalight", ignoreCase = true)) return@forEach

            runCatching {
                if (link.contains("multiup.io", ignoreCase = true)) {
                    val multiDoc = app.get(link, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to downloadsUrl)).document
                    val mirrors = multiDoc.select("a[href]")
                        .mapNotNull { fixUrlNull(it.attr("href").trim()) }
                        .filter { it.startsWith("http") }
                        .filter { !it.contains("multiup.io", ignoreCase = true) }
                        .distinct()
                        .take(20)

                    mirrors.forEach { mirror ->
                        runCatching {
                            if (!emitDirectMedia(mirror, link, callback)) {
                                loadExtractor(mirror, link, subtitleCallback, callback)
                            }
                        }
                    }
                    if (mirrors.isNotEmpty()) foundAny = true
                } else {
                    loadExtractor(link, downloadsUrl, subtitleCallback, callback)
                    foundAny = true
                }
            }
        }

        return foundAny
    }
}
