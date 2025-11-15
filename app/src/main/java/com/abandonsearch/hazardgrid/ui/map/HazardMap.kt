package com.abandonsearch.hazardgrid.ui.map

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.animation.OvershootInterpolator
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.domain.GeoBounds
import com.abandonsearch.hazardgrid.domain.GeoPoint
import com.abandonsearch.hazardgrid.domain.MapViewport
import com.abandonsearch.hazardgrid.ui.HazardGridViewModel
import com.abandonsearch.hazardgrid.ui.state.HazardUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Projection
import org.maplibre.android.maps.Style
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.random.Random

@Composable
fun HazardMap(
    modifier: Modifier = Modifier,
    uiState: HazardUiState,
    colorScheme: ColorScheme,
    primaryStyleUri: String,
    fallbackStyleUri: String,
    onMarkerSelected: (Place?) -> Unit,
    onViewportChanged: (MapViewport) -> Unit,
    onMapScrolled: () -> Unit,
    onMapPanChanged: (Boolean) -> Unit = {},
    mapEvents: Flow<HazardGridViewModel.MapCommand>,
    mergeShapesEnabled: Boolean,
) {
    val context = LocalContext.current
    MapLibre.getInstance(context)

    val mapView = rememberMapViewWithLifecycle()
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var cameraSnapshot by remember { mutableStateOf<CameraSnapshot?>(null) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var lastViewport by remember { mutableStateOf<MapViewport?>(null) }

    val markerStore = remember(colorScheme) { MarkerStyleStore(colorScheme) }

    LaunchedEffect(uiState.allPlaces) {
        markerStore.sync(uiState.allPlaces)
    }

    LaunchedEffect(uiState.activePlaceId) {
        markerStore.setActive(uiState.activePlaceId)
    }

    LaunchedEffect(mergeShapesEnabled) {
        markerStore.setAnimationsEnabled(mergeShapesEnabled)
    }

    val markerStates = remember(markerStore.version, uiState.allPlaces) {
        markerStore.statesFor(uiState.allPlaces)
    }

    val shapeLayer = remember { MorphingShapeLayer() }
    val shapeBatchWriter = remember { NativeShapeBatchWriter() }
    var currentStyle by remember { mutableStateOf<Style?>(null) }
    var currentStyleUri by remember { mutableStateOf(primaryStyleUri) }
    var styleGeneration by remember { mutableStateOf(0) }
    var lastUploadedGeneration by remember { mutableStateOf(-1) }

    DisposableEffect(Unit) {
        onDispose {
            shapeLayer.detach()
        }
    }

    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            mapLibreMap = map.apply {
                uiSettings.apply {
                    isCompassEnabled = false
                    isLogoEnabled = false
                }
                setMinZoomPreference(4.0)
                setMaxZoomPreference(19.0)
                cameraPosition = CameraPosition.Builder()
                    .target(LatLng(DEFAULT_LAT, DEFAULT_LON))
                    .zoom(DEFAULT_ZOOM)
                    .bearing(0.0)
                    .build()
            }
        }
        onDispose { mapLibreMap = null }
    }

    val latestMarkerStates by rememberUpdatedState(markerStates)
    val latestMapSize by rememberUpdatedState(mapSize)
    val latestCameraSnapshotState = rememberUpdatedState(cameraSnapshot)

    val tapHandler by rememberUpdatedState<(PointF) -> Unit> { point ->
        val snapshot = latestCameraSnapshotState.value
        if (snapshot == null || latestMapSize.width == 0 || latestMapSize.height == 0) {
            onMarkerSelected(null)
            return@rememberUpdatedState
        }
        val tappedMarker = findMarkerAt(
            point = point,
            markerStates = latestMarkerStates,
            cameraSnapshot = snapshot,
            mapSize = latestMapSize,
            mapProjection = mapLibreMap?.projection,
        )
        if (tappedMarker != null) {
            markerStore.setActive(tappedMarker.id)
            markerStore.triggerMorph(tappedMarker.id, force = true)
            onMarkerSelected(tappedMarker.place)
        } else {
            markerStore.setActive(null)
            onMarkerSelected(null)
        }
    }

    DisposableEffect(mapView, mapLibreMap) {
        val map = mapLibreMap ?: return@DisposableEffect onDispose {}
        val cameraChangingListener = MapView.OnCameraIsChangingListener {
            val updatedSnapshot = map.cameraPosition.toSnapshot()
            if (updatedSnapshot != latestCameraSnapshotState.value) {
                cameraSnapshot = updatedSnapshot
            }
        }
        mapView.addOnCameraIsChangingListener(cameraChangingListener)
        onDispose {
            mapView.removeOnCameraIsChangingListener(cameraChangingListener)
        }
    }

    LaunchedEffect(mapLibreMap, primaryStyleUri, fallbackStyleUri) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val targetUri = if (withContext(Dispatchers.IO) { isStyleReachableBlocking(primaryStyleUri) }) {
            primaryStyleUri
        } else {
            fallbackStyleUri
        }
        if (currentStyle != null && currentStyleUri == targetUri) {
            return@LaunchedEffect
        }
        shapeLayer.detach()
        currentStyle = null
        currentStyleUri = targetUri
        map.setStyle(Style.Builder().fromUri(targetUri)) { style ->
            currentStyle = style
            cameraSnapshot = map.cameraPosition.toSnapshot()
            styleGeneration++
        }
    }

    DisposableEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@DisposableEffect onDispose {}
        val cameraIdleListener = MapLibreMap.OnCameraIdleListener {
            cameraSnapshot = map.cameraPosition.toSnapshot()
        }
        val moveListener = object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) {
                onMapPanChanged(true)
            }
            override fun onMove(detector: MoveGestureDetector) {
                onMapScrolled()
            }

            override fun onMoveEnd(detector: MoveGestureDetector) {
                onMapPanChanged(false)
            }
        }
        val clickListener = MapLibreMap.OnMapClickListener { latLng ->
            val screenPoint = map.projection.toScreenLocation(latLng)
            tapHandler(PointF(screenPoint.x.toFloat(), screenPoint.y.toFloat()))
            true
        }
        map.addOnCameraIdleListener(cameraIdleListener)
        map.addOnMoveListener(moveListener)
        map.addOnMapClickListener(clickListener)
        onDispose {
            map.removeOnCameraIdleListener(cameraIdleListener)
            map.removeOnMoveListener(moveListener)
            map.removeOnMapClickListener(clickListener)
        }
    }

    LaunchedEffect(mapEvents, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        mapEvents.collectLatest { command ->
            handleMapCommand(map, command)
        }
    }

    LaunchedEffect(cameraSnapshot, mapSize) {
        val snapshot = cameraSnapshot
        if (snapshot != null && mapSize.width > 0 && mapSize.height > 0) {
            snapshot.toViewport(mapSize)?.let { viewport ->
                onViewportChanged(viewport)
                val previousCenter = lastViewport?.center
                if (previousCenter != null && viewport.center.isSignificantlyDifferent(previousCenter)) {
                    onMapScrolled()
                }
                lastViewport = viewport
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .matchParentSize()
                .onSizeChanged { mapSize = it },
        )
    }

    LaunchedEffect(markerStates, currentStyle, mapLibreMap, styleGeneration) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val style = currentStyle ?: return@LaunchedEffect
        shapeLayer.ensureAttached(style)
        while (isActive) {
            val frameTimeMs = withFrameNanos { it / 1_000_000 }
        val forceUpload = lastUploadedGeneration != styleGeneration
        val predictionTimeMs = frameTimeMs + FRAME_PREDICTION_MS
        val buffer = shapeBatchWriter.encode(markerStates, predictionTimeMs, forceUpload = forceUpload)
            if (buffer != null) {
                shapeLayer.update(buffer)
                lastUploadedGeneration = styleGeneration
                map.triggerRepaint()
            } else if (forceUpload) {
                lastUploadedGeneration = styleGeneration
            }
        }
    }
}

private fun findMarkerAt(
    point: PointF,
    markerStates: List<MarkerShapeState>,
    cameraSnapshot: CameraSnapshot,
    mapSize: IntSize,
    mapProjection: Projection?,
): MarkerShapeState? {
    val ordered = markerStates.sortedWith(
        compareBy<MarkerShapeState> { if (it.isActive) 1 else 0 }.thenBy { it.id }
    )
    return ordered.asReversed().firstOrNull { state ->
        val lat = state.place.lat ?: return@firstOrNull false
        val lon = state.place.lon ?: return@firstOrNull false
        val screen = projectToScreen(
            latLng = LatLng(lat, lon),
            camera = cameraSnapshot,
            mapSize = mapSize,
            mapProjection = mapProjection,
        ) ?: return@firstOrNull false
        val radius = if (state.isActive) ACTIVE_MARKER_RADIUS else DEFAULT_MARKER_RADIUS
        val dx = point.x - screen.x.toFloat()
        val dy = point.y - screen.y.toFloat()
        dx * dx + dy * dy <= (radius + MARKER_HIT_PADDING) * (radius + MARKER_HIT_PADDING)
    }
}

private data class GeometrySnapshot(
    val vertices: FloatArray,
    val fillColor: Int,
    val strokeColor: Int,
    val strokeWidth: Float,
)

private class NativeShapeBatchWriter {
    private var buffer: ByteBuffer = ByteBuffer.allocateDirect(DEFAULT_CAPACITY).order(ByteOrder.LITTLE_ENDIAN)
    private val path = Path()
    private val bounds = RectF()
    private val scaleMatrix = Matrix()
    private val rotationMatrix = Matrix()

    fun encode(
        markerStates: List<MarkerShapeState>,
        frameTimeMs: Long,
        forceUpload: Boolean = false,
    ): ByteBuffer? {
        val needsUpload = forceUpload || markerStates.any { it.geometryDirty || it.isAnimating }
        if (!needsUpload) {
            return null
        }
        ensureCapacity(Int.SIZE_BYTES)
        buffer.clear()
        buffer.putInt(0) // placeholder for count
        var written = 0
        markerStates.forEach { state ->
            val lat = state.place.lat ?: return@forEach
            val lon = state.place.lon ?: return@forEach
            val geometry = state.buildGeometrySnapshot(
                frameTimeMs = frameTimeMs,
                reusablePath = path,
                bounds = bounds,
                scaleMatrix = scaleMatrix,
                rotationMatrix = rotationMatrix,
            ) ?: return@forEach
            val vertexCount = geometry.vertices.size / 2
            if (vertexCount < 3) return@forEach
            val bytesNeeded =
                HEADER_BYTES + geometry.vertices.size * java.lang.Float.BYTES
            ensureCapacity(bytesNeeded)
            buffer.putInt(state.id)
            buffer.putDouble(lat)
            buffer.putDouble(lon)
            buffer.putInt(geometry.fillColor)
            buffer.putInt(geometry.strokeColor)
            buffer.putFloat(geometry.strokeWidth)
            buffer.putInt(vertexCount)
            geometry.vertices.forEach { buffer.putFloat(it) }
            written++
        }
        buffer.putInt(0, written)
        buffer.flip()
        return buffer
    }

    private fun ensureCapacity(requiredAdditionalBytes: Int) {
        if (buffer.remaining() >= requiredAdditionalBytes) return
        val needed = buffer.position() + requiredAdditionalBytes
        var newCapacity = buffer.capacity()
        while (newCapacity < needed) {
            newCapacity *= 2
        }
        val newBuffer = ByteBuffer.allocateDirect(newCapacity).order(ByteOrder.LITTLE_ENDIAN)
        buffer.flip()
        newBuffer.put(buffer)
        buffer = newBuffer
    }

    companion object {
        private const val DEFAULT_CAPACITY = 64 * 1024
        private const val HEADER_BYTES =
            Int.SIZE_BYTES + java.lang.Double.BYTES * 2 + Int.SIZE_BYTES * 4 + java.lang.Float.BYTES
    }
}

private fun MarkerShapeState.buildGeometrySnapshot(
    frameTimeMs: Long,
    reusablePath: Path,
    bounds: RectF,
    scaleMatrix: Matrix,
    rotationMatrix: Matrix,
): GeometrySnapshot? {
    val progress = animationProgress(frameTimeMs)
    val eased = MARKER_INTERPOLATOR.getInterpolation(progress)
    val radius = currentRadius(frameTimeMs)

    if (!isAnimating && !geometryDirty) {
        cachedSnapshot?.let { return it }
    }

    reusablePath.rewind()
    val morph = Morph(startPolygon, endPolygon)
    morph.toPath(eased, reusablePath)

    reusablePath.computeBounds(bounds, true)
    scaleMatrix.reset()
    scaleMatrix.setRectToRect(
        bounds,
        RectF(-radius, -radius, radius, radius),
        Matrix.ScaleToFit.CENTER,
    )
    reusablePath.transform(scaleMatrix)

    rotationMatrix.reset()
    rotationMatrix.postRotate(currentRotation(eased))
    reusablePath.transform(rotationMatrix)

    val vertices = reusablePath.toPolygonVertices() ?: return null

    if (progress >= 1f && isAnimating) {
        completeAnimation()
    }

    val fillColor = interpolateColor(eased)
    val strokeColor = Color(fillColor).copy(alpha = if (isActive) 0.45f else 0.35f).toArgb()
    val strokeWidth = if (isActive) ACTIVE_BORDER_WIDTH else DEFAULT_BORDER_WIDTH

    val snapshot = GeometrySnapshot(
        vertices = vertices,
        fillColor = fillColor,
        strokeColor = strokeColor,
        strokeWidth = strokeWidth,
    )
    if (isAnimating || isSelectionAnimating()) {
        geometryDirty = true
        cachedSnapshot = null
    } else {
        geometryDirty = false
        cachedSnapshot = snapshot
    }
    return snapshot
}

private fun Path.toPolygonVertices(sampleStep: Float = 6f): FloatArray? {
    val measure = PathMeasure(this, true)
    val points = ArrayList<Float>()
    val coords = FloatArray(2)
    var contourIndex = 0
    while (true) {
        val length = measure.length
        if (length > 0f) {
            val steps = maxOf(4, (length / sampleStep).roundToInt())
            for (i in 0..steps) {
                val distance = length * (i / steps.toFloat())
                if (measure.getPosTan(distance, coords, null)) {
                    appendPoint(points, coords[0], coords[1])
                }
            }
        }
        contourIndex++
        if (!measure.nextContour()) break
    }
    if (points.size < 6) return null
    val firstX = points[0]
    val firstY = points[1]
    val lastIndex = points.size - 2
    val lastX = points[lastIndex]
    val lastY = points[lastIndex + 1]
    if (kotlin.math.abs(lastX - firstX) < 0.1f && kotlin.math.abs(lastY - firstY) < 0.1f) {
        points.removeAt(points.lastIndex)
        points.removeAt(points.lastIndex)
    }
    val result = FloatArray(points.size)
    for (i in points.indices) {
        result[i] = points[i]
    }
    return result
}

private fun appendPoint(dest: MutableList<Float>, x: Float, y: Float, threshold: Float = 0.1f) {
    if (dest.isEmpty()) {
        dest.add(x)
        dest.add(y)
        return
    }
    val lastX = dest[dest.size - 2]
    val lastY = dest.last()
    if (kotlin.math.abs(x - lastX) < threshold && kotlin.math.abs(y - lastY) < threshold) {
        return
    }
    dest.add(x)
    dest.add(y)
}

private class MarkerStyleStore(
    colorScheme: ColorScheme,
) {
    private val shapes = shapeCatalog
    private val accentColors = listOf(
        colorScheme.primary,
        colorScheme.secondary,
        colorScheme.tertiary,
        colorScheme.primaryContainer,
        colorScheme.secondaryContainer,
        colorScheme.tertiaryContainer,
    ).map { it.toArgb() }.ifEmpty { listOf(Color(0xFFFF7043).toArgb(), Color(0xFF5E81AC).toArgb()) }

    private val states = LinkedHashMap<Int, MarkerShapeState>()
    private val random = Random(SystemClock.uptimeMillis())
    private var animationsEnabled = true

    var version by mutableIntStateOf(0)
        private set

    fun sync(places: List<Place>) {
        val seen = HashSet<Int>()
        places.forEach { place ->
            val id = place.id
            seen += id
            val state = states[id]
            if (state == null) {
                val shape = shapes[random.nextInt(shapes.size)]
                val color = accentColors[random.nextInt(accentColors.size)]
                val rotation = random.nextInt(360).toFloat()
                states[id] = MarkerShapeState(place, shape, color, rotation)
            } else {
                state.place = place
            }
        }
        val iterator = states.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!seen.contains(entry.key)) {
                iterator.remove()
            }
        }
        bump()
    }

    fun statesFor(places: List<Place>): List<MarkerShapeState> =
        places.mapNotNull { states[it.id] }

    fun setActive(id: Int?) {
        var changed = false
        states.values.forEach { state ->
            val active = id != null && state.id == id
            if (state.setActive(active)) {
                changed = true
            }
        }
        if (changed) bump()
    }

    fun triggerMorph(id: Int, force: Boolean = false) {
        if (!animationsEnabled && !force) return
        val state = states[id] ?: return
        val nextShape = randomDifferentShape(state.endPolygon)
        val nextColor = randomDifferentColor(state.endColor)
        val nextRotation = random.nextInt(360).toFloat()
        Log.d(
            "MarkerStyleStore",
            "triggerMorph id=$id shape=$nextShape color=${nextColor.toUInt().toString(16)} rotation=$nextRotation force=$force"
        )
        state.prepareForAnimation(nextShape, nextColor, nextRotation)
        bump()
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        if (animationsEnabled == enabled) return
        animationsEnabled = enabled
        if (!enabled) {
            states.values.forEach { state ->
                if (state.isAnimating) {
                    state.completeAnimation()
                }
            }
        }
        bump()
    }

    private fun randomDifferentShape(current: RoundedPolygon): RoundedPolygon {
        if (shapes.size == 1) return shapes.first()
        var next = shapes[random.nextInt(shapes.size)]
        while (next == current) {
            next = shapes[random.nextInt(shapes.size)]
        }
        return next
    }

    private fun randomDifferentColor(current: Int): Int {
        if (accentColors.size == 1) return accentColors.first()
        var next = accentColors[random.nextInt(accentColors.size)]
        while (next == current) {
            next = accentColors[random.nextInt(accentColors.size)]
        }
        return next
    }

    private fun bump() {
        version++
    }
}

private class MarkerShapeState(
    place: Place,
    initialPolygon: RoundedPolygon,
    initialColor: Int,
    initialRotation: Float,
) {
    var place: Place = place
    var startPolygon: RoundedPolygon = initialPolygon
    var endPolygon: RoundedPolygon = initialPolygon
    var startColor: Int = initialColor
    var endColor: Int = initialColor
    var startRotation: Float = initialRotation
    var endRotation: Float = initialRotation
    var isAnimating: Boolean = false
    var animationStartTime: Long = 0L
    var isActive: Boolean = false
    private var selectionAnimStart: Long = 0L
    private var selectionAnimFrom: Float = DEFAULT_MARKER_RADIUS
    private var selectionAnimTo: Float = DEFAULT_MARKER_RADIUS
    var cachedSnapshot: GeometrySnapshot? = null
    var geometryDirty: Boolean = true

    val id: Int get() = place.id

    fun animationProgress(now: Long): Float {
        if (!isAnimating) return 1f
        val elapsed = (now - animationStartTime).coerceAtLeast(0L)
        return (elapsed.toFloat() / MARKER_ANIMATION_DURATION).coerceIn(0f, 1f)
    }

    fun completeAnimation() {
        startPolygon = endPolygon
        startColor = endColor
        startRotation = endRotation
        isAnimating = false
        invalidateGeometryCache()
    }

    fun currentRotation(progress: Float): Float =
        startRotation + (endRotation - startRotation) * progress

    fun interpolateColor(progress: Float): Int {
        val fromA = (startColor shr 24) and 0xFF
        val fromR = (startColor shr 16) and 0xFF
        val fromG = (startColor shr 8) and 0xFF
        val fromB = startColor and 0xFF

        val toA = (endColor shr 24) and 0xFF
        val toR = (endColor shr 16) and 0xFF
        val toG = (endColor shr 8) and 0xFF
        val toB = endColor and 0xFF

        val a = (fromA + (toA - fromA) * progress).toInt().coerceIn(0, 255)
        val r = (fromR + (toR - fromR) * progress).toInt().coerceIn(0, 255)
        val g = (fromG + (toG - fromG) * progress).toInt().coerceIn(0, 255)
        val b = (fromB + (toB - fromB) * progress).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun prepareForAnimation(
        nextPolygon: RoundedPolygon,
        nextColor: Int,
        nextRotation: Float,
    ) {
        if (isAnimating) {
            completeAnimation()
        }
        Log.d(
            "MarkerShapeState",
            "prepareForAnimation id=$id nextShape=$nextPolygon color=${nextColor.toUInt().toString(16)} rotation=$nextRotation"
        )
        endPolygon = nextPolygon
        endColor = nextColor
        endRotation = nextRotation
        animationStartTime = SystemClock.uptimeMillis()
        isAnimating = true
        invalidateGeometryCache()
    }

    fun setActive(active: Boolean): Boolean {
        if (isActive == active) return false
        val now = SystemClock.uptimeMillis()
        val currentRadius = currentRadius(now)
        isActive = active
        selectionAnimStart = now
        selectionAnimFrom = currentRadius
        selectionAnimTo = if (active) ACTIVE_MARKER_RADIUS else DEFAULT_MARKER_RADIUS
        invalidateGeometryCache()
        return true
    }

    private fun invalidateGeometryCache() {
        geometryDirty = true
        cachedSnapshot = null
    }

    fun currentRadius(now: Long): Float {
        if (selectionAnimStart == 0L) {
            return if (isActive) ACTIVE_MARKER_RADIUS else DEFAULT_MARKER_RADIUS
        }
        val elapsed = (now - selectionAnimStart).coerceAtLeast(0L)
        if (elapsed >= MARKER_SELECTION_DURATION) {
            selectionAnimStart = 0L
            selectionAnimFrom = selectionAnimTo
            return selectionAnimTo
        }
        val progress = elapsed.toFloat() / MARKER_SELECTION_DURATION
        val eased = MARKER_INTERPOLATOR.getInterpolation(progress)
        return selectionAnimFrom + (selectionAnimTo - selectionAnimFrom) * eased
    }

    fun isSelectionAnimating(): Boolean = selectionAnimStart != 0L
}

private data class CameraSnapshot(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val direction: Double,
)

private data class ScreenProjection(
    val x: Double,
    val y: Double,
)

private fun projectToScreen(
    latLng: LatLng,
    camera: CameraSnapshot,
    mapSize: IntSize,
    mapProjection: Projection?,
): ScreenProjection? {
    if (mapSize.width == 0 || mapSize.height == 0) return null
    if (mapProjection != null) {
        val point = mapProjection.toScreenLocation(latLng)
        return ScreenProjection(point.x.toDouble(), point.y.toDouble())
    }
    val markerPixel = latLng.toPixel(camera.zoom)
    val centerPixel = LatLng(camera.latitude, camera.longitude).toPixel(camera.zoom)
    val worldWidth = TILE_SIZE * 2.0.pow(camera.zoom)
    var dx = markerPixel.x - centerPixel.x
    if (dx > worldWidth / 2) dx -= worldWidth
    if (dx < -worldWidth / 2) dx += worldWidth
    val dy = markerPixel.y - centerPixel.y
    val screenX = mapSize.width / 2.0 + dx
    val screenY = mapSize.height / 2.0 + dy
    return ScreenProjection(screenX, screenY)
}

private fun LatLng.toPixel(zoom: Double): ScreenProjection {
    val clampedLat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    val normalizedLon = normalizeLongitude(longitude)
    val sinLat = sin(Math.toRadians(clampedLat))
    val worldX = (normalizedLon + 180.0) / 360.0
    val worldY = 0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * PI)
    val scale = TILE_SIZE * 2.0.pow(zoom)
    return ScreenProjection(worldX * scale, worldY * scale)
}

private fun pixelToLatLng(pixelX: Double, pixelY: Double, zoom: Double): LatLng? {
    val scale = TILE_SIZE * 2.0.pow(zoom)
    val normalizedX = wrap01(pixelX / scale)
    val normalizedY = wrap01(pixelY / scale)
    val lon = normalizedX * 360.0 - 180.0
    val latRad = PI * (1 - 2 * normalizedY)
    val lat = Math.toDegrees(atan(sinh(latRad))).coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    return LatLng(lat, lon)
}

private fun CameraSnapshot.toViewport(mapSize: IntSize): MapViewport? {
    if (mapSize.width == 0 || mapSize.height == 0) return null
    val centerLatLng = LatLng(latitude, longitude)
    val centerPixel = centerLatLng.toPixel(zoom)
    val halfWidth = mapSize.width / 2.0
    val halfHeight = mapSize.height / 2.0
    val northWest = pixelToLatLng(centerPixel.x - halfWidth, centerPixel.y - halfHeight, zoom) ?: return null
    val southEast = pixelToLatLng(centerPixel.x + halfWidth, centerPixel.y + halfHeight, zoom) ?: return null
    val bounds = GeoBounds(
        north = max(northWest.latitude, southEast.latitude),
        south = min(northWest.latitude, southEast.latitude),
        east = normalizeLongitude(southEast.longitude),
        west = normalizeLongitude(northWest.longitude),
    )
    return MapViewport(
        center = GeoPoint(latitude, longitude),
        bounds = bounds,
    )
}

private fun CameraPosition.toSnapshot(): CameraSnapshot {
    val tgt = target
    val lat = tgt?.latitude ?: DEFAULT_LAT
    val lon = tgt?.longitude ?: DEFAULT_LON
    return CameraSnapshot(
        latitude = lat,
        longitude = lon,
        zoom = zoom,
        direction = bearing,
    )
}

private fun MapLibreMap.applyCameraUpdate(update: CameraUpdate, animate: Boolean) {
    if (animate) {
        animateCamera(update, ANIMATION_DURATION.toInt())
    } else {
        moveCamera(update)
    }
}

private fun handleMapCommand(map: MapLibreMap, command: HazardGridViewModel.MapCommand) {
    when (command) {
        is HazardGridViewModel.MapCommand.FocusOnPlace -> {
            val lat = command.place.lat
            val lon = command.place.lon
            if (lat != null && lon != null) {
                val update = CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), FOCUS_ZOOM)
                map.applyCameraUpdate(update, true)
            }
        }

        is HazardGridViewModel.MapCommand.FocusOnLocation -> {
            val latLng = LatLng(command.location.latitude, command.location.longitude)
            val update = command.zoom?.let { CameraUpdateFactory.newLatLngZoom(latLng, it) }
                ?: CameraUpdateFactory.newLatLng(latLng)
            map.applyCameraUpdate(update, command.animate)
        }

        is HazardGridViewModel.MapCommand.SetOrientation -> {
            val current = map.cameraPosition
            val position = CameraPosition.Builder(current)
                .bearing(normalizeBearing(command.bearing.toDouble()))
                .build()
            val update = CameraUpdateFactory.newCameraPosition(position)
            map.applyCameraUpdate(update, command.animate)
        }

        HazardGridViewModel.MapCommand.ResetOrientation -> {
            val current = map.cameraPosition
            val position = CameraPosition.Builder(current)
                .bearing(0.0)
                .build()
            val update = CameraUpdateFactory.newCameraPosition(position)
            map.applyCameraUpdate(update, true)
        }
    }
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = getMapLifecycleObserver(mapView)
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return mapView
}

private fun getMapLifecycleObserver(mapView: MapView): LifecycleEventObserver {
    return LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
            Lifecycle.Event.ON_START -> mapView.onStart()
            Lifecycle.Event.ON_RESUME -> mapView.onResume()
            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
            Lifecycle.Event.ON_STOP -> mapView.onStop()
            Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
            else -> Unit
        }
    }
}

private fun normalizeLongitude(value: Double): Double {
    var lon = value
    while (lon < -180.0) lon += 360.0
    while (lon > 180.0) lon -= 360.0
    return lon
}

private fun normalizeBearing(value: Double): Double {
    var bearing = value % 360.0
    if (bearing < 0) bearing += 360.0
    return bearing
}

private fun wrap01(value: Double): Double {
    var wrapped = value % 1.0
    if (wrapped < 0) wrapped += 1.0
    return wrapped
}

private fun GeoPoint.isSignificantlyDifferent(other: GeoPoint, epsilon: Double = 1e-4): Boolean {
    val latDiff = abs(latitude - other.latitude)
    val lonDiff = abs(normalizeLongitude(longitude - other.longitude))
    return latDiff > epsilon || lonDiff > epsilon
}

private fun isStyleReachableBlocking(uri: String): Boolean {
    fun request(method: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(uri).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 4000
                readTimeout = 4000
            }
            connection.connect()
            val code = connection.responseCode
            code in 200..399
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
    return request("HEAD") || request("GET")
}

private const val DEFAULT_LAT = 55.7558
private const val DEFAULT_LON = 37.6173
private const val DEFAULT_ZOOM = 10.0
private const val FOCUS_ZOOM = 15.0
private const val ANIMATION_DURATION = 800L
private const val TILE_SIZE = 256.0
private const val MAX_LATITUDE = 85.05112878
private const val DEFAULT_MARKER_RADIUS = 34f
private const val ACTIVE_MARKER_RADIUS = 44f
private const val DEFAULT_BORDER_WIDTH = 2f
private const val ACTIVE_BORDER_WIDTH = 3f
private const val MARKER_HIT_PADDING = 12f
private const val MARKER_ANIMATION_DURATION = 500L
private const val MARKER_SELECTION_DURATION = 160L
private const val FRAME_PREDICTION_MS = 24L
private val MARKER_INTERPOLATOR = OvershootInterpolator()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val shapeCatalog = listOf(
    MaterialShapes.Circle,
    MaterialShapes.Square,
    MaterialShapes.Slanted,
    MaterialShapes.Arch,
    MaterialShapes.Oval,
    MaterialShapes.Pill,
    MaterialShapes.Triangle,
    MaterialShapes.Arrow,
    MaterialShapes.Diamond,
    MaterialShapes.ClamShell,
    MaterialShapes.Pentagon,
    MaterialShapes.Gem,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie4Sided,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Cookie7Sided,
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Clover8Leaf,
    MaterialShapes.Flower,
    MaterialShapes.Ghostish,
    MaterialShapes.Bun
)
