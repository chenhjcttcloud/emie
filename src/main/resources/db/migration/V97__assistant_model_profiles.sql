CREATE TABLE assistant_model_profiles (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    dialect VARCHAR(30) NOT NULL,
    thinking_mode VARCHAR(15) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    model VARCHAR(150) NOT NULL,
    vision_supported BOOLEAN NOT NULL,
    encrypted_key TEXT NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE assistant_model_selection (
    id BIGINT NOT NULL PRIMARY KEY,
    active_profile_id VARCHAR(64) NOT NULL,
    enabled BOOLEAN
);

INSERT INTO assistant_model_selection (id, active_profile_id, enabled) VALUES (1, 'server', NULL);
