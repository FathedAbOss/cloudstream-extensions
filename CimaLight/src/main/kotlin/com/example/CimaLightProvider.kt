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

    // =========================
    // Helpers
    // =========================

    private fun isInternal(url: String): Boolean {
        return url.startsWith(mainUrl) || url.contains("cimalight", ignoreCase = true)
    }

    private fun safeDecode(text: String): String {
        return runCatching { URLDecoder.decode(text, "UTF-8") }.getOrElse { text }
    }

    private fun tryBase64ToUrl(raw: String): String? {
        val s = raw.trim().replace("\\/", "/")
        if (s.length < 16) return null

        val base64Like = Regex("^[A-Za-z0-9+/=]{16,}$").matches(s)
        if (!base64Like) return null

        return runCatching {
            val decoded = String(Base64.getDecoder().decode(s)).trim()
            if (decoded.startsWith("http")) decoded else null
        }.getOrNull()
    }

    private fun extractAllLinks(document: Document): List<String> {
        val candidates = mutableListOf<String>()

        // Direct links
        candidates += document.select("a[href]").map { it.attr("href") }
        candidates += document.select("iframe[src]").map { it.attr("src") }
        candidates += document.select("source[src], video[src]").map { it.attr("src") }

        // Data attributes
        candidates += document.select("[data-url], [data-href], [data-src], [data-link], [data-embed], [data-iframe]")
            .flatMap { el ->
                listOf(
                    el.attr("data-url"),
                    el.attr("data-href"),
                    el.attr("data-src"),
                    el.attr("data-link"),
                    el.attr("data-embed"),
                    el.attr("data-iframe")
                )
            }

        // onclick
        candidates += document.select("[onclick]").map { it.attr("onclick") }

        // scripts text
        val scriptText = document.select("script")
            .joinToString("\n") { it.data() + "\n" + it.html() }

        // URLs inside scripts
        candidates += Regex("(https?://[^\"'\\s<>]+)")
            .findAll(scriptText)
            .map { it.value }
            .toList()

        // "file":"..." / "src":"..." / "url":"..."
        candidates += Regex("\"(file|src|url)\"\\s*:\\s*\"(.*?)\"")
            .findAll(scriptText)
            .map { it.groupValues.getOrNull(2).orEmpty() }
            .toList()

        val out = mutableSetOf<String>()

        candidates.forEach { raw ->
            val cleaned = raw.trim()
            if (cleaned.isBlank()) return@forEach

            // If it's onclick, try pick URL from it
            val insideUrl = Regex("(https?://[^'\"\\s<>]+)").find(cleaned)?.value
            val maybe = insideUrl ?: cleaned

            val decoded = safeDecode(maybe)
            val fixed = fixUrlNull(decoded)?.trim()

            if (fixed != null && fixed.startsWith("http")) {
                out.add(fixed)
            } else {
                val b64 = tryBase64ToUrl(decoded)
                if (b64 != null) out.add(b64)
            }
        }

        return out.toList().distinct()
    }

    private suspend fun followInternalOnce(internalUrl: String, referer: String): List<String> {
        return runCatching {
            val res = app.get(internalUrl, referer = referer)

            // redirect to external directly
            val finalUrl = res.url
            if (finalUrl.startsWith("http") && !isInternal(finalUrl)) {
                return@runCatching listOf(finalUrl)
            }

            val doc = res.document
            val links = extractAllLinks(doc)

            links.filter { !isInternal(it) }.distinct()
        }.getOrElse { emptyList() }
    }

    // =========================
    // Main Page / Search
    // =========================

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

    // =========================
    // Load details
    // =========================

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

    val downloadsUrl = "$mainUrl/downloads.php?vid=$vid"

    val doc = runCatching {
        app.get(downloadsUrl, referer = watchUrl).document
    }.getOrNull() ?: return false

    val allLinks = doc.select("a[href]")
        .mapNotNull { fixUrlNull(it.attr("href").trim()) }
        .filter { it.startsWith("http") }
        .distinct()

    val externalLinks = allLinks
        .filter { !it.startsWith(mainUrl) && !it.contains("cimalight", ignoreCase = true) }
        .distinct()

    externalLinks.forEach { link ->
        runCatching {
            // key: send hoster page to cloudstream extractors
            loadExtractor(link, downloadsUrl, subtitleCallback, callback)
        }
    }

    return externalLinks.isNotEmpty()
}
