package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WeCimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.date"
    override var name = "WeCima"
    override var lang = "ar"
    override var hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // لو عندك أقسام جاهزة شغالة عندك، خليه مثل ما كان.
    // هذا مجرد مثال مرن.
    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/anime" to "أنمي"
    )

    // -------- Helpers --------

    private fun Element.smartAttr(vararg keys: String): String {
        for (k in keys) {
            val v = this.attr(k)?.trim()
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    private fun Document.pickCards(): List<Element> {
        // سيليكتورات مرنة لأن الواجهات تختلف حسب القسم
        val selectors = listOf(
            "article",
            "div.GridItem",
            "div.Thumb--GridItem",
            "div.Thumb",
            "div.item",
            "div.col-md-2, div.col-md-3, div.col-sm-6"
        )
        for (sel in selectors) {
            val got = this.select(sel)
            if (got.isNotEmpty()) return got
        }
        return this.select("body *")
    }

    private fun Element.cardLink(): String {
        val a = this.selectFirst("a[href]")
        return if (a != null) fixUrl(a.attr("href")) else ""
    }

    private fun Element.cardTitle(): String {
        return this.selectFirst("h3, h2, .title, .name, .Title")?.text()?.trim()
            ?: this.selectFirst("a[title]")?.attr("title")?.trim()
            ?: this.selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: ""
    }

    private fun Element.cardPoster(): String {
        val img = this.selectFirst("img")
        if (img != null) {
            val raw = img.smartAttr("data-src", "data-lazy-src", "src", "data-original")
            if (raw.isNotBlank()) return fixUrl(raw)
        }
        return ""
    }

    private fun String.isDirectMedia(): Boolean {
        val u = this.lowercase()
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".webm") || u.contains(".mkv")
    }

    private fun extractMediaFromText(text: String): List<String> {
        // التقط أي mp4/m3u8 مخبّي داخل سكربت
        val r = Regex("""https?:\/\/[^\s"'<>\\]+?\.(m3u8|mp4|webm|mkv)(\?[^\s"'<>\\]+)?""", RegexOption.IGNORE_CASE)
        return r.findAll(text).map { it.value }.distinct().toList()
    }

    // -------- Main Page --------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val items = doc.pickCards().mapNotNull { card ->
            val link = card.cardLink()
            val title = card.cardTitle()
            if (link.isBlank() || title.isBlank()) return@mapNotNull null
            val poster = card.cardPoster()
            newMovieSearchResponse(
                name = title,
                url = link,
                type = if (link.contains("/series/")) TvType.TvSeries else TvType.Movie
            ) {
                posterUrl = poster
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = false
        )
    }

    // -------- Search --------

    override suspend fun search(query: String): List<SearchResponse> {
        // غالباً عندهم صفحة بحث مثل /?s= أو /search/
        val urlsToTry = listOf(
            "$mainUrl/?s=${query.urlEncoded()}",
            "$mainUrl/search/${query.urlEncoded()}"
        )

        var doc: Document? = null
        for (u in urlsToTry) {
            runCatching {
                val d = app.get(u).document
                // إذا لقينا نتائج من أي سيليكتور معروف نوقف
                if (d.select("a[href]").isNotEmpty()) {
                    doc = d
                    return@runCatching
                }
            }
        }

        val document = doc ?: return emptyList()

        return document.pickCards().mapNotNull { card ->
            val link = card.cardLink()
            val title = card.cardTitle()
            if (link.isBlank() || title.isBlank()) return@mapNotNull null
            val poster = card.cardPoster()

            val type = when {
                link.contains("/series/") -> TvType.TvSeries
                link.contains("/anime") || title.contains("انمي") || title.contains("أنمي") -> TvType.Anime
                else -> TvType.Movie
            }

            newMovieSearchResponse(title, link, type) {
                posterUrl = poster
            }
        }
    }

    // -------- Load Details --------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, h2, .title, .Title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img.poster, img[alt]")?.smartAttr("data-src", "src", "data-original")
            ?: ""

        val plot = doc.selectFirst(".story, .synopsis, .post-content, .content, .desc")?.text()?.trim()

        val isSeries = url.contains("/series/") || doc.select("a[href*=\"مشاهدة-\"], a[href*=\"episode\"], a[href*=\"الحلقة\"]").isNotEmpty()

        if (!isSeries) {
            // فيلم
            return newMovieLoadResponse(title, url, TvType.Movie, dataUrl = url) {
                posterUrl = fixUrlNull(poster)
                this.plot = plot
            }
        }

        // مسلسل/أنمي
        val epLinks = doc.select("a[href]").mapNotNull { a ->
            val href = fixUrl(a.attr("href"))
            // روابط الحلقات عندهم غالباً فيها "مشاهدة-" أو "الحلقة"
            if (href.contains("مشاهدة") || href.contains("الحلقة") || href.contains("episode")) href else null
        }.distinct()

        val episodes = epLinks.mapIndexed { idx, epUrl ->
            // حاول استنتاج رقم الحلقة من الرابط/النص
            val text = doc.selectFirst("a[href=\"$epUrl\"]")?.text()?.trim().orEmpty()
            val numFromUrl = Regex("""(\d{1,4})""").find(epUrl)?.value?.toIntOrNull()
            val numFromText = Regex("""(\d{1,4})""").find(text)?.value?.toIntOrNull()
            val epNum = numFromText ?: numFromUrl ?: (idx + 1)

            Episode(
                data = epUrl,             // مهم جداً: هذا الذي يذهب لـ loadLinks
                name = "الحلقة $epNum",
                episode = epNum
            )
        }.sortedBy { it.episode ?: 0 }

        val tvType = if (url.contains("anime") || title.contains("انمي") || title.contains("أنمي")) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, tvType) {
            posterUrl = fixUrlNull(poster)
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // -------- Links Extraction (THE ONLY IMPORTANT CHANGE NOW) --------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val baseReferer = data.ifBlank { mainUrl }

        fun collectCandidates(document: Document): MutableSet<String> {
            val out = mutableSetOf<String>()

            // 1) iframes
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank()) out += fixUrl(src)
            }

            // 2) video/source
            document.select("video source[src], source[src], video[src]").forEach { s ->
                val src = s.attr("src").trim()
                if (src.isNotBlank()) out += fixUrl(src)
            }

            // 3) server buttons / links with data-url/data-href
            document.select("a[data-url], a[data-href], button[data-url], button[data-href], li[data-url], li[data-href], div[data-url], div[data-href]").forEach { el ->
                val u = el.smartAttr("data-url", "data-href").trim()
                if (u.isNotBlank()) out += fixUrl(u)
            }

            // 4) normal links that often represent servers
            document.select("a[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isNotBlank()) {
                    val fixed = fixUrl(href)
                    // أحياناً السيرفر يكون رابط خارجي مباشر أو صفحة وسيطة
                    if (fixed.startsWith("http")) out += fixed
                }
            }

            // 5) onclick="....('url')"
            document.select("[onclick]").forEach { el ->
                val oc = el.attr("onclick")
                val m = Regex("""https?:\/\/[^'"]+""").findAll(oc).map { it.value }.toList()
                m.forEach { out += it }
            }

            // 6) mp4/m3u8 inside scripts
            out += extractMediaFromText(document.html())

            return out
        }

        suspend fun followOnceIfInternal(url: String, referer: String): List<String> {
            // اتبع صفحة وسيطة داخل wecima مرة واحدة فقط
            if (!url.startsWith("http")) return emptyList()
            if (!url.startsWith(mainUrl)) return emptyList()
            // تجنب إعادة جلب نفس صفحة الحلقة
            if (url == data) return emptyList()

            return runCatching {
                val d = app.get(url, referer = referer).document
                collectCandidates(d).toList()
            }.getOrDefault(emptyList())
        }

        val doc = runCatching { app.get(data, referer = mainUrl).document }.getOrNull() ?: return false

        val candidates = collectCandidates(doc)

        // إذا ما طلع شيء واضح، جرّب تتابع روابط داخلية مرة واحدة (لأن كثير مواقع تعمل redirect داخلي)
        val extra = mutableSetOf<String>()
        candidates.take(30).forEach { u ->
            if (!u.isDirectMedia()) {
                followOnceIfInternal(u, baseReferer).forEach { extra += it }
            }
        }
        candidates += extra

        // نظّف وفلتر
        val finalUrls = candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(120)

        var foundAny = false

        for (u in finalUrls) {
            val url = u

            // إذا رابط مباشر mp4/m3u8 -> ارسله مباشرة
            if (url.isDirectMedia()) {
                foundAny = true
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        referer = baseReferer,
                        quality = Qualities.Unknown.value,
                        isM3u8 = url.lowercase().contains(".m3u8")
                    )
                )
                continue
            }

            // وإلا حاول بالاكستراكتورات (MixDrop/Vidplay/Streamtape.. إلخ)
            runCatching {
                val ok = loadExtractor(url, baseReferer, subtitleCallback, callback)
                if (ok) foundAny = true
            }
        }

        return foundAny
    }
}
