CREATE TABLE IF NOT EXISTS ride_finalization_jobs (
    id BIGSERIAL PRIMARY KEY,
    ride_record_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_run_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(120),
    locked_until TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    last_error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ride_finalization_jobs_ride_record UNIQUE (ride_record_id),
    CONSTRAINT fk_ride_finalization_jobs_ride_record
        FOREIGN KEY (ride_record_id) REFERENCES ride_records (id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ride_finalization_jobs_runnable
    ON ride_finalization_jobs (status, next_run_at, id);

CREATE INDEX IF NOT EXISTS idx_ride_finalization_jobs_expired_running
    ON ride_finalization_jobs (status, locked_until, id);

INSERT INTO ride_finalization_jobs (
    ride_record_id,
    status,
    attempt_count,
    max_attempts,
    next_run_at,
    created_at,
    updated_at
)
SELECT
    r.id,
    'PENDING',
    0,
    3,
    now(),
    now(),
    now()
FROM ride_records r
WHERE r.finalization_status = 'FINALIZING'
ON CONFLICT (ride_record_id) DO NOTHING;
