package com.abandonsearch.hazardgrid.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.shapes.MaterialShapes
import androidx.compose.ui.graphics.toArgb
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.abandonsearch.hazardgrid.data.Place
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class CustomMarkerOverlay(
    private val mapView: MapView,
    private val onMarkerSelected: (Place?) -> Unit,
    private val colorScheme: ColorScheme
) : Overlay() {

    private val markers = mutableListOf<Marker>()
    private val handler = Handler(Looper.getMainLooper())
    private var activeMarker: Marker? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
    }
    private val path = Path()
    private val matrix = Matrix()

    private val shapes = listOf(
        MaterialShapes.Circle,
        MaterialShapes.Oval,
        MaterialShapes.Diamond,
        MaterialShapes.Sunny,
        MaterialShapes.Square,
        MaterialShapes.Pill,
        MaterialShapes.Clamshell,
        MaterialShapes.Cookie4Sided,
        MaterialShapes.Slanted,
        MaterialShapes.Triangle,
        MaterialShapes.Pentagon,
        MaterialShapes.Cookie6Sided,
        MaterialShapes.Arch,
        MaterialShapes.Arrow,
        MaterialShapes.Gem,
        MaterialShapes.Cookie7Sided,
        MaterialShapes.Semicircle,
        MaterialShapes.Fan,
        MaterialShapes.VerySunny,
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Boom,
        MaterialShapes.Ghostish,
        MaterialShapes.SoftBoom,
        MaterialShapes.PixelCircle,
        MaterialShapes.Flower,
        MaterialShapes.PixelTriangle,
        MaterialShapes.Puffy,
        MaterialShapes.Bun,
        MaterialShapes.PuffyDiamond,
        MaterialShapes.Heart,
        MaterialShapes.Cookie12Sided
    )
    private val accentColors = listOf(
        colorScheme.primary,
        colorScheme.secondary,
        colorScheme.tertiary,
        colorScheme.primaryContainer,
        colorScheme.secondaryContainer,
        colorScheme.tertiaryContainer
    )

    fun setPlaces(places: List<Place>) {
        markers.clear()
        places.forEach { place ->
            val lat = place.lat ?: return@forEach
            val lon = place.lon ?: return@forEach
            markers.add(
                Marker(
                    id = place.id,
                    place = place,
                    point = GeoPoint(lat, lon),
                    startPolygon = shapes.random(),
                    endPolygon = shapes.random(),
                    color = accentColors.random().toArgb()
                )
            )
        }
        mapView.invalidate()
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val viewBounds = mapView.boundingBox.increaseByScale(1.5f)
        markers.forEach { marker ->
            if (viewBounds.contains(marker.point)) {
                val screenPoint = projection.toPixels(marker.point, null)
                val polygon = if (marker.isAnimating) {
                    val progress = (System.currentTimeMillis() - marker.animationStartTime) / ANIMATION_DURATION.toFloat()
                    if (progress >= 1f) {
                        marker.isAnimating = false
                        marker.startPolygon = marker.endPolygon
                        marker.startPolygon
                    } else {
                        val morph = Morph(marker.startPolygon, marker.endPolygon)
                        morph.toPath(progress, path)
                        null
                    }
                } else {
                    marker.startPolygon
                }

                if (polygon != null) {
                    val polygonBounds = polygon.calculateBounds()
                    matrix.setRectToRect(
                        RectF(polygonBounds[0], polygonBounds[1], polygonBounds[2], polygonBounds[3]),
                        RectF(-32f, -32f, 32f, 32f),
                        Matrix.ScaleToFit.CENTER
                    )
                    polygon.toPath(path)
                    path.transform(matrix)
                }
                path.offset(screenPoint.x.toFloat(), screenPoint.y.toFloat())

                fillPaint.color = marker.color
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val tappedMarker = getTappedMarker(e, mapView)

        if (tappedMarker != null) {
            if (activeMarker != tappedMarker) {
                activeMarker?.let { animateShapeChange(it) }
                animateShapeChange(tappedMarker)
                activeMarker = tappedMarker
            }
            onMarkerSelected(tappedMarker.place)
        } else {
            activeMarker?.let { animateShapeChange(it) }
            activeMarker = null
            onMarkerSelected(null)
        }
        mapView.postInvalidate()
        return tappedMarker != null
    }

    private fun animateShapeChange(marker: Marker) {
        marker.isAnimating = true
        marker.animationStartTime = System.currentTimeMillis()
        marker.endPolygon = shapes.random()
        handler.post(object : Runnable {
            override fun run() {
                if (marker.isAnimating) {
                    mapView.postInvalidate()
                    handler.postDelayed(this, 16)
                }
            }
        })
    }

    private fun getTappedMarker(e: MotionEvent, mapView: MapView): Marker? {
        val projection = mapView.projection
        val touchRect = android.graphics.Rect(e.x.toInt() - 40, e.y.toInt() - 40, e.x.toInt() + 40, e.y.toInt() + 40)

        for (i in markers.indices.reversed()) {
            val marker = markers[i]
            val screenPoint = projection.toPixels(marker.point, null)
            if (touchRect.contains(screenPoint.x, screenPoint.y)) {
                return marker
            }
        }
        return null
    }

    private data class Marker(
        val id: Int,
        val place: Place,
        val point: GeoPoint,
        var startPolygon: RoundedPolygon,
        var endPolygon: RoundedPolygon,
        var color: Int,
        var isAnimating: Boolean = false,
        var animationStartTime: Long = 0L,
        val animationSpec: AnimationSpec<Float> = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )

    companion object {
        private const val ANIMATION_DURATION = 500L
    }
}
