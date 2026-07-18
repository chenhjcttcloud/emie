CREATE TABLE IF NOT EXISTS runtime_alerts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    alert_type VARCHAR(60) NOT NULL,
    status VARCHAR(20) NOT NULL,
    detail LONGTEXT NULL,
    first_seen_at DATETIME NOT NULL,
    last_seen_at DATETIME NOT NULL,
    recovered_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_runtime_alert_type UNIQUE (alert_type),
    INDEX idx_runtime_alert_status_time (status, last_seen_at)
);
