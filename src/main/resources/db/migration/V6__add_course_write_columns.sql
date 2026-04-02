ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS description VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';

UPDATE courses
SET visibility = 'PUBLIC'
WHERE visibility IS NULL;

CREATE INDEX IF NOT EXISTS idx_courses_owner_visibility_id
    ON courses (owner_user_id ASC, visibility ASC, id ASC);
