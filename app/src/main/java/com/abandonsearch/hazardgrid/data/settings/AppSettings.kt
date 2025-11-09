package com.abandonsearch.hazardgrid.data.settings

data class AppSettings(
    val defaultMapApp: MapApp = MapApp.YANDEX,
    val mergeShapesEnabled: Boolean = true,
)

enum class MapApp(
    val label: String,
    val description: String
) {
    YANDEX(
        label = "Yandex Maps",
        description = "Detailed coverage for Eurasia and CIS regions."
    ),
    GOOGLE(
        label = "Google Maps",
        description = "Default option on most Android devices."
    ),
    OPEN_STREET_MAP(
        label = "OpenStreetMap",
        description = "Community maintained map tiles."
    ),
    APPLE(
        label = "Apple Maps",
        description = "Use when sharing with iOS users."
    );

    companion object {
        fun fromName(value: String?): MapApp =
            values().firstOrNull { it.name == value } ?: YANDEX
    }
}
