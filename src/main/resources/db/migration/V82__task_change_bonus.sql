ALTER TABLE sub_tasks
    ADD COLUMN pending_change_bonus_request_id VARCHAR(36) NULL,
    ADD COLUMN pending_change_bonus_points DECIMAL(12,2) NULL,
    ADD COLUMN pending_change_bonus_reason VARCHAR(500) NULL,
    ADD COLUMN pending_change_bonus_created_by VARCHAR(100) NULL;

ALTER TABLE point_ledgers
    ADD COLUMN reason VARCHAR(500) NULL,
    ADD COLUMN created_by VARCHAR(100) NULL;
