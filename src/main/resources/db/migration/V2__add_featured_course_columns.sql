ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS curated BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS featured_rank INTEGER,
    ADD COLUMN IF NOT EXISTS start_latitude NUMERIC(10,7),
    ADD COLUMN IF NOT EXISTS start_longitude NUMERIC(10,7);

CREATE INDEX IF NOT EXISTS idx_courses_curated_featured_rank_id
    ON courses (curated ASC, featured_rank ASC, id ASC);
