package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class WeCimaProvider : MainAPI() {

    // ✅ Domain اللي شغال عندك
    override var mainUrl = "https://wecima.date"

    // ✅ اسم صحيح (حتى لا يطلع MyCima بالغلط)
    override var name = "WeCima"
    override var lang = "ar"

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override var hasMainPage = true

    // ✅ Headers تساعد الموقع يرد مثل المتصفح
    private val safeHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        mainUrl to "الرئيسية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = safeHeaders).document

        val items = doc.select(
            "article, div.GridItem, div.Item, div.BlockItem, div.col-md-2, div.col-xs-6, li, .movie, .post, .item"
        ).mapNotNull { el ->

            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val url = fixUrl(a.attr("href"))

            // ✅ استخراج عنوان قوي لأن كثير مرات a.text فاضي
            val img = el.selectFirst("img")
            val title =
                a.attr("title").trim().ifBlank {
                    img?.attr("alt")?.trim().orEmpty().ifBlank {
                        el.selectFirst("h1,h2,h3,.title,.name")?.text()?.trim().orEmpty()
                    }
                }

            if (title.isBlank()) return@mapNotNull null

            val poster = img?.attr("src")?.ifBlank {
                img.attr("data-src").ifBlank { img.attr("data-image") }
            }

            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = fixUrlNull(poster)
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val doc = app.get("$mainUrl/?s=$q", headers = safeHeaders).document

        return doc.select(
            "article, div.GridItem, div.Item, div.BlockItem, div.col-md-2, div.col-xs-6, li, .movie, .post, .item"
        ).mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val url = fixUrl(a.attr("href"))

            val img = el.selectFirst("img")
            val title =
                a.attr("title").trim().ifBlank {
                    img?.attr("alt")?.trim().orEmpty().ifBlank {
                        el.selectFirst("h1,h2,h3,.title,.name")?.text()?.trim().orEmpty()
                    }
                }

            if (title.isBlank()) return@mapNotNull null

            val poster = img?.attr("src")?.ifBlank {
                img.attr("data-src").ifBlank { img.attr("data-image") }
            }

            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = fixUrlNull(poster)
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = safeHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "WeCima"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img")?.attr("src")

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = fixUrlNull(poster)
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val watchUrl = data.trim()
        val doc = app.get(watchUrl, headers = safeHeaders).document

        // ✅ 1) iframes مباشرة (أفضل سيناريو)
        val iframeLinks = doc.select("iframe[src]")
            .map { fixUrl(it.attr("src")) }
            .filter { it.isNotBlank() }
            .distinct()

        if (iframeLinks.isNotEmpty()) {
            iframeLinks.forEach { link ->
                loadExtractor(link, watchUrl, subtitleCallback, callback)
            }
            return true
        }

        // ✅ 2) سيرفرات مخفية داخل data attributes
        val dataLinks = doc.select("[data-embed-url], [data-url], [data-href]")
            .mapNotNull { el ->
                el.attr("data-embed-url").ifBlank {
                    el.attr("data-url").ifBlank {
                        el.attr("data-href")
                    }
                }.takeIf { it.isNotBlank() }
            }
            .map { fixUrl(it) }
            .distinct()

        if (dataLinks.isNotEmpty()) {
            dataLinks.forEach { link ->
                loadExtractor(link, watchUrl, subtitleCallback, callback)
            }
            return true
        }

        // ✅ 3) روابط داخل onclick
        val onclickLinks = doc.select("[onclick]")
            .mapNotNull { el ->
                val oc = el.attr("onclick")
                Regex("""(https?:\/\/[^'"]+|\/[^'"]+)""").find(oc)?.value
            }
            .map { fixUrl(it) }
            .distinct()

        if (onclickLinks.isNotEmpty()) {
            onclickLinks.forEach { link ->
                loadExtractor(link, watchUrl, subtitleCallback, callback)
            }
            return true
        }

        return false
    }
}
