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
import android.view.animation.OvershootInterpolator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.graphics.toArgb
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.abandonsearch.hazardgrid.data.Place
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.sqrt
import kotlin.math.abs

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class CustomMarkerOverlay(
    private val mapView: MapView,
    private val onMarkerSelected: (Place?) -> Unit,
    private val colorScheme: ColorScheme
) : Overlay() {

    private val markers = mutableListOf<Marker>()
    private val handler = Handler(Looper.getMainLooper())
    private var activeMarker: Marker? = null
    private var mergingEnabled = true
    private val interpolator = OvershootInterpolator()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
    }
    private val path = Path()
    private val matrix = Matrix()
    private val tempRect = RectF()

    private val shapes = listOf(
        MaterialShapes.Circle,
        MaterialShapes.Oval,
        MaterialShapes.Diamond,
        MaterialShapes.Sunny,
        MaterialShapes.Square,
        MaterialShapes.Pill,
        MaterialShapes.Cookie4Sided,
        MaterialShapes.Slanted,
        MaterialShapes.Triangle,
        MaterialShapes.Pentagon,
        MaterialShapes.Cookie6Sided,
        MaterialShapes.Arch,
        MaterialShapes.Gem,
        MaterialShapes.Cookie7Sided,
        MaterialShapes.Arrow,
        MaterialShapes.Ghostish,
        MaterialShapes.Flower,
        MaterialShapes.Bun
    )

    private val accentColors = listOf(
        colorScheme.primary,
        colorScheme.secondary,
        colorScheme.tertiary,
        colorScheme.primaryContainer,
        colorScheme.secondaryContainer,
        colorScheme.tertiaryContainer
    )

    // CIRCLE-BASED HITBOX - ALL MARKERS AND CLUSTERS USE SAME RADIUS
    private val markerRadius = 40f

    private val clusters = mutableMapOf<String, MarkerCluster>()
    private var lastZoomLevel = 0.0

    // SIZE-BASED clustering parameters - PURE DISTANCE
    private val maxClusterRadiusPx = 100f // Maximum radius a cluster can grow to
    private val mergeDistance = 80f // Distance to merge markers into cluster
    private val unmergeDistance = 150f // Distance to break cluster apart (hysteresis)

    private var isRecalculating = false

    fun setPlaces(places: List<Place>) {
        markers.clear()
        clusters.clear()
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
        lastZoomLevel = mapView.zoomLevelDouble
        if (mergingEnabled) {
            updateClusters(mapView.projection, mapView.boundingBox.increaseByScale(1.5f))
        }
        mapView.invalidate()
    }

    fun setMergingEnabled(enabled: Boolean) {
        if (mergingEnabled == enabled) return
        mergingEnabled = enabled
        if (!enabled) {
            clusters.clear()
            markers.forEach { marker ->
                marker.isInCluster = false
                marker.currentClusterId = null
                marker.isUnmerging = false
            }
            mapView.postInvalidate()
            return
        }
        updateClusters(mapView.projection, mapView.boundingBox.increaseByScale(1.5f))
        mapView.postInvalidate()
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val viewBounds = mapView.boundingBox.increaseByScale(1.5f)
        val currentZoom = mapView.zoomLevelDouble

        if (!mergingEnabled) {
            for (marker in markers) {
                if (viewBounds.contains(marker.point)) {
                    drawMarker(canvas, projection, marker)
                }
            }
            return
        }

        // Only recalculate clusters if zoom level changed significantly
        if (abs(currentZoom - lastZoomLevel) > 0.1 && !isRecalculating) {
            updateClusters(projection, viewBounds)
            lastZoomLevel = currentZoom
        }

        // Draw unmerging markers first
        for (marker in markers) {
            if (marker.isUnmerging) {
                drawUnmergingMarker(canvas, projection, marker)
            }
        }

        // Draw clusters
        for (cluster in clusters.values) {
            drawCluster(canvas, projection, cluster)
        }

        // Draw standalone markers
        for (marker in markers) {
            if (viewBounds.contains(marker.point) && !marker.isInCluster && !marker.isUnmerging) {
                drawMarker(canvas, projection, marker)
            }
        }
    }

    private fun updateClusters(projection: Projection, viewBounds: org.osmdroid.util.BoundingBox) {
        if (isRecalculating) return
        if (!mergingEnabled) {
            clusters.clear()
            isRecalculating = false
            return
        }
        isRecalculating = true

        val previousClusters = clusters.toMap()
        val newClusters = mutableMapOf<String, MarkerCluster>()

        // Get visible markers - INCLUDE unmerging markers so they can be re-clustered
        val visibleMarkers = markers.filter {
            viewBounds.contains(it.point)
        }

        // Reset all states
        for (marker in visibleMarkers) {
            marker.isInCluster = false
            marker.currentClusterId = null
        }

        // STEP 1: Validate and keep existing clusters that are still coherent
        for ((oldKey, oldCluster) in previousClusters) {
            val stillVisible = oldCluster.markers.filter { visibleMarkers.contains(it) }

            if (stillVisible.size < 2) {
                // Cluster dissolved - handle unmerging later
                continue
            }

            // Check if cluster is still coherent (all markers within unmergeDistance from center)
            val center = calculateClusterCenter(stillVisible)
            val centerScreen = projection.toPixels(center, null)
            var allClose = true

            for (marker in stillVisible) {
                val markerScreen = projection.toPixels(marker.point, null)
                val dist = fastDistance(
                    centerScreen.x.toFloat(), centerScreen.y.toFloat(),
                    markerScreen.x.toFloat(), markerScreen.y.toFloat()
                )
                if (dist > unmergeDistance) {
                    allClose = false
                    break
                }
            }

            if (allClose) {
                // Keep this cluster
                val radius = calculateClusterRadius(stillVisible, projection)

                oldCluster.markers = stillVisible
                oldCluster.centerPoint = center
                oldCluster.isFull = radius >= maxClusterRadiusPx * 0.95f

                // Mark these markers as clustered and CANCEL unmerge animation
                for (m in stillVisible) {
                    m.isInCluster = true
                    m.currentClusterId = oldKey

                    // Cancel unmerge animation if marker is being re-clustered
                    if (m.isUnmerging) {
                        m.isUnmerging = false
                    }

                    if (m.preClusterPolygon == null) {
                        m.preClusterPolygon = m.startPolygon
                        m.preClusterColor = m.color
                        m.preClusterRotation = m.rotation
                    }
                }

                newClusters[oldKey] = oldCluster
            }
        }

        // STEP 2: Try to add unclustered markers to existing clusters
        val unclustered = visibleMarkers.filter { !it.isInCluster }.toMutableList()

        for ((clusterKey, cluster) in newClusters.toList()) {
            if (cluster.isFull) continue

            val addedMarkers = mutableListOf<Marker>()

            for (marker in unclustered.toList()) {
                val markerScreen = projection.toPixels(marker.point, null)
                val clusterScreen = projection.toPixels(cluster.centerPoint, null)

                val distToCluster = fastDistance(
                    markerScreen.x.toFloat(), markerScreen.y.toFloat(),
                    clusterScreen.x.toFloat(), clusterScreen.y.toFloat()
                )

                if (distToCluster < mergeDistance) {
                    // Test if adding would exceed size limit
                    val testMarkers = cluster.markers + marker
                    val testRadius = calculateClusterRadius(testMarkers, projection)

                    if (testRadius <= maxClusterRadiusPx) {
                        addedMarkers.add(marker)
                        unclustered.remove(marker)
                    } else {
                        cluster.isFull = true
                        break
                    }
                }
            }

            if (addedMarkers.isNotEmpty()) {
                val updatedMarkers = cluster.markers + addedMarkers
                val newKey = updatedMarkers.map { it.id }.sorted().joinToString("-")

                cluster.markers = updatedMarkers
                cluster.centerPoint = calculateClusterCenter(updatedMarkers)

                val newRadius = calculateClusterRadius(updatedMarkers, projection)
                cluster.isFull = newRadius >= maxClusterRadiusPx * 0.95f

                // Update markers and CANCEL unmerge animation
                for (m in addedMarkers) {
                    m.isInCluster = true
                    m.currentClusterId = newKey

                    // Cancel unmerge animation if marker is being re-clustered
                    if (m.isUnmerging) {
                        m.isUnmerging = false
                    }

                    if (m.preClusterPolygon == null) {
                        m.preClusterPolygon = m.startPolygon
                        m.preClusterColor = m.color
                        m.preClusterRotation = m.rotation
                    }
                }

                for (m in cluster.markers) {
                    m.currentClusterId = newKey
                }

                // Update key in map
                if (clusterKey != newKey) {
                    newClusters.remove(clusterKey)
                    newClusters[newKey] = cluster
                }
            }
        }

        // STEP 3: Build NEW clusters from remaining unclustered markers using UNION-FIND
        val clusterGroups = buildClusterGroups(unclustered, projection)

        for (group in clusterGroups) {
            if (group.size < 2) continue

            val clusterKey = group.map { it.id }.sorted().joinToString("-")

            for (m in group) {
                m.isInCluster = true
                m.currentClusterId = clusterKey

                // Cancel unmerge animation if marker is being re-clustered
                if (m.isUnmerging) {
                    m.isUnmerging = false
                }

                if (m.preClusterPolygon == null) {
                    m.preClusterPolygon = m.startPolygon
                    m.preClusterColor = m.color
                    m.preClusterRotation = m.rotation
                }
            }

            val centerPoint = calculateClusterCenter(group)
            val radius = calculateClusterRadius(group, projection)

            val newCluster = MarkerCluster(
                markers = group,
                centerPoint = centerPoint,
                startPolygon = shapes.random(),
                endPolygon = shapes.random(),
                color = accentColors.random().toArgb(),
                isAnimating = true,
                animationStartTime = System.currentTimeMillis(),
                isFull = radius >= maxClusterRadiusPx * 0.95f
            )

            startClusterAnimation(newCluster)
            newClusters[clusterKey] = newCluster
        }

        // STEP 4: Handle unmerging - only for markers that are truly standalone now
        for ((oldKey, oldCluster) in previousClusters) {
            if (oldKey !in newClusters) {
                for (marker in oldCluster.markers) {
                    // Only start unmerge if marker is not in a new cluster and not already unmerging
                    if (!marker.isInCluster && !marker.isUnmerging && visibleMarkers.contains(marker)) {
                        startUnmergeAnimation(marker, oldCluster)
                    }
                }
            }
        }

        clusters.clear()
        clusters.putAll(newClusters)

        isRecalculating = false
    }

    private fun buildClusterGroups(markers: List<Marker>, projection: Projection): List<List<Marker>> {
        if (markers.isEmpty()) return emptyList()

        // Union-Find data structure to group connected markers
        val parent = mutableMapOf<Marker, Marker>()
        for (marker in markers) {
            parent[marker] = marker
        }

        fun find(marker: Marker): Marker {
            if (parent[marker] != marker) {
                parent[marker] = find(parent[marker]!!)
            }
            return parent[marker]!!
        }

        fun union(m1: Marker, m2: Marker) {
            val root1 = find(m1)
            val root2 = find(m2)
            if (root1 != root2) {
                parent[root2] = root1
            }
        }

        // Connect markers that are within mergeDistance
        for (i in markers.indices) {
            val m1 = markers[i]
            val m1Screen = projection.toPixels(m1.point, null)

            for (j in i + 1 until markers.size) {
                val m2 = markers[j]
                val m2Screen = projection.toPixels(m2.point, null)

                val dist = fastDistance(
                    m1Screen.x.toFloat(), m1Screen.y.toFloat(),
                    m2Screen.x.toFloat(), m2Screen.y.toFloat()
                )

                if (dist < mergeDistance) {
                    union(m1, m2)
                }
            }
        }

        // Group markers by their root
        val groups = mutableMapOf<Marker, MutableList<Marker>>()
        for (marker in markers) {
            val root = find(marker)
            groups.getOrPut(root) { mutableListOf() }.add(marker)
        }

        // Filter groups by size constraint and return
        return groups.values.filter { group ->
            val radius = calculateClusterRadius(group, projection)
            radius <= maxClusterRadiusPx && group.size >= 2
        }
    }

    private fun fastDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun calculateClusterCenter(markers: List<Marker>): GeoPoint {
        var totalLat = 0.0
        var totalLon = 0.0
        for (marker in markers) {
            totalLat += marker.point.latitude
            totalLon += marker.point.longitude
        }
        return GeoPoint(totalLat / markers.size, totalLon / markers.size)
    }

    private fun calculateClusterRadius(markers: List<Marker>, projection: Projection): Float {
        if (markers.isEmpty()) return 0f
        if (markers.size == 1) return markerRadius

        val center = calculateClusterCenter(markers)
        val centerScreen = projection.toPixels(center, null)
        val centerX = centerScreen.x.toFloat()
        val centerY = centerScreen.y.toFloat()

        var maxDist = 0f
        for (marker in markers) {
            val markerScreen = projection.toPixels(marker.point, null)
            val dist = fastDistance(
                centerX, centerY,
                markerScreen.x.toFloat(), markerScreen.y.toFloat()
            )
            if (dist > maxDist) {
                maxDist = dist
            }
        }

        return maxDist + markerRadius
    }

    private fun startUnmergeAnimation(marker: Marker, fromCluster: MarkerCluster) {
        marker.isUnmerging = true
        marker.unmergeStartTime = System.currentTimeMillis()
        marker.unmergeFromPolygon = fromCluster.startPolygon
        marker.unmergeFromColor = fromCluster.color
        marker.unmergeFromRotation = fromCluster.rotation
        marker.unmergeFromPoint = fromCluster.centerPoint

        marker.startPolygon = marker.preClusterPolygon ?: marker.startPolygon
        marker.endPolygon = marker.preClusterPolygon ?: marker.startPolygon
        marker.color = marker.preClusterColor ?: marker.color
        marker.rotation = marker.preClusterRotation ?: marker.rotation

        marker.preClusterPolygon = null
        marker.preClusterColor = null
        marker.preClusterRotation = null

        handler.post(object : Runnable {
            override fun run() {
                if (marker.isUnmerging) {
                    mapView.postInvalidate()
                    handler.postDelayed(this, 16)
                }
            }
        })
    }

    private fun startClusterAnimation(cluster: MarkerCluster) {
        handler.post(object : Runnable {
            override fun run() {
                if (cluster.isAnimating) {
                    mapView.postInvalidate()
                    handler.postDelayed(this, 16)
                }
            }
        })
    }

    private fun drawUnmergingMarker(canvas: Canvas, projection: Projection, marker: Marker) {
        var progress = (System.currentTimeMillis() - marker.unmergeStartTime) / ANIMATION_DURATION.toFloat()
        if (progress >= 1f) {
            marker.isUnmerging = false
            marker.startPolygon = marker.endPolygon
            progress = 1f
        }

        val interpolatedProgress = interpolator.getInterpolation(progress)

        val fromPoint = marker.unmergeFromPoint ?: marker.point
        val fromScreen = projection.toPixels(fromPoint, null)
        val toScreen = projection.toPixels(marker.point, null)

        val currentX = fromScreen.x + (toScreen.x - fromScreen.x) * interpolatedProgress
        val currentY = fromScreen.y + (toScreen.y - fromScreen.y) * interpolatedProgress

        path.reset()
        val fromPolygon = marker.unmergeFromPolygon ?: marker.startPolygon
        val morph = Morph(fromPolygon, marker.endPolygon)
        morph.toPath(interpolatedProgress, path)

        tempRect.setEmpty()
        path.computeBounds(tempRect, true)

        matrix.reset()
        matrix.setRectToRect(
            tempRect,
            RectF(-markerRadius, -markerRadius, markerRadius, markerRadius),
            Matrix.ScaleToFit.CENTER
        )
        path.transform(matrix)
        path.offset(currentX, currentY)

        val fromRotation = marker.unmergeFromRotation ?: marker.rotation
        val currentRotation = fromRotation + (marker.rotation - fromRotation) * interpolatedProgress

        val fromColor = marker.unmergeFromColor ?: marker.color
        val currentColor = interpolateColor(fromColor, marker.color, interpolatedProgress)

        canvas.save()
        canvas.rotate(currentRotation, currentX, currentY)
        fillPaint.color = currentColor
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        canvas.restore()
    }

    private fun interpolateColor(from: Int, to: Int, progress: Float): Int {
        val fromA = Color.alpha(from)
        val fromR = Color.red(from)
        val fromG = Color.green(from)
        val fromB = Color.blue(from)

        val toA = Color.alpha(to)
        val toR = Color.red(to)
        val toG = Color.green(to)
        val toB = Color.blue(to)

        return Color.argb(
            (fromA + (toA - fromA) * progress).coerceIn(0f, 255f).toInt(),
            (fromR + (toR - fromR) * progress).coerceIn(0f, 255f).toInt(),
            (fromG + (toG - fromG) * progress).coerceIn(0f, 255f).toInt(),
            (fromB + (toB - fromB) * progress).coerceIn(0f, 255f).toInt()
        )
    }

    private fun drawCluster(canvas: Canvas, projection: Projection, cluster: MarkerCluster) {
        val screenPoint = projection.toPixels(cluster.centerPoint, null)
        val scale = calculateClusterRadius(cluster.markers, projection)

        path.reset()
        if (cluster.isAnimating) {
            var progress = (System.currentTimeMillis() - cluster.animationStartTime) / ANIMATION_DURATION.toFloat()
            if (progress >= 1f) {
                cluster.isAnimating = false
                cluster.startPolygon = cluster.endPolygon
                progress = 1f
            }
            val interpolatedProgress = interpolator.getInterpolation(progress)
            val morph = Morph(cluster.startPolygon, cluster.endPolygon)
            morph.toPath(interpolatedProgress, path)
        } else {
            cluster.startPolygon.toPath(path)
        }

        val polygonBounds = if (cluster.isAnimating) {
            tempRect.setEmpty()
            path.computeBounds(tempRect, true)
            floatArrayOf(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom)
        } else {
            cluster.startPolygon.calculateBounds()
        }

        matrix.reset()
        matrix.setRectToRect(
            RectF(polygonBounds[0], polygonBounds[1], polygonBounds[2], polygonBounds[3]),
            RectF(-scale, -scale, scale, scale),
            Matrix.ScaleToFit.CENTER
        )
        path.transform(matrix)
        path.offset(screenPoint.x.toFloat(), screenPoint.y.toFloat())

        canvas.save()
        canvas.rotate(cluster.rotation, screenPoint.x.toFloat(), screenPoint.y.toFloat())
        fillPaint.color = cluster.color
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        canvas.restore()
    }

    private fun drawMarker(canvas: Canvas, projection: Projection, marker: Marker) {
        val screenPoint = projection.toPixels(marker.point, null)

        path.reset()
        if (marker.isAnimating) {
            var progress = (System.currentTimeMillis() - marker.animationStartTime) / ANIMATION_DURATION.toFloat()
            if (progress >= 1f) {
                marker.isAnimating = false
                marker.startPolygon = marker.endPolygon
                progress = 1f
            }
            val interpolatedProgress = interpolator.getInterpolation(progress)
            val morph = Morph(marker.startPolygon, marker.endPolygon)
            morph.toPath(interpolatedProgress, path)
        } else {
            marker.startPolygon.toPath(path)
        }

        val polygonBounds = if (marker.isAnimating) {
            tempRect.setEmpty()
            path.computeBounds(tempRect, true)
            floatArrayOf(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom)
        } else {
            marker.startPolygon.calculateBounds()
        }

        matrix.reset()
        matrix.setRectToRect(
            RectF(polygonBounds[0], polygonBounds[1], polygonBounds[2], polygonBounds[3]),
            RectF(-markerRadius, -markerRadius, markerRadius, markerRadius),
            Matrix.ScaleToFit.CENTER
        )
        path.transform(matrix)
        path.offset(screenPoint.x.toFloat(), screenPoint.y.toFloat())

        canvas.save()
        canvas.rotate(marker.rotation, screenPoint.x.toFloat(), screenPoint.y.toFloat())
        fillPaint.color = marker.color
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        canvas.restore()
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val tappedCluster = if (mergingEnabled) getTappedCluster(e, mapView) else null
        val tappedMarker = if (tappedCluster == null) getTappedMarker(e, mapView) else null

        if (tappedCluster != null) {
            zoomToUnmergeCluster(tappedCluster)
            return true
        } else if (tappedMarker != null) {
            animateShapeChange(tappedMarker)
            activeMarker = tappedMarker
            onMarkerSelected(tappedMarker.place)
        } else {
            activeMarker?.let { animateShapeChange(it) }
            activeMarker = null
            onMarkerSelected(null)
        }
        mapView.postInvalidate()
        return tappedCluster != null || tappedMarker != null
    }

    private fun zoomToUnmergeCluster(cluster: MarkerCluster) {
        val controller = mapView.controller

        val currentRadius = calculateClusterRadius(cluster.markers, mapView.projection)
        val targetRadius = unmergeDistance * 2f
        val zoomFactor = (targetRadius / currentRadius).coerceAtLeast(3.0f)

        val currentZoom = mapView.zoomLevelDouble
        val zoomIncrease = kotlin.math.log2(zoomFactor.toDouble()).coerceAtLeast(2.0)
        val targetZoom = (currentZoom + zoomIncrease).coerceIn(
            currentZoom + 2.0,
            mapView.maxZoomLevel.toDouble()
        )

        controller.animateTo(cluster.centerPoint, targetZoom, 400L)
    }

    private fun animateShapeChange(marker: Marker) {
        marker.isAnimating = true
        marker.animationStartTime = System.currentTimeMillis()

        var nextShape = shapes.random()
        while (nextShape == marker.startPolygon && shapes.size > 1) {
            nextShape = shapes.random()
        }
        marker.endPolygon = nextShape

        var nextColor = accentColors.random().toArgb()
        while (nextColor == marker.color && accentColors.size > 1) {
            nextColor = accentColors.random().toArgb()
        }
        marker.color = nextColor

        marker.rotation = (0..360).random().toFloat()

        handler.post(object : Runnable {
            override fun run() {
                if (marker.isAnimating) {
                    mapView.postInvalidate()
                    handler.postDelayed(this, 16)
                }
            }
        })
    }

    private fun getTappedCluster(e: MotionEvent, mapView: MapView): MarkerCluster? {
        if (!mergingEnabled) return null
        val projection = mapView.projection
        val touchX = e.x
        val touchY = e.y

        for (cluster in clusters.values.reversed()) {
            val screenPoint = projection.toPixels(cluster.centerPoint, null)
            val touchRadius = calculateClusterRadius(cluster.markers, projection) + 20f

            val distance = fastDistance(touchX, touchY, screenPoint.x.toFloat(), screenPoint.y.toFloat())
            if (distance <= touchRadius) {
                return cluster
            }
        }
        return null
    }

    private fun getTappedMarker(e: MotionEvent, mapView: MapView): Marker? {
        val projection = mapView.projection
        val touchX = e.x
        val touchY = e.y

        for (marker in markers.reversed()) {
            if (!marker.isInCluster && !marker.isUnmerging) {
                val screenPoint = projection.toPixels(marker.point, null)
                val distance = fastDistance(touchX, touchY, screenPoint.x.toFloat(), screenPoint.y.toFloat())
                if (distance <= markerRadius + 20f) {
                    return marker
                }
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
        var rotation: Float = (0..360).random().toFloat(),
        var isAnimating: Boolean = false,
        var animationStartTime: Long = 0L,
        var isInCluster: Boolean = false,
        var currentClusterId: String? = null,
        var isUnmerging: Boolean = false,
        var unmergeStartTime: Long = 0L,
        var unmergeFromPolygon: RoundedPolygon? = null,
        var unmergeFromColor: Int? = null,
        var unmergeFromRotation: Float? = null,
        var unmergeFromPoint: GeoPoint? = null,
        var preClusterPolygon: RoundedPolygon? = null,
        var preClusterColor: Int? = null,
        var preClusterRotation: Float? = null
    )

    private data class MarkerCluster(
        var markers: List<Marker>,
        var centerPoint: GeoPoint,
        var startPolygon: RoundedPolygon,
        var endPolygon: RoundedPolygon,
        var color: Int,
        var rotation: Float = (0..360).random().toFloat(),
        var isAnimating: Boolean = false,
        var animationStartTime: Long = 0L,
        var isFull: Boolean = false
    )

    companion object {
        private const val ANIMATION_DURATION = 500L
    }
}
