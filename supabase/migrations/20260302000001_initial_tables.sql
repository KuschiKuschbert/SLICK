-- SLICK Initial Schema
-- convoy_sessions, rider_breadcrumbs, sos_alerts

-- ─── Tables ──────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS convoy_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by UUID NOT NULL,
    member_ids UUID[] NOT NULL DEFAULT '{}',
    destination_lat DOUBLE PRECISION,
    destination_lon DOUBLE PRECISION,
    started_at TIMESTAMPTZ DEFAULT now(),
    ended_at TIMESTAMPTZ,
    convoy_code VARCHAR(8) UNIQUE
);

CREATE TABLE IF NOT EXISTS rider_breadcrumbs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rider_id UUID NOT NULL,
    convoy_id UUID REFERENCES convoy_sessions(id) ON DELETE SET NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    speed_kmh DOUBLE PRECISION,
    timestamp_24h VARCHAR(8) NOT NULL,
    recorded_at TIMESTAMPTZ DEFAULT now(),
    synced BOOLEAN DEFAULT false
);

CREATE TABLE IF NOT EXISTS sos_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rider_id UUID NOT NULL,
    convoy_id UUID REFERENCES convoy_sessions(id) ON DELETE SET NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    triggered_at TIMESTAMPTZ DEFAULT now(),
    cancelled_at TIMESTAMPTZ,
    resolved BOOLEAN DEFAULT false
);

-- ─── Indexes ─────────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_breadcrumbs_rider_synced
    ON rider_breadcrumbs (rider_id, synced);

CREATE INDEX IF NOT EXISTS idx_breadcrumbs_convoy
    ON rider_breadcrumbs (convoy_id);

CREATE INDEX IF NOT EXISTS idx_sos_alerts_rider
    ON sos_alerts (rider_id);

-- ─── Row Level Security ───────────────────────────────────────────────────────

ALTER TABLE convoy_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE rider_breadcrumbs ENABLE ROW LEVEL SECURITY;
ALTER TABLE sos_alerts ENABLE ROW LEVEL SECURITY;

-- Convoy sessions: readable/writable by creator and members
CREATE POLICY "convoy_creator_insert" ON convoy_sessions
    FOR INSERT WITH CHECK (auth.uid() = created_by);

CREATE POLICY "convoy_member_read" ON convoy_sessions
    FOR SELECT USING (
        auth.uid() = created_by
        OR auth.uid() = ANY(member_ids)
    );

CREATE POLICY "convoy_creator_update" ON convoy_sessions
    FOR UPDATE USING (auth.uid() = created_by);

-- Breadcrumbs: rider writes own, convoy members can read during active ride
CREATE POLICY "rider_breadcrumb_insert" ON rider_breadcrumbs
    FOR INSERT WITH CHECK (auth.uid() = rider_id);

CREATE POLICY "convoy_member_breadcrumb_read" ON rider_breadcrumbs
    FOR SELECT USING (
        auth.uid() = rider_id
        OR EXISTS (
            SELECT 1 FROM convoy_sessions cs
            WHERE cs.id = rider_breadcrumbs.convoy_id
              AND (auth.uid() = cs.created_by OR auth.uid() = ANY(cs.member_ids))
              AND (cs.ended_at IS NULL OR cs.ended_at > now() - INTERVAL '24 hours')
        )
    );

-- SOS: rider writes own, convoy members and creator can read
CREATE POLICY "rider_sos_insert" ON sos_alerts
    FOR INSERT WITH CHECK (auth.uid() = rider_id);

CREATE POLICY "convoy_member_sos_read" ON sos_alerts
    FOR SELECT USING (
        auth.uid() = rider_id
        OR EXISTS (
            SELECT 1 FROM convoy_sessions cs
            WHERE cs.id = sos_alerts.convoy_id
              AND (auth.uid() = cs.created_by OR auth.uid() = ANY(cs.member_ids))
        )
    );

