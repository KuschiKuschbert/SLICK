package com.slick.tactical.ui.components

import android.location.Geocoder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slick.tactical.ui.theme.SlickColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class PlaceSuggestion(
    val displayName: String,
    val suburb: String,
    val state: String,
    val lat: Double,
    val lon: Double,
)

/**
 * Waze/Google Maps-style place search bar for Australian cities and towns.
 *
 * Behaviour:
 * 1. Debounces input by 400ms before querying to avoid hammering the Geocoder
 * 2. Searches Android Geocoder (online, uses Google geocoding under the hood)
 * 3. Falls back to [AUSTRALIAN_CITIES] offline list for common QLD corridor towns
 * 4. Shows up to 6 results in a tactical dark dropdown
 * 5. Selecting a result calls [onPlaceSelected] with the resolved coordinates and
 *    populates [label] with the display name
 *
 * @param label Placeholder label (e.g., "ORIGIN", "DESTINATION")
 * @param query Current text input value
 * @param onQueryChange Called whenever the text changes
 * @param onPlaceSelected Called when the user taps a result; provides display name + coordinates
 */
@Composable
fun PlaceSearchBar(
    label: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onPlaceSelected: (displayName: String, lat: Double, lon: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var showSuggestions by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { newQuery ->
                onQueryChange(newQuery)
                showSuggestions = true

                // Debounce 400ms -- don't search on every keystroke
                searchJob?.cancel()
                if (newQuery.length >= 2) {
                    isSearching = true
                    searchJob = scope.launch {
                        delay(400)
                        val results = searchPlaces(context, newQuery)
                        suggestions = results
                        isSearching = false
                    }
                } else {
                    suggestions = emptyList()
                    isSearching = false
                }
            },
            label = {
                Text(label, color = SlickColors.DataSecondary, fontSize = 12.sp)
            },
            placeholder = {
                Text("Search town or city in Australia...", color = SlickColors.DataSecondary, fontSize = 13.sp)
            },
            singleLine = true,
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = SlickColors.Wash,
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SlickColors.DataPrimary,
                unfocusedTextColor = SlickColors.DataPrimary,
                focusedBorderColor = SlickColors.Alert,
                unfocusedBorderColor = SlickColors.Surface,
                cursorColor = SlickColors.Alert,
                focusedContainerColor = SlickColors.Surface,
                unfocusedContainerColor = SlickColors.Surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Dropdown suggestions
        AnimatedVisibility(visible = showSuggestions && suggestions.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .background(SlickColors.Surface),
            ) {
                items(suggestions, key = { "${it.lat}_${it.lon}" }) { suggestion ->
                    SuggestionRow(
                        suggestion = suggestion,
                        onClick = {
                            onQueryChange(suggestion.displayName)
                            onPlaceSelected(suggestion.displayName, suggestion.lat, suggestion.lon)
                            showSuggestions = false
                            suggestions = emptyList()
                        },
                    )
                    HorizontalDivider(color = SlickColors.Void, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: PlaceSuggestion,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.suburb,
                color = SlickColors.DataPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = suggestion.state,
                color = SlickColors.DataSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Searches for Australian places matching [query].
 *
 * Priority:
 * 1. Exact or prefix match in [AUSTRALIAN_CITIES] (offline, instant)
 * 2. Android Geocoder with "Australia" appended (online, requires network)
 *
 * Results are filtered to Australian bounds:
 * Lat: -44 to -10, Lon: 112 to 154
 */
private suspend fun searchPlaces(context: android.content.Context, query: String): List<PlaceSuggestion> {
    val trimmed = query.trim()

    // Priority 1: Offline city list (instant, no network)
    val offline = AUSTRALIAN_CITIES.filter { city ->
        city.displayName.contains(trimmed, ignoreCase = true) ||
            city.suburb.contains(trimmed, ignoreCase = true)
    }.take(6)

    if (offline.size >= 3) return offline

    // Priority 2: Android Geocoder (online)
    return try {
        withContext(Dispatchers.IO) {
            val geocoder = Geocoder(context, java.util.Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocationName("$trimmed, Australia", 8) ?: emptyList()

            val geocoderResults = addresses
                .filter { addr ->
                    // Filter to Australia bounds
                    addr.latitude in -44.0..-10.0 && addr.longitude in 112.0..154.0
                }
                .map { addr ->
                    val suburb = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: trimmed
                    val state = listOfNotNull(addr.adminArea, addr.countryName)
                        .joinToString(", ")
                    PlaceSuggestion(
                        displayName = "$suburb, $state",
                        suburb = suburb,
                        state = state,
                        lat = addr.latitude,
                        lon = addr.longitude,
                    )
                }
                .distinctBy { it.suburb }
                .take(6)

            // Merge offline + geocoder, deduplicate by proximity
            (offline + geocoderResults).distinctBy { "${it.suburb}_${it.state}" }.take(6)
        }
    } catch (e: Exception) {
        Timber.d(e, "Geocoder unavailable (likely offline) -- returning offline results only")
        offline
    }
}

// ─── Offline fallback: QLD corridor + major Australian cities ─────────────────

private val AUSTRALIAN_CITIES = listOf(
    // QLD Sunshine Coast corridor
    city("Kawana Waters", "QLD", -26.7380, 153.1230),
    city("Caloundra", "QLD", -26.8002, 153.1327),
    city("Maroochydore", "QLD", -26.6567, 153.0996),
    city("Buderim", "QLD", -26.6880, 153.0557),
    city("Noosa Heads", "QLD", -26.3995, 153.0924),
    city("Gympie", "QLD", -26.1867, 152.6652),
    city("Maryborough", "QLD", -25.5388, 152.7013),
    city("Childers", "QLD", -25.2367, 152.2800),
    city("Bundaberg", "QLD", -24.8672, 152.3508),
    city("Gladstone", "QLD", -23.8428, 151.2561),
    city("Rockhampton", "QLD", -23.3791, 150.5100),
    city("Yeppoon", "QLD", -23.1297, 150.7417),
    city("Emu Park", "QLD", -23.2584, 150.8283),
    // QLD major cities
    city("Brisbane", "QLD", -27.4698, 153.0251),
    city("Gold Coast", "QLD", -28.0167, 153.4000),
    city("Sunshine Coast", "QLD", -26.6500, 153.0667),
    city("Cairns", "QLD", -16.9186, 145.7781),
    city("Townsville", "QLD", -19.2590, 146.8169),
    city("Mackay", "QLD", -21.1549, 149.1683),
    city("Toowoomba", "QLD", -27.5598, 151.9507),
    city("Mount Isa", "QLD", -20.7256, 139.4927),
    city("Hervey Bay", "QLD", -25.2886, 152.8427),
    // NSW
    city("Sydney", "NSW", -33.8688, 151.2093),
    city("Newcastle", "NSW", -32.9283, 151.7817),
    city("Coffs Harbour", "NSW", -30.2963, 153.1135),
    city("Byron Bay", "NSW", -28.6467, 153.6155),
    city("Tweed Heads", "NSW", -28.1746, 153.5501),
    // VIC
    city("Melbourne", "VIC", -37.8136, 144.9631),
    city("Geelong", "VIC", -38.1499, 144.3617),
    city("Ballarat", "VIC", -37.5622, 143.8503),
    // SA
    city("Adelaide", "SA", -34.9285, 138.6007),
    // WA
    city("Perth", "WA", -31.9505, 115.8605),
    // NT
    city("Darwin", "NT", -12.4634, 130.8456),
    city("Alice Springs", "NT", -23.6980, 133.8807),
    // ACT
    city("Canberra", "ACT", -35.2809, 149.1300),
    // TAS
    city("Hobart", "TAS", -42.8821, 147.3272),
    city("Launceston", "TAS", -41.4382, 147.1347),
)

private fun city(name: String, state: String, lat: Double, lon: Double) = PlaceSuggestion(
    displayName = "$name, $state",
    suburb = name,
    state = state,
    lat = lat,
    lon = lon,
)
