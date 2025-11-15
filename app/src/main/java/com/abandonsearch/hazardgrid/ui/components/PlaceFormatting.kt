package com.abandonsearch.hazardgrid.ui.components

import android.net.Uri
import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.data.settings.MapApp

private const val NO_DATA = "N/A"

fun formatFloors(place: Place): String {
    val floors = place.floors
    return when {
        floors == null -> NO_DATA
        floors <= 0 -> NO_DATA
        else -> floors.toString()
    }
}

fun formatScale(value: Double?): String = when {
    value == null -> NO_DATA
    value % 1.0 == 0.0 -> "${value.toInt()}/10"
    else -> "${"%.1f".format(value)}/10"
}

fun formatAge(value: Double?): String = formatScale(value)

fun formatRating(value: Double?): String = formatScale(value)

fun buildMapsUrl(place: Place, mapApp: MapApp): String? {
    val lat = place.lat
    val lon = place.lon
    return when {
        lat != null && lon != null -> {
            when (mapApp) {
                MapApp.YANDEX -> "https://yandex.com/maps/?pt=$lon,$lat"
                MapApp.GOOGLE -> "https://www.google.com/maps/search/?api=1&query=$lat,$lon"
            }
        }
        place.address.isNotBlank() -> {
            val query = Uri.encode(place.address)
            when (mapApp) {
                MapApp.YANDEX -> "https://yandex.com/maps/?text=$query"
                MapApp.GOOGLE -> "https://www.google.com/maps/search/?api=1&query=$query"
            }
        }
        else -> null
    }
}
