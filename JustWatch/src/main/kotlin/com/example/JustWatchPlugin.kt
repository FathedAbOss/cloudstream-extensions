package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class JustWatchPlugin: CloudstreamPlugin() {
    override fun load(context: Context) {
        // All providers should be registered here
        registerMainAPI(JustWatchProvider())
    }
}
