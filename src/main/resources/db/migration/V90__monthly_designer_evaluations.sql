CREATE TABLE monthly_designer_evaluations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    month_key VARCHAR(7) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    completion_score DECIMAL(7,2) NOT NULL,
    adjustment_score DECIMAL(7,2) NOT NULL,
    aesthetic_score DECIMAL(7,2) NOT NULL,
    collaboration_score DECIMAL(7,2) NOT NULL,
    initiative_score DECIMAL(7,2) NOT NULL,
    attendance_score DECIMAL(7,2) NOT NULL,
    weekly_report_score DECIMAL(7,2) NOT NULL,
    comment VARCHAR(2000),
    weekly_report_deduction DECIMAL(7,2) NOT NULL DEFAULT 0,
    updated_by VARCHAR(255) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    total_score DECIMAL(7,2) NOT NULL,
    CONSTRAINT uk_designer_evaluation_month UNIQUE (user_id, month_key)
);
