package com.abandonsearch.hazardgrid.ui.map

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.animation.OvershootInterpolator
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.domain.GeoBounds
import com.abandonsearch.hazardgrid.domain.GeoPoint
import com.abandonsearch.hazardgrid.domain.MapViewport
import com.abandonsearch.hazardgrid.ui.HazardGridViewModel
import com.abandonsearch.hazardgrid.ui.state.HazardUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
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

@Suppress("UNUSED_PARAMETER")
@Composable
fun HazardMap(
    modifier: Modifier = Modifier,
    uiState: HazardUiState,
    colorScheme: ColorScheme,
    primaryStyleUri: String = OPEN_FREEMAP_STYLE_URL,
    fallbackStyleUri: String = OPEN_FREEMAP_STYLE_URL,
    onMarkerSelected: (Place?) -> Unit,
    onViewportChanged: (MapViewport) -> Unit,
    onMapScrolled: () -> Unit,
    onMapPanChanged: (Boolean) -> Unit = {},
    mapEvents: Flow<HazardGridViewModel.MapCommand>,
    mergeShapesEnabled: Boolean,
    userLocation: GeoPoint? = null,
    userHeading: Float? = null,
) {
    val context = LocalContext.current
    MapLibre.getInstance(context)

    val mapView = rememberMapViewWithLifecycle()
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var cameraSnapshot by remember { mutableStateOf<CameraSnapshot?>(null) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var lastViewport by remember { mutableStateOf<MapViewport?>(null) }
    var currentStyle by remember { mutableStateOf<Style?>(null) }

    val markerStore = remember(colorScheme) { MarkerStyleStore(colorScheme) }
    val shapeLayer = remember { MorphingShapeLayer() }
    val shapeBatchWriter = remember { NativeShapeBatchWriter() }
    val frameTimeMs = rememberFrameTimeMillis()

    DisposableEffect(Unit) {
        onDispose { shapeLayer.detach() }
    }

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

    DisposableEffect(mapView) {
        val resolvedStyleUri = primaryStyleUri.ifEmpty { fallbackStyleUri }
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
                setStyle(Style.Builder().fromUri(resolvedStyleUri)) { style ->
                    currentStyle = style
                    cameraSnapshot = cameraPosition.toSnapshot()
                }
            }
        }
        onDispose { mapLibreMap = null }
    }

    val latestMarkerStates = rememberUpdatedState(markerStates)
    val latestMapSize = rememberUpdatedState(mapSize)
    val latestCameraSnapshot = rememberUpdatedState(cameraSnapshot)

    val tapHandler by rememberUpdatedState(newValue = { point: PointF ->
        val snapshot = latestCameraSnapshot.value
        if (snapshot == null || latestMapSize.value.width == 0 || latestMapSize.value.height == 0) {
            onMarkerSelected(null)
            return@rememberUpdatedState
        }
        val tapped = findMarkerAt(
            point = point,
            markerStates = latestMarkerStates.value,
            cameraSnapshot = snapshot,
            mapSize = latestMapSize.value,
            mapProjection = mapLibreMap?.projection,
        )
        if (tapped != null) {
            markerStore.setActive(tapped.id)
            markerStore.triggerMorph(tapped.id)
            onMarkerSelected(tapped.place)
        } else {
            markerStore.setActive(null)
            onMarkerSelected(null)
        }
    })

    DisposableEffect(mapView, mapLibreMap) {
        val map = mapLibreMap ?: return@DisposableEffect onDispose {}
        val cameraChangingListener = MapView.OnCameraIsChangingListener {
            val updated = map.cameraPosition.toSnapshot()
            if (updated != latestCameraSnapshot.value) {
                cameraSnapshot = updated
            }
        }
        mapView.addOnCameraIsChangingListener(cameraChangingListener)
        onDispose { mapView.removeOnCameraIsChangingListener(cameraChangingListener) }
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
            val pt = map.projection.toScreenLocation(latLng)
            tapHandler(PointF(pt.x.toFloat(), pt.y.toFloat()))
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
                val prevCenter = lastViewport?.center
                if (prevCenter != null && viewport.center.isSignificantlyDifferent(prevCenter)) {
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

    LaunchedEffect(currentStyle, markerStates, frameTimeMs, mapLibreMap) {
        val style = currentStyle ?: return@LaunchedEffect
        shapeLayer.ensureAttached(style)
        val buffer = shapeBatchWriter.encode(markerStates, frameTimeMs) ?: return@LaunchedEffect
        shapeLayer.update(buffer)
        mapLibreMap?.triggerRepaint()
    }
}

private fun findMarkerAt(
    point: PointF,
    markerStates: List<MarkerShapeState>,
    cameraSnapshot: CameraSnapshot,
    mapSize: IntSize,
    mapProjection: Projection?,
): MarkerShapeState? {
    val ordered = markerStates.sortedWith(compareBy<MarkerShapeState> { if (it.isActive) 1 else 0 }.thenBy { it.id })
    return ordered.asReversed().firstOrNull { state ->
        val lat = state.place.lat ?: return@firstOrNull false
        val lon = state.place.lon ?: return@firstOrNull false
        val screen = projectToScreen(
            latLng = LatLng(lat, lon),
            camera = cameraSnapshot,
            mapSize = mapSize,
            mapProjection = mapProjection,
        ) ?: return@firstOrNull false
        val radius = state.hitRadius()
        val dx = point.x - screen.x.toFloat()
        val dy = point.y - screen.y.toFloat()
        dx * dx + dy * dy <= (radius + MARKER_HIT_PADDING) * (radius + MARKER_HIT_PADDING)
    }
}

@Composable
private fun rememberFrameTimeMillis(): Long {
    val frameTime by produceState(SystemClock.uptimeMillis()) {
        while (true) {
            withFrameNanos { value = it / 1_000_000 }
        }
    }
    return frameTime
}

private data class MarkerKeyframePayload(
    val vertexCount: Int,
    val startVertices: FloatArray,
    val endVertices: FloatArray,
    val startFillColor: Int,
    val endFillColor: Int,
    val startStrokeColor: Int,
    val endStrokeColor: Int,
    val animationStart: Long,
    val animationDuration: Long,
    val easingId: Int,
    val isSelected: Boolean,
)
private class NativeShapeBatchWriter {
    private var buffer: ByteBuffer = ByteBuffer.allocateDirect(DEFAULT_CAPACITY).order(ByteOrder.LITTLE_ENDIAN)
    private var lastMarkerCount = -1
    private val startPath = Path()
    private val endPath = Path()
    private val tempPath = Path()
    private val bounds = RectF()
    private val scaleMatrix = Matrix()
    private val rotationMatrix = Matrix()

    fun encode(
        markerStates: List<MarkerShapeState>,
        frameTimeMs: Long,
        forceUpload: Boolean = false,
    ): ByteBuffer? {
        val predictedTime = frameTimeMs + FRAME_PREDICTION_MS
        markerStates.forEach { it.updateAnimationState(predictedTime) }
        val markerCountChanged = lastMarkerCount != markerStates.size
        val needsUpload = forceUpload || markerCountChanged || markerStates.any { it.geometryDirty }
        if (!needsUpload) {
            return null
        }
        ensureCapacity(Int.SIZE_BYTES)
        buffer.clear()
        buffer.putInt(0)
        var written = 0
        markerStates.forEach { state ->
            val lat = state.place.lat ?: return@forEach
            val lon = state.place.lon ?: return@forEach
            val payload = state.ensureKeyframePayload(
                startPath = startPath,
                endPath = endPath,
                tempPath = tempPath,
                bounds = bounds,
                scaleMatrix = scaleMatrix,
                rotationMatrix = rotationMatrix,
            ) ?: return@forEach
            val markerBytes = MARKER_HEADER_BYTES + payload.vertexCount * MARKER_GEOMETRY_BYTES_PER_VERTEX
            ensureCapacity(markerBytes)
            buffer.putInt(state.id)
            buffer.putDouble(lat)
            buffer.putDouble(lon)
            buffer.putFloat(state.strokeWidth())
            buffer.putInt(payload.vertexCount)
            buffer.putLong(payload.animationStart)
            buffer.putLong(payload.animationDuration)
            buffer.putInt(payload.easingId)
            buffer.putInt(if (payload.isSelected) 1 else 0)
            buffer.putInt(payload.startFillColor)
            buffer.putInt(payload.endFillColor)
            buffer.putInt(payload.startStrokeColor)
            buffer.putInt(payload.endStrokeColor)
            payload.startVertices.forEach { buffer.putFloat(it) }
            payload.endVertices.forEach { buffer.putFloat(it) }
            written++
        }
        buffer.putInt(0, written)
        buffer.flip()
        lastMarkerCount = markerStates.size
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
        private const val MARKER_HEADER_BYTES =
            Int.SIZE_BYTES * 8 + Double.SIZE_BYTES * 2 + Float.SIZE_BYTES + Long.SIZE_BYTES * 2
        private const val MARKER_GEOMETRY_BYTES_PER_VERTEX = Float.SIZE_BYTES * 4
    }
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
                state.updatePlace(place)
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

    fun setAnimationsEnabled(enabled: Boolean) {
        if (animationsEnabled == enabled) return
        animationsEnabled = enabled
        if (!enabled) {
            states.values.forEach { it.finishAnimationNow() }
        }
        bump()
    }

    fun triggerMorph(id: Int, force: Boolean = false) {
        if (!animationsEnabled && !force) return
        val state = states[id] ?: return
        val nextShape = randomDifferentShape(state.endPolygon)
        val nextColor = randomDifferentColor(state.endColor)
        val nextRotation = random.nextInt(360).toFloat()
        state.prepareForAnimation(nextShape, nextColor, nextRotation)
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
    initialPlace: Place,
    initialPolygon: RoundedPolygon,
    initialColor: Int,
    initialRotation: Float,
) {
    var place: Place = initialPlace
        private set
    var startPolygon: RoundedPolygon = initialPolygon
        private set
    var endPolygon: RoundedPolygon = initialPolygon
        private set
    var startColor: Int = initialColor
        private set
    var endColor: Int = initialColor
        private set
    var startRotation: Float = initialRotation
        private set
    var endRotation: Float = initialRotation
        private set
    var startRadius: Float = DEFAULT_MARKER_RADIUS
        private set
    var endRadius: Float = DEFAULT_MARKER_RADIUS
        private set
    var animationStartTime: Long = 0L
        private set
    var animationDuration: Long = 0L
        private set
    var isAnimating: Boolean = false
        private set
    var isActive: Boolean = false
        private set
    var geometryDirty: Boolean = true
        private set
    var cachedSnapshot: MarkerKeyframePayload? = null
        private set
    var startStrokeColor: Int = strokeColorFor(false)
        private set
    var endStrokeColor: Int = strokeColorFor(false)
        private set

    val id: Int get() = place.id

    fun updatePlace(next: Place) {
        place = next
    }

    fun updateAnimationState(now: Long) {
        if (!isAnimating) return
        val elapsed = (now - animationStartTime).coerceAtLeast(0L)
        if (animationDuration <= 0L || elapsed >= animationDuration) {
            completeAnimation()
        }
    }

    fun finishAnimationNow() {
        if (!isAnimating) return
        completeAnimation()
    }

    private fun completeAnimation() {
        startPolygon = endPolygon
        startColor = endColor
        startRotation = endRotation
        startRadius = endRadius
        startStrokeColor = endStrokeColor
        animationStartTime = 0L
        animationDuration = 0L
        isAnimating = false
        geometryDirty = true
        cachedSnapshot = null
    }

    fun prepareForAnimation(
        nextPolygon: RoundedPolygon,
        nextColor: Int,
        nextRotation: Float,
    ) {
        if (isAnimating) {
            completeAnimation()
        }
        startPolygon = endPolygon
        startColor = endColor
        startRotation = endRotation
        startRadius = endRadius
        startStrokeColor = endStrokeColor
        endPolygon = nextPolygon
        endColor = nextColor
        endRotation = nextRotation
        endRadius = startRadius
        endStrokeColor = startStrokeColor
        animationStartTime = SystemClock.uptimeMillis()
        animationDuration = MARKER_ANIMATION_DURATION
        isAnimating = true
        markDirty()
    }

    fun setActive(active: Boolean): Boolean {
        if (isActive == active) return false
        val now = SystemClock.uptimeMillis()
        val currentRadius = currentRadius(now)
        isActive = active
        startPolygon = endPolygon
        endPolygon = endPolygon
        startColor = endColor
        endColor = endColor
        startRotation = endRotation
        endRotation = endRotation
        startRadius = currentRadius
        endRadius = if (active) ACTIVE_MARKER_RADIUS else DEFAULT_MARKER_RADIUS
        startStrokeColor = strokeColorFor(!active)
        endStrokeColor = strokeColorFor(active)
        animationStartTime = now
        animationDuration = MARKER_SELECTION_DURATION
        isAnimating = true
        markDirty()
        return true
    }

    fun strokeWidth(): Float = if (isActive) ACTIVE_BORDER_WIDTH else DEFAULT_BORDER_WIDTH

    fun hitRadius(): Float = if (isActive) ACTIVE_MARKER_RADIUS else DEFAULT_MARKER_RADIUS

    fun currentRadius(now: Long): Float {
        if (!isAnimating || animationDuration <= 0L) {
            return endRadius
        }
        val elapsed = (now - animationStartTime).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / animationDuration).coerceIn(0f, 1f)
        return startRadius + (endRadius - startRadius) * MARKER_INTERPOLATOR.getInterpolation(progress)
    }

    fun ensureKeyframePayload(
        startPath: Path,
        endPath: Path,
        tempPath: Path,
        bounds: RectF,
        scaleMatrix: Matrix,
        rotationMatrix: Matrix,
    ): MarkerKeyframePayload? {
        if (!geometryDirty) {
            cachedSnapshot?.let { return it }
        }
        val morph = Morph(startPolygon, if (isAnimating) endPolygon else startPolygon)
        startPath.rewind()
        morph.toPath(0f, startPath)
        val targetPath = Path()
        morph.toPath(if (isAnimating) 1f else 0f, targetPath)
        endPath.rewind()
        endPath.addPath(targetPath)

        val startVertices = buildVertices(
            source = startPath,
            pathScratch = tempPath,
            bounds = bounds,
            scaleMatrix = scaleMatrix,
            rotationMatrix = rotationMatrix,
            rotation = startRotation,
            radius = startRadius,
        ) ?: return null
        val endVertices = buildVertices(
            source = endPath,
            pathScratch = tempPath,
            bounds = bounds,
            scaleMatrix = scaleMatrix,
            rotationMatrix = rotationMatrix,
            rotation = if (isAnimating) endRotation else startRotation,
            radius = endRadius,
        ) ?: return null
        if (startVertices.size == endVertices.size) {
            endVertices.alignToReference(startVertices)
        }
        val payload = MarkerKeyframePayload(
            vertexCount = startVertices.size / 2,
            startVertices = startVertices,
            endVertices = endVertices,
            startFillColor = startColor,
            endFillColor = endColor,
            startStrokeColor = startStrokeColor,
            endStrokeColor = endStrokeColor,
            animationStart = animationStartTime,
            animationDuration = animationDuration,
            easingId = MARKER_EASING_ID,
            isSelected = isActive,
        )
        cachedSnapshot = payload
        geometryDirty = false
        return payload
    }

    private fun buildVertices(
        source: Path,
        pathScratch: Path,
        bounds: RectF,
        scaleMatrix: Matrix,
        rotationMatrix: Matrix,
        rotation: Float,
        radius: Float,
    ): FloatArray? {
        if (radius < SUSPICIOUS_RADIUS_THRESHOLD) return null
        pathScratch.reset()
        pathScratch.addPath(source)
        pathScratch.computeBounds(bounds, true)
        scaleMatrix.reset()
        scaleMatrix.setRectToRect(
            bounds,
            RectF(-radius, -radius, radius, radius),
            Matrix.ScaleToFit.CENTER,
        )
        pathScratch.transform(scaleMatrix)
        rotationMatrix.reset()
        rotationMatrix.postRotate(rotation)
        pathScratch.transform(rotationMatrix)
        return samplePath(pathScratch, VERTEX_SAMPLES)
    }

    private fun markDirty() {
        geometryDirty = true
        cachedSnapshot = null
    }
}
private fun samplePath(path: Path, vertices: Int): FloatArray? {
    if (vertices < 3) return null
    val approx = path.approximate(0.5f)
    if (approx.size < 3) return null
    val result = FloatArray(vertices * 2)
    for (i in 0 until vertices) {
        val target = i.toFloat() / vertices
        var idx = 0
        while (idx + 3 < approx.size && approx[idx + 3] < target) {
            idx += 3
        }
        val startT = approx[idx]
        val startX = approx[idx + 1]
        val startY = approx[idx + 2]
        val endT = approx[idx + 3].coerceAtLeast(startT + 1e-4f)
        val endX = approx[idx + 4]
        val endY = approx[idx + 5]
        val segmentT = ((target - startT) / (endT - startT)).coerceIn(0f, 1f)
        result[i * 2] = startX + (endX - startX) * segmentT
        result[i * 2 + 1] = startY + (endY - startY) * segmentT
    }
    return result
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

private fun strokeColorFor(active: Boolean): Int =
    Color.Black.copy(alpha = if (active) 0.85f else 0.65f).toArgb()

private fun FloatArray.alignToReference(reference: FloatArray): Boolean {
    if (reference.size != size || size % 2 != 0 || size == 0) return false
    val pointCount = size / 2
    var bestShift = 0
    var bestReversed = false
    var bestScore = Float.POSITIVE_INFINITY
    val orientations = listOf(false, true)
    for (reversed in orientations) {
        for (shift in 0 until pointCount) {
            var score = 0f
            var idx = 0
            while (idx < pointCount && score < bestScore) {
                val refX = reference[idx * 2]
                val refY = reference[idx * 2 + 1]
                val sourceIndex = if (!reversed) {
                    (idx + shift) % pointCount
                } else {
                    val offset = (idx + shift) % pointCount
                    pointCount - 1 - offset
                }
                val srcX = this[sourceIndex * 2]
                val srcY = this[sourceIndex * 2 + 1]
                val dx = srcX - refX
                val dy = srcY - refY
                score += dx * dx + dy * dy
                idx++
            }
            if (score < bestScore) {
                bestScore = score
                bestShift = shift
                bestReversed = reversed
            }
        }
    }
    if (!bestScore.isFinite()) return false
    if (bestShift == 0 && !bestReversed) {
        return true
    }
    val aligned = FloatArray(size)
    for (i in 0 until pointCount) {
        val sourceIndex = if (!bestReversed) {
            (i + bestShift) % pointCount
        } else {
            val offset = (i + bestShift) % pointCount
            pointCount - 1 - offset
        }
        aligned[i * 2] = this[sourceIndex * 2]
        aligned[i * 2 + 1] = this[sourceIndex * 2 + 1]
    }
    aligned.copyInto(this)
    return true
}

private const val OPEN_FREEMAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val DEFAULT_LAT = 55.7558
private const val DEFAULT_LON = 37.6173
private const val DEFAULT_ZOOM = 10.0
private const val FOCUS_ZOOM = 15.0
private const val ANIMATION_DURATION = 800L
private const val TILE_SIZE = 256.0
private const val MAX_LATITUDE = 85.05112878
private const val DEFAULT_MARKER_RADIUS = 18f
private const val ACTIVE_MARKER_RADIUS = 26f
private const val DEFAULT_BORDER_WIDTH = 2f
private const val ACTIVE_BORDER_WIDTH = 3f
private const val MARKER_HIT_PADDING = 12f
private const val MARKER_ANIMATION_DURATION = 500L
private const val MARKER_SELECTION_DURATION = 280L
private const val FRAME_PREDICTION_MS = 24L
private const val MARKER_EASING_ID = 1
private const val VERTEX_SAMPLES = 96
private const val SUSPICIOUS_RADIUS_THRESHOLD = 6f
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
    MaterialShapes.Bun,
)
