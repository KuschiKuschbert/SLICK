# SLICK Memory -- Brief Lessons Learned

Format: `[YYYY-MM-DD] **Topic**: Lesson`

Quick-reference for patterns discovered during development. Add here when something non-obvious is learned.

---

## Architecture Decisions

[2026-03-02] **Architecture**: Chose Hilt over Koin because Hilt provides compile-time DI verification -- critical for a foreground-service-heavy app where DI errors surface at runtime.

[2026-03-02] **Serialization**: Chose Protobuf over JSON for P2P packets. At 100-byte target per heartbeat, Protobuf is 5-10x smaller than equivalent JSON. This is the difference between fitting in a BLE payload and not.

[2026-03-02] **Routing**: Chose Valhalla over GraphHopper. Valhalla is fully open-source with a `motorcycle` profile, self-hostable, and the Stadia Maps hosted endpoint is free for development volume.

[2026-03-02] **Map tiles**: Chose Protomaps PMTiles over MapTiler/Stadia tile hosting. Single-file download eliminates per-tile-request fees entirely. QLD corridor fits in ~300MB. Host on Cloudflare R2 (free egress).

[2026-03-02] **Database**: Chose SQLCipher over EncryptedSharedPreferences for location history. SQLCipher AES-256 is the standard for encrypted SQLite. The Android Keystore backs the passphrase -- never stored in plain text.

## Weather Engine

[2026-03-02] **Open-Meteo BOM Model**: Must use `models=bom_access_global` for accurate QLD data. Default model gives European-tuned forecasts that miss coastal Queensland microclimate patterns.

[2026-03-02] **Asphalt Memory**: `past_minutely_15=2` returns the last 2 x 15-minute intervals. Array index 0 = T-30min, index 1 = T-15min. This is the window for "residual wetness" -- rain that stopped but road surface still wet.

## UI/UX

[2026-03-02] **Monospace for numbers**: SansSerif digits have variable widths ('1' is narrower than '8'). At engine vibration frequencies, proportional fonts cause horizontal jitter that makes speed unreadable. Monospace eliminates this.

[2026-03-02] **Zone 2 camera anchor**: User icon at 55% from top (not 50%) shows ~20% more road ahead. At 110 km/h, that extra lookahead is ~100m of reaction distance.

## P2P Mesh

[2026-03-02] **Realistic P2P range**: Nearby Connections Wi-Fi Direct typically achieves 30-50m in the field on a motorcycle. Never promise 100m -- that's open-field lab data. Metal frames, engine interference, and cross-traffic reduce it significantly.

[2026-03-02] **>4 Protocol**: MapLibre GeoJSON clustering must activate simultaneously with broadcast throttling. Throttling without clustering means stale icons freeze in wrong positions, confusing the Lead rider.

---

_Add new lessons here as they're learned. Keep entries brief -- this is a quick-reference, not documentation._
