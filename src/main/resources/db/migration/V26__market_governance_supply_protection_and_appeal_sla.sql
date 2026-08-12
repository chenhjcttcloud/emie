ALTER TABLE monthly_performance_configs ADD COLUMN supply_shortage BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE point_appeals ADD COLUMN due_at DATETIME(6) NULL;

CREATE TABLE designer_market_eligibility (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    suspended_until DATETIME(6) NULL,
    reason VARCHAR(500) NULL,
    violation_count INT NOT NULL DEFAULT 0,
    updated_by VARCHAR(100) NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_market_eligibility_user UNIQUE(user_id)
);
