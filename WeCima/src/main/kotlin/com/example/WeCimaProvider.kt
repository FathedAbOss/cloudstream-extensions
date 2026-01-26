package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.LinkedHashMap

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
        "Referer" to mainUrl
    )

    private val posterHeaders = mapOf(
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

    private fun Element.cleanUrl(u: String?): String? {
        val s = u?.trim().orEmpty()
        if (s.isBlank()) return null
        if (s.startsWith("data:")) return null
        return fixUrl(s)
    }

    private fun Element.extractPosterFromCard(): String? {
        val img = this.selectFirst("img")
        cleanUrl(img?.attr("src"))?.let { return it }

        val lazyAttrs = listOf(
            "data-src", "data-original", "data-lazy-src", "data-image",
            "data-thumb", "data-poster", "data-cover", "data-img",
            "data-bg", "data-background", "data-background-image"
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
            if (!og.isNullOrBlank()) {
                fixUrl(og)
            } else {
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

    private fun Document.extractServersFast(): List<String> {
        val out = LinkedHashSet<String>()

        this.select("[data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        this.select("[data-url], [data-href], [data-embed-url], [data-src]").forEach {
            val s = it.attr("data-watch")
                .ifBlank { it.attr("data-embed-url") }
                .ifBlank { it.attr("data-url") }
                .ifBlank { it.attr("data-href") }
                .ifBlank { it.attr("data-src") }
                .trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        this.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach
            val h = href.lowercase()

            if (
                h.contains("watch") || h.contains("player") || h.contains("play") || h.contains("embed") ||
                h.contains("vinovo") || h.contains("mixdrop") || h.contains("dood") || h.contains("voe") ||
                h.contains("streamtape") || h.contains("ok.ru") || h.contains("uqload")
            ) {
                out.add(fixUrl(href))
            }
        }

        return out.toList()
    }

    private suspend fun resolveInternalIfNeeded(link: String, referer: String): List<String> {
        val fixed = fixUrl(link).trim()
        if (fixed.isBlank()) return emptyList()

        val isInternal = fixed.contains("wecima") || fixed.startsWith(mainUrl)
        if (!isInternal) return listOf(fixed)

        if (!(fixed.contains("watch") || fixed.contains("player") || fixed.contains("play") || fixed.contains("embed"))) {
            return listOf(fixed)
        }

        return try {
            val doc = app.get(
                fixed,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Origin" to mainUrl)
            ).document

            val out = LinkedHashSet<String>()

            doc.select("iframe[src]").forEach {
                val s = it.attr("src").trim()
                if (s.isNotBlank()) out.add(fixUrl(s))
            }

            doc.select("[data-watch]").forEach {
                val s = it.attr("data-watch").trim()
                if (s.isNotBlank()) out.add(fixUrl(s))
            }

            if (out.isEmpty()) listOf(fixed) else out.toList()
        } catch (_: Throwable) {
            listOf(fixed)
        }
    }

    private fun Document.extractEpisodes(seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()

        val candidates = this.select(
            "a[href*=/episode], a[href*=/episodes], a:contains(الحلقة), a:contains(مشاهدة)"
        )

        candidates.forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach

            val link = normalizeUrl(fixUrl(href))
            val name = a.text().trim().ifBlank { "حلقة" }

            val allNums = Regex("""(\d{1,4})""")
                .findAll(name)
                .mapNotNull { it.value.toIntOrNull() }
                .toList()

            val epNum = allNums.lastOrNull()

            val ep = newEpisode(link) {
                this.name = name
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
    // MainPage / Search
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = safeHeaders).document

        val items = doc.select(
            "article, div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item, div.BlockItem, div.GridItem, div.Item"
        ).mapNotNull { element ->
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
                this.posterHeaders = this@WeCimaProvider.posterHeaders
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val doc = app.get("$mainUrl/?s=$q", headers = safeHeaders).document

        return doc.select(
            "article, div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item, div.BlockItem, div.GridItem, div.Item"
        ).mapNotNull { element ->
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
                this.posterHeaders = this@WeCimaProvider.posterHeaders
            }
        }.distinctBy { it.url }
    }

    // ---------------------------
    // Load ✅ FIX SERIES
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
                this.posterHeaders = this@WeCimaProvider.posterHeaders
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = this@WeCimaProvider.posterHeaders
            this.plot = plot
        }
    }

    // ---------------------------
    // LoadLinks ✅ Series Fix via play.php?vid=
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
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to pageUrl,
                "Origin" to mainUrl
            )
        ).document

        val servers = doc.extractServersFast()

        val serversOrPlay = if (servers.isEmpty()) {
            val html = doc.html()

            val vid =
                Regex("""vid=([A-Za-z0-9]+)""").find(pageUrl)?.groupValues?.getOrNull(1)
                    ?: Regex("""vid=([A-Za-z0-9]+)""").find(html)?.groupValues?.getOrNull(1)

            if (vid != null) {
                val playUrl = "$mainUrl/play.php?vid=$vid"
                val playDoc = app.get(
                    playUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to pageUrl,
                        "Origin" to mainUrl
                    )
                ).document

                playDoc.extractServersFast()
            } else {
                emptyList()
            }
        } else servers

        if (serversOrPlay.isEmpty()) return false

        val finalLinks = LinkedHashSet<String>()
        serversOrPlay.take(30).forEach { s ->
            finalLinks.addAll(resolveInternalIfNeeded(s, pageUrl))
        }

        if (finalLinks.isEmpty()) return false

        finalLinks.forEach { link ->
            loadExtractor(link, pageUrl, subtitleCallback, callback)
        }

        return true
    }
}
