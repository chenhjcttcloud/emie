ALTER TABLE sub_tasks
    ADD COLUMN allocation_status VARCHAR(30) NOT NULL DEFAULT 'direct_assigned',
    ADD COLUMN market_published_at DATETIME(6) NULL,
    ADD COLUMN claimed_at DATETIME(6) NULL;

-- 历史未指定负责人任务不能自动进入市场，避免意外暴露。
UPDATE sub_tasks
SET allocation_status = 'withdrawn'
WHERE designer_id IS NULL OR designer_id = '';

CREATE INDEX idx_sub_task_market
    ON sub_tasks (allocation_status, status, assignee_role, market_published_at);
