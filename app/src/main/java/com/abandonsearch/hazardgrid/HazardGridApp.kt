package com.abandonsearch.hazardgrid

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

class HazardGridApp : Application() {
    override fun onCreate() {
        super.onCreate()
        setupOsmDroid()
    }

    private fun setupOsmDroid() {
        val config = Configuration.getInstance()
        config.userAgentValue = packageName
        val basePath = File(filesDir, "osmdroid")
        val tileCache = File(cacheDir, "osmdroid")
        if (!basePath.exists()) {
            basePath.mkdirs()
        }
        if (!tileCache.exists()) {
            tileCache.mkdirs()
        }
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = tileCache
        config.isMapViewHardwareAccelerated = true
    }
}
