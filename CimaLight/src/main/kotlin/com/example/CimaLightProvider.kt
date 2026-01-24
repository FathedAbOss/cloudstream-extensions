package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document

class CimaLightProvider : MainAPI() {

    override var mainUrl = "https://w.cimalight.co"
    override var name = "CimaLight"
    override var lang = "ar"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // ... (getMainPage, search, load methods remain the same as before) ...

    /**
     * The definitive fix for CimaLight Streaming.
     * This method follows the 'xtgo' redirect chain to find the actual streaming servers
     * hidden on external news sites (like elif.news or alhakekanet.net).
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data.trim()
        val watchDoc = app.get(watchUrl).document
        
        var linksFound = 0

        // 1. Follow the 'xtgo' redirect link to the streaming server page
        val xtgoLink = watchDoc.selectFirst("a.xtgo")?.attr("href")
        if (xtgoLink != null) {
            val fullRedirectUrl = fixUrl(xtgoLink)
            // The redirect page (e.g., elif.news) contains the actual streaming player
            val serverPageDoc = runCatching { 
                app.get(fullRedirectUrl, referer = watchUrl).document 
            }.getOrNull()
            
            if (serverPageDoc != null) {
                // Look for streaming servers in the #sServer list or similar containers
                serverPageDoc.select("#sServer li, .WatchList li").forEach { li ->
                    val serverName = li.text().trim()
                    // The actual stream URL is often in a data attribute or a hidden link
                    val serverUrl = li.attr("data-url").ifEmpty { li.selectFirst("a")?.attr("href") }.orEmpty()
                    
                    if (serverUrl.isNotEmpty() && serverUrl.startsWith("http")) {
                        runCatching {
                            // Use loadExtractor to resolve the streaming hoster (e.g., Uqload, Supervideo)
                            loadExtractor(serverUrl, fullRedirectUrl, subtitleCallback, callback)
                            linksFound++
                        }
                    }
                }
                
                // Also check for any iframes that might be the direct player
                serverPageDoc.select("iframe[src]").forEach { iframe ->
                    val src = fixUrl(iframe.attr("src"))
                    if (!src.contains("google") && !src.contains("facebook")) {
                        runCatching {
                            loadExtractor(src, fullRedirectUrl, subtitleCallback, callback)
                            linksFound++
                        }
                    }
                }
            }
        }

        // 2. Fallback: Scrape the downloads.php page for additional hosters
        // Even though these are 'download' links, many hosters (like Updown, Multiup) 
        // provide a streaming player on their landing page which CloudStream can extract.
        val vid = Regex("vid=([A-Za-z0-9]+)").find(watchUrl)?.groupValues?.getOrNull(1)
        if (vid != null) {
            val downloadsUrl = "$mainUrl/downloads.php?vid=$vid"
            val downloadDoc = runCatching { 
                app.get(downloadsUrl, referer = watchUrl).document 
            }.getOrNull()
            
            downloadDoc?.select("a[href]")?.forEach { a ->
                val link = fixUrl(a.attr("href"))
                if (!link.startsWith(mainUrl) && !link.contains("cimalight") && link.startsWith("http")) {
                    runCatching {
                        loadExtractor(link, downloadsUrl, subtitleCallback, callback)
                        linksFound++
                    }
                }
            }
        }

        return linksFound > 0
    }
}
