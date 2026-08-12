CREATE TABLE point_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_code VARCHAR(80) NOT NULL,
    points INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_point_rules_code UNIQUE (rule_code),
    CONSTRAINT chk_point_rules_points CHECK (points >= 0)
);

CREATE TABLE point_ledgers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    sub_task_id BIGINT NOT NULL,
    rule_code VARCHAR(80) NOT NULL,
    points INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_point_ledger_task_rule_user UNIQUE (user_id, sub_task_id, rule_code),
    CONSTRAINT chk_point_ledger_points CHECK (points > 0),
    INDEX idx_point_ledgers_user_created (user_id, created_at),
    INDEX idx_point_ledgers_task (sub_task_id)
);

INSERT INTO point_rules (rule_code, points, enabled, description, created_at, updated_at)
SELECT 'TASK_APPROVED', 10, TRUE, '子任务完成验收奖励', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM point_rules WHERE rule_code = 'TASK_APPROVED');
