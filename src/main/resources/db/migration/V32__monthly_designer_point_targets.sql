CREATE TABLE IF NOT EXISTS monthly_user_point_targets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    month_key VARCHAR(7) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    user_name VARCHAR(100) NULL,
    target_points INT NOT NULL,
    updated_by VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_monthly_user_point_target UNIQUE (month_key, user_id),
    INDEX idx_monthly_user_point_target_month (month_key)
);
