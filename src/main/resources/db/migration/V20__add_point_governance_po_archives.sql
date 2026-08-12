CREATE TABLE IF NOT EXISTS point_appeals (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, point_ledger_id BIGINT NOT NULL,
 applicant_user_id VARCHAR(100) NOT NULL, applicant_name VARCHAR(100) NOT NULL,
 type VARCHAR(40) NOT NULL, reason VARCHAR(1000) NOT NULL, status VARCHAR(32) NOT NULL,
 planner_decision VARCHAR(20), planner_comment VARCHAR(1000), planner_user_id VARCHAR(100), planner_name VARCHAR(100), planner_processed_at DATETIME(6),
 admin_decision VARCHAR(20), admin_comment VARCHAR(1000), admin_user_id VARCHAR(100), admin_name VARCHAR(100), admin_reviewed_at DATETIME(6),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 INDEX idx_point_appeal_user (applicant_user_id, created_at), INDEX idx_point_appeal_status (status),
 CONSTRAINT fk_point_appeal_ledger FOREIGN KEY (point_ledger_id) REFERENCES point_ledgers(id)
);

CREATE TABLE IF NOT EXISTS po_point_projects (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(200) NOT NULL,
 owner_user_id VARCHAR(100) NOT NULL, owner_name VARCHAR(100) NOT NULL,
 monthly_points INT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_by VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 INDEX idx_po_project_owner (owner_user_id)
);

CREATE TABLE IF NOT EXISTS po_monthly_progress (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, po_project_id BIGINT NOT NULL,
 month_key VARCHAR(7) NOT NULL, summary VARCHAR(4000) NOT NULL, status VARCHAR(24) NOT NULL,
 submitted_by VARCHAR(100) NOT NULL, submitted_at DATETIME(6) NOT NULL,
 confirmed_by VARCHAR(100), confirmed_at DATETIME(6), review_comment VARCHAR(1000),
 CONSTRAINT uk_po_progress_month UNIQUE (po_project_id, month_key),
 CONSTRAINT fk_po_progress_project FOREIGN KEY (po_project_id) REFERENCES po_point_projects(id)
);

CREATE TABLE IF NOT EXISTS po_point_ledgers (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, progress_id BIGINT NOT NULL,
 po_project_id BIGINT NOT NULL, user_id VARCHAR(100) NOT NULL, month_key VARCHAR(7) NOT NULL,
 points INT NOT NULL, created_by VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_po_ledger_progress UNIQUE (progress_id),
 CONSTRAINT fk_po_ledger_progress FOREIGN KEY (progress_id) REFERENCES po_monthly_progress(id),
 CONSTRAINT fk_po_ledger_project FOREIGN KEY (po_project_id) REFERENCES po_point_projects(id),
 INDEX idx_po_ledger_user_month (user_id, month_key)
);

CREATE TABLE IF NOT EXISTS monthly_point_archives (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, month_key VARCHAR(7) NOT NULL, user_id VARCHAR(100) NOT NULL,
 earned_points INT NOT NULL DEFAULT 0, target_points INT NOT NULL DEFAULT 0, supplied_points INT NOT NULL DEFAULT 0,
 insufficient_supply_protection BOOLEAN NOT NULL DEFAULT FALSE,
 quarterly_average_points DOUBLE NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 archived_by VARCHAR(100), archived_at DATETIME(6), created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_monthly_archive_user UNIQUE (month_key, user_id), INDEX idx_monthly_archive_user (user_id, month_key)
);
