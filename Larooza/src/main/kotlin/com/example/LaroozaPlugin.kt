package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LaroozaPlugin: Plugin() {
      override fun load(context: Context) {
                // All providers should be registered here
                registerMainAPI(LaroozaProvider())
      }
}
