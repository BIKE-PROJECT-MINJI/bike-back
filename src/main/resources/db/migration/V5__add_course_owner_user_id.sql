ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;

ALTER TABLE courses
    ADD CONSTRAINT fk_courses_owner_user
        FOREIGN KEY (owner_user_id) REFERENCES users (id);

CREATE INDEX IF NOT EXISTS idx_courses_owner_user_id
    ON courses (owner_user_id ASC);
