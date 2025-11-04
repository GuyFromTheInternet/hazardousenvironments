package com.abandonsearch.hazardgrid.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
import com.abandonsearch.hazardgrid.R

class HazardMarkerFactory(private val context: Context) {

    private val markerSize = context.resources.getDimensionPixelSize(R.dimen.marker_size)

    private val defaultDrawable by lazy { createDrawable(isActive = false) }
    private val activeDrawable by lazy { createDrawable(isActive = true) }

    fun getDrawable(isActive: Boolean): Drawable = if (isActive) activeDrawable else defaultDrawable

    private fun createDrawable(isActive: Boolean): Drawable {
        val bitmap = Bitmap.createBitmap(markerSize, markerSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawable = HazardMarkerDrawable(isActive)
        drawable.setBounds(0, 0, markerSize, markerSize)
        drawable.draw(canvas)
        return BitmapDrawable(context.resources, bitmap)
    }
}
