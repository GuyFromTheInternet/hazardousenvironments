package com.abandonsearch.hazardgrid.ui.map

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.doOnDetach
import androidx.compose.ui.viewinterop.AndroidView
import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.domain.GeoBounds
import com.abandonsearch.hazardgrid.domain.GeoPoint
import com.abandonsearch.hazardgrid.domain.MapViewport
import com.abandonsearch.hazardgrid.ui.HazardGridViewModel
import com.abandonsearch.hazardgrid.ui.state.HazardUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.LinkedHashMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import kotlin.math.atan2

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HazardMap(
    modifier: Modifier = Modifier,
    uiState: HazardUiState,
    colorScheme: ColorScheme,
    onMarkerSelected: (Place?) -> Unit,
    onViewportChanged: (MapViewport) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    mapEvents: Flow<HazardGridViewModel.MapCommand>,
) {
    val context = LocalContext.current
    val mapView = remember(context) {
        Configuration.getInstance().userAgentValue = context.packageName
        Configuration.getInstance().tileDownloadThreads = 12
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(OsmGeoPoint(DEFAULT_LAT, DEFAULT_LON))
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
        }
    }
    val viewportWatcher = remember { ViewportWatcher(onViewportChanged) }
    val locationOverlay = remember { MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) }
    val markerOverlay = remember { CustomMarkerOverlay(mapView, onMarkerSelected, colorScheme) }

    DisposableEffect(mapView) {
        mapView.onResume()
        mapView.overlays.add(locationOverlay)
        mapView.overlays.add(markerOverlay)
        locationOverlay.enableMyLocation()
        viewportWatcher.attach(mapView)
        onDispose {
            viewportWatcher.detach(mapView)
            locationOverlay.disableMyLocation()
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(mapEvents) {
        mapEvents.collectLatest { command ->
            when (command) {
                is HazardGridViewModel.MapCommand.FocusOnPlace -> {
                    val lat = command.place.lat
                    val lon = command.place.lon
                    if (lat != null && lon != null) {
                        val geoPoint = OsmGeoPoint(lat, lon)
                        mapView.controller.animateTo(geoPoint, FOCUS_ZOOM, ANIMATION_DURATION)
                    }
                }
                is HazardGridViewModel.MapCommand.FocusOnLocation -> {
                    val location = command.location
                    val geoPoint = OsmGeoPoint(location.latitude, location.longitude)
                    if (command.animate) {
                        val zoom = command.zoom ?: mapView.zoomLevelDouble
                        mapView.controller.animateTo(geoPoint, zoom, ANIMATION_DURATION)
                    } else {
                        command.zoom?.let { mapView.controller.setZoom(it) }
                        mapView.controller.setCenter(geoPoint)
                    }
                }
                is HazardGridViewModel.MapCommand.SetOrientation -> {
                    mapView.mapOrientation = normalizeBearing(command.bearing)
                }
                HazardGridViewModel.MapCommand.ResetOrientation -> {
                    mapView.mapOrientation = 0f
                }
            }
        }
    }

    LaunchedEffect(uiState.allPlaces) {
        markerOverlay.setPlaces(uiState.allPlaces)
        mapView.invalidate()
    }

    var angle by remember { mutableStateOf(0f) }
    var previousAngle by remember { mutableStateOf(0f) }
    AndroidView(
        factory = { mapView },
        modifier = modifier.pointerInteropFilter {
            when (it.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onDragStart()
                    previousAngle = 0f
                    angle = 0f
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    previousAngle = 0f
                    angle = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (it.pointerCount > 1) {
                        val touch1 = MotionEvent.PointerCoords().also { c -> it.getPointerCoords(0, c) }
                        val touch2 = MotionEvent.PointerCoords().also { c -> it.getPointerCoords(1, c) }
                        angle = atan2(touch2.y - touch1.y, touch2.x - touch1.x) * 180 / Math.PI.toFloat()
                        if (previousAngle != 0f) {
                            val rotation = angle - previousAngle
                            mapView.mapOrientation = mapView.mapOrientation + rotation
                        }
                        previousAngle = angle
                    }
                }
                MotionEvent.ACTION_UP -> {
                    onDragEnd()
                    previousAngle = 0f
                    angle = 0f
                }
            }
            false
        }
    )
}

private class ViewportWatcher(
    private val onViewportChanged: (MapViewport) -> Unit,
) : MapListener {
    private val handler = Handler(Looper.getMainLooper())
    private val notifyRunnable = Runnable { dispatchViewport() }
    private var mapView: MapView? = null

    fun attach(mapView: MapView) {
        if (this.mapView === mapView) {
            scheduleDispatch()
            return
        }
        detach(this.mapView)
        this.mapView = mapView
        mapView.addMapListener(this)
        mapView.doOnDetach { detach(it as MapView) }
        scheduleDispatch()
    }

    fun detach(mapView: MapView?) {
        mapView?.removeMapListener(this)
        handler.removeCallbacks(notifyRunnable)
        if (this.mapView === mapView) {
            this.mapView = null
        }
    }

    override fun onScroll(event: ScrollEvent?): Boolean {
        scheduleDispatch()
        return false
    }

    override fun onZoom(event: ZoomEvent?): Boolean {
        scheduleDispatch()
        return false
    }

    private fun scheduleDispatch() {
        handler.removeCallbacks(notifyRunnable)
        handler.postDelayed(notifyRunnable, VIEWPORT_DISPATCH_DELAY_MS)
    }

    private fun dispatchViewport() {
        val view = mapView ?: return
        onViewportChanged(view.toViewport())
    }

    companion object {
        private const val VIEWPORT_DISPATCH_DELAY_MS = 16L
    }
}

private fun MapView.toViewport(): MapViewport {
    val centerPoint = mapCenter as? OsmGeoPoint ?: OsmGeoPoint(DEFAULT_LAT, DEFAULT_LON)
    val bounding = boundingBox
    val bounds = GeoBounds(
        north = bounding.latNorth,
        south = bounding.latSouth,
        east = bounding.lonEast,
        west = bounding.lonWest,
    )
    return MapViewport(
        center = GeoPoint(centerPoint.latitude, centerPoint.longitude),
        bounds = bounds,
    )
}

private fun normalizeBearing(bearing: Float): Float {
    var value = bearing % 360f
    if (value < 0f) value += 360f
    return value
}

private const val DEFAULT_LAT = 55.7558
private const val DEFAULT_LON = 37.6173
private const val DEFAULT_ZOOM = 10.0
private const val FOCUS_ZOOM = 15.0
private const val ANIMATION_DURATION = 800L
