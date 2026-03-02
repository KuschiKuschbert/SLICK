# SLICK -- AI Agent Instructions

Quick reference for every AI session. Read this before writing any code.

---

## Project Overview

**SLICK** (Situational Location & Integrated Convoy) is a safety-critical Android motorcycle navigation app. It transforms a standard Android phone into a rugged tactical telemetry HUD for long-distance touring at 110 km/h.

- **Target corridor**: Kawana to Yeppoon, QLD, Australia
- **Primary features**: GripMatrix weather engine, encrypted P2P convoy mesh
- **Package**: `com.slick.tactical`
- **Stack**: Kotlin, Jetpack Compose, Orbit-MVI, Hilt, Room + SQLCipher, MapLibre Native, Google Nearby Connections, Supabase

---

## Critical Reminders

1. **Feature branches only** -- never commit directly to `main`
2. **Result<T> mandatory** -- all network, DB, sensor, and math operations
3. **Strictly metric** -- Celsius, km/h, mm, meters. No imperial. No exceptions.
4. **Strictly 24h time** -- HH:mm format everywhere. No AM/PM.
5. **Timber only** -- no `Log.*` or `println` anywhere
6. **No dashcam** -- CameraX encoding banned by Musk Protocol
7. **No Material You** -- OLED palette is hardcoded in-flight
8. **No magic numbers** -- all constants in `SlickConstants` or companion objects
9. **File size limits** -- Services 400, Composables 250, ViewModels 300, Utils 150

---

## Documentation Index

### Always-Loaded Rules (apply to every session)

- [titan-protocols.mdc](.cursor/rules/titan-protocols.mdc) -- 5 Titan Protocols (Ramsay/Jobs/Musk/Buffett/Escoffier)
- [security-protocols.mdc](.cursor/rules/security-protocols.mdc) -- AES-256-GCM, SQLCipher, Keystore, no PII
- [architecture.mdc](.cursor/rules/architecture.mdc) -- Expendable vs Engine Room, Hilt, package structure
- [brand-voice.mdc](.cursor/rules/brand-voice.mdc) -- tactical tone, prohibited/required terms
- [fixfirst.mdc](.cursor/rules/fixfirst.mdc) -- never disable, always fix the root cause
- [context-hygiene.mdc](.cursor/rules/context-hygiene.mdc) -- handoff checkpoints, session-end checklist, bug memory
- [cleanup.mdc](.cursor/rules/cleanup.mdc) -- no magic numbers, Timber only, file size limits

### File-Scoped Rules (load when editing relevant files)

- [kotlin-standards.mdc](.cursor/rules/kotlin-standards.mdc) -- `**/*.kt`
- [ui-ux-hud.mdc](.cursor/rules/ui-ux-hud.mdc) -- `**/ui/**/*.kt`
- [testing-standards.mdc](.cursor/rules/testing-standards.mdc) -- `**/test/**/*.kt`
- [android-manifest.mdc](.cursor/rules/android-manifest.mdc) -- `**/AndroidManifest.xml`

### Domain Skills

- [slick-weather-engine](.cursor/skills/slick-weather-engine/SKILL.md) -- GripMatrix, Open-Meteo, Haversine, crosswind
- [slick-p2p-mesh](.cursor/skills/slick-p2p-mesh/SKILL.md) -- Nearby Connections, BLE, convoy roles, >4 Protocol
- [slick-maplibre](.cursor/skills/slick-maplibre/SKILL.md) -- MapLibre Native, PMTiles, line-gradient, clustering
- [slick-orbit-mvi](.cursor/skills/slick-orbit-mvi/SKILL.md) -- Orbit-MVI containers, multi-stream inputs
- [slick-service-credentials](.cursor/skills/slick-service-credentials/SKILL.md) -- API keys, service URLs (read this first)

### Process Skills (development discipline)

- [slick-leave-better](.cursor/skills/slick-leave-better/SKILL.md) -- every touch improves the codebase
- [slick-guardian](.cursor/skills/slick-guardian/SKILL.md) -- quality gate before claiming done
- [slick-craft](.cursor/skills/slick-craft/SKILL.md) -- UI must feel finished
- [slick-self-improve](.cursor/skills/slick-self-improve/SKILL.md) -- document every fix
- [slick-say-no](.cursor/skills/slick-say-no/SKILL.md) -- challenge scope, prefer reuse
- [slick-error-recovery](.cursor/skills/slick-error-recovery/SKILL.md) -- check known fixes first

### Supporting Documentation

- [ONBOARDING.md](.cursor/ONBOARDING.md) -- 15-minute quickstart for new sessions
- [SKILLS_INDEX.md](.cursor/SKILLS_INDEX.md) -- master index of all skills
- [TECH_DEBT.md](.cursor/TECH_DEBT.md) -- tracked debt backlog
- [TROUBLESHOOTING_LOG.md](docs/TROUBLESHOOTING_LOG.md) -- known errors and fixes
- [MEMORY.md](docs/brain/MEMORY.md) -- brief lessons learned

---

## Tech Stack

| Concern | Technology |
|---------|-----------|
| Language | Kotlin (latest stable) |
| Architecture | Orbit-MVI + Coroutines + Flow |
| DI | Hilt |
| Local DB | Room + SQLCipher (AES-256) |
| Maps | MapLibre Native + Protomaps PMTiles |
| Routing | Valhalla |
| P2P Mesh | Google Nearby Connections (P2P_CLUSTER) |
| Cloud | Supabase (PostgreSQL + Realtime + RLS) |
| Serialization | Protobuf (protobuf-kotlin-lite) |
| Weather | Open-Meteo API (BOM ACCESS-G models) |
| POIs | Overpass API (OpenStreetMap) |
| Location | FusedLocationProviderClient (hardware-batched) |
| Audio | AudioManager + TextToSpeech + MediaSessionCompat |
| Crypto | Android Keystore + AES-256-GCM |
| Logging | Timber |
| Testing | JUnit 5 + MockK + Turbine + Compose UI Test |
| CI/CD | GitHub Actions |

---

## Essential Gradle Commands

```bash
# Build
./gradlew assembleDebug

# All checks (run before commit)
./gradlew ktlintCheck detekt test assembleDebug

# Auto-fix style
./gradlew ktlintFormat

# Unit tests only
./gradlew test

# Single test class
./gradlew test --tests "com.slick.tactical.engine.weather.RouteForecasterTest"
```

---

## Architecture Summary

```
EXPENDABLE LAYER (UI -- can be killed)
  Jetpack Compose, MapLibre Renderer, HUD Zones 1-3
  → Observes StateFlow only. Never writes DB or calls network.

ENGINE ROOM (ConvoyForegroundService -- must survive)
  GPS, IMU, P2P Mesh, TTS/SCO, Weather Engine, Signal Recovery
  → Room/SQLCipher is single source of truth
```

---

## Kotlin Gotchas

- All time: `LocalTime` with `DateTimeFormatter.ofPattern("HH:mm")` -- never `SimpleDateFormat`
- All distances: `Double` in km -- never Int, never miles
- All temperatures: `Double` in Celsius -- never Fahrenheit
- `Result<T>`: use `getOrElse { return Result.failure(it) }` for clean propagation
- Coroutines: `withContext(Dispatchers.IO)` for all DB and network operations
- Room + SQLCipher: always use `SupportFactory` -- never plain `RoomDatabase.Builder`

---

## Getting Unstuck

1. Check `docs/TROUBLESHOOTING_LOG.md` -- has this error been seen before?
2. Check the domain `SKILL.md` GOTCHAS section for the relevant engine
3. Check `.cursor/rules/` for a pattern that applies
4. Apply the Ramsay Protocol: wrap in `Result<T>`, add proper error message
5. Log new findings in `docs/TROUBLESHOOTING_LOG.md`
