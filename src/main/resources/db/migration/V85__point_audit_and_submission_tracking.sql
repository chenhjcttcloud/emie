ALTER TABLE point_ledgers
 MODIFY COLUMN rule_code VARCHAR(128) NOT NULL,
 ADD COLUMN delivery_version_id BIGINT NULL,
 ADD COLUMN submitted_at DATETIME(6) NULL,
 ADD COLUMN confirmed_at DATETIME(6) NULL,
 ADD COLUMN rule_description VARCHAR(255) NULL,
 ADD INDEX idx_point_ledger_version (delivery_version_id);
ALTER TABLE point_adjustment_ledgers
 ADD COLUMN rule_code VARCHAR(80) NULL,
 ADD COLUMN rule_description VARCHAR(255) NULL,
 ADD COLUMN submitted_at DATETIME(6) NULL;
ALTER TABLE sub_task_delivery_versions
 ADD COLUMN expected_points DECIMAL(12,2) NULL,
 ADD COLUMN point_rule_code VARCHAR(80) NULL,
 ADD COLUMN point_rule_description VARCHAR(255) NULL,
 ADD COLUMN point_request_id VARCHAR(36) NULL,
 ADD COLUMN confirmed_at DATETIME(6) NULL,
 ADD COLUMN confirmed_by VARCHAR(100) NULL;
