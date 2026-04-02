ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS share_token VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_courses_share_token
    ON courses (share_token)
    WHERE share_token IS NOT NULL;
