package com.slick.tactical.ui.preflight

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "slick_settings")

data class SettingsUiState(
    val weatherStrategy: WeatherStrategy = WeatherStrategy.ONCE,
    val defaultSpeedKmh: Double = 100.0,
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
    }

    init {
        viewModelScope.launch {
            try {
                val prefs = context.settingsDataStore.data.first()
                val strategyName = prefs[KEY_WEATHER_STRATEGY] ?: WeatherStrategy.ONCE.name
                val speed = prefs[KEY_DEFAULT_SPEED] ?: 100.0
                _state.value = SettingsUiState(
                    weatherStrategy = WeatherStrategy.valueOf(strategyName),
                    defaultSpeedKmh = speed,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load settings from DataStore")
            }
        }
    }

    fun onWeatherStrategyChanged(strategy: WeatherStrategy) {
        _state.value = _state.value.copy(weatherStrategy = strategy)
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[KEY_WEATHER_STRATEGY] = strategy.name
            }
            Timber.i("Weather strategy saved: %s", strategy.name)
        }
    }

    fun onDefaultSpeedChanged(speedKmh: Double) {
        _state.value = _state.value.copy(defaultSpeedKmh = speedKmh)
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[KEY_DEFAULT_SPEED] = speedKmh
            }
        }
    }
}
