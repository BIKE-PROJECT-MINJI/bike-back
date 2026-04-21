ALTER TABLE ride_records
    ADD COLUMN IF NOT EXISTS finalization_status VARCHAR(32) NOT NULL DEFAULT 'FINALIZING',
    ADD COLUMN IF NOT EXISTS finalization_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS finalization_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS finalization_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS finalization_failed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS finalization_error_message TEXT;

CREATE TABLE IF NOT EXISTS ride_record_processed_points (
    id BIGSERIAL PRIMARY KEY,
    ride_record_id BIGINT NOT NULL,
    point_order INTEGER NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    longitude NUMERIC(10,7) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ride_record_processed_points_ride_record
        FOREIGN KEY (ride_record_id) REFERENCES ride_records (id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ride_record_processed_points_record_order
    ON ride_record_processed_points (ride_record_id ASC, point_order ASC);
