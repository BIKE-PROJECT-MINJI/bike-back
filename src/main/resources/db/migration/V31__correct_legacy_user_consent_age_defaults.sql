UPDATE user_consents
SET age_verified = FALSE,
    age_band = 'UNKNOWN',
    age_verified_at = consented_at,
    updated_at = now()
WHERE age_verified = TRUE
  AND age_band = 'ADULT';

ALTER TABLE user_consents
    ALTER COLUMN age_verified SET DEFAULT FALSE,
    ALTER COLUMN age_band SET DEFAULT 'UNKNOWN';
