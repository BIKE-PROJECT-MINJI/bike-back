CREATE TABLE IF NOT EXISTS beta_invitation_codes (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_by_user_id BIGINT,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_beta_invitation_codes_code
    ON beta_invitation_codes (code ASC);

CREATE INDEX IF NOT EXISTS idx_beta_invitation_codes_expires_at
    ON beta_invitation_codes (expires_at ASC);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS beta_access_granted BOOLEAN NOT NULL DEFAULT FALSE;
