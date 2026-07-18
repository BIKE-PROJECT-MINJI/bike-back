ALTER TABLE ride_records
    ADD COLUMN IF NOT EXISTS quality_status VARCHAR(16),
    ADD COLUMN IF NOT EXISTS quality_reasons TEXT;
