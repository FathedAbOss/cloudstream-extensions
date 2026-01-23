package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MyCimaPlugin: CloudstreamPlugin() {
    override fun load(context: Context) {
        // All providers should be registered here
        registerMainAPI(MyCimaProvider())
    }
}
