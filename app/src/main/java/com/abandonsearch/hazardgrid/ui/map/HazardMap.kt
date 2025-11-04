package com.abandonsearch.hazardgrid.ui.map

import android.os.Handler
import android.os.Looper
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.doOnDetach
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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.LinkedHashMap

@Composable
fun HazardMap(
    modifier: Modifier = Modifier,
    uiState: HazardUiState,
    colorScheme: ColorScheme,
    onMarkerSelected: (Place) -> Unit,
    onViewportChanged: (MapViewport) -> Unit,
    mapEvents: Flow<HazardGridViewModel.MapCommand>,
) {
    val context = LocalContext.current
    val mapView = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(OsmGeoPoint(DEFAULT_LAT, DEFAULT_LON))
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
        }
    }
    val markerClusterer = remember { CustomRadiusMarkerClusterer(context, colorScheme) }
    val markerController = remember { MarkerController(context, markerClusterer) }
    val viewportWatcher = remember { ViewportWatcher(onViewportChanged) }

    DisposableEffect(mapView) {
        mapView.onResume()
        mapView.overlays.add(markerClusterer)
        viewportWatcher.attach(mapView)
        onDispose {
            viewportWatcher.detach(mapView)
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
                        markerController.pulseActiveMarker(command.place.id)
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

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            viewportWatcher.attach(view)
            markerController.updateMarkers(
                mapView = view,
                places = uiState.visibleMarkers,
                activeId = uiState.activePlaceId,
                onMarkerSelected = onMarkerSelected
            )
        }
    )
}

private class MarkerController(
    context: android.content.Context,
    private val clusterer: CustomRadiusMarkerClusterer,
) {
    private val markers = LinkedHashMap<Int, Marker>()
    private val markerFactory = HazardMarkerFactory(context)

    fun updateMarkers(
        mapView: MapView,
        places: List<Place>,
        activeId: Int?,
        onMarkerSelected: (Place) -> Unit,
    ) {
        val existingPlaceIds = markers.keys.toMutableSet()
        for (place in places) {
            val marker = markers[place.id] ?: createMarker(mapView, place).also {
                markers[place.id] = it
                clusterer.add(it)
            }
            if (place.lat != null && place.lon != null) {
                marker.position = OsmGeoPoint(place.lat, place.lon)
            }
            marker.icon = markerFactory.getDrawable(place.id == activeId)
            marker.setOnMarkerClickListener { _, _ ->
                onMarkerSelected(place)
                true
            }
            marker.infoWindow = null
            existingPlaceIds.remove(place.id)
        }

        for (placeId in existingPlaceIds) {
            markers.remove(placeId)?.let {
                clusterer.remove(it)
            }
        }

        markers[activeId]?.icon = markerFactory.getDrawable(true)
        pulseActiveMarker(activeId)
        clusterer.invalidate()
    }

    fun pulseActiveMarker(activeId: Int?) {
        val marker = markers[activeId] ?: return
        val cluster = clusterer.getCluster(marker)
        if (cluster != null) {
            cluster.mItems.find { it == marker }?.icon = markerFactory.getDrawable(true)
        }
    }

    private fun createMarker(mapView: MapView, place: Place): Marker {
        val marker = Marker(mapView)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.infoWindow = null
        if (place.lat != null && place.lon != null) {
            marker.position = OsmGeoPoint(place.lat, place.lon)
        }
        return marker
    }
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

private const val DEFAULT_LAT = 55.7558
private const val DEFAULT_LON = 37.6173
private const val DEFAULT_ZOOM = 10.0
private const val FOCUS_ZOOM = 15.0
private const val ANIMATION_DURATION = 800L

private fun normalizeBearing(bearing: Float): Float {
    var value = bearing % 360f
    if (value < 0f) value += 360f
    return value
}
