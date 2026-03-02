---
name: slick-weather-engine
description: Implements SLICK GripMatrix weather engine. Use when working on RouteForecaster, GripMatrix, TwilightMatrix, SolarGlareVector, Open-Meteo API integration, Haversine node slicing, Asphalt Memory, or crosswind calculations.
---

# SLICK Weather Engine

## Purpose

Transforms a raw GPS polyline into a time-indexed array of 10km weather nodes. Each node carries: crosswind vector, residual moisture probability, twilight hazard elevation, solar glare risk, and topographic fuel drain.

## Open-Meteo API

**Base URL**: `BuildConfig.OPEN_METEO_BASE_URL` (default: `https://api.open-meteo.com`)

**Key endpoint**: `GET /v1/forecast`

Required query params for SLICK:
```
latitude={lat}
longitude={lon}
hourly=temperature_2m,precipitation,windspeed_10m,winddirection_10m,visibility
minutely_15=precipitation
past_minutely_15=2         ← pulls T-30min and T-15min history (Asphalt Memory)
forecast_minutely_15=1     ← T-0 (current)
timezone=Australia/Brisbane
models=bom_access_global   ← Australian BOM data, mandatory for QLD accuracy
daily=sunrise,sunset       ← for Twilight Matrix
```

**Asphalt Memory fields**: `minutely_15.precipitation[0]` = T-30min, `[1]` = T-15min, `[2]` = now

## Haversine Formula

Earth radius = 6371.0 km. Use for all distance calculations -- no external dependency.

```kotlin
fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return r * 2 * asin(sqrt(a))
}
```

## Crosswind Formula

```kotlin
// crosswindKmh = windSpeedKmh * |sin(windDir - bearing)|
val angleDiff = Math.toRadians(abs(weatherData.windDirDeg - node.bearingDeg))
val crosswindKmh = abs(weatherData.windSpeedKmh * sin(angleDiff))

// Danger thresholds
val danger = when {
    crosswindKmh < 15.0 -> DangerLevel.LOW
    crosswindKmh < 35.0 -> DangerLevel.MODERATE
    else -> DangerLevel.EXTREME  // Warn 2 nodes ahead
}
```

## Asphalt Memory (Residual Moisture)

```kotlin
val pastRainMm = precipT30 + precipT15
val isCurrentlyRaining = precipNow > 0.0
val residualWetness = !isCurrentlyRaining && pastRainMm > 0.2

val status = when {
    isCurrentlyRaining -> "Active Rain - Reduced Grip"
    residualWetness -> "Residual Wetness - Oil Slick Probability Elevated"
    else -> "Dry - Optimal Grip"
}
```

**Honest constraint**: 1km weather resolution. Label as "Residual Risk Index", not "surface sensor".

## Twilight Matrix

```kotlin
// Elevate hazard during civil twilight -- macropod peak activity
val sunriseTime = LocalTime.parse(daily.sunrise.first(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
val sunsetTime = LocalTime.parse(daily.sunset.first(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
val twilightWindowMinutes = 30L

val isDawn = nodeArrival.isAfter(sunriseTime.minusMinutes(twilightWindowMinutes))
          && nodeArrival.isBefore(sunriseTime.plusMinutes(twilightWindowMinutes))
val isDusk = nodeArrival.isAfter(sunsetTime.minusMinutes(twilightWindowMinutes))
          && nodeArrival.isBefore(sunsetTime.plusMinutes(twilightWindowMinutes))

if (isDawn || isDusk) node.hazardLevel = node.hazardLevel.elevate()
```

## Solar Glare Vector

Warn rider 10 minutes before sun hits the visor directly.

```kotlin
// High glare when sun is low AND within 20° of route bearing
val isGlareRisk = sunElevation < 15.0 && abs(sunAzimuth - node.bearingDeg) < 20.0
```

Solar position calculation uses `SunCalc` or a lightweight astronomical formula -- no heavy library.

## GripMatrix Color Gradient (MapLibre)

```kotlin
// line-gradient expression for MapLibre (requires lineMetrics: true on source)
// 0.0 = route start, 1.0 = route end
lineGradient(
    interpolate(linear(), lineProgress(),
        stop(0.0f, color(Color.parseColor("#9E9E9E"))),  // Grey = dry
        stop(0.4f, color(Color.parseColor("#00E5FF"))),  // Cyan = rain
        stop(0.8f, color(Color.parseColor("#FF5722"))),  // Orange = hazard
    )
)
```

Actual stop values computed per-node by `GripMatrix.computeGradientStops()`.

## GOTCHAS

- `past_minutely_15=2` returns the **last 2 intervals** (T-30 and T-15), not the last 2 minutes. Array index 0 = oldest.
- BOM model requires `timezone=Australia/Brisbane` -- default UTC gives wrong historical windows.
- If API returns empty arrays, fall back to last cached Room data -- never crash the HUD.
- Haversine gives "as the crow flies" -- it does not account for road curves. Actual road distance will always be slightly longer. Route polyline slicing handles this by accumulating segment distances.

## Reference Files

- `engine/weather/RouteForecaster.kt`
- `engine/weather/GripMatrix.kt`
- `engine/weather/TwilightMatrix.kt`
- `engine/weather/SolarGlareVector.kt`
- `data/remote/OpenMeteoClient.kt`
- `data/local/dao/WeatherNodeDao.kt`

## RETROFIT LOG

_Updated as the engine evolves during development._
