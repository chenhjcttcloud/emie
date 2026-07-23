-- 项目级子任务总流程：五阶段状态与不可覆盖的多轮审核历史。
-- 生产使用 ddl-auto=validate；本脚本可安全重复执行。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'workflow_stage') = 0,
    'ALTER TABLE projects ADD COLUMN workflow_stage VARCHAR(30) NULL DEFAULT ''design''',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'workflow_status') = 0,
    'ALTER TABLE projects ADD COLUMN workflow_status VARCHAR(30) NULL DEFAULT ''current''',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE projects
SET workflow_stage = CASE WHEN status = 'completed' THEN 'bulk' ELSE 'design' END
WHERE workflow_stage IS NULL OR workflow_stage = '';

UPDATE projects
SET workflow_status = CASE WHEN status = 'completed' THEN 'completed' ELSE 'current' END
WHERE workflow_status IS NULL OR workflow_status = '';

ALTER TABLE projects
    MODIFY COLUMN workflow_stage VARCHAR(30) NOT NULL DEFAULT 'design',
    MODIFY COLUMN workflow_status VARCHAR(30) NOT NULL DEFAULT 'current';

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sub_tasks' AND COLUMN_NAME = 'workflow_stage') = 0,
    'ALTER TABLE sub_tasks ADD COLUMN workflow_stage VARCHAR(30) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE sub_tasks
SET workflow_stage = 'design'
WHERE workflow_stage IS NULL OR workflow_stage = '';

CREATE TABLE IF NOT EXISTS project_workflow_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    stage_key VARCHAR(30) NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    submitted_by VARCHAR(100) NOT NULL,
    submitted_by_name VARCHAR(100) NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    reviewer_id VARCHAR(100) NULL,
    reviewer_name VARCHAR(100) NULL,
    reviewed_at DATETIME(6) NULL,
    comment TEXT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_project_workflow_attempt_round UNIQUE (project_id, stage_key, attempt_no),
    CONSTRAINT fk_project_workflow_attempt_project
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    INDEX idx_project_workflow_attempt_project (project_id, id),
    INDEX idx_project_workflow_attempt_status (status, submitted_at)
);
