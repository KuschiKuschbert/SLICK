package com.slick.tactical.ui.inflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.slick.tactical.service.OperationalState
import com.slick.tactical.ui.components.MassiveButton
import com.slick.tactical.ui.theme.SlickColors

/**
 * In-Flight HUD -- tactical command centre for 110 km/h riding.
 *
 * Three cognitive zones, screen-size adaptive:
 *
 * Zone 1 (top 20% / phone, 15% / tablet): Peripheral Header
 *   Monospace telemetry: next turn + distance, current speed, 24h ETA.
 *   Padded below the status bar / camera cutout via [WindowInsets.statusBars].
 *
 * Zone 2 (middle 50% / phone, 60% / tablet): Tactical Focus
 *   MapLibre render. Rider icon anchored at 55% from top.
 *   No inset padding -- map fills all the way to the screen edge for max situational awareness.
 *
 * Zone 3 (bottom 30% / phone, 25% / tablet): Interaction Deck
 *   Two massive glove-friendly buttons.
 *   Padded above the navigation bar via [WindowInsets.navigationBars].
 *
 * Tablet adaptation: [BoxWithConstraints] detects screen width > 600 dp and widens Zone 1/3.
 */
@Composable
fun InFlightHudScreen(
    viewModel: InFlightViewModel = hiltViewModel(),
    onSurvivalMode: () -> Unit,
    onBackToPreFlight: () -> Unit = {},
) {
    val uiState by viewModel.state.collectAsState()

    if (uiState.operationalMode == OperationalState.SURVIVAL_MODE) {
        onSurvivalMode()
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(SlickColors.Void),
    ) {
        val isTablet = maxWidth >= 600.dp

        // Zone weight distribution adapts to screen size
        val zone1Weight = if (isTablet) 0.15f else 0.20f
        val zone2Weight = if (isTablet) 0.60f else 0.50f
        val zone3Weight = if (isTablet) 0.25f else 0.30f

        Column(modifier = Modifier.fillMaxSize()) {
            // Zone 1: Peripheral Header — padded below status bar / notch
            Zone1Header(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(zone1Weight)
                    .windowInsetsPadding(WindowInsets.statusBars),
                nextTurnDistanceM = uiState.nextTurnDistanceMetres,
                nextTurnArrow = uiState.nextTurnArrow,
                nextTurnInstruction = uiState.nextTurnInstruction,
                speedKmh = uiState.speedKmh,
                eta24h = uiState.eta24h,
                remainingDistanceKm = uiState.remainingDistanceKm,
                nextHazard = uiState.nextHazard,
                nearestShelter = uiState.nearestShelter,
                nextStops = uiState.nextStops,
                isTablet = isTablet,
            )

            // Zone 2: Map — edge-to-edge with recenter button overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(zone2Weight),
            ) {
                Zone2MapWrapper(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )

                // Recenter button: appears when the rider has panned away from their position
                if (!uiState.isFollowingRider) {
                    FloatingActionButton(
                        onClick = viewModel::onRecenter,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(48.dp),
                        containerColor = SlickColors.Alert,
                        contentColor = SlickColors.Void,
                    ) {
                        Text(
                            text = "⊕",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Zone 3: Interaction Deck — padded above navigation bar
            Zone3InteractionDeck(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(zone3Weight)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                onMarkHazard = viewModel::onMarkHazard,
                onToggleMute = viewModel::onToggleMute,
                isMuted = uiState.isAudioMuted,
                isTablet = isTablet,
            )
        }
    }
}

/**
 * Zone 1: Pure telemetry header.
 *
 * Layout (left → right):
 * - Turn arrow + distance to maneuver (Monospace, large)
 * - Current speed (Monospace, largest — critical at 110 km/h)
 * - ETA (Monospace, smaller secondary)
 *
 * A second micro-row below shows the next maneuver instruction text (e.g., "Turn right onto
 * Bruce Highway") in smaller grey text — readable as a secondary glance.
 */
@Composable
fun Zone1Header(
    modifier: Modifier = Modifier,
    nextTurnDistanceM: Int,
    nextTurnArrow: String,
    nextTurnInstruction: String = "Follow route",
    speedKmh: Double,
    eta24h: String,
    remainingDistanceKm: Double = 0.0,
    nextHazard: HazardAlert? = null,
    nearestShelter: com.slick.tactical.data.local.entity.ShelterEntity? = null,
    nextStops: List<com.slick.tactical.data.local.entity.ShelterEntity> = emptyList(),
    isTablet: Boolean = false,
) {
    val arrowFontSize = if (isTablet) 34 else 28
    val speedFontSize = if (isTablet) 40 else 32
    val etaFontSize = if (isTablet) 22 else 16
    val instrFontSize = if (isTablet) 14 else 12
    val distanceText = when {
        nextTurnDistanceM >= 1000 -> "${"%.1f".format(nextTurnDistanceM / 1000.0)} km"
        else -> "${nextTurnDistanceM} m"
    }

    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .background(SlickColors.Void)
            .padding(horizontal = if (isTablet) 32.dp else 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Primary row: arrow + distance | speed | remaining distance + ETA
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Turn arrow + distance to maneuver
            Text(
                text = "$nextTurnArrow  $distanceText",
                color = SlickColors.DataPrimary,
                fontSize = arrowFontSize.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )

            // Speed — most prominent number on screen
            Text(
                text = "${speedKmh.toInt()} km/h",
                color = SlickColors.DataPrimary,
                fontSize = speedFontSize.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            // Remaining distance + ETA stacked on the right
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.End,
            ) {
                if (remainingDistanceKm > 0.1) {
                    Text(
                        text = "${"%.0f".format(remainingDistanceKm)} km",
                        color = SlickColors.DataPrimary,
                        fontSize = etaFontSize.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "ETA $eta24h",
                    color = SlickColors.DataSecondary,
                    fontSize = (etaFontSize - 2).sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Secondary row: full turn instruction text (glanceable, not safety-critical)
        if (nextTurnInstruction.isNotBlank() && nextTurnInstruction != "Follow route") {
            Text(
                text = nextTurnInstruction,
                color = SlickColors.DataSecondary,
                fontSize = instrFontSize.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }

        // Hazard strip: only visible when conditions are not DRY
        if (nextHazard != null) {
            WeatherStrip(
                hazard = nextHazard,
                shelter = nearestShelter,
                fontSize = instrFontSize,
            )
        }

        // Next stop: show nearest filtered POI ahead along the route
        val nearestStop = nextStops.firstOrNull()
        if (nearestStop != null) {
            val stopIcon = when (nearestStop.type) {
                com.slick.tactical.data.remote.ShelterType.FUEL -> "⛽"
                com.slick.tactical.data.remote.ShelterType.PUB -> "🍺"
                com.slick.tactical.data.remote.ShelterType.CAFE -> "☕"
                com.slick.tactical.data.remote.ShelterType.HOTEL -> "🏨"
                com.slick.tactical.data.remote.ShelterType.REST_AREA -> "🛑"
                com.slick.tactical.data.remote.ShelterType.CONVENIENCE -> "🛒"
                com.slick.tactical.data.remote.ShelterType.TOILET -> "🚻"
                com.slick.tactical.data.remote.ShelterType.WATER -> "💧"
                else -> "🏠"
            }
            Text(
                text = "$stopIcon ${nearestStop.name}",
                color = SlickColors.DataSecondary,
                fontSize = instrFontSize.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Compact single-line weather hazard strip for Zone 1.
 *
 * Colour-coded by danger level:
 * - DRY → Green (optimal)
 * - MODERATE → Yellow (light moisture)
 * - HIGH → Orange (active rain / strong crosswind)
 * - EXTREME → Red (severe — find cover)
 *
 * Shows: "⚠ RAIN 3mm · CROSSWIND 28km/h in 45km · SERVO 2.1km"
 */
@Composable
private fun WeatherStrip(
    hazard: HazardAlert,
    shelter: com.slick.tactical.data.local.entity.ShelterEntity?,
    fontSize: Int,
) {
    val stripColor = when (hazard.dangerLevel) {
        com.slick.tactical.engine.weather.GripMatrix.DangerLevel.EXTREME -> Color(0xFFF44336)  // Red
        com.slick.tactical.engine.weather.GripMatrix.DangerLevel.HIGH -> Color(0xFFFF9800)      // Orange
        com.slick.tactical.engine.weather.GripMatrix.DangerLevel.MODERATE -> Color(0xFFFFEB3B)  // Yellow
        else -> Color(0xFF4CAF50)                                                               // Green — clear
    }

    val distText = when {
        hazard.distanceMetres >= 1000 -> "in ${"%.0f".format(hazard.distanceMetres / 1000.0)}km"
        else -> "in ${hazard.distanceMetres}m"
    }

    val parts = mutableListOf<String>()
    if (hazard.rainfallStatus.contains("rain", ignoreCase = true) ||
        hazard.rainfallStatus.contains("wetness", ignoreCase = true)) {
        // Extract the key phrase (before the dash if present)
        val key = hazard.rainfallStatus.substringBefore(" -").take(20)
        parts.add(key)
    }
    if (hazard.crosswindKmh > 15.0) {
        parts.add("CROSSWIND ${"%.0f".format(hazard.crosswindKmh)}km/h")
    }

    val shelterText = shelter?.let { s ->
        "  ·  ${s.name.take(14)} →"
    } ?: ""

    val text = "⚠ ${parts.joinToString(" · ")} $distText$shelterText"

    Text(
        text = text,
        color = stripColor,
        fontSize = fontSize.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** Zone 2: MapLibre render — delegates to Zone2Map.kt. */
@Composable
fun Zone2MapWrapper(
    modifier: Modifier = Modifier,
    viewModel: InFlightViewModel,
) {
    val state by viewModel.state.collectAsState()
    Zone2Map(
        modifier = modifier,
        navState = state.navState,
        weatherNodes = state.weatherNodes,
        shelters = state.shelters,
        enabledPoiTypes = state.enabledPoiTypes,
        nextStops = state.nextStops,
        riderLat = state.riderLat,
        riderLon = state.riderLon,
        riderBearing = state.riderBearingDeg,
        convoyRiders = state.convoyRiders,
        isFollowingRider = state.isFollowingRider,
        onUserInteraction = viewModel::onMapInteraction,
    )
}

/** Zone 3: Two massive glove-friendly tap targets — nothing else. */
@Composable
fun Zone3InteractionDeck(
    modifier: Modifier = Modifier,
    onMarkHazard: () -> Unit,
    onToggleMute: () -> Unit,
    isMuted: Boolean,
    isTablet: Boolean = false,
) {
    val buttonHeight = if (isTablet) 80.dp else 64.dp
    val fontSize = if (isTablet) 18.sp else 14.sp

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MassiveButton(
            text = "MARK HAZARD",
            onClick = onMarkHazard,
            containerColor = SlickColors.Alert,
            contentColor = SlickColors.Void,
            modifier = Modifier
                .weight(1f)
                .height(buttonHeight),
            fontSize = fontSize,
        )

        MassiveButton(
            text = if (isMuted) "UNMUTE" else "AUDIO MUTE",
            onClick = onToggleMute,
            containerColor = if (isMuted) SlickColors.DataSecondary else SlickColors.Surface,
            contentColor = SlickColors.DataPrimary,
            modifier = Modifier
                .weight(1f)
                .height(buttonHeight),
            fontSize = fontSize,
        )
    }
}
