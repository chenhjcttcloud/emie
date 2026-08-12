ALTER TABLE sub_tasks
    ADD COLUMN collaborator_allocations_json TEXT NULL,
    ADD COLUMN milestone_month VARCHAR(7) NULL,
    ADD COLUMN assignment_reason VARCHAR(500) NULL,
    ADD COLUMN self_initiated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN self_initiated_approved BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE point_ledgers ADD COLUMN accounting_month VARCHAR(7) NULL;
UPDATE point_ledgers SET accounting_month = DATE_FORMAT(created_at, '%Y-%m') WHERE accounting_month IS NULL;
CREATE INDEX idx_point_ledgers_user_month ON point_ledgers(user_id, accounting_month);
