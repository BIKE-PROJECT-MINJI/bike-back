ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS source_ride_record_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_courses_owner_source_ride_record_id
    ON courses (owner_user_id, source_ride_record_id);
