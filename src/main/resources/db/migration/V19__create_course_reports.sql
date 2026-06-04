ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS report_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS report_hidden_reason VARCHAR(60),
    ADD COLUMN IF NOT EXISTS report_hidden_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS course_reports (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL,
    reporter_user_id BIGINT NOT NULL,
    reason VARCHAR(60) NOT NULL,
    reported_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_course_reports_course
        FOREIGN KEY (course_id) REFERENCES courses(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_course_reports_reporter
        FOREIGN KEY (reporter_user_id) REFERENCES users(id)
        ON DELETE CASCADE,
    CONSTRAINT uq_course_reports_course_reporter
        UNIQUE (course_id, reporter_user_id)
);

CREATE INDEX IF NOT EXISTS idx_course_reports_course
    ON course_reports (course_id ASC);
