package com.abandonsearch.hazardgrid.ui.map

import android.util.Log
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CustomLayer
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Helper that owns the lifecycle of the custom native layer responsible for drawing Material shapes.
 */
internal class MorphingShapeLayer(
    private val layerId: String = "hazard-shapes-" + UUID.randomUUID().toString(),
) {
    private var nativePtr: Long = 0L
    private var customLayer: CustomLayer? = null
    private var attachedStyle: Style? = null

    fun ensureAttached(style: Style) {
        if (customLayer != null && attachedStyle === style) return
        detach()
        nativePtr = NativeHazardShapes.createLayerHost()
        val layer = CustomLayer(layerId, nativePtr)
        style.addLayer(layer)
        customLayer = layer
        attachedStyle = style
    }

    fun detach() {
        val style = attachedStyle
        val layer = customLayer
        if (style != null && layer != null) {
            try {
                style.removeLayer(layer)
            } catch (err: IllegalStateException) {
                Log.w("HazardShapeLayer", "Unable to remove layer; style is shutting down", err)
            }
        }
        customLayer = null
        attachedStyle = null
        nativePtr = 0L
    }

    fun update(buffer: ByteBuffer) {
        val ptr = nativePtr
        if (ptr == 0L) return
        NativeHazardShapes.updateLayer(ptr, buffer, buffer.limit())
    }
}
