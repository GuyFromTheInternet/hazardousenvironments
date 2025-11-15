package com.abandonsearch.hazardgrid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abandonsearch.hazardgrid.data.settings.AppSettings
import com.abandonsearch.hazardgrid.data.settings.MapApp
import com.abandonsearch.hazardgrid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    fun setDefaultMapApp(mapApp: MapApp) {
        viewModelScope.launch {
            repository.setDefaultMapApp(mapApp)
        }
    }

    fun setMergeShapesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setMergeShapesEnabled(enabled)
        }
    }

}

class SettingsViewModelFactory(
    private val repository: SettingsRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return SettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
