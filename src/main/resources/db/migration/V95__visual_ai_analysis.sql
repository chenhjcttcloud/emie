CREATE TABLE visual_ai_config (
    id BIGINT NOT NULL PRIMARY KEY,
    base_url VARCHAR(500) NOT NULL,
    model VARCHAR(150) NOT NULL,
    enabled BOOLEAN NOT NULL,
    encrypted_key TEXT
);
CREATE TABLE visual_analysis_cache (
    stored_name VARCHAR(255) NOT NULL PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    profile_json TEXT,
    message VARCHAR(240),
    attempts INT NOT NULL DEFAULT 0,
    retry_after DATETIME,
    updated_at DATETIME NOT NULL
);
