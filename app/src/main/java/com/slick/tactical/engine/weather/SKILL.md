---
name: engine-weather
description: Domain knowledge for the SLICK weather engine subsystem. Use when working on RouteForecaster, GripMatrix, TwilightMatrix, SolarGlareVector, or OpenMeteoClient in this directory.
---

# Weather Engine Domain

## Files in this package

| File | Purpose |
|------|---------|
| `RouteForecaster.kt` | Slices polyline into 10km nodes via Haversine. Calculates 24h ETAs. |
| `GripMatrix.kt` | Evaluates crosswind + Asphalt Memory per node. Returns DangerLevel + colour. |
| `TwilightMatrix.kt` | Flags nodes within ±30min of sunrise/sunset for macropod hazard. |
| `SolarGlareVector.kt` | Detects solar glare risk (low sun + aligned with bearing). |

## Key Invariants

- All nodes must have `estimatedArrival24h` in `HH:mm` format (never AM/PM)
- Crosswind is always positive (`abs()` applied) -- never negative km/h
- Haversine uses `EARTH_RADIUS_KM = 6371.0` -- no substitution
- GripMatrix colour output: DRY=#9E9E9E, MODERATE=#00E5FF, HIGH=#FF9800, EXTREME=#FF5722

## GOTCHAS

_Updated as issues are discovered during development._
