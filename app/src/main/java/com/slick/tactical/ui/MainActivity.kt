package com.slick.tactical.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
 * Single activity. Edge-to-edge enabled -- the app draws behind status bar and
 * navigation bar, then uses [WindowInsets.safeDrawing] per-screen to pad content
 * away from notches, punch-hole cameras, and rounded corners.
 *
 * Navigation destinations:
 * - "preflight"  -- Route config, convoy setup, settings (kickstand down)
 * - "inflight"   -- Tactical OLED HUD with MapLibre + GripMatrix (in motion)
 * - "survival"   -- Text-only black HUD (battery < 15%)
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var activityRecognitionManager: ActivityRecognitionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind system bars -- each screen applies its own inset padding
        enableEdgeToEdge()

        activityRecognitionManager.startMonitoring()

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val isDirectSunMode by viewModel.isDirectSunMode.collectAsState()
            val isInVehicle by activityRecognitionManager.isInVehicle.collectAsState()

            SlickTheme(isDirectSunMode = isDirectSunMode) {
                val navController = rememberNavController()
                val currentRoute by navController.currentBackStackEntryAsState()

                // Auto-transition to InFlight when ActivityRecognition detects IN_VEHICLE
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
                    modifier = Modifier.fillMaxSize(),
                    // Slide left/right between screens
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Start,
                            tween(300),
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Start,
                            tween(300),
                        )
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.End,
                            tween(300),
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.End,
                            tween(300),
                        )
                    },
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
                            onBackToPreFlight = {
                                navController.navigate("preflight") {
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
