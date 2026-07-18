CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(120) NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    applied_at DATETIME NOT NULL,
    PRIMARY KEY (version)
);
