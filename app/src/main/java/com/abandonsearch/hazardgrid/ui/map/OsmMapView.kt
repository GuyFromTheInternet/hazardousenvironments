package com.abandonsearch.hazardgrid.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.views.MapView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory

@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    onMapView: (MapView) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).apply {
                setTileSource(TileSourceFactory.USGS_SAT)
                setMultiThreaded(true)
                isTilesScaledToDpi = true
                onMapView(this)
            }
        }
    )
}
