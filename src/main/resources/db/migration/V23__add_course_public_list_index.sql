CREATE INDEX IF NOT EXISTS idx_courses_public_list_page
    ON courses (visibility, report_hidden, display_order ASC, id ASC)
    INCLUDE (title, distance_km, estimated_duration_min);
