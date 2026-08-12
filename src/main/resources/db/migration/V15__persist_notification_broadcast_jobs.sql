CREATE TABLE notification_broadcast_jobs (
    id VARCHAR(36) NOT NULL,
    operator_user_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    result_json LONGTEXT NULL,
    error VARCHAR(500) NULL,
    owner_instance_id VARCHAR(36) NOT NULL,
    running_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'running' THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT chk_notification_broadcast_status CHECK (status IN ('running', 'completed', 'failed')),
    CONSTRAINT uk_notification_broadcast_single_running UNIQUE (running_slot),
    INDEX idx_notification_broadcast_status (status),
    INDEX idx_notification_broadcast_created (created_at)
);
