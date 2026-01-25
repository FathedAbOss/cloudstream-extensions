package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

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

    // ✅ Strong poster headers (hotlink protection fix)
    private val posterHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    // ✅ Website-like sections
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية (جديد)",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/episodes/" to "آخر الحلقات",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    // ---------------------------
    // Helpers: Title / Poster / Type
    // ---------------------------

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

    private fun Element.extractPosterStrong(): String? {
        val img = this.selectFirst("img")

        fun clean(u: String?): String? {
            val s = u?.trim().orEmpty()
            if (s.isBlank()) return null
            if (s.startsWith("data:")) return null
            return fixUrl(s)
        }

        // 1) normal src
        clean(img?.attr("src"))?.let { return it }

        // 2) common lazy-load attributes
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
                clean(img.attr(a))?.let { return it }
            }
        }

        // 3) srcset / data-srcset
        val srcset = img?.attr("srcset")?.ifBlank { img.attr("data-srcset") }?.trim()
        if (!srcset.isNullOrBlank()) {
            val first = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
            clean(first)?.let { return it }
        }

        // 4) background-image style on this element
        val style = this.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            clean(m?.groupValues?.getOrNull(2))?.let { return it }
        }

        // 5) background-image on child elements
        val childBg = this.selectFirst("[style*=background-image]")
        if (childBg != null) {
            val st = childBg.attr("style")
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(st)
            clean(m?.groupValues?.getOrNull(2))?.let { return it }
        }

        // 6) any element with lazy attributes
        val any = this.selectFirst("[data-src], [data-original], [data-image], [data-thumb], [data-poster], [data-bg]")
        if (any != null) {
            for (a in lazyAttrs) {
                clean(any.attr(a))?.let { return it }
            }
        }

        return null
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

    private fun unwrapProtectedLink(input: String): String {
        val u = input.trim()

        // protector like ?url=https%3A%2F%2Fvinovo...
        val m = Regex("""[?&](url|u|r)=([^&]+)""").find(u)
        if (m != null) {
            val encoded = m.groupValues.getOrNull(2)
            if (!encoded.isNullOrBlank()) {
                return try {
                    URLDecoder.decode(encoded, "UTF-8")
                } catch (_: Throwable) {
                    u
                }
            }
        }

        return u
    }

    private fun Document.extractPossibleLinks(): List<String> {
        val out = LinkedHashSet<String>()

        // iframe
        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // ✅ MAIN: data-watch (WeCima current structure)
        this.select("li[data-watch], [data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // other data attrs
        this.select("[data-url], [data-href], [data-embed-url], [data-embed]").forEach {
            val s = it.attr("data-watch").ifBlank { it.attr("data-embed-url") }
                .ifBlank { it.attr("data-url") }
                .ifBlank { it.attr("data-href") }
                .ifBlank { it.attr("data-embed") }
                .trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // onclick
        this.select("[onclick]").forEach { el ->
            val on = el.attr("onclick")
            val m = Regex("""(https?://[^\s'"]+|//[^\s'"]+)""").find(on)
            val u = m?.value?.trim()
            if (!u.isNullOrBlank()) out.add(fixUrl(u))
        }

        return out.toList()
    }

    private suspend fun resolveIfInternal(link: String, baseUrl: String): List<String> {
        val cleaned = unwrapProtectedLink(link)

        val isInternal = cleaned.contains("wecima") || cleaned.startsWith(mainUrl)
        if (!isInternal) return listOf(cleaned)

        return try {
            val doc = app.get(cleaned, headers = safeHeaders).document
            val more = doc.extractPossibleLinks()
            if (more.isEmpty()) listOf(cleaned) else more
        } catch (_: Throwable) {
            listOf(cleaned)
        }
    }

    // ---------------------------
    // Main Page / Search
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = safeHeaders).document

        val items = doc.select(
            "article, div.col-md-2, div.col-xs-6, div.movie, article.post, div.post-block, div.box, li.item, div.BlockItem, div.GridItem, div.Item"
        ).mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href").trim())

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val poster = element.extractPosterStrong()
            val type = guessTypeFrom(link, title)

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
            val link = fixUrl(a.attr("href").trim())

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val poster = element.extractPosterStrong()
            val type = guessTypeFrom(link, title)

            newMovieSearchResponse(title, link, type) {
                posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders
            }
        }.distinctBy { it.url }
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

        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = this@WeCimaProvider.posterHeaders
            this.plot = plot
        }
    }

    // ---------------------------
    // LoadLinks ✅ working
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

        // Step 1: get links from the page
        val rawLinks = LinkedHashSet<String>()
        rawLinks.addAll(doc.extractPossibleLinks())

        if (rawLinks.isEmpty()) return false

        // Step 2: resolve internal/protected links (one step)
        val finalLinks = LinkedHashSet<String>()
        for (l in rawLinks) {
            val resolved = resolveIfInternal(l, pageUrl)
            resolved.forEach { finalLinks.add(it) }
        }

        if (finalLinks.isEmpty()) return false

        // Step 3: send links to extractors
        finalLinks.forEach { link ->
            loadExtractor(link, pageUrl, subtitleCallback, callback)
        }

        return true
    }
}
