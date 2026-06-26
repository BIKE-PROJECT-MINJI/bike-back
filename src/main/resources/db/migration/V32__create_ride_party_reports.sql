CREATE TABLE ride_party_reports (
    id BIGSERIAL PRIMARY KEY,
    party_id BIGINT NOT NULL,
    reporter_user_id BIGINT NOT NULL,
    reason VARCHAR(60) NOT NULL,
    reported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ride_party_reports_party_reporter UNIQUE (party_id, reporter_user_id),
    CONSTRAINT fk_ride_party_reports_party
        FOREIGN KEY (party_id) REFERENCES ride_parties (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_ride_party_reports_reporter
        FOREIGN KEY (reporter_user_id) REFERENCES users (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_ride_party_reports_party
    ON ride_party_reports (party_id);
