CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR(4000);

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(120) NOT NULL UNIQUE,
    email VARCHAR(160),
    password_hash VARCHAR(255),
    display_name VARCHAR(80) NOT NULL,
    profile_image_url VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
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
    visibility VARCHAR(20) NOT NULL,
    share_token VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE course_route_points (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL,
    point_order INTEGER NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    longitude NUMERIC(10,7) NOT NULL
);

CREATE TABLE ride_records (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
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
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
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
    route_progress_pct NUMERIC(5,2)
);

CREATE TABLE ride_record_processed_points (
    id BIGSERIAL PRIMARY KEY,
    ride_record_id BIGINT NOT NULL,
    point_order INTEGER NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    longitude NUMERIC(10,7) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
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
