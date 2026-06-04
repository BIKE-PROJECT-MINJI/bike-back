CREATE TABLE IF NOT EXISTS user_preference (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    scenic BOOLEAN NOT NULL,
    bike_road_priority VARCHAR(20) NOT NULL,
    avoid_dust BOOLEAN NOT NULL,
    avoid_unsafe_surface BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_user_preference_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE
);
