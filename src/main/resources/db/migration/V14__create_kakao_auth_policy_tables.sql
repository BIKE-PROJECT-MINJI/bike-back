ALTER TABLE users
    ADD COLUMN IF NOT EXISTS account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS kakao_account_links (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    provider_user_id VARCHAR(80) NOT NULL UNIQUE,
    linked_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_kakao_account_links_user_id
    ON kakao_account_links (user_id ASC);

CREATE TABLE IF NOT EXISTS user_consents (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    privacy_policy_version VARCHAR(80) NOT NULL,
    terms_version VARCHAR(80) NOT NULL,
    location_terms_version VARCHAR(80) NOT NULL,
    consented_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_consents_user_id
    ON user_consents (user_id ASC);

ALTER TABLE user_consents
    ADD COLUMN IF NOT EXISTS age_verified BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS age_band VARCHAR(20) NOT NULL DEFAULT 'ADULT',
    ADD COLUMN IF NOT EXISTS age_verified_at TIMESTAMPTZ NOT NULL DEFAULT now();
