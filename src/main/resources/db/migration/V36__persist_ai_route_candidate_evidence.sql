ALTER TABLE ai_route_candidates
    ADD COLUMN IF NOT EXISTS score_breakdown_json TEXT,
    ADD COLUMN IF NOT EXISTS evidence_badges_json TEXT,
    ADD COLUMN IF NOT EXISTS routing_metadata_json TEXT,
    ADD COLUMN IF NOT EXISTS preference_summary VARCHAR(500),
    ADD COLUMN IF NOT EXISTS elevation_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS scenery_evidence_status VARCHAR(32);
