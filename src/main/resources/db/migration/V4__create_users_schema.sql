CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(120) UNIQUE,
    email VARCHAR(160) UNIQUE,
    password_hash VARCHAR(255),
    display_name VARCHAR(80) NOT NULL,
    profile_image_url VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_users_external_id
    ON users (external_id ASC);

CREATE INDEX IF NOT EXISTS idx_users_email
    ON users (email ASC);
