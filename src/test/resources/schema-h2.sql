CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR(4000);

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(120) NOT NULL UNIQUE,
    email VARCHAR(160) UNIQUE,
    password_hash VARCHAR(255),
    display_name VARCHAR(80) NOT NULL,
    profile_image_url VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    beta_access_granted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE kakao_account_links (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    provider_user_id VARCHAR(80) NOT NULL UNIQUE,
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE user_consents (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    privacy_policy_version VARCHAR(80) NOT NULL,
    terms_version VARCHAR(80) NOT NULL,
    location_terms_version VARCHAR(80) NOT NULL,
    age_verified BOOLEAN NOT NULL DEFAULT TRUE,
    age_band VARCHAR(20) NOT NULL DEFAULT 'ADULT',
    age_verified_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    consented_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE beta_invitation_codes (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_by_user_id BIGINT,
    used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(40) NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE
);

CREATE TABLE user_preference (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    scenic BOOLEAN NOT NULL,
    bike_road_priority VARCHAR(20) NOT NULL,
    avoid_dust BOOLEAN NOT NULL,
    avoid_unsafe_surface BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE courses (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    distance_km NUMERIC(5,1) NOT NULL,
    estimated_duration_min INTEGER NOT NULL,
    display_order INTEGER NOT NULL,
    curated BOOLEAN NOT NULL,
    featured_rank INTEGER,
    start_latitude NUMERIC(10,7),
    start_longitude NUMERIC(10,7),
    owner_user_id BIGINT,
    source_ride_record_id BIGINT,
    source_detached BOOLEAN NOT NULL DEFAULT FALSE,
    source_ai_route_session_id BIGINT,
    source_ai_route_candidate_id BIGINT,
    visibility VARCHAR(20) NOT NULL,
    share_token VARCHAR(64),
    report_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    report_hidden_reason VARCHAR(60),
    report_hidden_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE course_reports (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL,
    reporter_user_id BIGINT NOT NULL,
    reason VARCHAR(60) NOT NULL,
    reported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_course_reports_course_reporter UNIQUE (course_id, reporter_user_id)
);

CREATE TABLE course_list_summaries (
    course_id BIGINT PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    distance_km NUMERIC(5,1) NOT NULL,
    estimated_duration_min INTEGER NOT NULL,
    display_order INTEGER NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE course_route_points (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL,
    point_order INTEGER NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    longitude NUMERIC(10,7) NOT NULL,
    CONSTRAINT uq_course_route_points_course_order UNIQUE (course_id, point_order)
);

CREATE TABLE ride_records (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    client_ride_id VARCHAR(80),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE NOT NULL,
    distance_m INTEGER NOT NULL,
    duration_sec INTEGER NOT NULL,
    finalization_status VARCHAR(32) NOT NULL DEFAULT 'FINALIZING',
    finalization_attempts INTEGER NOT NULL DEFAULT 0,
    finalization_started_at TIMESTAMP WITH TIME ZONE,
    finalization_completed_at TIMESTAMP WITH TIME ZONE,
    finalization_failed_at TIMESTAMP WITH TIME ZONE,
    finalization_error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_ride_records_owner_client_ride_id UNIQUE (owner_user_id, client_ride_id)
);

CREATE TABLE ride_record_points (
    id BIGSERIAL PRIMARY KEY,
    ride_record_id BIGINT NOT NULL,
    point_order INTEGER NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    longitude NUMERIC(10,7) NOT NULL,
    captured_at TIMESTAMP WITH TIME ZONE,
    accuracy_m NUMERIC(8,2),
    speed_mps NUMERIC(8,2),
    bearing_deg NUMERIC(6,2),
    altitude_m NUMERIC(8,2),
    distance_to_route_m NUMERIC(8,2),
    route_progress_pct NUMERIC(5,2),
    CONSTRAINT uq_ride_record_points_record_order UNIQUE (ride_record_id, point_order),
    CONSTRAINT fk_ride_record_points_ride_record
        FOREIGN KEY (ride_record_id) REFERENCES ride_records (id)
        ON DELETE CASCADE
);

CREATE TABLE ride_record_processed_points (
    id BIGSERIAL PRIMARY KEY,
    ride_record_id BIGINT NOT NULL,
    point_order INTEGER NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    longitude NUMERIC(10,7) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_ride_record_processed_points_record_order UNIQUE (ride_record_id, point_order),
    CONSTRAINT fk_ride_record_processed_points_ride_record
        FOREIGN KEY (ride_record_id) REFERENCES ride_records (id)
        ON DELETE CASCADE
);

CREATE TABLE client_events (
    id BIGSERIAL PRIMARY KEY,
    event_name VARCHAR(100) NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100),
    occurred_at_client TIMESTAMP WITH TIME ZONE,
    received_at_server TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    screen_name VARCHAR(100),
    course_id BIGINT,
    ride_record_id BIGINT,
    app_version VARCHAR(50),
    os_name VARCHAR(50),
    device_type VARCHAR(50),
    location_permission_state VARCHAR(50),
    properties_json JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_courses_owner_source_ride_record
    ON courses (owner_user_id, source_ride_record_id);

CREATE INDEX idx_ride_records_owner_status_ended_at
    ON ride_records (owner_user_id, finalization_status, ended_at);

CREATE INDEX idx_courses_owner_created_at
    ON courses (owner_user_id, created_at);

CREATE INDEX idx_courses_public_list_page
    ON courses (visibility, report_hidden, display_order, id);

CREATE INDEX idx_course_list_summaries_page
    ON course_list_summaries (display_order, course_id);

CREATE TABLE ai_route_generation_sessions (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    fallback_used BOOLEAN NOT NULL DEFAULT FALSE,
    provider VARCHAR(60) NOT NULL,
    fallback_reason VARCHAR(120),
    request_text VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE ai_route_candidates (
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

CREATE INDEX idx_ai_route_generation_sessions_owner_created_at
    ON ai_route_generation_sessions (owner_user_id, created_at DESC);

CREATE INDEX idx_ai_route_candidates_session_id
    ON ai_route_candidates (session_id, id);

CREATE UNIQUE INDEX uq_courses_source_ai_route_candidate
    ON courses (source_ai_route_candidate_id);

CREATE TABLE achievement_grants (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    achievement_type VARCHAR(60) NOT NULL,
    source_key VARCHAR(100) NOT NULL,
    source_course_id BIGINT,
    source_ride_record_id BIGINT,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_achievement_grants_user_type_source UNIQUE (user_id, achievement_type, source_key)
);
