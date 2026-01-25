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

    // رأس مخصص للصور لتجاوز الحماية
    private val posterHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    private val posterCache = LinkedHashMap<String, String?>()
    private val linksCache = LinkedHashMap<String, List<String>>()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية (جديد)",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/episodes/" to "آخر الحلقات",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%85%D9%8A/" to "أفلام أنمي",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D9%86%D9%85%D9%8A/" to "مسلسلات أنمي"
    )

    // ---------------------------
    // مساعدات استخراج الصور (محسنة للمسلسلات)
    // ---------------------------

    private fun Element.extractPosterFromCard(): String? {
        // 1. البحث عن الصورة في الخلفية (شائع في المسلسلات)
        // يبحث في العنصر نفسه أو أي عنصر ابن له style
        val bgElements = this.select("[style*=background-image]") + this
        for (el in bgElements) {
            val style = el.attr("style")
            if (style.contains("background-image")) {
                val m = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
                val url = m?.groupValues?.getOrNull(2)?.trim()
                if (!url.isNullOrBlank() && !url.startsWith("data:")) return fixUrl(url)
            }
        }
        
        // 2. البحث عن الوسم img العادي
        val img = this.selectFirst("img") ?: return null

        fun clean(u: String?): String? {
            val s = u?.trim().orEmpty()
            if (s.isBlank()) return null
            if (s.startsWith("data:")) return null
            return fixUrl(s)
        }

        // تجربة كل السمات المحتملة للصور المؤجلة (Lazy Loading)
        val lazyAttrs = listOf(
            "data-src", "data-original", "data-lazy-src", "data-image", 
            "data-bg", "src"
        )
        for (a in lazyAttrs) {
            clean(img.attr(a))?.let { return it }
        }

        return null
    }

    private fun looksPlaceholder(poster: String?): Boolean {
        if (poster.isNullOrBlank()) return true
        val p = poster.lowercase()
        return p.contains("placeholder") || p.contains("noimage") || p.contains("default")
    }

    // ---------------------------
    // استخراج العناوين والروابط
    // ---------------------------

    private fun Element.extractTitleStrong(): String? {
        val h = this.selectFirst("h1,h2,h3,.title,.name")?.text()?.trim()
        if (!h.isNullOrBlank()) return h
        return this.selectFirst("a")?.attr("title")?.trim() ?: this.selectFirst("a")?.text()?.trim()
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
            val d = app.get(detailsUrl, headers = mapOf("Referer" to mainUrl)).document
            val og = d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            if (!og.isNullOrBlank()) fixUrl(og) else null
        } catch (_: Throwable) { null }
        if (posterCache.size > 200) posterCache.remove(posterCache.keys.first())
        posterCache[detailsUrl] = result
        return result
    }

    private fun unwrapProtectedLink(input: String): String {
        val u = input.trim()
        val m = Regex("""[?&](url|u|r)=([^&]+)""").find(u)
        if (m != null) {
            val encoded = m.groupValues.getOrNull(2)
            if (!encoded.isNullOrBlank()) {
                return try { URLDecoder.decode(encoded, "UTF-8") } catch (_: Throwable) { u }
            }
        }
        return u
    }

    // ---------------------------
    // الصفحة الرئيسية والبحث
    // ---------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = safeHeaders).document
        val items = doc.select("div.GridItem, div.BlockItem, div.movie, article").mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href").trim())
            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)
            
            // محاولة استخراج الصورة بقوة أكبر
            var poster = element.extractPosterFromCard()
            // إذا فشل الاستخراج من الكارد، نجلبه من صفحة التفاصيل (كحل أخير)
            if (looksPlaceholder(poster)) poster = fetchOgPosterCached(link)

            newMovieSearchResponse(title, link, type) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders
            }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val doc = app.get("$mainUrl/AjaxCenter/Searching/$q/", headers = safeHeaders).document
        return doc.select("div.GridItem, div.BlockItem, div.movie").mapNotNull { element ->
            val a = element.selectFirst("a[href]") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href").trim())
            val title = element.extractTitleStrong() ?: return@mapNotNull null
            val type = guessTypeFrom(link, title)
            var poster = element.extractPosterFromCard()
            if (looksPlaceholder(poster)) poster = fetchOgPosterCached(link)

            newMovieSearchResponse(title, link, type) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders
            }
        }.distinctBy { it.url }
    }

    // ---------------------------
    // تحميل التفاصيل (Load)
    // ---------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = safeHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim() 
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "WeCima"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") 
            ?: doc.selectFirst("img[src]")?.attr("src")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")

        // التحقق مما إذا كان مسلسلاً عبر وجود قائمة حلقات
        val episodeElements = doc.select(".EpisodesList a, .Seasons--Episodes a")
        
        if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull {
                val href = it.attr("href")
                if (href.isBlank()) return@mapNotNull null
                val name = it.text().trim()
                val epNum = Regex("\\d+").findAll(name).lastOrNull()?.value?.toIntOrNull()
                
                Episode(
                    data = fixUrl(href),
                    name = name,
                    episode = epNum
                )
            }.reversed()
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@WeCimaProvider.posterHeaders
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = this@WeCimaProvider.posterHeaders
            this.plot = plot
        }
    }

    // ---------------------------
    // استخراج الروابط (تم التعديل لجلب كل السيرفرات)
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
        val foundLinks = LinkedHashSet<String>()

        // 1. استخراج السيرفرات من القائمة (Tabs) - هذا هو الجزء المفقود
        // نبحث عن كل عنصر li داخل قائمة السيرفرات
        val serverListItems = doc.select("ul.WatchServers > li, ul.ServersList > li, .ServersList li")
        
        serverListItems.forEach { li ->
            // نحاول استخراج الرابط من data-watch أو data-url أو من زر داخلي
            val link = li.attr("data-watch").ifBlank { 
                li.attr("data-url").ifBlank { 
                    li.selectFirst("a")?.attr("data-watch") 
                } 
            }?.trim()

            if (!link.isNullOrBlank()) {
                foundLinks.add(fixUrl(unwrapProtectedLink(link)))
            }
        }

        // 2. استخراج الروابط من iframes مباشرة (للسيرفر النشط حالياً)
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) foundLinks.add(fixUrl(src))
        }

        // 3. (اختياري) استخدام الزحف كخيار احتياطي إذا لم نجد شيئاً في القوائم
        if (foundLinks.isEmpty()) {
             // كود الزحف القديم يمكن وضعه هنا، لكن استخراج القوائم عادة ما يكون كافياً وأسرع
        }

        // تمرير الروابط للمستخرجات
        foundLinks.forEach { link ->
            loadExtractor(link, pageUrl, subtitleCallback, callback)
        }

        return foundLinks.isNotEmpty()
    }
}
