CREATE TABLE ride_parties (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL,
    host_user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    scheduled_start_at TIMESTAMP WITH TIME ZONE,
    capacity INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_ride_parties_course
        FOREIGN KEY (course_id) REFERENCES courses (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_ride_parties_host_user
        FOREIGN KEY (host_user_id) REFERENCES users (id)
        ON DELETE CASCADE
);

CREATE TABLE ride_party_members (
    id BIGSERIAL PRIMARY KEY,
    party_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
    left_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_ride_party_members_party_user UNIQUE (party_id, user_id),
    CONSTRAINT fk_ride_party_members_party
        FOREIGN KEY (party_id) REFERENCES ride_parties (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_ride_party_members_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_ride_parties_course_status_created
    ON ride_parties (course_id, status, created_at DESC, id DESC);

CREATE INDEX idx_ride_party_members_party_status
    ON ride_party_members (party_id, status);
