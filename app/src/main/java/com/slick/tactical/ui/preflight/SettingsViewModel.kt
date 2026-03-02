package com.slick.tactical.ui.preflight

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slick.tactical.data.remote.ShelterType
import com.slick.tactical.util.slickSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** All POI types visible by default. User can disable types they don't want cluttering the map. */
val DEFAULT_ENABLED_POI_TYPES: Set<String> = setOf(
    ShelterType.FUEL,
    ShelterType.REST_AREA,
    ShelterType.PUB,
    ShelterType.CAFE,
    ShelterType.HOTEL,
    ShelterType.CONVENIENCE,
    ShelterType.SHELTER,
    ShelterType.TOILET,
    ShelterType.WATER,
)

data class SettingsUiState(
    val weatherStrategy: WeatherStrategy = WeatherStrategy.ONCE,
    val defaultSpeedKmh: Double = 100.0,
    val enabledPoiTypes: Set<String> = DEFAULT_ENABLED_POI_TYPES,
)

/**
 * ViewModel for [SettingsScreen]. Persists preferences via DataStore.
 *
 * Settings are read once on init and written immediately on change.
 * The weather strategy choice is observed by [com.slick.tactical.service.ConvoyForegroundService]
 * to determine whether to schedule WorkManager or Activity Recognition on-stop sync.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    companion object {
        val KEY_WEATHER_STRATEGY = stringPreferencesKey("weather_strategy")
        val KEY_DEFAULT_SPEED = doublePreferencesKey("default_speed_kmh")
        val KEY_ENABLED_POI_TYPES = stringPreferencesKey("enabled_poi_types")
    }

    init {
        viewModelScope.launch {
            try {
                val prefs = context.slickSettingsDataStore.data.first()
                val strategyName = prefs[KEY_WEATHER_STRATEGY] ?: WeatherStrategy.ONCE.name
                val speed = prefs[KEY_DEFAULT_SPEED] ?: 100.0
                val poiTypesRaw = prefs[KEY_ENABLED_POI_TYPES]
                val enabledPoiTypes = if (poiTypesRaw != null) {
                    poiTypesRaw.split(",").filter { it.isNotBlank() }.toSet()
                } else {
                    DEFAULT_ENABLED_POI_TYPES
                }
                _state.value = SettingsUiState(
                    weatherStrategy = WeatherStrategy.valueOf(strategyName),
                    defaultSpeedKmh = speed,
                    enabledPoiTypes = enabledPoiTypes,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load settings from DataStore")
            }
        }
    }

    fun onWeatherStrategyChanged(strategy: WeatherStrategy) {
        _state.value = _state.value.copy(weatherStrategy = strategy)
        viewModelScope.launch {
            context.slickSettingsDataStore.edit { prefs ->
                prefs[KEY_WEATHER_STRATEGY] = strategy.name
            }
            Timber.i("Weather strategy saved: %s", strategy.name)
        }
    }

    fun onDefaultSpeedChanged(speedKmh: Double) {
        _state.value = _state.value.copy(defaultSpeedKmh = speedKmh)
        viewModelScope.launch {
            context.slickSettingsDataStore.edit { prefs ->
                prefs[KEY_DEFAULT_SPEED] = speedKmh
            }
        }
    }

    fun onTogglePoiType(type: String) {
        val current = _state.value.enabledPoiTypes
        val updated = if (type in current) current - type else current + type
        _state.value = _state.value.copy(enabledPoiTypes = updated)
        viewModelScope.launch {
            context.slickSettingsDataStore.edit { prefs ->
                prefs[KEY_ENABLED_POI_TYPES] = updated.joinToString(",")
            }
            Timber.d("POI filter toggled: %s → enabled=%s", type, type in updated)
        }
    }
}
