# SLICK Troubleshooting Log

Known errors and their fixes. **Check this before spending time debugging.**
Format: `Symptom | Root Cause | Fix | Derived Rule`

---

## How to Use

**Before debugging**: Search this file for your error symptom. If found, apply the documented fix.

**After debugging**: Add a row for every bug that took more than one attempt to fix.

```markdown
| Symptom | Root Cause | Fix | Derived Rule |
|---------|-----------|-----|--------------|
| Brief description of what went wrong | What actually caused it | What resolved it | Rule to prevent recurrence |
```

---

## Known Issues

| Symptom | Root Cause | Fix | Derived Rule |
|---------|-----------|-----|--------------|
| `gotrue-kt` not found | Supabase renamed `gotrue-kt` to `auth-kt` in v3 | Use `auth-kt` in libs.versions.toml + import `io.github.jan.supabase.auth.Auth` in AppModule | Never use `gotrue-kt` in Supabase v3+ |
| Kotlin version mismatch (2.1.x vs 2.3.x) | Supabase 3.4.1 + Ktor 3.4.0 require Kotlin 2.3.10 but KSP doesn't support 2.3.x yet | Downgrade to Supabase 3.1.4 + Ktor 3.1.2 + Kotlin 2.1.20 + KSP 2.1.20-1.0.32 | Pin Supabase ≤ 3.1.x until KSP supports Kotlin 2.3.x |
| `androidx.browser:1.9.0` requires compileSdk 36 | Supabase auth-kt pulls in browser dependency | Upgrade AGP 8.8.0 → 8.9.3, compileSdk 35 → 36 | Check AGP compatibility when adding Supabase |
| `ServiceState.newFromBundle()` unresolved | API removed in Android API 34 | Read state int directly: `intent.extras?.getInt("state", STATE_OUT_OF_SERVICE)` | Never use ServiceState.newFromBundle() -- it's gone in API 34+ |
| `OPEN_METEO_API_KEY=` causes `BuildConfig.java: illegal start of expression` | Empty value in local.properties generates `= ;` (no value) in Java BuildConfig | Add to `ignoreList` in secrets {} block in app/build.gradle.kts | Always put a value or ignore empty keys in Secrets Gradle Plugin |
| `return` inside `= try {}` expression body | Kotlin 2.1.x disallows `return` in expression body functions | Convert `fun foo(): Result<X> = try { return ... }` to block body `fun foo(): Result<X> { return try { ... } }` | Never use early return inside expression body try blocks |
| KSP `KaInvalidLifetimeOwnerAccessException` | KSP version doesn't match Kotlin version | KSP version must exactly match Kotlin: `2.1.20-1.0.32` for Kotlin `2.1.20` | Always check KSP releases page for matching version |
| Haversine Kawana→Yeppoon distance test wrong | Straight-line is 467.6km not 380km (380 is road distance approximation) | Update test range to `440.0..500.0` | Haversine = great-circle (shortest path), road distance is always longer |

---

## Categories

Issues will be logged here as they arise during development:

- **SQLCipher / Room**: DB encryption, schema migrations, key timing
- **Nearby Connections**: OEM permissions, range, reconnection
- **MapLibre**: GL thread violations, lifecycle, PMTiles protocol
- **Open-Meteo**: BOM model, `past_minutely_15` indexing, free tier limits
- **AES-256-GCM**: IV handling, key alias, GCM auth tag
- **Foreground Service**: OEM battery optimizers, `foregroundServiceType`
- **Activity Recognition**: Transition delays, IN_VEHICLE detection accuracy
- **Bluetooth SCO**: Cardo/Sena firmware variations, audio focus conflicts
- **GPS Batching**: `setMaxUpdateDelayMillis` not honored on some OEMs

---

_This log grows as the project grows. Every fix documented here saves hours in future sessions._
