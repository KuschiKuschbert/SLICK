# SLICK -- New Session Quickstart

Read this before writing any code. ~15 minutes.

---

## 1. What SLICK Is

SLICK (Situational Location & Integrated Convoy) is a **safety-critical Android motorcycle navigation app**. It transforms a standard Android phone into a tactical telemetry HUD for long-distance touring at highway speeds (110 km/h).

**Two primary features**:
1. **GripMatrix™** -- Physics-based weather engine: crosswind vectors, residual moisture (Asphalt Memory), twilight wildlife hazards, solar glare
2. **P2P Convoy Mesh** -- Encrypted offline group tracking via Nearby Connections (no external hardware)

**Target user**: Regional QLD motorcycle riders (Kawana to Yeppoon corridor).

---

## 2. Read These First (In Order)

1. [architecture.mdc](.cursor/rules/architecture.mdc) -- Expendable Layer vs Engine Room (5 min)
2. [titan-protocols.mdc](.cursor/rules/titan-protocols.mdc) -- 5 engineering philosophies (3 min)
3. [kotlin-standards.mdc](.cursor/rules/kotlin-standards.mdc) -- Result<T>, metric, 24h, Timber (3 min)
4. [security-protocols.mdc](.cursor/rules/security-protocols.mdc) -- AES, SQLCipher, no PII (2 min)
5. [brand-voice.mdc](.cursor/rules/brand-voice.mdc) -- prohibited words, required terminology (2 min)

Total: ~15 minutes. This prevents the most common mistakes.

---

## 3. Key Non-Obvious Facts

- **No dashcam** -- explicitly banned by the Musk Protocol (thermal risk)
- **No Material You** -- OLED palette hardcoded in-flight (`#000000`, `#FF5722`, `#00E5FF`)
- **Monospace for all numbers** -- prevents horizontal jitter at speed (speed, ETA, distance)
- **P2P range is ~30-50m** -- market as "close-formation link", never promise 100m
- **Asphalt Memory is 1km resolution** -- it's a "Residual Risk Index", not a surface sensor
- **No continuous wake word** -- Tap-to-Talk via `MediaSessionCompat` only (battery constraint)
- **Protobuf, not JSON** -- 5-10x smaller payloads; critical for BLE's 100-byte constraint
- **Activity Recognition has 30-90s delay** -- designed for fuel stops, not red lights

---

## 4. Development Workflow

```bash
# Start a new feature
git checkout -b feature/my-feature-name

# Before every commit
./gradlew ktlintCheck detekt test

# Auto-fix style issues
./gradlew ktlintFormat

# Build check
./gradlew assembleDebug
```

Never commit directly to `main`. Feature branches only.

---

## 5. Before Your First Commit

- [ ] All new functions return `Result<T>` for external operations
- [ ] No `Log.*` or `println` -- use Timber only
- [ ] All numeric literals are named constants in `SlickConstants`
- [ ] All timestamps use 24h format (HH:mm)
- [ ] All measurements use metric (km/h, °C, mm, km)
- [ ] File sizes within limits (Services 400, Composables 250, ViewModels 300)
- [ ] KDoc on all public functions
- [ ] `./gradlew ktlintCheck detekt test` passes

---

## 6. Getting Unstuck

1. `docs/TROUBLESHOOTING_LOG.md` -- check for the exact error first
2. Domain `engine/*/SKILL.md` GOTCHAS -- known traps per subsystem
3. `.cursor/skills/slick-error-recovery/SKILL.md` -- structured recovery protocol
4. Apply the Ramsay Protocol: wrap everything in `Result<T>`, add meaningful error messages

---

## 7. Skills Index

See `.cursor/SKILLS_INDEX.md` for the full list. Most important for new features:

- Engine work → relevant `engine/*/SKILL.md` (created as each phase builds)
- Weather → [slick-weather-engine](.cursor/skills/slick-weather-engine/SKILL.md)
- P2P → [slick-p2p-mesh](.cursor/skills/slick-p2p-mesh/SKILL.md)
- Maps → [slick-maplibre](.cursor/skills/slick-maplibre/SKILL.md)
- State → [slick-orbit-mvi](.cursor/skills/slick-orbit-mvi/SKILL.md)
- Credentials → [slick-service-credentials](.cursor/skills/slick-service-credentials/SKILL.md)
