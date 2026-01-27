package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
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

    // ✅ Poster extractor that handles lazy-load and different HTML structures
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
    // WeCima-style helpers (reused)
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

        // 1) data-watch (same pattern as WeCima)
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

        // 5) anchors (sometimes include direct hosts)
        this.select("a[href]").forEach {
            val s = it.attr("href").trim()
            if (s.startsWith("http")) out.add(fixUrl(s))
        }

        return out.toList()
    }

    // ---------------------------
    // Redirect helpers (JS/meta redirect)
    // ---------------------------

    private fun Document.extractJsOrMetaRedirect(): String? {
        // 1) <meta http-equiv="refresh" content="0;url=https://...">
        this.selectFirst("meta[http-equiv~=(?i)refresh]")?.attr("content")?.let { c ->
            val u = c.substringAfter("url=", "").trim()
            if (u.startsWith("http")) return u
        }

        val html = this.html()

        // 2) redirectUrl='https://...';
        Regex("""redirectUrl\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.let { if (it.startsWith("http")) return it }

        // 3) location.replace("https://...")
        Regex("""location\.replace\(\s*['"]([^'"]+)['"]\s*\)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.let { if (it.startsWith("http")) return it }

        // 4) window.location = "https://..."
        Regex("""window\.location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.let { if (it.startsWith("http")) return it }

        // 5) Fallback: take the first http(s) URL inside scripts (rare but useful)
        Regex("""https?://[^"'<> ]+""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.trim()
            ?.let { if (it.startsWith("http")) return it }

        return null
    }

    private suspend fun getDocFollowRedirectOnce(url: String, referer: String): Pair<String, Document>? {
        val first = runCatching {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).document
        }.getOrNull() ?: return null

        val redirect = first.extractJsOrMetaRedirect()
        if (!redirect.isNullOrBlank() && redirect.startsWith("http")) {
            val second = runCatching {
                app.get(redirect, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url)).document
            }.getOrNull()
            if (second != null) return redirect to second
        }

        return url to first
    }

    private fun shouldOneStepFollow(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("cimalight") || u.contains("elif.news") || u.contains("alhakekanet.net")
    }

    private fun hostLabel(url: String): String {
        return runCatching { URI(url).host ?: "direct" }.getOrNull() ?: "direct"
    }

    // ---------------------------
    // WeCima gateway helpers
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

    // Follow data-watch gateway (slp_watch) and scrape resulting page for iframe/mp4/m3u8
    private suspend fun expandDataWatchLink(dataWatchUrl: String, referer: String): List<String> {
        val out = LinkedHashSet<String>()
        val fixed = fixUrl(dataWatchUrl).trim()
        if (fixed.isBlank()) return emptyList()

        val slp = extractSlpWatchParam(fixed)
        val decodedUrl = slp?.let { decodeSlpWatchUrl(it) }

        val target = decodedUrl ?: fixed
        out.add(target)

        // fetch (and follow possible JS/meta redirect once)
        runCatching {
            val (finalUrl, doc) = getDocFollowRedirectOnce(target, referer) ?: return@runCatching
            out.add(finalUrl)
            doc.extractServersFast().forEach { out.add(it) }
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }
        }

        return out.toList()
    }

    // ---------------------------
    // One-step "server page" scraper
    // ---------------------------

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

        // Also expand any data-watch found inside this "server page"
        val dw = doc.select("[data-watch]")
            .mapNotNull { it.attr("data-watch")?.trim() }
            .filter { it.isNotBlank() }
            .take(25)

        dw.forEach { dwUrl ->
            runCatching { expandDataWatchLink(dwUrl, finalUrl).forEach { out.add(it) } }
        }

        return out.toList()
    }

    // Emit direct mp4/m3u8 using newExtractorLink
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
        val document = app.get("$mainUrl/search?q=$q", headers = safeHeaders).document

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

        // ✅ keep it as movie for now (your current behavior)
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
    // LoadLinks
    // ---------------------------

    override suspend fun loadLinks(
        data: String, // watch URL e.g. https://w.cimalight.co/watch.php?vid=xxxx
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val watchUrl = data.trim()

        val vid = Regex("vid=([A-Za-z0-9]+)")
            .find(watchUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        var foundAny = false

        // ✅ STEP 1: Try streaming servers from play.php first
        val playUrl = "$mainUrl/play.php?vid=$vid"

        val playDoc = runCatching {
            app.get(playUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to watchUrl)).document
        }.getOrNull()

        val collected = LinkedHashSet<String>()

        if (playDoc != null) {
            playDoc.extractServersFast().forEach { collected.add(it) }
            playDoc.extractDirectMediaFromScripts().forEach { collected.add(it) }

            // expand any data-watch gateways
            val dw = playDoc.select("[data-watch]")
                .mapNotNull { it.attr("data-watch")?.trim() }
                .filter { it.isNotBlank() }
                .take(25)

            dw.forEach { link ->
                runCatching { expandDataWatchLink(link, playUrl).forEach { collected.add(it) } }
            }
        }

        // Emit what we got from play.php
        collected.toList().distinct().take(120).forEach { link ->
            // direct media
            if (emitDirectMedia(link, playUrl, callback)) {
                foundAny = true
                return@forEach
            }

            if (!link.startsWith("http")) return@forEach

            // ✅ NEW: follow internal/gateway pages one step (cimalight/elif/alhakekanet)
            if (shouldOneStepFollow(link)) {
                val inner = runCatching { scrapeOneStepServers(link, playUrl) }.getOrNull().orEmpty()
                inner.distinct().take(80).forEach { innerUrl ->
                    if (emitDirectMedia(innerUrl, link, callback)) {
                        foundAny = true
                    } else if (innerUrl.startsWith("http") && !innerUrl.contains("cimalight", ignoreCase = true)) {
                        runCatching { loadExtractor(innerUrl, link, subtitleCallback, callback) }
                        foundAny = true
                    }
                }
                return@forEach
            }

            // external extractor
            if (!link.contains("cimalight", ignoreCase = true)) {
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
        dlCollected.toList().distinct().take(140).forEach { link ->
            if (emitDirectMedia(link, downloadsUrl, callback)) {
                foundAny = true
                return@forEach
            }

            if (!link.startsWith("http")) return@forEach

            // ✅ NEW: follow internal/gateway pages one step (cimalight/elif/alhakekanet)
            if (shouldOneStepFollow(link)) {
                val inner = runCatching { scrapeOneStepServers(link, downloadsUrl) }.getOrNull().orEmpty()
                inner.distinct().take(80).forEach { innerUrl ->
                    if (emitDirectMedia(innerUrl, link, callback)) {
                        foundAny = true
                    } else if (innerUrl.startsWith("http") && !innerUrl.contains("cimalight", ignoreCase = true)) {
                        runCatching { loadExtractor(innerUrl, link, subtitleCallback, callback) }
                        foundAny = true
                    }
                }
                return@forEach
            }

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
                    if (!link.contains("cimalight", ignoreCase = true)) {
                        loadExtractor(link, downloadsUrl, subtitleCallback, callback)
                        foundAny = true
                    }
                }
            }
        }

        return foundAny
    }
}
