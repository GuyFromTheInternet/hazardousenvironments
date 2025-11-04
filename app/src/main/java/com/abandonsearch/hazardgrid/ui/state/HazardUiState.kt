package com.abandonsearch.hazardgrid.ui.state

import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.domain.FilterState
import com.abandonsearch.hazardgrid.domain.MapViewport
import com.abandonsearch.hazardgrid.domain.hasActiveFilters

data class HazardUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val allPlaces: List<Place> = emptyList(),
    val filteredPlaces: List<Place> = emptyList(),
    val searchResults: List<Place> = emptyList(),
    val visibleMarkers: List<Place> = emptyList(),
    val filterState: FilterState = FilterState(),
    val viewport: MapViewport? = null,
    val activePlaceId: Int? = null,
    val activePlace: Place? = null,
    val totalValid: Int = 0,
) {
    val isReady: Boolean get() = !isLoading && errorMessage == null
    val hasFilters: Boolean get() = filterState.hasActiveFilters()
}
