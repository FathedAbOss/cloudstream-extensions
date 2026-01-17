package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

class WeCimaProvider : MainAPI() {
      override var mainUrl = "https://wecima.click/"
      override var name = "WeCima"
      override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
          override var lang = "ar"
      override var hasMainPage = true

      override suspend fun search(query: String): List<SearchResponse> {
                // Scraper logic will be implemented here
                return listOf()
      }
}
