package com.slick.tactical.ui.inflight

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.slick.tactical.BuildConfig
import com.slick.tactical.data.local.entity.WeatherNodeEntity
import com.slick.tactical.engine.mesh.RiderState
import com.slick.tactical.engine.weather.GripMatrix
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.lineProgress
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineGradient
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import timber.log.Timber
import java.io.File

/**
 * Zone 2: Tactical Focus -- the MapLibre map with GripMatrix weather gradient.
 *
 * Style resolution order:
 * 1. Local PMTiles file on device (fully offline, populated by GarageSyncWorker)
 * 2. Protomaps CDN (online fallback using BuildConfig.PMTILES_URL if set)
 * 3. Embedded asset style (always available, no tile data)
 *
 * The style JSON is embedded in assets/tactical-oled-style.json and can be loaded
 * without any network connection. The PMTiles URL is injected at runtime via the
 * style JSON template substitution.
 */
@Composable
fun Zone2Map(
    modifier: Modifier = Modifier,
    weatherNodes: List<WeatherNodeEntity> = emptyList(),
    riderLat: Double = -26.7380,
    riderLon: Double = 153.1230,
    riderBearing: Float = 0f,
    convoyRiders: Map<String, RiderState> = emptyMap(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val gripMatrix = remember { GripMatrix() }

    // Initialize MapLibre SDK
    MapLibre.getInstance(context)

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
        }
    }

    // Wire MapView to lifecycle
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

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { mv ->
            mv.getMapAsync { map ->
                val styleUri = resolveMapStyle(context)

                map.setStyle(styleUri) { style ->
                    // Camera: rider icon anchored at 55% from top (shows more road ahead)
                    val cameraPosition = CameraPosition.Builder()
                        .target(LatLng(riderLat, riderLon))
                        .zoom(13.0)
                        .bearing(riderBearing.toDouble())
                        .tilt(45.0)
                        .build()
                    map.cameraPosition = cameraPosition

                    // Strip all default UI -- pure tactical display
                    map.uiSettings.isCompassEnabled = false
                    map.uiSettings.isAttributionEnabled = false
                    map.uiSettings.isLogoEnabled = false

                    // Draw weather gradient if nodes are available
                    if (weatherNodes.isNotEmpty()) {
                        drawGripMatrixPolyline(style, weatherNodes, gripMatrix)
                    }
                }
            }
        },
    )

    // Re-draw gradient whenever weather nodes update
    LaunchedEffect(weatherNodes) {
        if (weatherNodes.isNotEmpty()) {
            mapView.getMapAsync { map ->
                map.style?.let { style ->
                    drawGripMatrixPolyline(style, weatherNodes, gripMatrix)
                }
            }
        }
    }
}

/**
 * Resolves the MapLibre style URI using the following priority:
 *
 * 1. **Local PMTiles file** (`files/slick-corridor.pmtiles`) -- fully offline, populated by
 *    [com.slick.tactical.service.GarageSyncWorker]. Uses embedded style JSON as template.
 * 2. **Protomaps CDN PMTiles** (`BuildConfig.PMTILES_URL`) -- online fallback, configured via
 *    `local.properties`. Uses embedded style JSON as template.
 * 3. **Asset style only** (`asset://tactical-oled-style.json`) -- style loads but no tile data.
 *    The map renders background/labels only. Functional for development without PMTiles set up.
 *
 * The embedded style JSON in `assets/tactical-oled-style.json` uses `{pmtiles_url}` as a
 * placeholder which is replaced at runtime with the actual PMTiles source.
 */
fun resolveMapStyle(context: android.content.Context): String {
    val localPmtilesFile = File(context.filesDir, "slick-corridor.pmtiles")

    return when {
        // Priority 1: locally downloaded PMTiles (fully offline)
        localPmtilesFile.exists() -> {
            Timber.i("Zone2Map: using local PMTiles file (%d MB)", localPmtilesFile.length() / 1_048_576)
            buildStyleWithPmtilesUrl(context, "pmtiles://${localPmtilesFile.absolutePath}")
        }

        // Priority 2: remote PMTiles URL configured
        BuildConfig.PMTILES_URL.isNotBlank() && !BuildConfig.PMTILES_URL.contains("YOUR_BUCKET") -> {
            Timber.i("Zone2Map: using remote PMTiles URL")
            buildStyleWithPmtilesUrl(context, "pmtiles://${BuildConfig.PMTILES_URL}")
        }

        // Priority 3: asset style only (no tiles -- background + labels render, no roads)
        else -> {
            Timber.w("Zone2Map: no PMTiles configured, using asset style without tile data")
            "asset://tactical-oled-style.json"
        }
    }
}

/**
 * Reads the embedded style JSON from assets and substitutes the PMTiles source URL.
 * Writes the resolved style to a temp file and returns its URI.
 */
private fun buildStyleWithPmtilesUrl(context: android.content.Context, pmtilesUri: String): String {
    return try {
        val styleJson = context.assets.open("tactical-oled-style.json")
            .bufferedReader()
            .use { it.readText() }
            .replace("{pmtiles_url}", pmtilesUri)

        // Write resolved style to internal cache for MapLibre to load
        val cacheFile = File(context.cacheDir, "slick-map-style.json")
        cacheFile.writeText(styleJson)
        Timber.d("Zone2Map: resolved style written to %s", cacheFile.absolutePath)
        cacheFile.toURI().toString()
    } catch (e: Exception) {
        Timber.e(e, "Zone2Map: failed to build style with PMTiles URL, falling back to asset")
        "asset://tactical-oled-style.json"
    }
}

/**
 * Draws the GripMatrix weather gradient as a MapLibre LineLayer.
 *
 * Danger level → colour mapping:
 * - DRY: #9E9E9E (grey)
 * - MODERATE: #00E5FF (cyan -- Wash)
 * - HIGH: #FF9800 (amber)
 * - EXTREME: #FF5722 (Alert orange)
 *
 * GOTCHA: [lineMetrics] MUST be true on the GeoJSON source for line-gradient.
 * See slick-maplibre SKILL.md.
 */
private fun drawGripMatrixPolyline(
    style: Style,
    nodes: List<WeatherNodeEntity>,
    gripMatrix: GripMatrix,
) {
    try {
        val sourceId = "grip-matrix-source"
        val layerId = "grip-matrix-layer"

        style.removeLayer(layerId)
        style.removeSource(sourceId)

        val coordinates = nodes.joinToString(",") { "[${it.longitude},${it.latitude}]" }
        val geoJson = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coordinates]}}"""

        val source = GeoJsonSource(
            sourceId,
            geoJson,
            GeoJsonOptions().withLineMetrics(true),
        )
        style.addSource(source)

        val layer = LineLayer(layerId, sourceId).apply {
            setProperties(
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
                lineWidth(10f),
                lineGradient(buildGradientStops(nodes, gripMatrix)),
            )
        }
        style.addLayer(layer)
        Timber.d("GripMatrix polyline drawn: %d nodes", nodes.size)
    } catch (e: Exception) {
        Timber.e(e, "Failed to draw GripMatrix polyline")
    }
}

private fun buildGradientStops(
    nodes: List<WeatherNodeEntity>,
    gripMatrix: GripMatrix,
): org.maplibre.android.style.expressions.Expression {
    val totalNodes = nodes.size
    if (totalNodes <= 1) {
        return interpolate(
            linear(), lineProgress(),
            stop(0.0f, color(Color.parseColor("#9E9E9E"))),
            stop(1.0f, color(Color.parseColor("#9E9E9E"))),
        )
    }

    val stops = nodes.mapIndexed { index, node ->
        val progress = index.toFloat() / (totalNodes - 1).toFloat()
        val dangerLevel = gripMatrix.evaluateNode(node)
            .map { it.dangerLevel }
            .getOrElse { GripMatrix.DangerLevel.DRY }
        stop(progress, color(Color.parseColor(gripMatrix.dangerLevelToColor(dangerLevel))))
    }

    return interpolate(linear(), lineProgress(), *stops.toTypedArray())
}
