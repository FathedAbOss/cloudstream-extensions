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

    // ✅ Hotlink protection for images
    private val posterHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    // ✅ cache posters so we don't slow down
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

        // ✅ data-watch
        this.select("li[data-watch], [data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // ✅ iframe direct
        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // ✅ fallback attributes
        this.select("[data-embed-url], [data-url], [data-href]").forEach {
            val s = it.attr("data-embed-url")
                .ifBlank { it.attr("data-url") }
                .ifBlank { it.attr("data-href") }
                .trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        return out.toList()
    }

    // ✅ Resolve internal WeCima links (watch/player) to real iframe
    private suspend fun resolveInternalIfNeeded(link: String, referer: String): List<String> {
        val fixed = fixUrl(link).trim()
        if (fixed.isBlank()) return emptyList()

        val isInternal = fixed.contains("wecima") || fixed.startsWith(mainUrl)
        if (!isInternal) return listOf(fixed)

        if (!(fixed.contains("watch") || fixed.contains("player") || fixed.contains("play"))) {
            return listOf(fixed)
        }

        return try {
            val doc = app.get(
                fixed,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
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

    // ✅ Extract episodes list from series page (sorted correctly)
    private fun Document.extractEpisodes(seriesUrl: String): List<Episode> {
        val found = LinkedHashMap<String, Episode>()

        val candidates = this.select(
            "a[href*=/episode], a[href*=/episodes], a:contains(الحلقة), a:contains(مشاهدة)"
        )

        candidates.forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach

            val link = normalizeUrl(fixUrl(href))
            if (!link.contains("wecima") && !link.startsWith(mainUrl)) return@forEach

            val name = a.text().trim().ifBlank { "حلقة" }

            // ✅ take LAST number from the text (usually episode number)
            val allNums = Regex("""(\d{1,4})""")
                .findAll(name)
                .mapNotNull { it.value.toIntOrNull() }
                .toList()

            val epNum = allNums.lastOrNull()

            // ✅ Use newEpisode (avoid deprecated Episode constructor)
            val ep = newEpisode(link) {
                this.name = name
                this.season = 1
                this.episode = epNum
            }

            found[link] = ep
        }

        // fallback: if no episode links found
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

        // ✅ sort by episode number
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
    // Load ✅ FIX SERIES (API compatible)
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

            // ✅ Your API requires episodes parameter
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
    // LoadLinks ✅ internal resolve
    // ---------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl = data.trim()
        if (pageUrl.isBlank()) return false

        val doc = app.get(pageUrl, headers = safeHeaders).document
        val servers = doc.extractServersFast()
        if (servers.isEmpty()) return false

        val finalLinks = LinkedHashSet<String>()

        // ✅ resolve internal links (watch/player) one-step only
        servers.take(25).forEach { s ->
            finalLinks.addAll(resolveInternalIfNeeded(s, pageUrl))
        }

        if (finalLinks.isEmpty()) return false

        finalLinks.forEach { link ->
            loadExtractor(link, pageUrl, subtitleCallback, callback)
        }

        return true
    }
}
