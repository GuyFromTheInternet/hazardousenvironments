package com.abandonsearch.hazardgrid.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaceImage(
    val mime: String,
    val data: String,
)

@Serializable
data class Place(
    val id: Int,
    val title: String,
    val description: String,
    val address: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val url: String,
    val date: String,
    @SerialName("security")
    val security: Double? = null,
    @SerialName("interior")
    val interior: Double? = null,
    @SerialName("age")
    val age: Double? = null,
    @SerialName("rating")
    val rating: Double? = null,
    @SerialName("floors")
    val floors: Int? = null,
    val images: List<PlaceImage> = emptyList(),
) {
    val hasValidCoordinates: Boolean
        get() = lat != null && lon != null
}
