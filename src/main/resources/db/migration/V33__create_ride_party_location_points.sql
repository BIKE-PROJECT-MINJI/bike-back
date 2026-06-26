CREATE TABLE ride_party_location_points (
    id BIGSERIAL PRIMARY KEY,
    party_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    longitude NUMERIC(10,7) NOT NULL,
    accuracy_m NUMERIC(8,2),
    speed_mps NUMERIC(8,2),
    bearing_deg NUMERIC(6,2),
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_ride_party_location_points_party
        FOREIGN KEY (party_id) REFERENCES ride_parties (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_ride_party_location_points_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_ride_party_location_points_party_created
    ON ride_party_location_points (party_id, created_at DESC, id DESC);

CREATE INDEX idx_ride_party_location_points_created
    ON ride_party_location_points (created_at);
