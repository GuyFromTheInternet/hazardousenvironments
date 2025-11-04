package com.abandonsearch.hazardgrid.ui.components

import android.net.Uri
import com.abandonsearch.hazardgrid.data.Place

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

fun buildMapsUrl(place: Place): String? {
    val lat = place.lat
    val lon = place.lon
    return when {
        lat != null && lon != null -> {
            val label = Uri.encode(place.title.ifBlank { "Location" })
            "https://maps.google.com/?q=$lat,$lon($label)"
        }
        place.address.isNotBlank() -> {
            "https://maps.google.com/?q=${Uri.encode(place.address)}"
        }
        else -> null
    }
}
