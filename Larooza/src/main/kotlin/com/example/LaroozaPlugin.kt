package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.registerMainAPI

class LaroozaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LaroozaProvider())
    }
}
