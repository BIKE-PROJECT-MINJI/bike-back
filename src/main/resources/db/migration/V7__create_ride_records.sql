CREATE TABLE IF NOT EXISTS ride_records (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ NOT NULL,
    distance_m INTEGER NOT NULL,
    duration_sec INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ride_records_owner_user
        FOREIGN KEY (owner_user_id) REFERENCES users (id)
);

CREATE INDEX IF NOT EXISTS idx_ride_records_owner_user_id
    ON ride_records (owner_user_id ASC, id ASC);

CREATE TABLE IF NOT EXISTS ride_record_points (
    id BIGSERIAL PRIMARY KEY,
    ride_record_id BIGINT NOT NULL,
    point_order INTEGER NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    longitude NUMERIC(10,7) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ride_record_points_ride_record
        FOREIGN KEY (ride_record_id) REFERENCES ride_records (id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ride_record_points_record_order
    ON ride_record_points (ride_record_id ASC, point_order ASC);
