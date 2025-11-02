package com.abandonsearch.hazardgrid.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.abandonsearch.hazardgrid.R
import com.yandex.runtime.image.ImageProvider

class HazardMarkerFactory(private val context: Context) {

    // TODO: Add this to your dimens.xml file: <dimen name="marker_size">48dp</dimen>
    private val markerSize = context.resources.getDimensionPixelSize(R.dimen.marker_size)

    private val defaultProvider by lazy { createDrawable(isActive = false) }
    private val activeProvider by lazy { createDrawable(isActive = true) }

    fun getDrawable(isActive: Boolean): ImageProvider = if (isActive) activeProvider else defaultProvider

    fun getClusterDrawable(clusterSize: Int): ImageProvider {
        val bitmap = Bitmap.createBitmap(markerSize, markerSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawable = HazardMarkerDrawable(isCluster = true, clusterSize = clusterSize)
        drawable.setBounds(0, 0, markerSize, markerSize)
        drawable.draw(canvas)
        return ImageProvider.fromBitmap(bitmap)
    }

    private fun createDrawable(isActive: Boolean): ImageProvider {
        val bitmap = Bitmap.createBitmap(markerSize, markerSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawable = HazardMarkerDrawable(isActive = isActive)
        drawable.setBounds(0, 0, markerSize, markerSize)
        drawable.draw(canvas)
        return ImageProvider.fromBitmap(bitmap)
    }
}
