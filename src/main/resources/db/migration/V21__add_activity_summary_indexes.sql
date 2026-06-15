CREATE INDEX IF NOT EXISTS idx_ride_records_owner_status_ended_at
    ON ride_records (owner_user_id, finalization_status, ended_at);

CREATE INDEX IF NOT EXISTS idx_courses_owner_created_at
    ON courses (owner_user_id, created_at);
