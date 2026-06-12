CREATE TABLE IF NOT EXISTS course_list_summaries (
    course_id BIGINT PRIMARY KEY REFERENCES courses(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    distance_km NUMERIC(5,1) NOT NULL,
    estimated_duration_min INTEGER NOT NULL,
    display_order INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO course_list_summaries (
    course_id,
    title,
    distance_km,
    estimated_duration_min,
    display_order,
    updated_at
)
SELECT
    id,
    title,
    distance_km,
    estimated_duration_min,
    display_order,
    now()
FROM courses
WHERE visibility = 'PUBLIC'
  AND report_hidden = false
ON CONFLICT (course_id) DO UPDATE SET
    title = EXCLUDED.title,
    distance_km = EXCLUDED.distance_km,
    estimated_duration_min = EXCLUDED.estimated_duration_min,
    display_order = EXCLUDED.display_order,
    updated_at = now();

CREATE INDEX IF NOT EXISTS idx_course_list_summaries_page
    ON course_list_summaries (display_order ASC, course_id ASC);

CREATE OR REPLACE FUNCTION refresh_course_list_summary()
RETURNS trigger AS $$
BEGIN
    IF NEW.visibility = 'PUBLIC' AND NEW.report_hidden = false THEN
        INSERT INTO course_list_summaries (
            course_id,
            title,
            distance_km,
            estimated_duration_min,
            display_order,
            updated_at
        )
        VALUES (
            NEW.id,
            NEW.title,
            NEW.distance_km,
            NEW.estimated_duration_min,
            NEW.display_order,
            now()
        )
        ON CONFLICT (course_id) DO UPDATE SET
            title = EXCLUDED.title,
            distance_km = EXCLUDED.distance_km,
            estimated_duration_min = EXCLUDED.estimated_duration_min,
            display_order = EXCLUDED.display_order,
            updated_at = now();
    ELSE
        DELETE FROM course_list_summaries WHERE course_id = NEW.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_refresh_course_list_summary ON courses;

CREATE TRIGGER trg_refresh_course_list_summary
AFTER INSERT OR UPDATE OF title, distance_km, estimated_duration_min, display_order, visibility, report_hidden
ON courses
FOR EACH ROW
EXECUTE FUNCTION refresh_course_list_summary();
