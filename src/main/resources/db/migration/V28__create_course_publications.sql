CREATE TABLE IF NOT EXISTS course_publications (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    unpublished_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_course_publications_course
        FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_course_publications_owner
        FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT uq_course_publications_course
        UNIQUE (course_id)
);

CREATE INDEX IF NOT EXISTS idx_course_publications_owner_status
    ON course_publications (owner_user_id ASC, status ASC);
