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

    // ✅ Hotlink protection (images)
    private val posterHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    // ✅ Cache to avoid slow repeated requests
    private val posterCache = LinkedHashMap<String, String?>()
    private val linksCache = LinkedHashMap<String, List<String>>()

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
    // Helpers
    // ---------------------------

    private fun headersWithReferer(ref: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to ref
        )
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

        fun clean(u: String?): String? {
            val s = u?.trim().orEmpty()
            if (s.isBlank()) return null
            if (s.startsWith("data:")) return null
            return fixUrl(s)
        }

        // 1) src
        clean(img?.attr("src"))?.let { return it }

        // 2) lazy attributes
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

        // 4) background-image on element or child
        val bgEl = this.selectFirst("[style*=background-image]") ?: this
        val style = bgEl.attr("style")
        if (style.contains("background-image")) {
            val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            clean(m?.groupValues?.getOrNull(2))?.let { return it }
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
            val d = app.get(detailsUrl, headers = headersWithReferer(mainUrl)).document
            val og = d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            if (!og.isNullOrBlank()) fixUrl(og) else null
        } catch (_: Throwable) {
            null
        }

        // keep cache small
        if (posterCache.size > 250) {
            val firstKey = posterCache.keys.firstOrNull()
            if (firstKey != null) posterCache.remove(firstKey)
        }

        posterCache[detailsUrl] = result
        return result
    }

    private fun unwrapProtectedLink(input: String): String {
        val u = input.trim()
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

    // ---------------------------
    // Link Extraction (strong)
    // ---------------------------

    private fun unescapeScriptUrl(s: String): String {
        // handle https:\/\/domain\/path
        return s.replace("\\/", "/").replace("\\u0026", "&")
    }

    private fun Document.extractServersFromScripts(): List<String> {
        val out = LinkedHashSet<String>()
        val scripts = this.select("script")

        // WeCima often stores server URLs in scripts (json-like)
        val rx = Regex("""(https?:\\?/\\?/[^"'\s]+|https?://[^"'\s]+)""")
        for (sc in scripts) {
            val t = sc.data()
            rx.findAll(t).forEach { m ->
                val raw = m.value.trim()
                val clean = unescapeScriptUrl(raw)
                if (clean.contains("google") || clean.contains("facebook")) return@forEach
                out.add(clean)
            }

            // Also catch "data-watch":"..."
            val rxWatch = Regex("""data-watch["']\s*:\s*["']([^"']+)["']""")
            rxWatch.findAll(t).forEach { m ->
                val v = unescapeScriptUrl(m.groupValues[1].trim())
                if (v.isNotBlank()) out.add(fixUrl(v))
            }
        }
        return out.toList()
    }

    private fun Document.extractPossibleLinks(): List<String> {
        val out = LinkedHashSet<String>()

        // iframe
        this.select("iframe[src]").forEach {
            val s = it.attr("src").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // ✅ MAIN: data-watch
        this.select("li[data-watch], [data-watch]").forEach {
            val s = it.attr("data-watch").trim()
            if (s.isNotBlank()) out.add(fixUrl(s))
        }

        // more data attrs
        this.select("[data-embed-url], [data-url], [data-href], [data-embed]").forEach {
            val s = it.attr("data-embed-url").ifBlank { it.attr("data-url") }
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

        // anchors that look like players
        this.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.contains("player") || href.contains("embed") || href.contains("watch") || href.contains("play.php")) {
                out.add(fixUrl(href))
            }
        }

        // scripts fallback
        this.extractServersFromScripts().forEach { out.add(fixUrl(it)) }

        return out.toList()
    }

    private suspend fun crawlResolveLinks(startUrl: String, maxDepth: Int = 3): List<String> {
        // cache first
        if (linksCache.containsKey(startUrl)) return linksCache[startUrl] ?: emptyList()

        val visited = HashSet<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        val final = LinkedHashSet<String>()

        queue.add(startUrl to 0)
        visited.add(startUrl)

        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (depth > maxDepth) continue

            val cleaned = unwrapProtectedLink(current)

            // External link: keep it as final extractor candidate
            val isInternal = cleaned.contains("wecima") || cleaned.startsWith(mainUrl)
            if (!isInternal) {
                final.add(cleaned)
                continue
            }

            // Internal: open and extract
            val doc = try {
                app.get(cleaned, headers = headersWithReferer(startUrl)).document
            } catch (_: Throwable) {
                continue
            }

            val newLinks = doc.extractPossibleLinks()
            for (l in newLinks) {
                val fx = fixUrl(unwrapProtectedLink(l))
                if (fx.isBlank()) continue
                if (visited.add(fx)) {
                    queue.add(fx to (depth + 1))
                }
            }
        }

        val result = final.toList()

        // keep cache small
        if (linksCache.size > 200) {
            val firstKey = linksCache.keys.firstOrNull()
            if (firstKey != null) linksCache.remove(firstKey)
        }

        linksCache[startUrl] = result
        return result
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
            val link = fixUrl(a.attr("href").trim())

            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)

            // ✅ FAST posters: use card first
            var poster = element.extractPosterFromCard()

            // ✅ only fetch og:image when needed (no more slow always)
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
            val link = fixUrl(a.attr("href").trim())

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
    // LoadLinks (Permanent fix)
    // ---------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl = data.trim()
        if (pageUrl.isBlank()) return false

        // ✅ Crawl resolve up to 3 hops to always catch servers
        val finalLinks = crawlResolveLinks(pageUrl, maxDepth = 3)

        if (finalLinks.isEmpty()) return false

        // ✅ Send all found links to extractors
        finalLinks.forEach { link ->
            loadExtractor(link, pageUrl, subtitleCallback, callback)
        }

        return true
    }
}
