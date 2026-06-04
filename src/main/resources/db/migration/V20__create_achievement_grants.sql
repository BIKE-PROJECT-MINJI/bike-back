CREATE TABLE IF NOT EXISTS achievement_grants (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    achievement_type VARCHAR(60) NOT NULL,
    source_key VARCHAR(100) NOT NULL,
    source_course_id BIGINT,
    source_ride_record_id BIGINT,
    granted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_achievement_grants_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE,
    CONSTRAINT uq_achievement_grants_user_type_source
        UNIQUE (user_id, achievement_type, source_key)
);

CREATE INDEX IF NOT EXISTS idx_achievement_grants_user_id
    ON achievement_grants (user_id ASC, granted_at DESC);
