---
name: slick-service-credentials
description: Maps all SLICK API keys, service URLs, dashboard links, and environment variables. Use at the start of any conversation involving deployment, env vars, API integration, or service configuration. Read this before asking the user where keys are.
---

# SLICK Service Credentials

## SELF-UPDATE RULES (MANDATORY)

This skill is self-improving. Any AI session that adds env vars, integrates a new service, or changes service config **MUST update this file** before ending the conversation.

Update triggers:
1. New env var added to `local.properties` → add to env var inventory below
2. New service integrated → add Quick Reference row + Service Details section
3. Supabase schema changed → update Supabase section
4. P2P service ID changed → update Nearby Connections section

---

## Quick Reference

| Service | Purpose | Env Key |
|---------|---------|---------|
| Supabase | Cloud sync, SOS relay, convoy sessions | `SUPABASE_URL`, `SUPABASE_ANON_KEY` |
| Open-Meteo | Weather engine (BOM models, free tier) | `OPEN_METEO_BASE_URL`, `OPEN_METEO_API_KEY` |
| Valhalla | Route polyline generation | `VALHALLA_BASE_URL` |
| Protomaps | PMTiles offline map hosting (Cloudflare R2) | `PMTILES_URL` |
| MapLibre Style | Tactical OLED style JSON | `MAP_STYLE_URL` |
| Nearby Connections | P2P mesh identifier | `P2P_SERVICE_ID` |
| Android Keystore | SQLCipher + AES key management | `SQLCIPHER_PASSPHRASE_ALIAS` (runtime only) |

## Supabase Project Details

- **Project name**: slick
- **Project ref**: `sqddpmhjpzgzggbsqgpr`
- **API URL**: `https://sqddpmhjpzgzggbsqgpr.supabase.co`
- **Dashboard**: https://supabase.com/dashboard/project/sqddpmhjpzgzggbsqgpr
- **Anon key**: stored in `local.properties` (gitignored) -- safe for Android app
- **Service role key**: GitHub Actions secret `SUPABASE_SERVICE_ROLE_KEY` ONLY -- never in Android app
- **DB password**: not stored in any project file -- keep in a password manager

---

## Environment Variables (`local.properties`)

```properties
# Supabase (SLICK dedicated project -- NOT the PrepFlow project)
SUPABASE_URL=https://YOUR_SLICK_PROJECT.supabase.co
SUPABASE_ANON_KEY=eyJ...

# Open-Meteo (free tier -- no key needed initially; add if upgrading to commercial)
OPEN_METEO_BASE_URL=https://api.open-meteo.com
OPEN_METEO_API_KEY=

# Valhalla routing (Stadia Maps hosted or self-hosted)
VALHALLA_BASE_URL=https://valhalla1.openstreetmap.de

# Protomaps PMTiles (Cloudflare R2 or S3)
PMTILES_URL=https://your-r2-bucket.r2.dev/qld-corridor.pmtiles

# MapLibre Tactical OLED style JSON
MAP_STYLE_URL=https://your-r2-bucket.r2.dev/slick-tactical-style.json

# Nearby Connections service identifier
P2P_SERVICE_ID=com.slick.tactical.mesh.v1

# Android Keystore alias (NOT a secret -- just an alias string)
SQLCIPHER_PASSPHRASE_ALIAS=slick_db_key_v1
```

---

## Service Details

### Supabase (SLICK Project)

- **Separate project from PrepFlow** -- do not share
- **Tables**: `convoy_sessions`, `rider_breadcrumbs`, `sos_alerts`
- **Auth**: Supabase Auth (anonymous sign-in for device identity, no email required)
- **RLS**: Enabled on all tables. Convoy data requires active membership + time-bound access (24h)
- **Dashboard**: https://supabase.com/dashboard (create new project for SLICK)
- **Service Role Key**: CI/GitHub Actions only -- never in the Android app

### Open-Meteo

- **Free tier**: up to 10,000 API calls/day -- sufficient for development
- **Model**: `bom_access_global` for Australian Bureau of Meteorology accuracy
- **Key endpoint**: `GET /v1/forecast` with `past_minutely_15=2` for Asphalt Memory
- **No key required** on free tier -- `OPEN_METEO_API_KEY` left empty unless upgrading

### Valhalla

- **Default endpoint**: `https://valhalla1.openstreetmap.de` (public, free, community-run)
- **Profile**: `motorcycle` for moto-appropriate routing
- **Self-hosted option**: Docker image `ghcr.io/valhalla/valhalla:latest` for production reliability

### Protomaps / PMTiles

- **Source**: https://protomaps.com/downloads/osm (download QLD region)
- **Hosting**: Cloudflare R2 (free egress tier sufficient for app downloads)
- **File naming**: `qld-kawana-yeppoon-{date}.pmtiles`
- **Update strategy**: Monthly, via `GarageSyncWorker` (Wi-Fi + charging only)

### Nearby Connections

- **No API key** required -- uses Google Play Services
- **Service ID** must match exactly across all SLICK devices for handshake to succeed
- **Current ID**: `com.slick.tactical.mesh.v1` (increment version if protocol changes)

---

## How to Add New Keys

1. Add to `local.properties` locally
2. Add to GitHub Actions secrets for CI builds
3. **MANDATORY**: Update THIS skill file with the new key name + service section
4. Document in `docs/TROUBLESHOOTING_LOG.md` if any setup issues arise
