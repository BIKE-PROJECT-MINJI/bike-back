ALTER TABLE ride_records
    ADD COLUMN IF NOT EXISTS client_ride_id VARCHAR(80);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ride_records_owner_client_ride_id
    ON ride_records (owner_user_id, client_ride_id)
    WHERE client_ride_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_ride_record_points_record_order
    ON ride_record_points (ride_record_id, point_order);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ride_record_processed_points_record_order
    ON ride_record_processed_points (ride_record_id, point_order);

CREATE UNIQUE INDEX IF NOT EXISTS uq_course_route_points_course_order
    ON course_route_points (course_id, point_order);

CREATE UNIQUE INDEX IF NOT EXISTS uq_courses_owner_source_ride_record
    ON courses (owner_user_id, source_ride_record_id)
    WHERE source_ride_record_id IS NOT NULL;
