CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(40) NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_roles_role
    ON user_roles (role ASC);

INSERT INTO user_roles (user_id, role)
SELECT id, 'USER'
FROM users
ON CONFLICT DO NOTHING;
