CREATE EXTENSION IF NOT EXISTS postgis;

ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS start_point_geom geometry(Point, 4326)
        GENERATED ALWAYS AS (
            CASE
                WHEN start_latitude IS NULL OR start_longitude IS NULL THEN NULL
                ELSE ST_SetSRID(ST_MakePoint(start_longitude::double precision, start_latitude::double precision), 4326)
            END
        ) STORED,
    ADD COLUMN IF NOT EXISTS route_line_geom geometry(LineString, 4326);

ALTER TABLE course_route_points
    ADD COLUMN IF NOT EXISTS point_geom geometry(Point, 4326)
        GENERATED ALWAYS AS (
            ST_SetSRID(ST_MakePoint(longitude::double precision, latitude::double precision), 4326)
        ) STORED;

ALTER TABLE ride_record_points
    ADD COLUMN IF NOT EXISTS point_geom geometry(Point, 4326)
        GENERATED ALWAYS AS (
            ST_SetSRID(ST_MakePoint(longitude::double precision, latitude::double precision), 4326)
        ) STORED;

ALTER TABLE ride_record_processed_points
    ADD COLUMN IF NOT EXISTS point_geom geometry(Point, 4326)
        GENERATED ALWAYS AS (
            ST_SetSRID(ST_MakePoint(longitude::double precision, latitude::double precision), 4326)
        ) STORED;

CREATE INDEX IF NOT EXISTS idx_courses_start_point_geom_gist
    ON courses USING GIST (start_point_geom);

CREATE INDEX IF NOT EXISTS idx_courses_route_line_geom_gist
    ON courses USING GIST (route_line_geom);

CREATE INDEX IF NOT EXISTS idx_course_route_points_point_geom_gist
    ON course_route_points USING GIST (point_geom);

CREATE INDEX IF NOT EXISTS idx_ride_record_points_point_geom_gist
    ON ride_record_points USING GIST (point_geom);

CREATE INDEX IF NOT EXISTS idx_ride_record_processed_points_point_geom_gist
    ON ride_record_processed_points USING GIST (point_geom);
