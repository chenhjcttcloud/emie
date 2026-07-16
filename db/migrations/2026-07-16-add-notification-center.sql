-- 通知中心第一阶段：可靠 Outbox、站内通知、渠道投递和追加式审计。
-- 所有 CREATE TABLE 使用 IF NOT EXISTS，可安全重复执行。

CREATE TABLE IF NOT EXISTS notification_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    aggregate_version INT NULL,
    actor_user_id VARCHAR(100) NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    payload LONGTEXT NULL,
    occurred_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    error_msg TEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_event_idempotency (idempotency_key),
    KEY idx_notification_event_status_time (status, occurred_at),
    KEY idx_notification_event_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    recipient_user_id VARCHAR(100) NOT NULL,
    category VARCHAR(80) NOT NULL,
    priority VARCHAR(20) NOT NULL DEFAULT 'normal',
    mandatory BIT NOT NULL DEFAULT b'0',
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    deep_link VARCHAR(500) NULL,
    aggregate_type VARCHAR(50) NULL,
    aggregate_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'unread',
    created_at DATETIME(6) NOT NULL,
    read_at DATETIME(6) NULL,
    clicked_at DATETIME(6) NULL,
    archived_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_notification_recipient_status (recipient_user_id, status, created_at),
    KEY idx_notification_event (event_id),
    KEY idx_notification_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notification_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    notification_id BIGINT NOT NULL,
    channel VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    delivered_at DATETIME(6) NULL,
    external_message_id VARCHAR(200) NULL,
    error_msg TEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_delivery_channel (notification_id, channel),
    KEY idx_notification_delivery_status_time (status, next_retry_at),
    KEY idx_notification_delivery_notification (notification_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notification_audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NULL,
    notification_id BIGINT NULL,
    delivery_id BIGINT NULL,
    action VARCHAR(60) NOT NULL,
    operator_user_id VARCHAR(100) NULL,
    detail LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notification_audit_notification_time (notification_id, created_at),
    KEY idx_notification_audit_event_time (event_id, created_at),
    KEY idx_notification_audit_action_time (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
