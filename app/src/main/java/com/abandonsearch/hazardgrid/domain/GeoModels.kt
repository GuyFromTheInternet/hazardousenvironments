package com.abandonsearch.hazardgrid.domain

data class GeoPoint(val latitude: Double, val longitude: Double)

data class GeoBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
) {
    fun contains(point: GeoPoint): Boolean {
        val lat = point.latitude
        val lon = point.longitude
        if (lat.isNaN() || lon.isNaN()) return false
        val withinLat = lat in south..north
        val withinLon = if (east >= west) {
            lon in west..east
        } else {
            lon >= west || lon <= east
        }
        return withinLat && withinLon
    }
}

data class MapViewport(
    val center: GeoPoint,
    val bounds: GeoBounds,
)
