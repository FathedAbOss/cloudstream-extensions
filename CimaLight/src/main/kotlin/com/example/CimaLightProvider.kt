package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CimaLightProvider : MainAPI() {

    override var mainUrl = "https://w.cimalight.co"
    override var name = "CimaLight"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // صفحات ثابتة للمحتوى (واضحة ومليانة أفلام)
    override val mainPage = mainPageOf(
        "$mainUrl/movies.php" to "أحدث الأفلام",
        "$mainUrl/main15" to "جديد الموقع",
        "$mainUrl/most.php" to "الأكثر مشاهدة"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(request.data).document

        // أفلام/مسلسلات غالباً تظهر كـ h3 > a
        val items = document.select("h3 a").mapNotNull { a ->
            val title = a.text().trim()
            val link = fixUrl(a.attr("href"))

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            newMovieSearchResponse(
                name = title,
                url = link,
                type = TvType.Movie
            )
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

            newMovieSearchResponse(
                name = title,
                url = link,
                type = TvType.Movie
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        // مهم جداً: نخلي data = صفحة التحميلات downloads.php لأنها فيها روابط السيرفرات
        val vid = Regex("vid=([A-Za-z0-9]+)").find(url)?.groupValues?.getOrNull(1)
        val downloadsUrl = if (vid != null) "$mainUrl/downloads.php?vid=$vid" else url

        return newMovieLoadResponse(
            name = title.ifBlank { "CimaLight" },
            url = url,
            type = TvType.Movie,
            dataUrl = downloadsUrl
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // data هو downloads.php?vid=xxx
        val document = app.get(data).document

        // الروابط الخارجية مثل Krakenfiles / Vidnest / Abstream...
        val links = document.select("a[href]")
            .map { it.attr("href") }
            .map { fixUrl(it) }
            .filter { it.startsWith("http") }
            .filter { !it.contains(mainUrl) }

        // خلي Cloudstream يستخدم extractors الجاهزين
        links.forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        return links.isNotEmpty()
    }
}
