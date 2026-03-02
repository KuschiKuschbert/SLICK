package com.slick.tactical.ui.inflight

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.slick.tactical.BuildConfig
import com.slick.tactical.data.local.entity.ShelterEntity
import com.slick.tactical.data.local.entity.WeatherNodeEntity
import com.slick.tactical.data.remote.ShelterType
import com.slick.tactical.engine.mesh.RiderState
import com.slick.tactical.engine.navigation.NavigationState
import com.slick.tactical.engine.weather.Coordinate
import com.slick.tactical.engine.weather.GripMatrix
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.lineProgress
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineGradient
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import timber.log.Timber
import java.io.File

// ─── Source / Layer IDs ───────────────────────────────────────────────────────
private const val SRC_ROUTE = "slick-route"
private const val SRC_GRIP = "slick-grip"
private const val SRC_ORIGIN = "slick-origin"
private const val SRC_DEST = "slick-dest"
private const val SRC_RIDER = "slick-rider"
private const val SRC_SHELTERS = "slick-shelters"
private const val SRC_HAZARDS = "slick-hazards"
private const val SRC_NEXT_STOPS = "slick-next-stops"

private const val LYR_ROUTE_CASING = "slick-route-casing"
private const val LYR_ROUTE = "slick-route-fill"
private const val LYR_GRIP = "slick-grip-fill"
private const val LYR_ORIGIN = "slick-origin-dot"
private const val LYR_DEST = "slick-dest-dot"
private const val LYR_SHELTERS = "slick-shelters-dot"
private const val LYR_SHELTERS_LABEL = "slick-shelters-label"
private const val LYR_HAZARDS = "slick-hazards-dot"
private const val LYR_NEXT_STOPS = "slick-next-stops-dot"
private const val LYR_NEXT_STOPS_LABEL = "slick-next-stops-label"
private const val LYR_RIDER = "slick-rider-dot"

/**
 * Zone 2: Tactical Focus — the full navigation map.
 *
 * Navigation behaviour (identical to Waze driving mode):
 * - Camera locked to rider's GPS position with 45° tilt
 * - Map rotates to match rider's bearing (road ahead always points "up")
 * - 500ms smooth camera animation on each GPS update
 * - Zooms immediately to rider position when InFlight screen opens
 *
 * Rendering layers (bottom → top):
 * 1. Route casing (dark, thick) — road-following outline
 * 2. Route fill (grey) — road-following centre line
 * 3. GripMatrix gradient — weather-coloured overlay on the route
 * 4. Origin marker (green) — route start
 * 5. Destination marker (orange) — route end
 * 6. Rider marker (white + orange ring) — current GPS position
 */
@Composable
fun Zone2Map(
    modifier: Modifier = Modifier,
    navState: NavigationState = NavigationState(),
    weatherNodes: List<WeatherNodeEntity> = emptyList(),
    shelters: List<ShelterEntity> = emptyList(),
    /** POI types to render on the map. Only shelters matching these types are shown. */
    enabledPoiTypes: Set<String> = emptySet(),
    /** Next 5 stops ahead of the rider — rendered as larger highlighted markers. */
    nextStops: List<ShelterEntity> = emptyList(),
    riderLat: Double = -26.7380,
    riderLon: Double = 153.1230,
    riderBearing: Float = 0f,
    convoyRiders: Map<String, RiderState> = emptyMap(),
    /** True while the map should auto-follow the rider. Set to false on user pan. */
    isFollowingRider: Boolean = true,
    /** Callback fired when the user starts panning or zooming the map manually. */
    onUserInteraction: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val gripMatrix = remember { GripMatrix() }

    MapLibre.getInstance(context)

    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // ── Initial map + style setup (runs once) ─────────────────────────────────
    LaunchedEffect(Unit) {
        mapView.getMapAsync { map ->
            mapRef = map
            map.uiSettings.apply {
                isCompassEnabled = false
                isAttributionEnabled = false
                isLogoEnabled = false
                // Allow scroll and zoom so the rider can pan ahead / inspect hazard nodes.
                // Rotate and tilt remain locked: map rotates with rider bearing (Waze-mode).
                isScrollGesturesEnabled = true
                isZoomGesturesEnabled = true
                isRotateGesturesEnabled = true   // rider can manually rotate, recenter re-snaps to bearing
                isTiltGesturesEnabled = false
            }

            // Detect when the user manually pans or zooms (vs programmatic camera moves)
            // to disengage auto-follow and show the recenter button.
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    onUserInteraction()
                }
            }

            map.setStyle(resolveMapStyle(context)) { style ->
                styleLoaded = true
                addAllLayers(style)

                // Prefer the route origin for the initial camera — it is always populated by the
                // time the InFlight screen opens (the route was synced on PreFlight). The raw
                // riderLat/riderLon may still be the Kawana default if GPS hasn't fixed yet.
                val initialTarget = navState.origin
                    ?.let { LatLng(it.lat, it.lon) }
                    ?: LatLng(riderLat, riderLon)

                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(initialTarget)
                            .zoom(14.0)
                            .bearing(riderBearing.toDouble())
                            .tilt(45.0)
                            .build(),
                    ),
                )
                Timber.d("Zone2Map: style loaded, layers initialised, camera set to %s",
                    if (navState.origin != null) "route origin" else "rider default")
            }
        }
    }

    // ── Route polyline: updated when route is set OR when style finishes loading ──
    // styleLoaded must be a key so this re-fires after the async setStyle() callback completes.
    // Without it the route data arrives before the style is ready, the effect returns early,
    // and the polyline never renders because the key (fullPolyline) never changes again.
    LaunchedEffect(navState.fullPolyline, styleLoaded) {
        if (!styleLoaded || navState.fullPolyline.size < 2) return@LaunchedEffect
        val style = mapRef?.getStyle() ?: return@LaunchedEffect

        style.getSourceAs<GeoJsonSource>(SRC_ROUTE)
            ?.setGeoJson(lineStringGeoJson(navState.fullPolyline))

        navState.origin?.let { o ->
            style.getSourceAs<GeoJsonSource>(SRC_ORIGIN)?.setGeoJson(pointGeoJson(o.lat, o.lon))
        }
        navState.destination?.let { d ->
            style.getSourceAs<GeoJsonSource>(SRC_DEST)?.setGeoJson(pointGeoJson(d.lat, d.lon))
        }

        Timber.d("Zone2Map: route polyline updated (%d pts)", navState.fullPolyline.size)
    }

    // ── Shelter POI markers (filtered by enabledPoiTypes) ────────────────────
    LaunchedEffect(shelters, enabledPoiTypes, styleLoaded) {
        if (!styleLoaded || shelters.isEmpty()) return@LaunchedEffect
        val filtered = if (enabledPoiTypes.isEmpty()) shelters
                       else shelters.filter { it.type in enabledPoiTypes }
        mapRef?.getStyle()?.getSourceAs<GeoJsonSource>(SRC_SHELTERS)
            ?.setGeoJson(shelterFeatureCollection(filtered))
        Timber.d("Zone2Map: %d/%d shelter markers rendered (filter=%s)",
            filtered.size, shelters.size, enabledPoiTypes.joinToString())
    }

    // ── Next 5 stops ahead of the rider — highlighted markers ────────────────
    LaunchedEffect(nextStops, styleLoaded) {
        if (!styleLoaded) return@LaunchedEffect
        mapRef?.getStyle()?.getSourceAs<GeoJsonSource>(SRC_NEXT_STOPS)
            ?.setGeoJson(nextStopsFeatureCollection(nextStops))
        Timber.d("Zone2Map: %d next-stop markers updated", nextStops.size)
    }

    // ── GripMatrix gradient: updated when weather nodes or the route polyline changes ──
    // Also keyed on styleLoaded (same race condition as the route effect above).
    // SRC_GRIP geometry uses the full road-following polyline so the colour gradient
    // tracks the actual road — weather nodes alone would produce straight connector lines.
    LaunchedEffect(weatherNodes, navState.fullPolyline, styleLoaded) {
        if (!styleLoaded || weatherNodes.size < 2) return@LaunchedEffect
        val style = mapRef?.getStyle() ?: return@LaunchedEffect

        val gripGeometry = if (navState.fullPolyline.size >= 2) navState.fullPolyline
            else weatherNodes.map { Coordinate(it.latitude, it.longitude) }
        style.getSourceAs<GeoJsonSource>(SRC_GRIP)
            ?.setGeoJson(lineStringGeoJson(gripGeometry))

        style.getLayerAs<LineLayer>(LYR_GRIP)?.setProperties(
            lineGradient(buildGradientStops(weatherNodes, gripMatrix)),
        )

        // Hazard node markers: HIGH and EXTREME nodes get a coloured dot on the route
        style.getSourceAs<GeoJsonSource>(SRC_HAZARDS)
            ?.setGeoJson(hazardNodeFeatureCollection(weatherNodes, gripMatrix))

        Timber.d("Zone2Map: GripMatrix gradient + hazard markers updated (%d nodes)", weatherNodes.size)
    }

    // ── Rider position: always updated regardless of follow-mode ─────────────
    // styleLoaded is a key so the first GPS fix is not silently dropped when it
    // arrives before the map style has finished loading.
    LaunchedEffect(riderLat, riderLon, riderBearing, styleLoaded) {
        if (!styleLoaded) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect

        // Always update the rider icon on the map
        map.getStyle()?.getSourceAs<GeoJsonSource>(SRC_RIDER)
            ?.setGeoJson(pointGeoJson(riderLat, riderLon))

        // Camera follow: only animate when the rider hasn't manually panned away.
        // When isFollowingRider=false, the map stays where the user positioned it
        // until they tap the recenter button.
        if (isFollowingRider) {
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(riderLat, riderLon))
                        .zoom(15.0)
                        .bearing(riderBearing.toDouble())
                        .tilt(45.0)
                        .build(),
                ),
                500,
            )
        }
    }

    // ── Recenter: snap camera back to rider when follow-mode is re-engaged ───
    LaunchedEffect(isFollowingRider) {
        if (!styleLoaded || !isFollowingRider) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(riderLat, riderLon))
                    .zoom(15.0)
                    .bearing(riderBearing.toDouble())
                    .tilt(45.0)
                    .build(),
            ),
            400,
        )
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = {},  // All updates are handled by LaunchedEffects above
    )
}

// ─── Style resolution ─────────────────────────────────────────────────────────

/**
 * Resolves the MapLibre style URI:
 * 1. Local PMTiles file (fully offline, populated by GarageSyncWorker)
 * 2. Remote PMTiles URL from BuildConfig
 * 3. OpenFreeMap dark style (online, no API key required)
 */
fun resolveMapStyle(context: android.content.Context): String {
    val localFile = File(context.filesDir, "slick-corridor.pmtiles")
    return when {
        localFile.exists() -> {
            Timber.i("Zone2Map: local PMTiles (%.0f MB)", localFile.length() / 1_048_576.0)
            buildStyleWithPmtilesUrl(context, "pmtiles://${localFile.absolutePath}")
        }
        BuildConfig.PMTILES_URL.isNotBlank() && !BuildConfig.PMTILES_URL.contains("YOUR_BUCKET") -> {
            Timber.i("Zone2Map: remote PMTiles URL")
            buildStyleWithPmtilesUrl(context, "pmtiles://${BuildConfig.PMTILES_URL}")
        }
        else -> {
            Timber.w("Zone2Map: no PMTiles configured, using OpenFreeMap dark tiles (online)")
            "https://tiles.openfreemap.org/styles/dark"
        }
    }
}

private fun buildStyleWithPmtilesUrl(context: android.content.Context, pmtilesUri: String): String {
    return try {
        val json = context.assets.open("tactical-oled-style.json")
            .bufferedReader().use { it.readText() }
            .replace("{pmtiles_url}", pmtilesUri)
        val cache = File(context.cacheDir, "slick-map-style.json")
        cache.writeText(json)
        cache.toURI().toString()
    } catch (e: Exception) {
        Timber.e(e, "Zone2Map: style build failed, falling back to OpenFreeMap")
        "https://tiles.openfreemap.org/styles/dark"
    }
}

// ─── Layer initialisation (called once when style loads) ─────────────────────

private fun addAllLayers(style: Style) {
    // Empty GeoJSON sources — data pushed later via LaunchedEffects
    style.addSource(GeoJsonSource(SRC_ROUTE))
    style.addSource(GeoJsonSource(SRC_GRIP, GeoJsonOptions().withLineMetrics(true)))
    style.addSource(GeoJsonSource(SRC_ORIGIN))
    style.addSource(GeoJsonSource(SRC_DEST))
    style.addSource(GeoJsonSource(SRC_SHELTERS))
    style.addSource(GeoJsonSource(SRC_HAZARDS))
    style.addSource(GeoJsonSource(SRC_NEXT_STOPS))
    style.addSource(GeoJsonSource(SRC_RIDER))

    // Route casing: dark outline so the route is visible over any base tile
    style.addLayer(
        LineLayer(LYR_ROUTE_CASING, SRC_ROUTE).apply {
            setProperties(lineCap(LINE_CAP_ROUND), lineJoin(LINE_JOIN_ROUND),
                lineWidth(14f), lineColor("#1C1C1E"))
        },
    )

    // Route fill: road-following grey centre line
    style.addLayer(
        LineLayer(LYR_ROUTE, SRC_ROUTE).apply {
            setProperties(lineCap(LINE_CAP_ROUND), lineJoin(LINE_JOIN_ROUND),
                lineWidth(8f), lineColor("#4A4A4E"))
        },
    )

    // GripMatrix gradient: weather-coloured overlay (starts green = no data = assume clear)
    style.addLayer(
        LineLayer(LYR_GRIP, SRC_GRIP).apply {
            setProperties(
                lineCap(LINE_CAP_ROUND), lineJoin(LINE_JOIN_ROUND), lineWidth(8f),
                lineGradient(
                    interpolate(linear(), lineProgress(),
                        stop(0f, color(Color.parseColor("#4CAF50"))),
                        stop(1f, color(Color.parseColor("#4CAF50"))),
                    ),
                ),
            )
        },
    )

    // Origin: green dot
    style.addLayer(
        CircleLayer(LYR_ORIGIN, SRC_ORIGIN).apply {
            setProperties(circleRadius(11f), circleColor("#4CAF50"),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f))
        },
    )

    // Destination: orange dot (matches Alert colour)
    style.addLayer(
        CircleLayer(LYR_DEST, SRC_DEST).apply {
            setProperties(circleRadius(11f), circleColor("#FF5722"),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f))
        },
    )

    // ── Shelter POI markers (colour-coded by category) ────────────────────────
    //   fuel / rest_area → amber  (⛽ servos and roadside rests)
    //   pub / bar        → purple (🍺 take-cover spots)
    //   cafe / restaurant/ fast_food / convenience / hotel / motel → cyan
    //   toilet / water   → grey (small, low priority)
    style.addLayer(
        CircleLayer(LYR_SHELTERS, SRC_SHELTERS).apply {
            setProperties(
                circleRadius(
                    match(get("category"),
                        literal("critical"), literal(9f),
                        literal("cover"), literal(7f),
                        literal(5f),
                    ),
                ),
                circleColor(
                    match(get("category"),
                        literal("critical"), color(Color.parseColor("#FF9800")),
                        literal("cover"), color(Color.parseColor("#CE93D8")),
                        color(Color.parseColor("#78909C")),
                    ),
                ),
                circleStrokeColor("#1C1C1E"),
                circleStrokeWidth(1.5f),
            )
        },
    )

    // Shelter name label (visible only from zoom 13+)
    style.addLayer(
        SymbolLayer(LYR_SHELTERS_LABEL, SRC_SHELTERS).apply {
            setMinZoom(13f)
            setProperties(
                textField(get("name")),
                textSize(11f),
                textColor("#FFFFFF"),
                textHaloColor("#000000"),
                textHaloWidth(1.5f),
                textOffset(arrayOf(0f, 1.5f)),
                textAllowOverlap(false),
                textIgnorePlacement(false),
            )
        },
    )

    // ── HIGH/EXTREME hazard node markers (coloured pulse on the route) ────────
    style.addLayer(
        CircleLayer(LYR_HAZARDS, SRC_HAZARDS).apply {
            setProperties(
                circleRadius(
                    match(get("danger"),
                        literal("EXTREME"), literal(12f),
                        literal(8f),  // HIGH
                    ),
                ),
                circleColor(
                    match(get("danger"),
                        literal("EXTREME"), color(Color.parseColor("#F44336")),  // Red
                        color(Color.parseColor("#FF9800")),                       // HIGH = orange
                    ),
                ),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            )
        },
    )

    // ── Next 5 stops ahead — larger, bright markers with white stroke (topmost POI layer) ──
    style.addLayer(
        CircleLayer(LYR_NEXT_STOPS, SRC_NEXT_STOPS).apply {
            setProperties(
                circleRadius(13f),
                circleColor(
                    match(get("category"),
                        literal("critical"), color(Color.parseColor("#FF9800")),
                        literal("cover"), color(Color.parseColor("#CE93D8")),
                        color(Color.parseColor("#9E9E9E")),
                    ),
                ),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
            )
        },
    )

    // Next-stop name label — always visible (no min-zoom restriction)
    style.addLayer(
        SymbolLayer(LYR_NEXT_STOPS_LABEL, SRC_NEXT_STOPS).apply {
            setProperties(
                textField(get("name")),
                textSize(12f),
                textColor("#FFFFFF"),
                textHaloColor("#000000"),
                textHaloWidth(2f),
                textOffset(arrayOf(0f, 1.8f)),
                textAllowOverlap(false),
                textIgnorePlacement(false),
            )
        },
    )

    // Rider: white filled circle with orange ring — rendered last (topmost)
    style.addLayer(
        CircleLayer(LYR_RIDER, SRC_RIDER).apply {
            setProperties(circleRadius(9f), circleColor("#FFFFFF"),
                circleStrokeColor("#FF9800"), circleStrokeWidth(4f))
        },
    )
}

// ─── GeoJSON helpers ──────────────────────────────────────────────────────────

private fun pointGeoJson(lat: Double, lon: Double): String =
    """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]}}"""

private fun lineStringGeoJson(points: List<Coordinate>): String {
    if (points.size < 2) return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[]}}"""
    val coords = points.joinToString(",") { "[${it.lon},${it.lat}]" }
    return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}"""
}

/**
 * Builds a GeoJSON FeatureCollection for shelter POIs with a "category" property
 * that drives the MapLibre data-driven circle colour and size expressions.
 *
 * Categories:
 * - "critical" → fuel stations, rest areas (amber, large)
 * - "cover"    → pubs, bars, cafes, hotels (purple, medium)
 * - "amenity"  → toilets, water (grey, small)
 */
private fun shelterFeatureCollection(shelters: List<ShelterEntity>): String {
    if (shelters.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = shelters.joinToString(",") { shelter ->
        val category = when (shelter.type) {
            ShelterType.FUEL, ShelterType.REST_AREA -> "critical"
            ShelterType.PUB, ShelterType.CAFE, ShelterType.HOTEL, ShelterType.CONVENIENCE -> "cover"
            else -> "amenity"
        }
        val safeName = shelter.name.replace("\"", "'").replace("\\", "")
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${shelter.longitude},${shelter.latitude}]},"properties":{"name":"$safeName","category":"$category","type":"${shelter.type}"}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/**
 * Builds a GeoJSON FeatureCollection for the next 5 POIs ahead of the rider.
 * Uses the same "category" property as shelterFeatureCollection for consistent colour coding,
 * but these markers are rendered larger with a brighter white stroke to stand out.
 */
private fun nextStopsFeatureCollection(stops: List<ShelterEntity>): String {
    if (stops.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = stops.mapIndexed { idx, shelter ->
        val category = when (shelter.type) {
            ShelterType.FUEL, ShelterType.REST_AREA -> "critical"
            ShelterType.PUB, ShelterType.CAFE, ShelterType.HOTEL, ShelterType.CONVENIENCE -> "cover"
            else -> "amenity"
        }
        val safeName = shelter.name.replace("\"", "'").replace("\\", "")
        val icon = when (shelter.type) {
            ShelterType.FUEL -> "⛽"
            ShelterType.PUB -> "🍺"
            ShelterType.CAFE -> "☕"
            ShelterType.HOTEL -> "🏨"
            ShelterType.REST_AREA -> "🛑"
            ShelterType.CONVENIENCE -> "🛒"
            else -> "•"
        }
        val label = "${idx + 1}. $icon $safeName"
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${shelter.longitude},${shelter.latitude}]},"properties":{"name":"$label","category":"$category","type":"${shelter.type}"}}"""
    }
    return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
}

/**
 * Builds a GeoJSON FeatureCollection for HIGH and EXTREME weather nodes.
 * Only includes nodes that are hazardous — DRY and MODERATE nodes are skipped
 * (they're already visible from the GripMatrix gradient colour).
 */
private fun hazardNodeFeatureCollection(
    nodes: List<WeatherNodeEntity>,
    gripMatrix: GripMatrix,
): String {
    val hazardFeatures = nodes.mapNotNull { node ->
        val report = gripMatrix.evaluateNode(node).getOrNull() ?: return@mapNotNull null
        if (report.dangerLevel == GripMatrix.DangerLevel.DRY ||
            report.dangerLevel == GripMatrix.DangerLevel.MODERATE) return@mapNotNull null
        val danger = report.dangerLevel.name  // "HIGH" or "EXTREME"
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${node.longitude},${node.latitude}]},"properties":{"danger":"$danger"}}"""
    }
    if (hazardFeatures.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    return """{"type":"FeatureCollection","features":[${hazardFeatures.joinToString(",")}]}"""
}

// ─── GripMatrix gradient expression ──────────────────────────────────────────

private fun buildGradientStops(
    nodes: List<WeatherNodeEntity>,
    gripMatrix: GripMatrix,
): org.maplibre.android.style.expressions.Expression {
    val total = nodes.size
    if (total < 2) {
        return interpolate(linear(), lineProgress(),
            stop(0f, color(Color.parseColor("#4CAF50"))),   // Green — no data = assume clear
            stop(1f, color(Color.parseColor("#4CAF50"))),
        )
    }
    val stops = nodes.mapIndexed { i, node ->
        val progress = i.toFloat() / (total - 1).toFloat()
        val level = gripMatrix.evaluateNode(node).map { it.dangerLevel }
            .getOrElse { GripMatrix.DangerLevel.DRY }
        stop(progress, color(Color.parseColor(gripMatrix.dangerLevelToColor(level))))
    }
    return interpolate(linear(), lineProgress(), *stops.toTypedArray())
}