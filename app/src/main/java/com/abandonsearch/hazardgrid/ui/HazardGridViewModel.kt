package com.abandonsearch.hazardgrid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abandonsearch.hazardgrid.data.Place
import com.abandonsearch.hazardgrid.data.PlacesRepository
import com.abandonsearch.hazardgrid.domain.FilterState
import com.abandonsearch.hazardgrid.domain.FloorsFilter
import com.abandonsearch.hazardgrid.domain.GeoPoint
import com.abandonsearch.hazardgrid.domain.MapViewport
import com.abandonsearch.hazardgrid.domain.PlaceFilters
import com.abandonsearch.hazardgrid.domain.RatingFilter
import com.abandonsearch.hazardgrid.domain.ScaleFilter
import com.abandonsearch.hazardgrid.domain.SortOption
import com.abandonsearch.hazardgrid.domain.AgeFilter
import com.abandonsearch.hazardgrid.ui.state.HazardUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class HazardGridViewModel(
    private val repository: PlacesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HazardUiState())
    val uiState: StateFlow<HazardUiState> = _uiState.asStateFlow()

    private val _mapEvents = MutableSharedFlow<MapCommand>(extraBufferCapacity = 4)
    val mapEvents: SharedFlow<MapCommand> = _mapEvents.asSharedFlow()

    init {
        loadPlaces()

        viewModelScope.launch {
            uiState.debounce(50L).collect {
                recompute()
            }
        }
    }

    fun loadPlaces() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val places = repository.loadPlaces().filter { it.hasValidCoordinates }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        allPlaces = places,
                        totalValid = places.size,
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = throwable.message ?: "Failed to load data"
                    )
                }
            }
        }
    }

    fun updateViewport(viewport: MapViewport?) {
        _uiState.update { it.copy(viewport = viewport) }
    }

    fun updateQuery(query: String) {
        val sanitized = query.take(MAX_QUERY_LENGTH)
        val newFilter = _uiState.value.filterState.copy(query = sanitized)
        _uiState.update { it.copy(filterState = newFilter) }
    }

    fun updateFloors(filter: FloorsFilter) {
        val newFilter = _uiState.value.filterState.copy(floors = filter)
        _uiState.update { it.copy(filterState = newFilter) }
    }

    fun updateSecurity(filter: ScaleFilter) {
        _uiState.update { it.copy(filterState = it.filterState.copy(security = filter)) }
    }

    fun updateInterior(filter: ScaleFilter) {
        _uiState.update { it.copy(filterState = it.filterState.copy(interior = filter)) }
    }

    fun updateAge(filter: AgeFilter) {
        _uiState.update { it.copy(filterState = it.filterState.copy(age = filter)) }
    }

    fun updateRating(filter: RatingFilter) {
        _uiState.update { it.copy(filterState = it.filterState.copy(rating = filter)) }
    }

    fun updateSort(sort: SortOption) {
        _uiState.update { it.copy(filterState = it.filterState.copy(sort = sort)) }
    }

    fun clearFilters() {
        _uiState.update { current ->
            current.copy(
                filterState = FilterState(query = current.filterState.query)
            )
        }
    }

    fun setActivePlace(placeId: Int?, centerOnMap: Boolean) {
        val place = placeId?.let { id -> findPlaceById(id) }
        _uiState.update { it.copy(activePlaceId = place?.id, activePlace = place) }
        if (centerOnMap && place != null) {
            _mapEvents.tryEmit(MapCommand.FocusOnPlace(place))
        }
    }

    fun sendMapCommand(command: MapCommand) {
        _mapEvents.tryEmit(command)
    }

    fun focusNext(offset: Int) {
        val current = _uiState.value
        val results = current.searchResults
        if (results.isEmpty()) return
        val currentIndex = current.activePlaceId?.let { id -> results.indexOfFirst { it.id == id } } ?: -1
        val targetIndex = (currentIndex + offset).let { idx ->
            when {
                idx < 0 -> results.lastIndex
                idx > results.lastIndex -> 0
                else -> idx
            }
        }
        val target = results.getOrNull(targetIndex) ?: return
        setActivePlace(target.id, centerOnMap = true)
    }

    private fun recompute() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = _uiState.value
            val filtered = PlaceFilters.applyFilters(snapshot.allPlaces, snapshot.filterState)
            val sorted = PlaceFilters.sortPlaces(filtered, snapshot.filterState, snapshot.viewport)
            val searchResults = sorted.take(MAX_RESULTS)
            val activePlaceId = snapshot.activePlaceId?.takeIf { id ->
                sorted.any { it.id == id } || snapshot.allPlaces.any { it.id == id }
            }
            val activePlace = activePlaceId?.let { id ->
                sorted.firstOrNull { it.id == id } ?: snapshot.allPlaces.firstOrNull { it.id == id }
            }
            val visibleMarkers = PlaceFilters.computeVisibleMarkers(
                filtered = sorted,
                viewport = snapshot.viewport,
                activeId = activePlaceId,
                maxMarkers = MAX_MARKERS
            )
            _uiState.update {
                it.copy(
                    filteredPlaces = sorted,
                    searchResults = searchResults,
                    visibleMarkers = visibleMarkers,
                    activePlaceId = activePlace?.id,
                    activePlace = activePlace,
                    totalValid = snapshot.allPlaces.size,
                )
            }
        }
    }

    private fun findPlaceById(id: Int): Place? {
        val current = _uiState.value
        return current.filteredPlaces.firstOrNull { it.id == id }
            ?: current.allPlaces.firstOrNull { it.id == id }
    }

    sealed class MapCommand {
        data class FocusOnPlace(val place: Place) : MapCommand()
        data class FocusOnLocation(
            val location: GeoPoint,
            val zoom: Double? = null,
            val animate: Boolean = true,
        ) : MapCommand()
        data class SetOrientation(
            val bearing: Float,
            val animate: Boolean = false,
        ) : MapCommand()
        data object ResetOrientation : MapCommand()
    }

    companion object {
        private const val MAX_RESULTS = 200
        private const val MAX_MARKERS = 200
        private const val MAX_QUERY_LENGTH = 120
    }
}

class HazardGridViewModelFactory(
    private val repository: PlacesRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HazardGridViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HazardGridViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
