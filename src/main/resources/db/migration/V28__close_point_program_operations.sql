CREATE TABLE point_task_proposals (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NULL, applicant_user_id VARCHAR(100) NOT NULL,
 applicant_name VARCHAR(100) NOT NULL, title VARCHAR(200) NOT NULL, description TEXT NOT NULL,
 point_rule_code VARCHAR(80) NOT NULL, difficulty_code VARCHAR(40) NOT NULL, planned_date VARCHAR(10) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED', review_comment VARCHAR(1000), reviewed_by VARCHAR(100),
 reviewed_at DATETIME(6), created_task_id BIGINT, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 INDEX idx_point_proposal_applicant(applicant_user_id,created_at), INDEX idx_point_proposal_status(status)
);
CREATE TABLE point_task_forecasts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, month_key VARCHAR(7) NOT NULL, title VARCHAR(200) NOT NULL,
 description TEXT, point_rule_code VARCHAR(80), estimated_count INT NOT NULL DEFAULT 1,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', published_by VARCHAR(100), published_at DATETIME(6),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 INDEX idx_point_forecast_month_status(month_key,status)
);
