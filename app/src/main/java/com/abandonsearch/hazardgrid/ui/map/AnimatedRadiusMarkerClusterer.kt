package com.abandonsearch.hazardgrid.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.animation.DecelerateInterpolator
import com.google.android.material.animation.AnimationUtils
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.clusters.RadiusMarkerClusterer

class AnimatedRadiusMarkerClusterer(context: Context) : RadiusMarkerClusterer(context) {

    override fun buildClusterMarker(cluster: StaticCluster, mapView: MapView): Marker {
        val marker = super.buildClusterMarker(cluster, mapView)
        marker.alpha = 0.5f
        marker.scaleX = 0.5f
        marker.scaleY = 0.5f
        marker.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return marker
    }
}
