package com.slick.tactical.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.slick.tactical.engine.location.ActivityRecognitionManager
import com.slick.tactical.service.ConvoyForegroundService
import com.slick.tactical.ui.inflight.InFlightHudScreen
import com.slick.tactical.ui.preflight.PreFlightScreen
import com.slick.tactical.ui.survival.SurvivalHudScreen
import com.slick.tactical.ui.theme.SlickTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Single activity hosting all SLICK screens via Jetpack Navigation Compose.
 *
 * Navigation destinations:
 * - "preflight" -- Route config, convoy setup, settings (kickstand down)
 * - "inflight" -- Tactical HUD with MapLibre + GripMatrix (kickstand up)
 * - "survival" -- Text-only HUD (battery < 15%)
 *
 * The [ConvoyForegroundService] is started when the user initiates a convoy session.
 * It persists independently of this Activity's lifecycle.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var activityRecognitionManager: ActivityRecognitionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Timber.d("MainActivity created")
        activityRecognitionManager.startMonitoring()

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val isDirectSunMode by viewModel.isDirectSunMode.collectAsState()
            val isInVehicle by activityRecognitionManager.isInVehicle.collectAsState()

            SlickTheme(isDirectSunMode = isDirectSunMode) {
                val navController = rememberNavController()
                val currentRoute by navController.currentBackStackEntryAsState()

                // Auto-transition to In-Flight when Android detects IN_VEHICLE
                LaunchedEffect(isInVehicle) {
                    val current = currentRoute?.destination?.route
                    if (isInVehicle && current == "preflight") {
                        Timber.i("Auto-transitioning to In-Flight (IN_VEHICLE detected)")
                        startConvoyService()
                        navController.navigate("inflight") {
                            popUpTo("preflight") { inclusive = false }
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = "preflight",
                ) {
                    composable("preflight") {
                        PreFlightScreen(
                            onStartConvoy = {
                                startConvoyService()
                                navController.navigate("inflight") {
                                    popUpTo("preflight") { inclusive = false }
                                }
                            },
                        )
                    }
                    composable("inflight") {
                        InFlightHudScreen(
                            onSurvivalMode = {
                                navController.navigate("survival") {
                                    popUpTo("inflight") { inclusive = true }
                                }
                            },
                        )
                    }
                    composable("survival") {
                        SurvivalHudScreen(
                            onRestoreFullMode = {
                                navController.navigate("inflight") {
                                    popUpTo("survival") { inclusive = true }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun startConvoyService() {
        val intent = Intent(this, ConvoyForegroundService::class.java)
        startForegroundService(intent)
        Timber.i("ConvoyForegroundService started")
    }
}
