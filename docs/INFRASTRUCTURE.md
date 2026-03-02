# SLICK Infrastructure Setup

Manual setup steps required before Phase 1 coding can begin. Complete these in order.

---

## 1. Supabase Project (SLICK Dedicated)

Create a **new** Supabase project separate from PrepFlow.

**Dashboard**: https://supabase.com/dashboard

### Tables to Create

```sql
-- Convoy sessions (multi-tenant, time-bound)
CREATE TABLE convoy_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by UUID NOT NULL,             -- rider_id of Lead
    member_ids UUID[] NOT NULL DEFAULT '{}',
    destination_lat DOUBLE PRECISION,
    destination_lon DOUBLE PRECISION,
    started_at TIMESTAMPTZ DEFAULT now(),
    ended_at TIMESTAMPTZ,
    convoy_code VARCHAR(8) UNIQUE         -- QR code value
);

-- GPS breadcrumbs (offline buffer, flushed on signal recovery)
CREATE TABLE rider_breadcrumbs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rider_id UUID NOT NULL,
    convoy_id UUID REFERENCES convoy_sessions(id),
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    speed_kmh DOUBLE PRECISION,
    timestamp_24h VARCHAR(8) NOT NULL,    -- HH:mm:ss
    recorded_at TIMESTAMPTZ DEFAULT now(),
    synced BOOLEAN DEFAULT false
);

-- SOS alerts
CREATE TABLE sos_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rider_id UUID NOT NULL,
    convoy_id UUID REFERENCES convoy_sessions(id),
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    triggered_at TIMESTAMPTZ DEFAULT now(),
    cancelled_at TIMESTAMPTZ,
    resolved BOOLEAN DEFAULT false
);
```

### Row Level Security Policies

```sql
-- Enable RLS on all tables
ALTER TABLE convoy_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE rider_breadcrumbs ENABLE ROW LEVEL SECURITY;
ALTER TABLE sos_alerts ENABLE ROW LEVEL SECURITY;

-- Convoy sessions: readable by members only
CREATE POLICY "convoy_member_read" ON convoy_sessions
    FOR SELECT USING (auth.uid() = ANY(member_ids) OR auth.uid() = created_by);

CREATE POLICY "convoy_creator_insert" ON convoy_sessions
    FOR INSERT WITH CHECK (auth.uid() = created_by);

-- Breadcrumbs: rider can write own, convoy members can read
CREATE POLICY "rider_breadcrumb_insert" ON rider_breadcrumbs
    FOR INSERT WITH CHECK (auth.uid() = rider_id);

CREATE POLICY "convoy_member_breadcrumb_read" ON rider_breadcrumbs
    FOR SELECT USING (
        auth.uid() = rider_id
        OR EXISTS (
            SELECT 1 FROM convoy_sessions
            WHERE id = rider_breadcrumbs.convoy_id
            AND auth.uid() = ANY(member_ids)
            AND (ended_at IS NULL OR ended_at > now() - INTERVAL '24 hours')
        )
    );

-- SOS: rider can write own, convoy members can read
CREATE POLICY "rider_sos_insert" ON sos_alerts
    FOR INSERT WITH CHECK (auth.uid() = rider_id);

CREATE POLICY "convoy_member_sos_read" ON sos_alerts
    FOR SELECT USING (
        auth.uid() = rider_id
        OR EXISTS (
            SELECT 1 FROM convoy_sessions
            WHERE id = sos_alerts.convoy_id
            AND auth.uid() = ANY(member_ids)
        )
    );
```

After creating: copy `SUPABASE_URL` and `SUPABASE_ANON_KEY` into `local.properties`.

---

## 2. Valhalla Routing Endpoint

**Option A (Development -- free)**: Use public Stadia Maps Valhalla endpoint:
```
VALHALLA_BASE_URL=https://valhalla1.openstreetmap.de
```

**Option B (Production)**: Self-hosted Docker:
```bash
docker run -d -p 8002:8002 ghcr.io/valhalla/valhalla:latest
# VALHALLA_BASE_URL=http://localhost:8002
```

Test with motorcycle profile:
```bash
curl "https://valhalla1.openstreetmap.de/route?json={\"locations\":[{\"lat\":-26.73,\"lon\":153.12},{\"lat\":-23.13,\"lon\":150.74}],\"costing\":\"motorcycle\"}"
```

---

## 3. Protomaps PMTiles

**Download QLD region**: https://protomaps.com/downloads/osm

Select the bounding box covering:
- South: -28.0 (south of Brisbane)
- North: -22.0 (north of Yeppoon)
- West: 149.0
- East: 154.0

Expected file size: ~200-400MB for this corridor.

**Host on Cloudflare R2**:
```bash
# Install wrangler: npm install -g wrangler
wrangler r2 bucket create slick-maps
wrangler r2 object put slick-maps/qld-corridor.pmtiles --file qld-corridor.pmtiles
```

Set `PMTILES_URL` in `local.properties` to the public R2 URL.

---

## 4. MapLibre Tactical OLED Style

Create `slick-tactical-style.json` and upload to R2. Minimal dark style:

```json
{
  "version": 8,
  "name": "SLICK Tactical OLED",
  "sources": {
    "slick": {
      "type": "vector",
      "url": "pmtiles://https://YOUR_R2_URL/qld-corridor.pmtiles"
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#000000" } },
    { "id": "water", "type": "fill", "source": "slick", "source-layer": "water",
      "paint": { "fill-color": "#0A1628" } },
    { "id": "roads-minor", "type": "line", "source": "slick", "source-layer": "transportation",
      "filter": ["!in", "class", "motorway", "trunk", "primary"],
      "paint": { "line-color": "#1C1C1C", "line-width": 1.5 } },
    { "id": "roads-major", "type": "line", "source": "slick", "source-layer": "transportation",
      "filter": ["in", "class", "primary", "trunk"],
      "paint": { "line-color": "#2C2C2C", "line-width": 3 } },
    { "id": "highways", "type": "line", "source": "slick", "source-layer": "transportation",
      "filter": ["==", "class", "motorway"],
      "paint": { "line-color": "#3C3C3C", "line-width": 5 } },
    { "id": "labels-place", "type": "symbol", "source": "slick", "source-layer": "place",
      "filter": ["in", "class", "city", "town"],
      "layout": { "text-field": "{name}", "text-size": 12 },
      "paint": { "text-color": "#9E9E9E" } }
  ]
}
```

Set `MAP_STYLE_URL` in `local.properties` to the public R2 URL.

---

## 5. Google Play Console -- Background Location

Before submitting to Play Store:
1. Record a screen capture showing:
   - Rider mounting phone and starting SLICK
   - Convoy Link Active (background location in use)
   - Screen turning off while BLE heartbeat continues
   - SOS detection functioning without foreground
2. Save at `docs/play-store/background-location-justification.mp4`
3. Submit with justification: "Safety-critical motorcycle convoy tracking and crash detection require background location to function when screen is off for rider safety"

---

_Once all above steps are complete, update `slick-service-credentials/SKILL.md` with actual URLs and project IDs._
