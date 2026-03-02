---
name: data-local
description: Domain knowledge for SLICK local Room+SQLCipher database. Use when working on SlickDatabase, DAOs, or entities in this directory.
---

# Local Database Domain

## Files in this package

| File | Purpose |
|------|---------|
| `SlickDatabase.kt` | Room database with SQLCipher encryption. Version 1. |
| `entity/WeatherNodeEntity.kt` | GripMatrix node with all weather fields. |
| `entity/RiderBreadcrumbEntity.kt` | Offline GPS breadcrumbs pending Supabase sync. |
| `entity/ShelterEntity.kt` | Emergency haven POIs from Overpass API. |
| `dao/WeatherNodeDao.kt` | Observe + insert weather nodes. |
| `dao/RiderBreadcrumbDao.kt` | Insert, query pending, mark synced, purge. |
| `dao/ShelterDao.kt` | Query by corridor ID. |

## Key Invariants

- `estimatedArrival24h` fields: always HH:mm (24h), never AM/PM
- All `Double` fields in metric units (km, km/h, °C, mm)
- `RiderBreadcrumbEntity.synced` must be set to `true` before `deleteSynced()` is called
- Never call `clearAllNodes()` before replacing with new data in the same transaction

## GOTCHAS

- SQLCipher passphrase must NOT be retrieved in `Application.onCreate()`. Keystore is not available until first user unlock. Use lazy initialization in `DataModule.getOrCreateDatabasePassphrase()`.
- Room schema changes require incrementing `version` in `@Database` AND providing a migration. `fallbackToDestructiveMigrationOnDowngrade()` is set but not `fallbackToDestructiveMigration()` -- upgrade paths must be explicit.
