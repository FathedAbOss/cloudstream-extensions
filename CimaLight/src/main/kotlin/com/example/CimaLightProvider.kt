package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.util.Base64

class CimaLightProvider : MainAPI() {

    override var mainUrl = "https://w.cimalight.co"
    override var name = "CimaLight"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/movies.php" to "أحدث الأفلام",
        "$mainUrl/main15" to "جديد الموقع",
        "$mainUrl/most.php" to "الأكثر مشاهدة"
    )

    private fun isInternal(url: String): Boolean {
        return url.startsWith(mainUrl) || url.contains("cimalight", ignoreCase = true)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val items = document.select("h3 a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href"))

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = a.parent()?.parent()
                ?.selectFirst("img")
                ?.attr("src")
                ?.trim()
                ?.let { fixUrlNull(it) }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/search?q=$q").document

        return document.select("h3 a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href"))

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            val poster = a.parent()?.parent()
                ?.selectFirst("img")
                ?.attr("src")
                ?.trim()
                ?.let { fixUrlNull(it) }

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.trim()
            ?.let { fixUrlNull(it) }

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // 1) try find vid in URL
        var vid = Regex("vid=([A-Za-z0-9]+)").find(url)?.groupValues?.getOrNull(1)

        // 2) else try find vid inside HTML
        if (vid.isNullOrBlank()) {
            val vidLink = document.selectFirst("a[href*=\"downloads.php?vid=\"]")?.attr("href")
            vid = vidLink?.let {
                Regex("vid=([A-Za-z0-9]+)").find(it)?.groupValues?.getOrNull(1)
            }
        }

        val downloadsUrl = if (!vid.isNullOrBlank()) "$mainUrl/downloads.php?vid=$vid" else url

        return newMovieLoadResponse(
            name = title.ifBlank { "CimaLight" },
            url = url,
            type = TvType.Movie,
            dataUrl = downloadsUrl
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // --------- Helpers for better extraction ----------

    private fun safeDecode(text: String): String {
        return runCatching { URLDecoder.decode(text, "UTF-8") }.getOrElse { text }
    }

    private fun tryBase64ToUrl(raw: String): String? {
        val s = raw.trim().replace("\\/", "/")
        if (s.length < 16) return null

        // base64-ish check (cheap)
        val base64Like = Regex("^[A-Za-z0-9+/=]{16,}$").matches(s)
        if (!base64Like) return null

        return runCatching {
            val decoded = String(Base64.getDecoder().decode(s)).trim()
            if (decoded.startsWith("http")) decoded else null
        }.getOrNull()
    }

    private fun extractAllLinks(document: Document): List<String> {
        val candidates = mutableListOf<String>()

        // normal direct links
        candidates += document.select("a[href]")
            .map { it.attr("href") }

        candidates += document.select("iframe[src]")
            .map { it.attr("src") }

        candidates += document.select("source[src], video[src]")
            .map { it.attr("src") }

        // data attributes (common for buttons/servers)
        candidates += document.select("[data-url], [data-href], [data-src], [data-link], [data-embed]")
            .flatMap { el ->
                listOf(
                    el.attr("data-url"),
                    el.attr("data-href"),
                    el.attr("data-src"),
                    el.attr("data-link"),
                    el.attr("data-embed")
                )
            }

        // onclick may contain url or encoded string
        candidates += document.select("[onclick]")
            .map { it.attr("onclick") }

        // scripts: pull strings that look like urls or fields: file/src/url
        val scriptText = document.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        candidates += Regex("(https?://[^\"'\\s<>]+)").findAll(scriptText).map { it.value }.toList()
        candidates += Regex("\"(file|src|url)\"\\s*:\\s*\"(.*?)\"").findAll(scriptText)
            .map { it.groupValues.getOrNull(2).orEmpty() }
            .toList()

        // cleanup -> fix -> decode -> also attempt base64 decode
        val out = mutableSetOf<String>()

        candidates.forEach { raw ->
            val cleaned = raw.trim()
            if (cleaned.isBlank()) return@forEach

            // If it's onclick text, try pull URL inside it
            val insideUrl = Regex("(https?://[^'\"\\s<>]+)").find(cleaned)?.value
            val maybe = insideUrl ?: cleaned

            // URL decode + fixUrlNull
            val decoded = safeDecode(maybe)
            val fixed = fixUrlNull(decoded)?.trim()

            if (fixed != null && fixed.startsWith("http")) {
                out.add(fixed)
            } else {
                // base64 attempt (very common)
                val b64 = tryBase64ToUrl(decoded)
                if (b64 != null) out.add(b64)
            }
        }

        return out.toList().distinct()
    }

    // Follow internal links and capture redirects / second-page externals
    private suspend fun followInternal(internalUrl: String, referer: String): Pair<List<String>, List<String>> {
        return runCatching {
            val res = app.get(internalUrl, referer = referer)

            // If internal redirects directly to external
            val finalUrl = res.url
            if (finalUrl.startsWith("http") && !isInternal(finalUrl)) {
                return@runCatching Pair(listOf(finalUrl), emptyList())
            }

            val doc = res.document
            val links = extractAllLinks(doc)

            val externals = links.filter { !isInternal(it) }.distinct()
            val internals = links.filter { isInternal(it) }.distinct()

            Pair(externals, internals)
        }.getOrElse { Pair(emptyList(), emptyList()) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc1 = app.get(data).document
        val firstLinks = extractAllLinks(doc1)

        val externalLinks = firstLinks.filter { !isInternal(it) }.distinct()
        val internalLinksLvl1 = firstLinks.filter { isInternal(it) }.distinct()

        // 1) external direct
        externalLinks.forEach { link ->
            runCatching {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        // 2) follow internal up to 2 steps (limited)
        val visited = mutableSetOf<String>()

        val lvl2Candidates = mutableListOf<String>()

        internalLinksLvl1.take(15).forEach { internal ->
            if (!visited.add(internal)) return@forEach

            val (externals, internals) = followInternal(internal, data)

            // Use internal as referer for extracted links (often required)
            externals.forEach { ext ->
                runCatching {
                    loadExtractor(ext, internal, subtitleCallback, callback)
                }
            }

            lvl2Candidates.addAll(internals)
        }

        // step 2
        lvl2Candidates.distinct().take(25).forEach { internal2 ->
            if (!visited.add(internal2)) return@forEach

            val (externals2, _) = followInternal(internal2, data)

            externals2.forEach { ext2 ->
                runCatching {
                    loadExtractor(ext2, internal2, subtitleCallback, callback)
                }
            }
        }

        return firstLinks.isNotEmpty()
    }
}
