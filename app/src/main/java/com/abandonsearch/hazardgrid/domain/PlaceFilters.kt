package com.abandonsearch.hazardgrid.domain

import com.abandonsearch.hazardgrid.data.Place
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6371000.0

object PlaceFilters {
    fun applyFilters(places: List<Place>, filterState: FilterState): List<Place> {
        if (places.isEmpty()) return emptyList()
        val normalizedQuery = normalizeQuery(filterState.query)
        return places.filter { place ->
            place.hasValidCoordinates &&
                matchesQuery(place, normalizedQuery) &&
                matchesFloors(place, filterState.floors) &&
                matchesScale(place.security, filterState.security) &&
                matchesScale(place.interior, filterState.interior) &&
                matchesAge(place.age, filterState.age) &&
                matchesRating(place.rating, filterState.rating)
        }
    }

    fun sortPlaces(
        places: List<Place>,
        filterState: FilterState,
        viewport: MapViewport?,
    ): List<Place> {
        return when (filterState.sort) {
            SortOption.RELEVANCE -> places
            SortOption.DISTANCE -> {
                val center = viewport?.center
                if (center == null) places else places.sortedBy { place ->
                    place.toGeoPoint()?.let { distanceMeters(center, it) } ?: Double.MAX_VALUE
                }
            }
            SortOption.RATING -> places.sortedWith(compareByDescending<Place> { it.rating ?: Double.NEGATIVE_INFINITY }.thenBy { it.id })
            SortOption.SECURITY -> places.sortedWith(compareByDescending<Place> { it.security ?: Double.NEGATIVE_INFINITY }.thenBy { it.id })
        }
    }

    fun computeVisibleMarkers(
        filtered: List<Place>,
        viewport: MapViewport?,
        activeId: Int?,
        maxMarkers: Int,
    ): List<Place> {
        if (viewport == null) return filtered.take(maxMarkers)
        if (filtered.isEmpty()) return emptyList()

        val inside = mutableListOf<Place>()
        val outside = mutableListOf<Pair<Double, Place>>()
        val center = viewport.center

        for (place in filtered) {
            val point = place.toGeoPoint() ?: continue
            if (viewport.bounds.contains(point)) {
                inside += place
            } else if (inside.size < maxMarkers) {
                val distance = distanceMeters(center, point)
                outside += distance to place
            }
        }

        var selected = inside
        if (selected.size > maxMarkers) {
            val step = (selected.size.toDouble() / maxMarkers).toInt().coerceAtLeast(1)
            selected = selected.filterIndexed { index, _ -> index % step == 0 }.take(maxMarkers).toMutableList()
        } else if (selected.size < maxMarkers && outside.isNotEmpty()) {
            outside.sortBy { it.first }
            for ((_, place) in outside) {
                if (selected.size >= maxMarkers) break
                selected.add(place)
            }
        }

        if (activeId != null && selected.none { it.id == activeId }) {
            filtered.firstOrNull { it.id == activeId }?.let { selected.add(it) }
        }

        return selected.distinctBy { it.id }
    }

    private fun matchesQuery(place: Place, normalizedQuery: String): Boolean {
        if (normalizedQuery.isEmpty()) return true
        val haystacks = listOf(place.title, place.description, place.address, place.url)
        return haystacks.any { field ->
            field.isNotBlank() && field.lowercase().contains(normalizedQuery)
        }
    }

    private fun matchesScale(value: Double?, filter: ScaleFilter): Boolean = when (filter) {
        ScaleFilter.ANY -> true
        ScaleFilter.UNKNOWN -> value == null
        ScaleFilter.LOW -> value != null && value <= 3.0
        ScaleFilter.MEDIUM -> value != null && value > 3.0 && value <= 6.0
        ScaleFilter.HIGH -> value != null && value > 6.0
    }

    private fun matchesAge(value: Double?, filter: AgeFilter): Boolean = when (filter) {
        AgeFilter.ANY -> true
        AgeFilter.UNKNOWN -> value == null
        AgeFilter.NEW -> value != null && value <= 2.0
        AgeFilter.RECENT -> value != null && value > 2.0 && value <= 4.0
        AgeFilter.CLASSIC -> value != null && value > 4.0 && value <= 7.0
        AgeFilter.HERITAGE -> value != null && value > 7.0
    }

    private fun matchesRating(value: Double?, filter: RatingFilter): Boolean = when (filter) {
        RatingFilter.ANY -> true
        RatingFilter.UNKNOWN -> value == null
        else -> {
            val min = filter.minValue
            value != null && min != null && value >= min
        }
    }

    private fun matchesFloors(place: Place, filter: FloorsFilter): Boolean {
        when (filter) {
            FloorsFilter.ANY -> return true
            FloorsFilter.UNKNOWN -> return place.floors == null
            FloorsFilter.LOW -> return place.floors != null && place.floors in 1..5
            FloorsFilter.MID -> return place.floors != null && place.floors in 6..7
            FloorsFilter.HIGH -> return place.floors != null && place.floors in 8..12
            FloorsFilter.TOWER -> return place.floors != null && place.floors >= 13
        }
    }

    private fun normalizeQuery(query: String): String = query.trim().lowercase()

    private fun Place.toGeoPoint(): GeoPoint? {
        val latValue = lat
        val lonValue = lon
        if (latValue == null || lonValue == null) return null
        if (latValue.isNaN() || lonValue.isNaN()) return null
        return GeoPoint(latValue, lonValue)
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(wrapDegrees(b.longitude - a.longitude))
        val sinHalfLat = sin(dLat / 2.0)
        val sinHalfLon = sin(dLon / 2.0)
        val h = sinHalfLat * sinHalfLat + cos(lat1) * cos(lat2) * sinHalfLon * sinHalfLon
        val c = 2.0 * atan2(sqrt(h), sqrt(1 - h))
        return EARTH_RADIUS_METERS * c
    }

    private fun wrapDegrees(value: Double): Double {
        var newValue = value
        while (newValue < -180.0) newValue += 360.0
        while (newValue > 180.0) newValue -= 360.0
        return newValue
    }
}
