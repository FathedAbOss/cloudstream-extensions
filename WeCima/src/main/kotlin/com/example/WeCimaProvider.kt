package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.LinkedHashMap
import kotlin.math.min

class WeCimaProvider : MainAPI() {

    override var mainUrl = "https://wecima.date"
    override var name = "WeCima"
    override var lang = "ar"
    override var hasMainPage = true

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val safeHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    /**
     * ✅ FIX: posters disappeared => usually "Origin/Accept" causes hotlink issues.
     * Keep it minimal: UA + Referer only.
     */
    private val posterHeadersSafe = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    // ✅ poster cache to reduce slowdown
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
            "data-background-image",
            "srcset"
        )

        if (img != null) {
            for (a in lazyAttrs) {
                val v = img.attr(a)
                if (a == "srcset" && v.isNotBlank()) {
                    val first = v.split(" ").firstOrNull { it.startsWith("http") || it.startsWith("/") }?.trim()
                    cleanUrl(first)?.let { return it }
                } else {
                    cleanUrl(v)?.let { return it }
                }
            }
        }

        val holder = this.selectFirst("[data-src], [data-image], [data-bg], [data-background-image]") ?: this
        for (a in lazyAttrs) {
            val v = holder.attr(a)
            if (a == "srcset" && v.isNotBlank()) {
                val first = v.split(" ").firstOrNull { it.startsWith("http") || it.startsWith("/") }?.trim()
                cleanUrl(first)?.let { return it }
            } else {
                cleanUrl(v)?.let { return it }
            }
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

    // ✅ OG fallback poster (cached + limited use)
    private suspend fun fetchOgPosterCached(detailsUrl: String): String? {
        if (posterCache.containsKey(detailsUrl)) return posterCache[detailsUrl]

        val result = try {
            val d = app.get(detailsUrl, headers = safeHeaders).document
            val og = d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            if (!og.isNullOrBlank()) fixUrl(og) else null
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

    // ✅ Extract MP4/M3U8 from page scripts
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

    // ✅ extract servers from WeCima page (fast)
    private fun Document.extractServersFast(): List<String> {
        val out = LinkedHashSet<String>()

        // data-watch
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

    // ✅ Resolve internal watch/player pages one step deeper (LIMITED)
    private suspend fun resolveInternalIfNeeded(link: String, referer: String): List<String> {
        val fixed = fixUrl(link).trim()
        if (fixed.isBlank()) return emptyList()

        val isInternal = fixed.startsWith(mainUrl) || fixed.contains("wecima", ignoreCase = true)
        if (!isInternal) return listOf(fixed)

        // Only follow if it looks like a player/watch page
        val l = fixed.lowercase()
        val shouldFollow = l.contains("watch") || l.contains("player") || l.contains("play") || l.contains("مشاهدة")
        if (!shouldFollow) return listOf(fixed)

        return try {
            val doc = app.get(
                fixed,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            ).document

            val out = LinkedHashSet<String>()
            out.addAll(doc.extractServersFast())
            doc.extractDirectMediaFromScripts().forEach { out.add(it) }

            if (out.isEmpty()) listOf(fixed) else out.toList()
        } catch (_: Throwable) {
            listOf(fixed)
        }
    }

    // ✅ better episode extraction for series pages
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

        val ogLimit = 12
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
                this.posterHeaders = this@WeCimaProvider.posterHeadersSafe
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
            if (looksPlaceholder(poster)) {
                poster = fetchOgPosterCached(link)
            }

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeadersSafe
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
                this.posterHeaders = this@WeCimaProvider.posterHeadersSafe
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = this@WeCimaProvider.posterHeadersSafe
            this.plot = plot
        }
    }

    // ---------------------------
    // LoadLinks (LESS REQUESTS + MORE STABLE)
    // ---------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl = data.trim()
        if (pageUrl.isBlank()) return false

        val doc = app.get(
            pageUrl,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        val baseCandidates = LinkedHashSet<String>()

        // 1) direct media
        doc.extractDirectMediaFromScripts().forEach { baseCandidates.add(it) }

        // 2) fast server attrs/iframes
        doc.extractServersFast().forEach { baseCandidates.add(it) }

        if (baseCandidates.isEmpty()) return false

        // ✅ IMPORTANT: don't crawl too many (this causes forever loading)
        val limited = baseCandidates.toList().take(18)

        val finalLinks = LinkedHashSet<String>()
        limited.forEach { s ->
            finalLinks.addAll(resolveInternalIfNeeded(s, pageUrl))
        }

        if (finalLinks.isEmpty()) return false

        var foundAny = false

        finalLinks.distinct().take(25).forEach { link ->
            val l = link.lowercase()

            if (l.contains(".mp4") || l.contains(".m3u8")) {
                val isM3u8 = l.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "WeCima Direct",
                        url = link,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to pageUrl
                        )
                    }
                )
                foundAny = true
                return@forEach
            }

            runCatching {
                loadExtractor(link, pageUrl, subtitleCallback, callback)
                foundAny = true
            }
        }

        return foundAny
    }
}
