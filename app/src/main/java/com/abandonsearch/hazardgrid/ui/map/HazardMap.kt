package com.abandonsearch.hazardgrid.ui.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.domain.GeoBounds
import com.abandonsearch.hazardgrid.domain.GeoPoint
import com.abandonsearch.hazardgrid.domain.MapViewport
import com.abandonsearch.hazardgrid.ui.HazardGridViewModel
import com.abandonsearch.hazardgrid.ui.state.HazardUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import world.mappable.mapkit.Animation
import world.mappable.mapkit.geometry.Point
import world.mappable.mapkit.map.CameraListener
import world.mappable.mapkit.map.CameraPosition
import world.mappable.mapkit.map.CameraUpdateReason
import world.mappable.mapkit.map.Cluster
import world.mappable.mapkit.map.ClusterListener
import world.mappable.mapkit.map.ClusterTapListener
import world.mappable.mapkit.map.ClusterizedPlacemarkCollection
import world.mappable.mapkit.map.IconStyle
import world.mappable.mapkit.map.MapObject
import world.mappable.mapkit.map.MapObjectTapListener
import world.mappable.mapkit.map.PlacemarkMapObject
import world.mappable.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider

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
    val mapView = remember(context) {
        MapView(context).apply {
            map.move(
                CameraPosition(Point(DEFAULT_LAT, DEFAULT_LON), DEFAULT_ZOOM, 0f, 0f),
                Animation(Animation.Type.SMOOTH, 0f),
                null
            )
        }
    }
    val markerController = remember { MarkerController(context, onMarkerSelected) }
    val viewportWatcher = remember { ViewportWatcher(onViewportChanged) }

    DisposableEffect(mapView) {
        mapView.onStart()
        viewportWatcher.attach(mapView)
        markerController.attach(mapView)
        onDispose {
            markerController.detach(mapView)
            viewportWatcher.detach(mapView)
            mapView.onStop()
        }
    }

    LaunchedEffect(mapEvents) {
        mapEvents.collectLatest { command ->
            when (command) {
                is HazardGridViewModel.MapCommand.FocusOnPlace -> {
                    val lat = command.place.lat
                    val lon = command.place.lon
                    if (lat != null && lon != null) {
                        val point = Point(lat, lon)
                        mapView.map.move(
                            CameraPosition(point, FOCUS_ZOOM, 0f, 0f),
                            Animation(Animation.Type.SMOOTH, ANIMATION_DURATION),
                            null
                        )
                        markerController.pulseActiveMarker(command.place.id)
                    }
                }
                is HazardGridViewModel.MapCommand.FocusOnLocation -> {
                    val location = command.location
                    val point = Point(location.latitude, location.longitude)
                    val zoom = command.zoom?.toFloat() ?: mapView.map.cameraPosition.zoom
                    val cameraPosition = CameraPosition(point, zoom, mapView.map.cameraPosition.azimuth, mapView.map.cameraPosition.tilt)
                    if (command.animate) {
                        mapView.map.move(
                            cameraPosition,
                            Animation(Animation.Type.SMOOTH, ANIMATION_DURATION),
                            null
                        )
                    } else {
                        mapView.map.move(cameraPosition)
                    }
                }
                is HazardGridViewModel.MapCommand.SetOrientation -> {
                    val current = mapView.map.cameraPosition
                    mapView.map.move(
                        CameraPosition(current.target, current.zoom, command.bearing, current.tilt)
                    )
                }
                HazardGridViewModel.MapCommand.ResetOrientation -> {
                    val current = mapView.map.cameraPosition
                    mapView.map.move(
                        CameraPosition(current.target, current.zoom, 0f, current.tilt)
                    )
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
                places = uiState.visibleMarkers,
                activeId = uiState.activePlaceId,
            )
            val activePlace = uiState.activePlace
            if (activePlace?.lat != null && activePlace.lon != null) {
                val point = Point(activePlace.lat, activePlace.lon)
                val screenPoint = view.mapWindow.worldToScreen(point)
                if (screenPoint != null) {
                    onActiveMarkerPosition(Offset(screenPoint.x, screenPoint.y))
                } else {
                    onActiveMarkerPosition(null)
                }
            } else {
                onActiveMarkerPosition(null)
            }
        }
    )
}

private class MarkerController(
    context: Context,
    private val onMarkerSelected: (Place) -> Unit,
) : ClusterListener, ClusterTapListener, MapObjectTapListener {
    private val markerFactory = HazardMarkerFactory(context)
    private var collection: ClusterizedPlacemarkCollection? = null
    private val placesById = mutableMapOf<Int, Place>()

    fun attach(mapView: MapView) {
        val mapObjects = mapView.map.mapObjects
        collection = mapObjects.addClusterizedPlacemarkCollection(this)
    }

    fun detach(mapView: MapView) {
        collection?.let {
            mapView.map.mapObjects.remove(it)
        }
        collection = null
    }

    fun updateMarkers(
        places: List<Place>,
        activeId: Int?,
    ) {
        val coll = collection ?: return
        placesById.clear()
        places.forEach { placesById[it.id] = it }

        val points = places.mapNotNull { place ->
            place.lat?.let { lat ->
                place.lon?.let { lon ->
                    Point(lat, lon)
                }
            }
        }
        coll.clear()
        val placemarks = coll.addPlacemarks(points, markerFactory.getDrawable(false), IconStyle())
        for ((i, placemark) in placemarks.withIndex()) {
            placemark.userData = places[i].id
            placemark.addTapListener(this)
        }
        coll.clusterPlacemarks(60.0, 15)
    }

    fun pulseActiveMarker(activeId: Int?) {
        // Intentionally left for future pulse animation hook
    }

    override fun onClusterAdded(cluster: Cluster) {
        cluster.appearance.setIcon(markerFactory.getClusterDrawable(cluster.size))
        cluster.addClusterTapListener(this)
    }

    override fun onClusterTap(cluster: Cluster): Boolean {
        // TODO: Implement cluster tap logic (e.g., zoom in)
        return true
    }

    override fun onMapObjectTap(mapObject: MapObject, point: Point): Boolean {
        val placeId = mapObject.userData as? Int ?: return false
        val place = placesById[placeId] ?: return false
        onMarkerSelected(place)
        return true
    }
}

private class ViewportWatcher(
    private val onViewportChanged: (MapViewport) -> Unit,
) : CameraListener {
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
        mapView.map.addCameraListener(this)
        scheduleDispatch()
    }

    fun detach(mapView: MapView?) {
        mapView?.map?.removeCameraListener(this)
        handler.removeCallbacks(notifyRunnable)
        if (this.mapView === mapView) {
            this.mapView = null
        }
    }

    override fun onCameraPositionChanged(
        p0: world.mappable.mapkit.map.Map,
        p1: CameraPosition,
        p2: CameraUpdateReason,
        p3: Boolean
    ) {
        scheduleDispatch()
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
    val visibleRegion = map.visibleRegion
    val bounds = GeoBounds(
        north = visibleRegion.topLeft.latitude,
        south = visibleRegion.bottomRight.latitude,
        east = visibleRegion.bottomRight.longitude,
        west = visibleRegion.topLeft.longitude,
    )
    return MapViewport(
        center = GeoPoint(map.cameraPosition.target.latitude, map.cameraPosition.target.longitude),
        bounds = bounds,
    )
}

private const val DEFAULT_LAT = 55.7558
private const val DEFAULT_LON = 37.6173
private const val DEFAULT_ZOOM = 10.0f
private const val FOCUS_ZOOM = 15.0f
private const val ANIMATION_DURATION = 800f
