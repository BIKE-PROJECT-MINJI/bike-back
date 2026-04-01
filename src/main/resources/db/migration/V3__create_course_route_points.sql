CREATE TABLE IF NOT EXISTS course_route_points (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL,
    point_order INTEGER NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    longitude NUMERIC(10,7) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_course_route_points_course
        FOREIGN KEY (course_id) REFERENCES courses (id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_course_route_points_course_order
    ON course_route_points (course_id ASC, point_order ASC);
