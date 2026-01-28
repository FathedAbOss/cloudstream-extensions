package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64
import java.util.LinkedHashMap
import kotlin.math.min

class WeCimaProvider : MainAPI() {

    // ✅ change THIS only when a domain dies
    private var baseUrl = "https://mycima.rip"
    // old: https://wecima.date
    // alt: https://wecima.click (often cloudflare)

    override var mainUrl = baseUrl
    override var name = "WeCima"
    override var lang = "ar"
    override var hasMainPage = true

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private fun headersFor(referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer
    )

    private val safeHeaders = headersFor("$mainUrl/")

    // ✅ Hotlink protection for images (posters)
    private fun posterHeadersFor(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    private val posterCache = LinkedHashMap<String, String?>()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية (جديد)",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/episodes/" to "آخر الحلقات",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    // ---------------------------
    // Helpers
    // ---------------------------

    private fun normalizeUrl(u: String): String {
        return u.substringBefore("?").substringBefore("#").trim()
    }

    private fun Element.cleanUrl(u: String?): String? {
        val s = u?.trim().orEmpty()
        if (s.isBlank()) return null
        if (s.startsWith("data:")) return null
        return fixUrl(s)
    }

    private fun Element.extractTitleStrong(): String? {
        val h = this.selectFirst("h1,h2,h3,.title,.name")?.text()?.trim()
        if (!h.isNullOrBlank()) return h

        val a = this.selectFirst("a") ?: return null
        val t = a.attr("title")?.trim()
        if (!t.isNullOrBlank()) return t

        val imgAlt = this.selectFirst("img")?.attr("alt")?.trim()
        if (!imgAlt.isNullOrBlank()) return imgAlt

        val at = a.text()?.trim()
        if (!at.isNullOrBlank() && at.length > 2) return at

        return null
    }

    private fun Element.extractPosterFromCard(): String? {
        val img = this.selectFirst("img")
        cleanUrl(img?.attr("src"))?.let { return it }

        val lazyAttrs = listOf(
            "data-src",
            "data-original",
            "data-lazy-src",
            "data-image",
            "data-thumb",
            "data-poster",
            "data-cover",
            "data-img",
            "data-bg",
            "data-background",
            "data-background-image"
        )

        if (img != null) {
            for (a in lazyAttrs) {
                cleanUrl(img.attr(a))?.let { return it }
            }
        }

        val holder = this.selectFirst("[data-src], [data-image], [data-bg], [data-background-image]") ?: this
        for (a in lazyAttrs) {
            cleanUrl(holder.attr(a))?.let { return it }
        }

        val bgEl = this.selectFirst("[style*=background-image]") ?: this
        val style = bgEl.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            cleanUrl(m?.groupValues?.getOrNull(2))?.let { return it }
        }

        return null
    }

    private fun looksPlaceholder(poster: String?): Boolean {
        if (poster.isNullOrBlank()) return true
        val p = poster.lowercase()
        return p.contains("placeholder") || p.contains("noimage") || p.contains("default") || p.endsWith(".svg")
    }

    private fun guessTypeFrom(url: String, title: String): TvType {
        val u = url.lowercase()
        val t = title.lowercase()

        if (u.contains("/series/") || u.contains("/episodes/") || t.contains("الحلقة") || t.contains("موسم"))
            return TvType.TvSeries

        if (u.contains("انمي") || t.contains("انمي"))
            return TvType.Anime

        return TvType.Movie
    }

    private suspend fun fetchOgPosterCached(detailsUrl: String): String? {
        if (posterCache.containsKey(detailsUrl)) return posterCache[detailsUrl]

        val result = try {
            val d = app.get(detailsUrl, headers = safeHeaders).document
            val og = d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            if (!og.isNullOrBlank()) fixUrl(og)
            else {
                val anyImg = d.selectFirst("img[src]")?.attr("src")?.trim()
                if (!anyImg.isNullOrBlank()) fixUrl(anyImg) else null
            }
        } catch (_: Throwable) {
            null
        }

        if (posterCache.size > 250) {
            val firstKey = posterCache.keys.firstOrNull()
            if (firstKey != null) posterCache.remove(firstKey)
        }

        posterCache[detailsUrl] = result
        return result
    }

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

        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        val attrs = listOf("data-url", "data-href", "data-embed", "data-src", "data-link")
        for (a in attrs) {
            this.select("[$a]").forEach {
                val s = it.attr(a).trim()
                if (s.isNotBlank()) out.add(fixUrl(s))
            }
        }

        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        this.select("[onclick]").forEach {
            val oc = it.attr("onclick")
            val m = Regex("""https?://[^"'\s<>]+""").find(oc)
            if (m != null) out.add(fixUrl(m.value))
        }

        return out.toList()
    }

    private fun extractSlpWatchParam(url: String): String? {
        val u = url.trim()
        if (!u.contains("slp_watch=")) return null
        return runCatching {
            u.substringAfter("slp_watch=").substringBefore("&").trim().ifBlank { null }
        }.getOrNull()
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

        return runCatching {
            val bytes = Base64.getDecoder().decode(normalized)
            val decoded = String(bytes, Charsets.UTF_8).trim()
            if (decoded.startsWith("http")) decoded else null
        }.getOrNull()
    }

    private suspend fun expandDataWatchLink(dataWatchUrl: String, referer: String): List<String> {
        val out = LinkedHashSet<String>()

        val fixed = fixUrl(dataWatchUrl).trim()
        if (fixed.isBlank()) return emptyList()

        val slp = extractSlpWatchParam(fixed)
        val decoded = slp?.let { decodeSlpWatchUrl(it) }

        val target = decoded ?: fixed
        out.add(target)

        runCatching {
            val doc = app.get(target, headers = headersFor(referer)).document
            doc.extractServersFast().forEach { out.add(it) }
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }
        }

        return out.toList()
    }

    private suspend fun resolveInternalIfNeeded(link: String, referer: String): List<String> {
        val fixed = fixUrl(link).trim()
        if (fixed.isBlank()) return emptyList()

        val isInternal = fixed.contains("wecima") || fixed.contains("mycima") || fixed.startsWith(mainUrl)
        if (!isInternal) return listOf(fixed)

        if (!(fixed.contains("watch") || fixed.contains("player") || fixed.contains("play") || fixed.contains("مشاهدة"))) {
            return listOf(fixed)
        }

        return runCatching {
            val doc = app.get(fixed, headers = headersFor(referer)).document
            val out = LinkedHashSet<String>()
            out.addAll(doc.extractServersFast())
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }
            if (out.isEmpty()) listOf(fixed) else out.toList()
        }.getOrElse { listOf(fixed) }
    }

    private fun Document.extractEpisodes(seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()

        val candidates = this.select(
            "a[href*=%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9], " +
                "a:contains(الحلقة), a:contains(مشاهدة), " +
                "a[href*=/episode], a[href*=/episodes], " +
                "a[href*=%D9%85%D8%B4%D8%A7%D9%87%D8%AF%D8%A9-%D9%85%D8%B3%D9%84%D8%B3%D9%84]"
        )

        candidates.forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach

            val link = normalizeUrl(fixUrl(href))
            if (!link.startsWith(mainUrl)) return@forEach

            val rawName = a.text().trim().ifBlank { "حلقة" }

            val epNum =
                Regex("""الحلقة\s*(\d{1,4})""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""[-/](\d{1,4})(?:/)?$""").find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""(\d{1,4})""").findAll(rawName).mapNotNull { it.value.toIntOrNull() }.lastOrNull()

            val ep = newEpisode(link) {
                this.name = rawName
                this.season = 1
                this.episode = epNum
            }

            found[link] = ep
        }

        val list = found.values.toList()
        if (list.isEmpty()) {
            return listOf(
                newEpisode(seriesUrl) {
                    this.name = "مشاهدة"
                    this.season = 1
                    this.episode = 1
                }
            )
        }

        return list.sortedWith(compareBy<Episode> { it.episode ?: 99999 })
    }

    // ---------------------------
    // MainPage
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = safeHeaders).document

        val elements = doc.select(
            "article, div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item, div.BlockItem, div.GridItem, div.Item"
        )

        val ogLimit = 20
        var ogCount = 0

        val items = elements.mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null
            val link = normalizeUrl(fixUrl(a.attr("href").trim()))

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)

            var poster = element.extractPosterFromCard()
            if (looksPlaceholder(poster) && ogCount < ogLimit) {
                ogCount++
                poster = fetchOgPosterCached(link)
            }

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = posterHeadersFor()
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val doc = app.get("$mainUrl/?s=$q", headers = safeHeaders).document

        val elements = doc.select(
            "article, div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item, div.BlockItem, div.GridItem, div.Item"
        )

        val items = elements.mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null
            val link = normalizeUrl(fixUrl(a.attr("href").trim()))

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)

            var poster = element.extractPosterFromCard()
            if (looksPlaceholder(poster)) poster = fetchOgPosterCached(link)

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = posterHeadersFor()
            }
        }.distinctBy { it.url }

        return items
    }

    // ---------------------------
    // Load
    // ---------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = safeHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "WeCima"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src]")?.attr("src")?.trim()

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val type = guessTypeFrom(url, title)

        if (type == TvType.TvSeries) {
            val episodes = doc.extractEpisodes(url)
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = posterHeadersFor()
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = posterHeadersFor()
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

        val pageUrl = data.trim()
        if (pageUrl.isBlank()) return false

        val doc = app.get(pageUrl, headers = headersFor("$mainUrl/")).document

        val servers = LinkedHashSet<String>()

        // 1) expand data-watch gateways (most important)
        val dataWatchLinks = doc.select("[data-watch]")
            .mapNotNull { it.attr("data-watch")?.trim() }
            .filter { it.isNotBlank() }

        dataWatchLinks.take(30).forEach { dw ->
            runCatching { expandDataWatchLink(dw, pageUrl).forEach { servers.add(it) } }
        }

        // 2) direct media from scripts
        doc.extractDirectMediaFromScripts().forEach { servers.add(it) }

        // 3) iframes / onclick / other attrs
        doc.extractServersFast().forEach { servers.add(it) }

        if (servers.isEmpty()) return false

        // resolve internal links one step
        val finalLinks = LinkedHashSet<String>()
        val takeN = min(servers.size, 80)
        servers.toList().take(takeN).forEach { s ->
            finalLinks.addAll(resolveInternalIfNeeded(s, pageUrl))
        }

        if (finalLinks.isEmpty()) return false

        var foundAny = false

        finalLinks.distinct().take(120).forEach { link ->
            val l = link.lowercase()

            // direct mp4/m3u8
            if (l.contains(".mp4") || l.contains(".m3u8")) {
                val type = if (l.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                val el = newExtractorLink(
                    source = name,
                    name = "WeCima Direct",
                    url = link,
                    type = type
                ) {
                    referer = pageUrl
                    quality = Qualities.Unknown.value
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to pageUrl
                    )
                }

                callback(el)
                foundAny = true
                return@forEach
            }

            // normal extractors
            runCatching {
                val ok = loadExtractor(link, pageUrl, subtitleCallback, callback)
                if (ok) foundAny = true
            }
        }

        return foundAny
    }
}
