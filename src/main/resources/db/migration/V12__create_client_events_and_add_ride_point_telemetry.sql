CREATE TABLE IF NOT EXISTS client_events (
    id BIGSERIAL PRIMARY KEY,
    event_name VARCHAR(100) NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100),
    occurred_at_client TIMESTAMPTZ,
    received_at_server TIMESTAMPTZ NOT NULL DEFAULT now(),
    screen_name VARCHAR(100),
    course_id BIGINT,
    ride_record_id BIGINT,
    app_version VARCHAR(50),
    os_name VARCHAR(50),
    device_type VARCHAR(50),
    location_permission_state VARCHAR(50),
    properties_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_client_events_event_name ON client_events (event_name);
CREATE INDEX IF NOT EXISTS idx_client_events_user_id ON client_events (user_id);
CREATE INDEX IF NOT EXISTS idx_client_events_session_id ON client_events (session_id);
CREATE INDEX IF NOT EXISTS idx_client_events_course_id ON client_events (course_id);
CREATE INDEX IF NOT EXISTS idx_client_events_received_at ON client_events (received_at_server);
CREATE INDEX IF NOT EXISTS idx_client_events_event_name_received_at ON client_events (event_name, received_at_server);

ALTER TABLE ride_record_points
    ADD COLUMN IF NOT EXISTS captured_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS accuracy_m NUMERIC(8,2) NULL,
    ADD COLUMN IF NOT EXISTS speed_mps NUMERIC(8,2) NULL,
    ADD COLUMN IF NOT EXISTS bearing_deg NUMERIC(6,2) NULL,
    ADD COLUMN IF NOT EXISTS altitude_m NUMERIC(8,2) NULL,
    ADD COLUMN IF NOT EXISTS distance_to_route_m NUMERIC(8,2) NULL,
    ADD COLUMN IF NOT EXISTS route_progress_pct NUMERIC(5,2) NULL;
