SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS sub_task_delivery_versions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sub_task_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    submission_type VARCHAR(20) NOT NULL,
    change_summary VARCHAR(500) NULL,
    deliverables TEXT NULL,
    reference_images_json LONGTEXT NULL,
    attachments_json LONGTEXT NULL,
    actual_date VARCHAR(10) NULL,
    self_score DOUBLE NULL,
    submitted_by_id VARCHAR(255) NULL,
    submitted_by_name VARCHAR(255) NULL,
    submitted_by_role VARCHAR(30) NULL,
    submitted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sub_task_delivery_version UNIQUE (sub_task_id, version_no),
    CONSTRAINT fk_delivery_version_sub_task FOREIGN KEY (sub_task_id) REFERENCES sub_tasks (id) ON DELETE CASCADE,
    INDEX idx_delivery_version_task_time (sub_task_id, submitted_at)
);

INSERT INTO sub_task_delivery_versions
    (sub_task_id, version_no, submission_type, change_summary, deliverables,
     reference_images_json, attachments_json, actual_date, self_score,
     submitted_by_id, submitted_by_name, submitted_by_role, submitted_at)
SELECT t.id, 1, 'initial', '历史交付数据初始化', t.deliverables,
       t.reference_images_json, t.attachments_json, t.actual_date, t.self_score,
       t.designer_id, t.designer_name, t.assignee_role, COALESCE(t.updated_at, t.created_at, NOW(6))
FROM sub_tasks t
WHERE t.deliverables IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sub_task_delivery_versions v WHERE v.sub_task_id = t.id
  );
