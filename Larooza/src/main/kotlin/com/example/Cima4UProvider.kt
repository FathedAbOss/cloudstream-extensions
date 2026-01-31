package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import kotlin.math.min

class Cima4UProvider : MainAPI() {

    override var mainUrl = "https://cfu.cam"
    override var name = "Cima4U"
    override var lang = "ar"
    override var hasMainPage = true

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // ---------------------------
    // Limits
    // ---------------------------
    private val MAX_ITEMS = 90
    private val MAX_SEARCH_RESULTS = 30
    private val MAX_INTERNAL_RESOLVE = 18
    private val MAX_FINAL_LINKS = 80
    private val PER_REQ_TIMEOUT_MS = 6500L

    private fun headersOf(referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer
    )

    private fun posterHeaders() = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    private fun canonical(raw: String): String {
        val u = raw.trim()
        if (u.isBlank()) return u
        return fixUrl(u).substringBefore("#").trim()
    }

    private fun isInternalUrl(u: String): Boolean {
        if (!u.startsWith("http")) return true
        val host = runCatching { URI(u).host?.lowercase().orEmpty() }.getOrDefault("")
        if (host.isBlank()) return true
        return host.contains("cfu") || host.contains("cima")
    }

    // ✅ رفض صفحات الأقسام والمينيو
    private fun isBadListingOrMenuUrl(url: String): Boolean {
        val u = url.lowercase().trimEnd('/')

        val badContains = listOf(
            "/category/", "/tag/", "/page/", "/wp-", "/privacy", "/contact", "/about", "/dmca",
            "facebook.com", "twitter.com", "t.me", "telegram", "instagram.com", "youtube.com"
        )
        if (badContains.any { u.contains(it) }) return true

        val badEnds = listOf(
            "/movies", "/movie", "/series", "/anime",
            "/aflam", "/mosalsalat", "/مسلسلات", "/افلام"
        )
        if (badEnds.any { u.endsWith(it) }) return true

        val home = mainUrl.lowercase().trimEnd('/')
        if (u == home) return true

        return false
    }

    private fun looksLikeRealPoster(posterUrl: String): Boolean {
        val p = posterUrl.lowercase()
        if (p.startsWith("data:")) return false
        if (p.contains("logo") || p.contains("icon") || p.contains("placeholder") || p.contains("sprite")) return false

        val goodExt = p.contains(".jpg") || p.contains(".jpeg") || p.contains(".png") || p.contains(".webp")
        val wpUploads = p.contains("wp-content/uploads") || p.contains("/uploads/")
        return goodExt || wpUploads
    }

    private fun guessTypeFrom(url: String, title: String): TvType {
        val u = url.lowercase()
        val t = title.lowercase()
        if (u.contains("series") || u.contains("episode") || t.contains("مسلسل") || t.contains("الحلقة") || t.contains("موسم"))
            return TvType.TvSeries
        if (u.contains("anime") || t.contains("انمي") || t.contains("أنمي"))
            return TvType.Anime
        return TvType.Movie
    }

    // ---------------------------
    // MainPage
    // ---------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الأحدث",
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/anime" to "أنمي",
        "$mainUrl/category/movies/" to "أفلام (تصنيف)",
        "$mainUrl/category/series/" to "مسلسلات (تصنيف)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) base else "$base/page/$page/"

        val doc = runCatching { app.get(url, headers = headersOf("$mainUrl/")).document }
            .getOrElse {
                val alt = if (page <= 1) base else "$base?page=$page"
                app.get(alt, headers = headersOf("$mainUrl/")).document
            }

        val items = parsePosterCardsOnly(doc)
        return newHomePageResponse(request.name, items)
    }

    // ---------------------------
    // ✅ Parser جديد: يعتمد على "الصور الحقيقية" فقط
    // ---------------------------
    private fun Element.safePosterUrl(): String? {
        val img = this.selectFirst("img") ?: return null
        val raw = img.attr("data-src").trim()
            .ifBlank { img.attr("data-lazy-src").trim() }
            .ifBlank { img.attr("data-original").trim() }
            .ifBlank { img.attr("src").trim() }

        if (raw.isBlank()) return null
        val fixed = fixUrl(raw)
        return if (looksLikeRealPoster(fixed)) fixed else null
    }

    private fun titleFrom(imgOrCard: Element): String? {
        val imgAlt = imgOrCard.selectFirst("img")?.attr("alt")?.trim()
        if (!imgAlt.isNullOrBlank() && imgAlt.length >= 2) return imgAlt

        val t = imgOrCard.selectFirst("h1,h2,h3,.title,.name,.entry-title,strong")?.text()?.trim()
        if (!t.isNullOrBlank() && t.length >= 2) return t

        val aTitle = imgOrCard.selectFirst("a")?.attr("title")?.trim()
        if (!aTitle.isNullOrBlank() && aTitle.length >= 2) return aTitle

        val aText = imgOrCard.selectFirst("a")?.text()?.trim()
        if (!aText.isNullOrBlank() && aText.length >= 2) return aText

        return null
    }

    private fun findNearestLink(el: Element): String? {
        val a = el.closest("a[href]") ?: el.selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null
        return canonical(href)
    }

    private suspend fun parsePosterCardsOnly(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val ph = posterHeaders()

        // 1) الطريقة الأقوى: كل بوستر حقيقي -> خذ رابط أقرب a[href]
        val images = doc.select("img")
        for (img in images) {
            val raw = img.attr("data-src").trim()
                .ifBlank { img.attr("data-lazy-src").trim() }
                .ifBlank { img.attr("data-original").trim() }
                .ifBlank { img.attr("src").trim() }
            if (raw.isBlank()) continue

            val poster = fixUrl(raw)
            if (!looksLikeRealPoster(poster)) continue

            val link = findNearestLink(img) ?: continue
            if (!isInternalUrl(link)) continue
            if (isBadListingOrMenuUrl(link)) continue

            val card = img.parent() ?: img
            val title = (img.attr("alt")?.trim().takeIf { !it.isNullOrBlank() && it.length >= 2 })
                ?: titleFrom(card)
                ?: continue

            val badTitles = setOf("الرئيسية", "أفلام", "مسلسلات", "السينما للجميع", "انمي", "أنمي")
            if (badTitles.contains(title.trim())) continue

            val type = guessTypeFrom(link, title)
            val sr = newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                posterHeaders = ph
            }

            out.putIfAbsent(link, sr)
            if (out.size >= MAX_ITEMS) break
        }

        // 2) fallback: a[href]:has(img) بشرط وجود poster حقيقي
        if (out.isEmpty()) {
            val linksWithImg = doc.select("a[href]:has(img)")
            for (a in linksWithImg) {
                val href = a.attr("href").trim()
                if (href.isBlank()) continue
                val link = canonical(href)
                if (!isInternalUrl(link)) continue
                if (isBadListingOrMenuUrl(link)) continue

                val poster = a.safePosterUrl() ?: continue // ✅ poster-only
                val title = titleFrom(a) ?: continue

                val badTitles = setOf("الرئيسية", "أفلام", "مسلسلات", "السينما للجميع", "انمي", "أنمي")
                if (badTitles.contains(title.trim())) continue

                val type = guessTypeFrom(link, title)
                val sr = newMovieSearchResponse(title, link, type) {
                    posterUrl = fixUrlNull(poster)
                    posterHeaders = ph
                }
                out.putIfAbsent(link, sr)
                if (out.size >= MAX_ITEMS) break
            }
        }

        return out.values.toList()
    }

    // ---------------------------
    // Search
    // ---------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val enc = URLEncoder.encode(q, "UTF-8")
        val encPlus = q.replace(" ", "+")

        val urls = listOf(
            "$mainUrl/search/$encPlus",
            "$mainUrl/?s=$enc",
            "$mainUrl/?search=$enc"
        )

        for (u in urls) {
            val doc = runCatching { app.get(u, headers = headersOf("$mainUrl/")).document }.getOrNull() ?: continue
            val parsed = parsePosterCardsOnly(doc)
            val filtered = parsed.filter { it.name.contains(q, ignoreCase = true) }.take(MAX_SEARCH_RESULTS)
            if (filtered.isNotEmpty()) return filtered
        }

        val doc = runCatching { app.get("$mainUrl/?s=$enc", headers = headersOf("$mainUrl/")).document }.getOrNull()
            ?: return emptyList()
        return parsePosterCardsOnly(doc).take(MAX_SEARCH_RESULTS)
    }

    // ---------------------------
    // Load details
    // ---------------------------
    override suspend fun load(url: String): LoadResponse {
        val pageUrl = canonical(url)
        val doc = app.get(pageUrl, headers = headersOf("$mainUrl/")).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Cima4U"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src]")?.attr("src")?.trim()

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val type = guessTypeFrom(pageUrl, title)
        val ph = posterHeaders()

        return if (type == TvType.TvSeries) {
            val episodes = doc.extractEpisodes(pageUrl)
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = fixUrlNull(poster?.let { fixUrl(it) })
                posterHeaders = ph
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, pageUrl, type, pageUrl) {
                posterUrl = fixUrlNull(poster?.let { fixUrl(it) })
                posterHeaders = ph
                this.plot = plot
            }
        }
    }

    private fun Document.extractEpisodes(seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()
        val epArabic = Regex("""الحلقة\s*(\d{1,4})""")

        val links = this.select("a[href]")
        for (a in links) {
            val href = a.attr("href").trim()
            if (href.isBlank()) continue
            val link = canonical(href)
            if (!isInternalUrl(link)) continue
            if (canonical(seriesUrl) == link) continue
            if (isBadListingOrMenuUrl(link)) continue

            val text = a.text().trim()
            val looksEpisode =
                text.contains("الحلقة") ||
                    link.contains("episode", true) ||
                    link.contains("الحلقة", true)

            if (!looksEpisode) continue

            val epNum = epArabic.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val name = if (text.isNotBlank()) text else (if (epNum != null) "الحلقة $epNum" else "مشاهدة")

            found.putIfAbsent(link, newEpisode(link) {
                this.name = name
                this.season = 1
                this.episode = epNum
            })
        }

        if (found.isEmpty()) {
            return listOf(newEpisode(seriesUrl) {
                name = "مشاهدة"
                season = 1
                episode = 1
            })
        }

        return found.values.toList().sortedBy { it.episode ?: 999999 }
    }

    // ---------------------------
    // Link discovery
    // ---------------------------
    private fun Document.extractServerCandidates(): List<String> {
        val out = LinkedHashSet<String>()

        // data-watch
        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(canonical(s))
        }

        // data-* common
        val attrs = listOf("data-url", "data-href", "data-embed", "data-src", "data-link", "data-iframe")
        for (a in attrs) {
            this.select("[$a]").forEach {
                val s = it.attr(a).trim()
                if (s.isNotBlank()) out.add(canonical(s))
            }
        }

        // iframe
        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(canonical(s))
        }

        // onclick url
        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(canonical(m.value))
        }

        // anchors
        this.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href").trim() }
            if (href.isBlank()) return@forEach
            val low = href.lowercase()
            val ok = listOf(
                "embed", "player", "watch", "play", "download",
                "dood", "filemoon", "mixdrop", "streamtape", "ok.ru", "uqload",
                "m3u8", ".mp4", "slp_watch="
            ).any { low.contains(it) }
            if (ok) out.add(canonical(href))
        }

        // direct from html
        val html = this.html()
        Regex("""https?://[^\s"'<>]+?\.(mp4|m3u8)(\?[^\s"'<>]+)?""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.value) }

        return out.toList()
    }

    private fun extractSlpWatchParam(url: String): String? {
        val u = url.trim()
        if (!u.contains("slp_watch=")) return null
        val q = u.substringAfter("slp_watch=", "")
        return if (q.isBlank()) null else q.substringBefore("&").trim()
    }

    private fun decodeSlpWatchUrl(encoded: String): String? {
        val raw = encoded.trim()
        if (raw.isBlank()) return null
        val normalized = raw.replace('-', '+').replace('_', '/').let { s ->
            val mod = s.length % 4
            if (mod == 0) s else s + "=".repeat(4 - mod)
        }
        return runCatching {
            val bytes = Base64.getDecoder().decode(normalized)
            val decoded = String(bytes, Charsets.UTF_8).trim()
            if (decoded.startsWith("http")) decoded else null
        }.getOrNull()
    }

    private suspend fun resolveInternalOnce(url: String, referer: String): List<String> {
        val fixed = canonical(url)
        if (fixed.isBlank()) return emptyList()

        return withTimeoutOrNull(PER_REQ_TIMEOUT_MS) {
            runCatching {
                val doc = app.get(fixed, headers = headersOf(referer)).document
                val out = LinkedHashSet<String>()
                doc.extractServerCandidates().forEach { out.add(it) }

                val slp = extractSlpWatchParam(fixed)
                val decoded = slp?.let { decodeSlpWatchUrl(it) }
                if (!decoded.isNullOrBlank()) out.add(decoded)

                out.toList()
            }.getOrElse { emptyList() }
        } ?: emptyList()
    }

    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = url.lowercase().contains(".m3u8")
        callback(
            newExtractorLink(
                name,
                "Cima4U Direct",
                url,
                referer,
                Qualities.Unknown.value,
                isM3u8,
                headersOf(referer),
                null
            )
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = canonical(data)
        if (pageUrl.isBlank()) return false

        val doc = app.get(pageUrl, headers = headersOf("$mainUrl/")).document

        val candidates = LinkedHashSet<String>()
        doc.extractServerCandidates().forEach { candidates.add(it) }

        if (candidates.isEmpty()) return false

        val expanded = LinkedHashSet<String>()
        expanded.addAll(candidates)

        var internalUsed = 0
        for (c in candidates.toList().take(min(candidates.size, 140))) {
            if (internalUsed >= MAX_INTERNAL_RESOLVE) break

            val slp = extractSlpWatchParam(c)
            val decoded = slp?.let { decodeSlpWatchUrl(it) }
            if (!decoded.isNullOrBlank()) expanded.add(decoded)

            if (isInternalUrl(c)) {
                val more = resolveInternalOnce(c, pageUrl)
                more.forEach { expanded.add(it) }
                internalUsed++
            }
        }

        val finals = expanded.toList().take(MAX_FINAL_LINKS)
        if (finals.isEmpty()) return false

        var foundAny = false
        for (raw in finals) {
            val u = canonical(raw)
            if (u.isBlank()) continue
            val low = u.lowercase()

            if (low.contains(".mp4") || low.contains(".m3u8")) {
                emitDirect(u, pageUrl, callback)
                foundAny = true
                continue
            }

            if (!isInternalUrl(u)) {
                runCatching {
                    loadExtractor(u, pageUrl, subtitleCallback, callback)
                    foundAny = true
                }
            }
        }

        return foundAny
    }
}
