package com.abandonsearch.hazardgrid.ui.map

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PathMeasure
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
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.absoluteValue
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    userLocation: GeoPoint?,
    userHeading: Float?,
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

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    val userMarkerState = remember {
        MarkerShapeState(
            initialPlace = createUserPlace(null),
            initialPolygon = MaterialShapes.Arrow,
            initialColor = colorScheme.error.toArgb(),
            initialRotation = 0f,
        )
    }

    val userMarkerColor = colorScheme.error.toArgb()

    LaunchedEffect(userLocation, userMarkerColor) {
        val location = userLocation
        if (location == null) {
            if (userMarkerState.hasValidLocation()) {
                userMarkerState.updatePlace(createUserPlace(null))
            }
            return@LaunchedEffect
        }
        userMarkerState.updatePlace(createUserPlace(location))
        userMarkerState.setStaticState(MaterialShapes.Arrow, userMarkerColor, userMarkerState.endRotation)
    }

    LaunchedEffect(userHeading) {
        val headingValue = userHeading ?: return@LaunchedEffect
        if (!userMarkerState.hasValidLocation()) return@LaunchedEffect
        val normalized = normalizeBearing(headingValue.toDouble()).toFloat()
        if ((userMarkerState.endRotation - normalized).absoluteValue < 1f &&
            userMarkerState.endColor == userMarkerColor) {
            return@LaunchedEffect
        }
        userMarkerState.setStaticState(MaterialShapes.Arrow, userMarkerColor, normalized)
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
        val sortedMarkers = markerStates.sortedBy { if (it.isActive) 1 else 0 }
        val renderStates = if (userMarkerState.hasValidLocation()) {
            sortedMarkers + userMarkerState
        } else {
            sortedMarkers
        }
        val buffer = shapeBatchWriter.encode(renderStates, predictionTimeMs, forceUpload = forceUpload)
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
        val radius = if (state.isActive) ACTIVE_MARKER_RADIUS else INACTIVE_MARKER_RADIUS
        val dx = point.x - screen.x.toFloat()
        val dy = point.y - screen.y.toFloat()
        dx * dx + dy * dy <= (radius + MARKER_HIT_PADDING) * (radius + MARKER_HIT_PADDING)
    }
}

private data class MarkerKeyframePayload(
    val vertexCount: Int,
    val startVertices: FloatArray,
    val endVertices: FloatArray,
    val startColor: Int,
    val endColor: Int,
    val startStrokeColor: Int,
    val endStrokeColor: Int,
)

private fun MarkerKeyframePayload.deepCopy(): MarkerKeyframePayload =
    MarkerKeyframePayload(
        vertexCount = vertexCount,
        startVertices = startVertices.copyOf(),
        endVertices = endVertices.copyOf(),
        startColor = startColor,
        endColor = endColor,
        startStrokeColor = startStrokeColor,
        endStrokeColor = endStrokeColor,
    )

private enum class KeyframeReason {
    None,
    Morph,
    Selection,
    Full,
}

private class PolygonSampler {
    private val path = Path()
    private val bounds = RectF()
    private val targetBounds = RectF()
    private val scaleMatrix = Matrix()
    private val rotationMatrix = Matrix()
    private val measure = PathMeasure()
    private val coords = FloatArray(2)

    fun sample(
        morph: Morph,
        progress: Float,
        rotation: Float,
        radius: Float,
        markerId: Int,
        phase: String,
        forcedVertexCount: Int? = null,
    ): FloatArray? {
        path.rewind()
        morph.toPath(progress, path)
        path.computeBounds(bounds, true)
        targetBounds.set(-radius, -radius, radius, radius)
        scaleMatrix.reset()
        scaleMatrix.setRectToRect(bounds, targetBounds, Matrix.ScaleToFit.CENTER)
        path.transform(scaleMatrix)
        rotationMatrix.reset()
        rotationMatrix.postRotate(rotation)
        path.transform(rotationMatrix)
        measure.setPath(path, true)
        val totalLength = measure.length
        if (totalLength <= 0f) {
            return null
        }
        val vertexCount = forcedVertexCount ?: maxOf(
            MIN_VERTEX_COUNT,
            (totalLength / SAMPLE_STEP).roundToInt(),
        )
        if (vertexCount < 3) {
            return null
        }
        val result = FloatArray(vertexCount * 2)
        val step = totalLength / vertexCount
        var distance = 0f
        for (i in 0 until vertexCount) {
            if (!measure.getPosTan(distance.coerceAtMost(totalLength), coords, null)) {
                return null
            }
            val idx = i * 2
            result[idx] = coords[0]
            result[idx + 1] = coords[1]
            distance += step
        }
        return result
    }

    companion object {
        private const val SAMPLE_STEP = 6f
        private const val MIN_VERTEX_COUNT = 24
    }
}

private fun FloatArray.resampleClosedPolygon(targetCount: Int): FloatArray? {
    val pointCount = size / 2
    if (pointCount < 3 || targetCount < 3) return null
    val cumulative = FloatArray(pointCount + 1)
    var perimeter = 0f
    for (i in 0 until pointCount) {
        val next = (i + 1) % pointCount
        val x0 = this[i * 2]
        val y0 = this[i * 2 + 1]
        val x1 = this[next * 2]
        val y1 = this[next * 2 + 1]
        val dx = x1 - x0
        val dy = y1 - y0
        perimeter += kotlin.math.sqrt(dx * dx + dy * dy)
        cumulative[i + 1] = perimeter
    }
    if (perimeter <= 0f) return null
    val step = perimeter / targetCount
    val result = FloatArray(targetCount * 2)
    var distance = 0f
    for (i in 0 until targetCount) {
        val target = distance % perimeter
        var edgeIndex = 0
        while (edgeIndex < pointCount && target > cumulative[edgeIndex + 1]) {
            edgeIndex++
        }
        val startDist = cumulative[edgeIndex]
        val endDist = cumulative[(edgeIndex + 1) % (pointCount + 1)]
        val idxA = (edgeIndex % pointCount) * 2
        val idxB = ((edgeIndex + 1) % pointCount) * 2
        val ax = this[idxA]
        val ay = this[idxA + 1]
        val bx = this[idxB]
        val by = this[idxB + 1]
        val segmentLength = if (endDist >= startDist) endDist - startDist else perimeter - startDist + endDist
        val localTarget = if (target >= startDist) target - startDist else perimeter - startDist + target
        val t = if (segmentLength == 0f) 0f else (localTarget / segmentLength).coerceIn(0f, 1f)
        result[i * 2] = ax + (bx - ax) * t
        result[i * 2 + 1] = ay + (by - ay) * t
        distance += step
    }
    return result
}

private data class PolygonBounds(
    val count: Int,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minRadius: Float,
    val maxRadius: Float,
) {
    private val spanX: Float get() = maxX - minX
    private val spanY: Float get() = maxY - minY

    fun isSuspicious(threshold: Float = SUSPICIOUS_RADIUS_THRESHOLD): Boolean {
        if (count <= 0) return true
        return minRadius < threshold || spanX < threshold || spanY < threshold
    }

    fun describe(): String =
        String.format(
            Locale.US,
            "count=%d x=[%.3f, %.3f] y=[%.3f, %.3f] r=[%.3f, %.3f]",
            count,
            minX.toDouble(),
            maxX.toDouble(),
            minY.toDouble(),
            maxY.toDouble(),
            minRadius.toDouble(),
            maxRadius.toDouble(),
        )
}

private fun FloatArray.boundsSnapshot(): PolygonBounds? {
    if (isEmpty()) return null
    val pointCount = size / 2
    if (pointCount <= 0) return null
    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var minRadius = Float.POSITIVE_INFINITY
    var maxRadius = Float.NEGATIVE_INFINITY
    for (i in 0 until pointCount) {
        val x = this[i * 2]
        val y = this[i * 2 + 1]
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
        val radius = kotlin.math.sqrt((x * x + y * y).toDouble()).toFloat()
        if (radius < minRadius) minRadius = radius
        if (radius > maxRadius) maxRadius = radius
    }
    return PolygonBounds(
        count = pointCount,
        minX = minX,
        maxX = maxX,
        minY = minY,
        maxY = maxY,
        minRadius = minRadius,
        maxRadius = maxRadius,
    )
}

private fun FloatArray.describeVertices(limit: Int = 3): String {
    if (isEmpty()) return "[]"
    val pairCount = size / 2
    val maxPairs = min(limit, pairCount)
    val builder = StringBuilder("[")
    for (i in 0 until maxPairs) {
        if (i > 0) builder.append(", ")
        val x = this[i * 2]
        val y = this[i * 2 + 1]
        builder.append(
            String.format(
                Locale.US,
                "(%.2f, %.2f)",
                x.toDouble(),
                y.toDouble(),
            )
        )
    }
    if (pairCount > maxPairs) builder.append(", ...")
    builder.append(']')
    return builder.toString()
}

private fun FloatArray.hasInvalidValues(): Boolean =
    any { it.isNaN() || it.isInfinite() }

private fun FloatArray.alignToReference(reference: FloatArray): FloatArray {
    val count = size / 2
    if (reference.size != size || count < 3) return this
    var bestOffset = 0
    var bestReversed = false
    var bestScore = Double.POSITIVE_INFINITY

    fun score(offset: Int, reversed: Boolean): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val refIndex = i * 2
            val candidatePosition = if (!reversed) {
                (i + offset) % count
            } else {
                val value = count + offset - i
                ((value % count) + count) % count
            }
            val candIndex = candidatePosition * 2
            val dx = reference[refIndex] - this[candIndex]
            val dy = reference[refIndex + 1] - this[candIndex + 1]
            sum += dx * dx + dy * dy
            if (sum >= bestScore) return sum
        }
        return sum
    }

    for (offset in 0 until count) {
        val forwardScore = score(offset, false)
        if (forwardScore < bestScore) {
            bestScore = forwardScore
            bestOffset = offset
            bestReversed = false
        }
        val reverseScore = score(offset, true)
        if (reverseScore < bestScore) {
            bestScore = reverseScore
            bestOffset = offset
            bestReversed = true
        }
    }
    if (bestOffset == 0 && !bestReversed) return this
    val aligned = FloatArray(size)
    for (i in 0 until count) {
        val sourcePosition = if (!bestReversed) {
            (i + bestOffset) % count
        } else {
            val value = count + bestOffset - i
            ((value % count) + count) % count
        }
        val srcIndex = sourcePosition * 2
        val destIndex = i * 2
        aligned[destIndex] = this[srcIndex]
        aligned[destIndex + 1] = this[srcIndex + 1]
    }
    return aligned
}

private fun describeShape(shape: RoundedPolygon): String {
    val index = shapeCatalog.indexOf(shape)
    return if (index >= 0) {
        "shape#$index"
    } else {
        "shape#${shape.hashCode().toUInt().toString(16)}"
    }
}

private fun Int.isUserMarkerId(): Boolean = this == USER_PLACE_ID

private fun Int.toDebugColor(): String =
    "0x" + toUInt().toString(16).padStart(8, '0')

private class NativeShapeBatchWriter {
    private var buffer: ByteBuffer = ByteBuffer.allocateDirect(DEFAULT_CAPACITY).order(ByteOrder.LITTLE_ENDIAN)
    private val sampler = PolygonSampler()
    private var lastMarkerCount: Int = -1

    fun encode(
        markerStates: List<MarkerShapeState>,
        frameTimeMs: Long,
        forceUpload: Boolean = false,
    ): ByteBuffer? {
        markerStates.forEach { it.updateAnimationState(frameTimeMs) }
        val markerCountChanged = markerStates.size != lastMarkerCount
        val needsUpload = forceUpload || markerCountChanged || markerStates.any { it.geometryDirty }
        if (!needsUpload) {
            return null
        }
        ensureCapacity(Int.SIZE_BYTES)
        buffer.clear()
        buffer.putInt(0) // placeholder for count
        var written = 0
        markerStates.forEach { state ->
            val lat = state.place.lat
            val lon = state.place.lon
            if (lat == null || lon == null) {
                return@forEach
            }
            val payload = state.ensureKeyframePayload(frameTimeMs, sampler) ?: return@forEach
            if (payload.vertexCount < 3) return@forEach
            val bytesNeeded = HEADER_BYTES +
                COLOR_BYTES * 4 +
                payload.vertexCount * 2 * java.lang.Float.BYTES * 2
            ensureCapacity(bytesNeeded)
            val animationStart = state.animationStartTimestamp()
            val animationDuration = state.animationDuration()
            val uploadAnimationStart = animationStart
            buffer.putInt(state.id)
            buffer.putDouble(lat)
            buffer.putDouble(lon)
            buffer.putFloat(state.strokeWidth())
            buffer.putInt(payload.vertexCount)
            buffer.putLong(uploadAnimationStart)
            buffer.putLong(animationDuration)
            buffer.putInt(EASING_OVERSHOOT)
            buffer.putInt(if (state.isActive) 1 else 0)
            buffer.putInt(payload.startColor)
            buffer.putInt(payload.endColor)
            buffer.putInt(payload.startStrokeColor)
            buffer.putInt(payload.endStrokeColor)
            payload.startVertices.forEach { buffer.putFloat(it) }
            payload.endVertices.forEach { buffer.putFloat(it) }
            written++
        }
        buffer.putInt(0, written)
        lastMarkerCount = markerStates.size
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
            Int.SIZE_BYTES +
                java.lang.Double.BYTES * 2 +
                java.lang.Float.BYTES +
                Int.SIZE_BYTES +
                java.lang.Long.BYTES * 2 +
                Int.SIZE_BYTES * 2
        private const val COLOR_BYTES = Int.SIZE_BYTES
        private const val EASING_OVERSHOOT = 1
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

    fun triggerMorph(id: Int, force: Boolean = false) {
        if (!animationsEnabled && !force) return
        val state = states[id] ?: return
        val nextShape = randomDifferentShape(state.endPolygon)
        val nextColor = randomDifferentColor(state.endColor)
        val nextRotation = random.nextInt(360).toFloat()
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
    initialPlace: Place,
    initialPolygon: RoundedPolygon,
    initialColor: Int,
    initialRotation: Float,
        ) {
    var place: Place = initialPlace
        private set
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
    private var selectionAnimFrom: Float = INACTIVE_MARKER_RADIUS
    private var selectionAnimTo: Float = INACTIVE_MARKER_RADIUS
    private var selectionActiveFromState: Boolean = false
    private var selectionActiveToState: Boolean = false
    private var cachedKeyframe: MarkerKeyframePayload? = null
    private var lastKeyframe: MarkerKeyframePayload? = null
    var geometryDirty: Boolean = true
        private set
    private var dirtyReason: KeyframeReason = KeyframeReason.Full

    val id: Int get() = place.id
    fun updatePlace(next: Place) {
        if (place.lat != next.lat || place.lon != next.lon) {
            markDirty(KeyframeReason.Full)
        }
        place = next
    }

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

private fun strokeColorFor(activeState: Boolean): Int =
    Color.Black.copy(alpha = if (activeState) 0.85f else 0.65f).toArgb()

    fun prepareForAnimation(
        nextPolygon: RoundedPolygon,
        nextColor: Int,
        nextRotation: Float,
    ) {
        if (isAnimating) {
            completeAnimation()
        }
        endPolygon = nextPolygon
        endColor = nextColor
        endRotation = nextRotation
        animationStartTime = SystemClock.uptimeMillis()
        isAnimating = true
        markDirty(KeyframeReason.Morph)
    }

    fun setActive(active: Boolean): Boolean {
        if (isActive == active) return false
        val now = SystemClock.uptimeMillis()
        val currentRadius = currentRadius(now)
        val previousActive = isActive
        isActive = active
        selectionAnimStart = now
        selectionAnimFrom = currentRadius
        selectionAnimTo = if (active) ACTIVE_MARKER_RADIUS else INACTIVE_MARKER_RADIUS
        selectionActiveFromState = previousActive
        selectionActiveToState = active
        markDirty(KeyframeReason.Selection)
        return true
    }

    fun setStaticState(
        polygon: RoundedPolygon,
        color: Int,
        rotation: Float,
    ) {
        startPolygon = polygon
        endPolygon = polygon
        startColor = color
        endColor = color
        startRotation = rotation
        endRotation = rotation
        isAnimating = false
        selectionAnimStart = 0L
        selectionAnimFrom = if (isActive) ACTIVE_MARKER_RADIUS else INACTIVE_MARKER_RADIUS
        selectionAnimTo = selectionAnimFrom
        markDirty(KeyframeReason.Full)
    }

    private fun markDirty(reason: KeyframeReason) {
        geometryDirty = true
        dirtyReason = reason
        cachedKeyframe = null
    }

    fun currentRadius(now: Long): Float {
        if (selectionAnimStart == 0L) {
            return if (isActive) ACTIVE_MARKER_RADIUS else INACTIVE_MARKER_RADIUS
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

    fun ensureKeyframePayload(frameTimeMs: Long, sampler: PolygonSampler): MarkerKeyframePayload? {
        if (!geometryDirty) {
            return cachedKeyframe
        }
        val morph = Morph(startPolygon, endPolygon)
        val startProgress = when (dirtyReason) {
            KeyframeReason.Selection -> animationProgress(frameTimeMs)
            else -> 0f
        }.coerceIn(0f, 1f)
        val endProgress = if (isAnimating) 1f else startProgress
        val easedStart = MARKER_INTERPOLATOR.getInterpolation(startProgress)
        val easedEnd = MARKER_INTERPOLATOR.getInterpolation(endProgress)
        val startRotationValue = currentRotation(easedStart)
        val endRotationValue = currentRotation(easedEnd)
        val startRadiusValue = startRadius()
        val endRadiusValue = endRadius()
        val previousFrame = lastKeyframe
        val startActiveFlag = when (dirtyReason) {
            KeyframeReason.Selection -> selectionActiveFromState
            else -> isActive
        }
        val reuseLastGeometry = dirtyReason != KeyframeReason.Morph && previousFrame != null
        val startVerticesBase: FloatArray
        var startFillColor = interpolateColor(easedStart)
        var startStrokeColorValue = strokeColorFor(startActiveFlag)
        if (reuseLastGeometry && previousFrame != null) {
            startVerticesBase = previousFrame.endVertices.copyOf()
            startFillColor = previousFrame.endColor
            startStrokeColorValue = previousFrame.endStrokeColor
        } else {
            startVerticesBase = sampler.sample(
                morph = morph,
                progress = easedStart,
                rotation = startRotationValue,
                radius = startRadiusValue,
                markerId = id,
                phase = "start-base",
            ) ?: return null
        }
        val endVerticesInitial = sampler.sample(
            morph = morph,
            progress = easedEnd,
            rotation = endRotationValue,
            radius = endRadiusValue,
            markerId = id,
            phase = "end-base",
        ) ?: return null
        val startCount = startVerticesBase.size / 2
        val endCount = endVerticesInitial.size / 2
        val targetCount = max(startCount, endCount)
        var startSource = "base($startCount)"
        val finalStartVertices = when {
            startCount == targetCount -> startVerticesBase
            reuseLastGeometry -> {
                var fallbackUsed = false
                val normalized = startVerticesBase.resampleClosedPolygon(targetCount) ?: run {
                    fallbackUsed = true
                    sampler.sample(
                        morph = morph,
                        progress = easedStart,
                        rotation = startRotationValue,
                        radius = startRadiusValue,
                        markerId = id,
                        phase = "start-resample-fallback",
                        forcedVertexCount = targetCount,
                    ) ?: return null
                }
                startSource = if (fallbackUsed) {
                    "cached-fallback($startCount->$targetCount)"
                } else {
                    "cached-resample($startCount->$targetCount)"
                }
                normalized
            }
            else -> {
                startSource = "sampled($startCount->$targetCount)"
                sampler.sample(
                    morph = morph,
                    progress = easedStart,
                    rotation = startRotationValue,
                    radius = startRadiusValue,
                    markerId = id,
                    phase = "start-resample",
                    forcedVertexCount = targetCount,
                ) ?: return null
            }
        }
        val rawEndVertices: FloatArray
        val endSource: String
        if (endCount == targetCount) {
            rawEndVertices = endVerticesInitial
            endSource = "base($endCount)"
        } else {
            endSource = "sampled($endCount->$targetCount)"
            rawEndVertices = sampler.sample(
                morph = morph,
                progress = easedEnd,
                rotation = endRotationValue,
                radius = endRadiusValue,
                markerId = id,
                phase = "end-resample",
                forcedVertexCount = targetCount,
            ) ?: return null
        }
        val finalEndVertices = if (rawEndVertices.size == finalStartVertices.size) {
            rawEndVertices.alignToReference(finalStartVertices)
        } else {
            rawEndVertices
        }
        val endFillColor = interpolateColor(easedEnd)
        val startActive = startActiveFlag
        val endActive = when (dirtyReason) {
            KeyframeReason.Selection -> selectionActiveToState
            else -> isActive
        }
        val endStrokeColorValue = strokeColorFor(endActive)
        val payload = MarkerKeyframePayload(
            vertexCount = targetCount,
            startVertices = finalStartVertices,
            endVertices = finalEndVertices,
            startColor = startFillColor,
            endColor = endFillColor,
            startStrokeColor = startStrokeColorValue,
            endStrokeColor = endStrokeColorValue,
        )
        cachedKeyframe = payload
        lastKeyframe = payload.deepCopy()
        geometryDirty = false
        dirtyReason = KeyframeReason.None
        selectionActiveFromState = isActive
        selectionActiveToState = isActive
        return payload
    }

    fun updateAnimationState(now: Long) {
        if (isAnimating) {
            val elapsed = (now - animationStartTime).coerceAtLeast(0L)
            if (elapsed >= MARKER_ANIMATION_DURATION) {
                completeAnimation()
            }
        }
        if (selectionAnimStart != 0L) {
            val elapsed = (now - selectionAnimStart).coerceAtLeast(0L)
            if (elapsed >= MARKER_SELECTION_DURATION) {
                selectionAnimStart = 0L
                selectionAnimFrom = selectionAnimTo
            }
        }
    }

    fun strokeWidth(): Float = if (isActive) ACTIVE_BORDER_WIDTH else DEFAULT_BORDER_WIDTH

    fun animationStartTimestamp(): Long =
        when {
            isAnimating -> animationStartTime
            selectionAnimStart != 0L -> selectionAnimStart
            else -> 0L
        }

    fun animationDuration(): Long =
        when {
            isAnimating -> MARKER_ANIMATION_DURATION
            selectionAnimStart != 0L -> MARKER_SELECTION_DURATION
            else -> 0L
        }

    fun debugDirtyReason(): KeyframeReason = dirtyReason

    private fun startRadius(): Float =
        if (selectionAnimStart != 0L) selectionAnimFrom else if (isActive) ACTIVE_MARKER_RADIUS else INACTIVE_MARKER_RADIUS

    private fun endRadius(): Float =
        if (selectionAnimStart != 0L) selectionAnimTo else if (isActive) ACTIVE_MARKER_RADIUS else INACTIVE_MARKER_RADIUS
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
private const val INACTIVE_MARKER_RADIUS = 28f
private const val ACTIVE_MARKER_RADIUS = 44f
private const val DEFAULT_BORDER_WIDTH = 2f
private const val ACTIVE_BORDER_WIDTH = 3f
private const val USER_PLACE_ID = Int.MIN_VALUE
private const val MARKER_HIT_PADDING = 12f
private const val MARKER_ANIMATION_DURATION = 650L
private const val MARKER_SELECTION_DURATION = 280L
private const val FRAME_PREDICTION_MS = 24L
private val MARKER_INTERPOLATOR = OvershootInterpolator()
private const val SUSPICIOUS_RADIUS_THRESHOLD = DEFAULT_MARKER_RADIUS * 0.35f
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val shapeCatalog = listOf(
    MaterialShapes.Circle,
    MaterialShapes.Square,
    MaterialShapes.Slanted,
    MaterialShapes.Arch,
    MaterialShapes.Oval,
    MaterialShapes.Pill,
    MaterialShapes.Triangle,
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

private fun MarkerShapeState.hasValidLocation(): Boolean =
    place.lat != null && place.lon != null

private fun createUserPlace(location: GeoPoint?): Place =
    Place(
        id = USER_PLACE_ID,
        title = "You",
        description = "",
        address = "",
        lat = location?.latitude,
        lon = location?.longitude,
        url = "",
        date = "",
        images = emptyList(),
    )
