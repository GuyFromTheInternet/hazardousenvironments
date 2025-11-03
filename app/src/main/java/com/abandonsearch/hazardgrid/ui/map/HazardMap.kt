package com.abandonsearch.hazardgrid.ui.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.domain.GeoBounds
import com.abandonsearch.hazardgrid.domain.GeoPoint
import com.abandonsearch.hazardgrid.domain.MapViewport
import com.abandonsearch.hazardgrid.ui.HazardGridViewModel
import com.abandonsearch.hazardgrid.ui.state.HazardUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker

@Composable
fun HazardMap(
    modifier: Modifier = Modifier,
    uiState: HazardUiState,
    onMarkerSelected: (Place) -> Unit,
    onViewportChanged: (MapViewport) -> Unit,
    mapEvents: Flow<HazardGridViewModel.MapCommand>,
    onActiveMarkerPosition: (Offset?) -> Unit = {},
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val markerController = remember { MarkerController(context, mapView, onMarkerSelected) }
    val viewportWatcher = remember { ViewportWatcher(mapView, onViewportChanged) }

    DisposableEffect(mapView) {
        viewportWatcher.attach()
        onDispose {
            viewportWatcher.detach()
        }
    }

    LaunchedEffect(mapEvents) {
        mapEvents.collectLatest { command ->
            when (command) {
                is HazardGridViewModel.MapCommand.FocusOnPlace -> {
                    val lat = command.place.lat
                    val lon = command.place.lon
                    if (lat != null && lon != null) {
                        val point = org.osmdroid.util.GeoPoint(lat, lon)
                        mapView.controller.animateTo(point, FOCUS_ZOOM, ANIMATION_DURATION)
                        markerController.pulseActiveMarker(command.place.id)
                    }
                }
                is HazardGridViewModel.MapCommand.FocusOnLocation -> {
                    val location = command.location
                    val point = org.osmdroid.util.GeoPoint(location.latitude, location.longitude)
                    val zoom = command.zoom ?: mapView.zoomLevelDouble
                    if (command.animate) {
                        mapView.controller.animateTo(point, zoom, ANIMATION_DURATION)
                    } else {
                        mapView.controller.setZoom(zoom)
                        mapView.controller.setCenter(point)
                    }
                }
                is HazardGridViewModel.MapCommand.SetOrientation -> {
                    mapView.mapOrientation = command.bearing
                }
                HazardGridViewModel.MapCommand.ResetOrientation -> {
                    mapView.mapOrientation = 0f
                }
            }
        }
    }

    OsmMapView(
        modifier = modifier,
        onMapView = {
            it.setMultiTouchControls(true)
            it.controller.setZoom(DEFAULT_ZOOM)
            it.controller.setCenter(org.osmdroid.util.GeoPoint(DEFAULT_LAT, DEFAULT_LON))
        }
    )

    LaunchedEffect(uiState.visibleMarkers, uiState.activePlaceId) {
        markerController.updateMarkers(
            places = uiState.visibleMarkers,
            activeId = uiState.activePlaceId,
        )
    }

    val activePlace = uiState.activePlace
    if (activePlace?.lat != null && activePlace.lon != null) {
        val point = org.osmdroid.util.GeoPoint(activePlace.lat, activePlace.lon)
        val screenPoint = mapView.projection.toPixels(point, null)
        if (screenPoint != null) {
            onActiveMarkerPosition(Offset(screenPoint.x.toFloat(), screenPoint.y.toFloat()))
        } else {
            onActiveMarkerPosition(null)
        }
    } else {
        onActiveMarkerPosition(null)
    }
}

private class MarkerController(
    private val context: Context,
    private val mapView: MapView,
    private val onMarkerSelected: (Place) -> Unit,
) {
    private val markerFactory = HazardMarkerFactory(context)
    private val markerOverlay = FolderOverlay()
    private val placesById = mutableMapOf<Int, Place>()

    init {
        mapView.overlays.add(markerOverlay)
    }

    fun updateMarkers(
        places: List<Place>,
        activeId: Int?,
    ) {
        placesById.clear()
        places.forEach { placesById[it.id] = it }

        markerOverlay.items.clear()
        places.forEach { place ->
            val lat = place.lat
            val lon = place.lon
            if (lat != null && lon != null) {
                val marker = Marker(mapView)
                marker.position = org.osmdroid.util.GeoPoint(lat, lon)
                marker.icon = markerFactory.getDrawable(place.id == activeId)
                marker.setOnMarkerClickListener { _, _ ->
                    onMarkerSelected(place)
                    true
                }
                markerOverlay.add(marker)
            }
        }
        mapView.invalidate()
    }

    fun pulseActiveMarker(activeId: Int?) {
        // Intentionally left for future pulse animation hook
    }
}

private class ViewportWatcher(
    private val mapView: MapView,
    private val onViewportChanged: (MapViewport) -> Unit,
) : MapListener {
    private val handler = Handler(Looper.getMainLooper())
    private val notifyRunnable = Runnable { dispatchViewport() }

    fun attach() {
        mapView.addMapListener(this)
        scheduleDispatch()
    }

    fun detach() {
        mapView.removeMapListener(this)
        handler.removeCallbacks(notifyRunnable)
    }

    override fun onScroll(event: ScrollEvent?): Boolean {
        scheduleDispatch()
        return true
    }

    override fun onZoom(event: ZoomEvent?): Boolean {
        scheduleDispatch()
        return true
    }

    private fun scheduleDispatch() {
        handler.removeCallbacks(notifyRunnable)
        handler.postDelayed(notifyRunnable, VIEWPORT_DISPATCH_DELAY_MS)
    }

    private fun dispatchViewport() {
        onViewportChanged(mapView.toViewport())
    }

    companion object {
        private const val VIEWPORT_DISPATCH_DELAY_MS = 16L
    }
}

private fun MapView.toViewport(): MapViewport {
    val boundingBox = boundingBox
    val bounds = GeoBounds(
        north = boundingBox.latNorth,
        south = boundingBox.latSouth,
        east = boundingBox.lonEast,
        west = boundingBox.lonWest,
    )
    return MapViewport(
        center = GeoPoint(mapCenter.latitude, mapCenter.longitude),
        bounds = bounds,
    )
}

private const val DEFAULT_LAT = 55.7558
private const val DEFAULT_LON = 37.6173
private const val DEFAULT_ZOOM = 10.0
private const val FOCUS_ZOOM = 15.0
private const val ANIMATION_DURATION = 800L
