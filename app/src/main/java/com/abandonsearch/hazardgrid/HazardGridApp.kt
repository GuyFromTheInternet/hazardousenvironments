package com.abandonsearch.hazardgrid

import android.app.Application
import org.maplibre.android.MapLibre

class HazardGridApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
