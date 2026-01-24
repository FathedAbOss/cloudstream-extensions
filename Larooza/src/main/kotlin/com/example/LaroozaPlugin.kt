package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LaroozaPlugin : Plugin() {
    override fun load(context: Context) {
        // We do NOT import registerMainAPI; we just call it.
        registerMainAPI(LaroozaProvider())
    }
}
