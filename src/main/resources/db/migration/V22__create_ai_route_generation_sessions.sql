ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS source_ai_route_session_id BIGINT,
    ADD COLUMN IF NOT EXISTS source_ai_route_candidate_id BIGINT;

CREATE TABLE IF NOT EXISTS ai_route_generation_sessions (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    fallback_used BOOLEAN NOT NULL DEFAULT FALSE,
    provider VARCHAR(60) NOT NULL,
    fallback_reason VARCHAR(120),
    request_text VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ai_route_candidates (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    summary VARCHAR(1000),
    distance_km NUMERIC(5,1) NOT NULL,
    estimated_duration_min INTEGER NOT NULL,
    recommendation_score INTEGER NOT NULL,
    elevation_summary_json TEXT,
    route_point_count INTEGER NOT NULL,
    route_points_json TEXT NOT NULL,
    promoted_course_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_route_generation_sessions_owner_created_at
    ON ai_route_generation_sessions (owner_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_route_candidates_session_id
    ON ai_route_candidates (session_id, id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_courses_source_ai_route_candidate
    ON courses (source_ai_route_candidate_id)
    WHERE source_ai_route_candidate_id IS NOT NULL;
