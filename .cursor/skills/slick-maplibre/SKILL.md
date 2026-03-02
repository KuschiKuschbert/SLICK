---
name: slick-maplibre
description: Implements MapLibre Native for SLICK. Use when working on map rendering, PMTiles offline tiles, GripMatrix gradient polyline, convoy clustering, camera anchoring, dark-mode style, or MapLibre lifecycle management.
---

# SLICK MapLibre Integration

## Purpose

GPU-accelerated vector map rendering using MapLibre Native Android SDK with Protomaps PMTiles for fully offline QLD corridor maps. No tile server fees. No network required during rides.

## Dependencies

```kotlin
implementation("org.maplibre.gl:android-sdk:11.8.0")  // verify latest
```

MapLibre must be initialized before `setContentView`:
```kotlin
MapLibre.getInstance(context)
```

## PMTiles (Protomaps Offline)

PMTiles is a single-file archive of vector tiles. Host on Cloudflare R2:

```kotlin
// In MapLibre style JSON, use PMTiles protocol handler:
// "tiles": ["pmtiles://https://r2.example.com/qld-corridor.pmtiles/{z}/{x}/{y}"]

// Download to local storage during pre-flight sync:
val localPmtilesPath = context.filesDir.absolutePath + "/maps/qld-corridor.pmtiles"

// Point style at local file when offline:
val localStyleUrl = "file://${localPmtilesPath}"
```

`GarageSyncWorker` handles delta updates on Wi-Fi + charging. Always check available storage before download.

## Tactical OLED Map Style

```json
{
  "version": 8,
  "name": "SLICK Tactical OLED",
  "sources": {
    "slick-tiles": {
      "type": "vector",
      "url": "pmtiles://path/to/qld.pmtiles"
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#000000" } },
    { "id": "roads", "type": "line", "source": "slick-tiles", "source-layer": "transportation",
      "paint": { "line-color": "#2A2A2A", "line-width": 2 } },
    { "id": "highways", "type": "line", "source": "slick-tiles", "source-layer": "transportation",
      "filter": ["==", "class", "motorway"],
      "paint": { "line-color": "#3A3A3A", "line-width": 4 } }
  ]
}
```

Minimal labels, black background, dark roads. Load from `BuildConfig.MAP_STYLE_URL`.

## GripMatrix Gradient Polyline

Requires `lineMetrics: true` on the GeoJSON source:

```kotlin
val source = GeoJsonSource(
    "route-source",
    routeGeoJson,
    GeoJsonOptions().withLineMetrics(true)  // MANDATORY for line-gradient
)
style.addSource(source)

val layer = LineLayer("route-gradient-layer", "route-source").apply {
    setProperties(
        lineCap(PROPERTY_LINE_CAP_ROUND),
        lineJoin(PROPERTY_LINE_JOIN_ROUND),
        lineWidth(8f),
        lineGradient(
            interpolate(linear(), lineProgress(),
                stop(0.0f, color(Color.parseColor("#9E9E9E"))),  // Grey (dry)
                stop(0.4f, color(Color.parseColor("#00E5FF"))),  // Cyan (rain)
                stop(0.8f, color(Color.parseColor("#FF5722"))),  // Orange (hazard)
            )
        )
    )
}
style.addLayer(layer)
```

## GeoJSON Convoy Clustering

```kotlin
// When convoy > 4 riders, enable clustering
val source = GeoJsonSource(
    "convoy-source",
    riderLocationsFeatureCollection,
    GeoJsonOptions()
        .withCluster(true)
        .withClusterMaxZoom(14)
        .withClusterRadius(50)
)
```

Individual rider icons show below zoom 14. Above 14, unclustered icons appear.

## Camera Anchoring (Shows More Road Ahead)

Rider icon at 55% from top (not center) -- shows more of the road ahead:

```kotlin
map.setFocalBearing(bearing = heading, x = screenWidth / 2f, y = screenHeight * 0.55f, duration = 200)
```

## Lifecycle Management (MANDATORY)

```kotlin
// MapView must be wired to Activity/Fragment lifecycle exactly
override fun onStart() { super.onStart(); mapView?.onStart() }
override fun onResume() { super.onResume(); mapView?.onResume() }
override fun onPause() { super.onPause(); mapView?.onPause() }
override fun onStop() { super.onStop(); mapView?.onStop() }
override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
override fun onDestroy() { super.onDestroy(); mapView?.onDestroy() }
```

Missing `onDestroy()` causes memory leaks that crash the HUD during long rides.

## Survival Mode -- Map Suspension

```kotlin
// In BatterySurvivalManager SURVIVAL_MODE:
mapView?.onPause()  // Suspends GPU rendering
mapView?.visibility = View.GONE  // Removes from layout
// Replace with SurvivalHudScreen composable
```

Do NOT call `onDestroy()` -- just pause and hide. Restoring is faster than reinitializing.

## GOTCHAS

- `lineMetrics: true` MUST be set at source creation time -- cannot be added afterward.
- MapLibre renders on the GL thread. Never call `style.addLayer()` from the UI thread; use `mapView.getMapAsync { map -> map.style?.addLayer(...) }`.
- PMTiles requires the `pmtiles://` protocol handler to be registered before map init. Use `MapLibre.addProtocolHandler("pmtiles", PmTilesHandler())`.
- Convoy GeoJSON must be updated atomically -- partial updates cause flickering. Use `(source as GeoJsonSource).setGeoJson(newCollection)`.

## Reference Files

- `ui/inflight/Zone2Map.kt`
- `engine/weather/GripMatrix.kt` (gradient computation)
- `service/GarageSyncWorker.kt` (PMTiles sync)

## RETROFIT LOG

_Updated as the map implementation evolves._
