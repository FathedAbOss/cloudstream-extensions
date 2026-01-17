package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

class MyCimaProvider : MainAPI() {
      override var mainUrl = "https://mycima.direct/"
      override var name = "MyCima"
      override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
          override var lang = "ar"
      override var hasMainPage = true

      override suspend fun search(query: String): List<SearchResponse> {
                // Scraper logic will be implemented here
                return listOf()
      }
}
