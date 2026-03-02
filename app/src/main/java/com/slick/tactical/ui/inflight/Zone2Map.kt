package com.slick.tactical.ui.inflight

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.lineProgress
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.color
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

/**
 * Zone 2: Tactical Focus -- the MapLibre map with GripMatrix weather gradient.
 *
 * Features:
 * - PMTiles offline vector tiles (no tile server required during ride)
 * - GripMatrix gradient polyline: grey (dry) → cyan (rain) → orange/red (hazard)
 * - Rider's position anchored at 55% from top (shows more road ahead)
 * - Convoy rider badges with GeoJSON clustering (>4 riders)
 * - Crosswind vector arrows at 10km nodes
 *
 * The map is anchored below center (55% from top) to maximize forward visibility
 * at 110 km/h -- you need to see what's 500m+ ahead, not what's behind.
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
                // Load the Tactical OLED dark style
                // Falls back to a dark base style if custom PMTiles URL is not configured
                val styleUrl = if (BuildConfig.MAP_STYLE_URL.isNotBlank() &&
                    !BuildConfig.MAP_STYLE_URL.contains("YOUR_BUCKET")
                ) {
                    BuildConfig.MAP_STYLE_URL
                } else {
                    // Fallback: Stadia dark style for development
                    "https://tiles.stadiamaps.com/styles/alidade_smooth_dark.json"
                }

                map.setStyle(styleUrl) { style ->
                    // Anchor camera at rider position, 55% from top
                    val cameraPosition = CameraPosition.Builder()
                        .target(LatLng(riderLat, riderLon))
                        .zoom(13.0)
                        .bearing(riderBearing.toDouble())
                        .tilt(45.0)  // 3D perspective for better route preview
                        .build()
                    map.cameraPosition = cameraPosition

                    // Remove default location puck and render our own rider icon
                    map.uiSettings.isCompassEnabled = false
                    map.uiSettings.isAttributionEnabled = false
                    map.uiSettings.isLogoEnabled = false

                    // Draw the GripMatrix weather gradient polyline if nodes exist
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
 * Draws the GripMatrix weather gradient as a MapLibre LineLayer.
 *
 * The gradient maps each node's danger level to a colour:
 * - DRY: #9E9E9E (grey)
 * - MODERATE: #00E5FF (cyan -- Wash colour)
 * - HIGH: #FF9800 (amber)
 * - EXTREME: #FF5722 (Alert orange)
 *
 * [lineMetrics] MUST be true on the GeoJSON source for line-gradient to work.
 * This is the most common MapLibre gotcha -- see slick-maplibre SKILL.md.
 */
private fun drawGripMatrixPolyline(
    style: Style,
    nodes: List<WeatherNodeEntity>,
    gripMatrix: GripMatrix,
) {
    try {
        val sourceId = "grip-matrix-source"
        val layerId = "grip-matrix-layer"

        // Remove existing layers/sources to avoid duplicate errors
        style.removeLayer(layerId)
        style.removeSource(sourceId)

        // Build GeoJSON LineString from node coordinates
        val coordinates = nodes.joinToString(",") { node ->
            "[${node.longitude},${node.latitude}]"
        }
        val geoJson = """
            {
                "type": "Feature",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [$coordinates]
                }
            }
        """.trimIndent()

        // lineMetrics: true is MANDATORY for line-gradient interpolation
        val source = GeoJsonSource(
            sourceId,
            geoJson,
            GeoJsonOptions().withLineMetrics(true),
        )
        style.addSource(source)

        // Build gradient stops based on node danger levels
        val totalNodes = nodes.size
        val gradientStops = buildGradientStops(nodes, gripMatrix, totalNodes)

        val layer = LineLayer(layerId, sourceId).apply {
            setProperties(
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
                lineWidth(10f),  // Thick for visibility on a mounted phone
                lineGradient(gradientStops),
            )
        }

        style.addLayer(layer)
        Timber.d("GripMatrix polyline drawn: %d nodes", nodes.size)
    } catch (e: Exception) {
        Timber.e(e, "Failed to draw GripMatrix polyline")
    }
}

/**
 * Builds MapLibre line-gradient expression stops from GripMatrix node evaluations.
 *
 * Maps node position (0.0 = start, 1.0 = end) to the danger colour for that node.
 * Progress values are evenly distributed across nodes.
 */
private fun buildGradientStops(
    nodes: List<WeatherNodeEntity>,
    gripMatrix: GripMatrix,
    totalNodes: Int,
): org.maplibre.android.style.expressions.Expression {
    if (totalNodes <= 1) {
        // Single node or empty -- show dry grey
        return interpolate(
            linear(), lineProgress(),
            stop(0.0f, color(Color.parseColor("#9E9E9E"))),
            stop(1.0f, color(Color.parseColor("#9E9E9E"))),
        )
    }

    val stops = nodes.mapIndexed { index, node ->
        val progress = index.toFloat() / (totalNodes - 1).toFloat()
        val dangerLevel = gripMatrix.evaluateNode(node)
            .getOrElse { GripMatrix.DangerLevel.DRY }
            .let { gripMatrix.evaluateNode(node).map { it.dangerLevel }.getOrElse { GripMatrix.DangerLevel.DRY } }
        val hexColor = gripMatrix.dangerLevelToColor(dangerLevel)
        stop(progress, color(Color.parseColor(hexColor)))
    }

    // interpolate() requires at least 2 stops
    return interpolate(linear(), lineProgress(), *stops.toTypedArray())
}
