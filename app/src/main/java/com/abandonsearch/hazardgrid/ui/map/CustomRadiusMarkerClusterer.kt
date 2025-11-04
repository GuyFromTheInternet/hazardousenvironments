package com.abandonsearch.hazardgrid.ui.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import org.osmdroid.bonuspack.clustering.RadiusMarkerClusterer
import org.osmdroid.bonuspack.clustering.StaticCluster
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class CustomRadiusMarkerClusterer(
    private val context: Context,
    private val colorScheme: ColorScheme
) : RadiusMarkerClusterer(context) {

    private val clusterPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    override fun buildClusterMarker(cluster: StaticCluster, mapView: MapView): Marker {
        val marker = Marker(mapView)
        marker.setOnMarkerClickListener { _, _ ->
            animateCluster(mapView, cluster)
            true
        }

        clusterPaint.color = colorScheme.primary.toArgb()
        textPaint.color = colorScheme.onPrimary.toArgb()

        val size = cluster.size.toString()
        val radius = 45f
        val bitmap = Bitmap.createBitmap(90, 90, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(radius, radius, radius, clusterPaint)
        canvas.drawText(size, radius, radius - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        marker.icon = BitmapDrawable(context.resources, bitmap)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

        return marker
    }

    private fun animateCluster(mapView: MapView, cluster: StaticCluster) {
        // Simply zoom in - we can't access the markers directly
        val clusterCenter = cluster.position
        mapView.controller.animateTo(clusterCenter, mapView.zoomLevelDouble + 1.5, 500L)
    }
}
