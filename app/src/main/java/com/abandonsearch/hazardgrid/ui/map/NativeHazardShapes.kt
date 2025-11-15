package com.abandonsearch.hazardgrid.ui.map

import java.nio.ByteBuffer

/**
 * Thin JNI bridge for the custom MapLibre layer backed by native rendering code.
 */
internal object NativeHazardShapes {
    init {
        System.loadLibrary("maplibre")
    }

    external fun createLayerHost(): Long

    external fun updateLayer(hostPtr: Long, buffer: ByteBuffer, length: Int)
}
