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
| _None yet -- log added during Phase 0_ | | | |

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
