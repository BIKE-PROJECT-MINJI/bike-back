ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email VARCHAR(160);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email
    ON users (email ASC)
    WHERE email IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_users_email
    ON users (email ASC);

ALTER TABLE users
    ALTER COLUMN external_id DROP NOT NULL;
