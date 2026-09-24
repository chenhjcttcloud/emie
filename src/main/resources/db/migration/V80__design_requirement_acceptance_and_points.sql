ALTER TABLE design_requirements
    ADD COLUMN point_rule_code VARCHAR(80) NULL,
    ADD COLUMN base_point_snapshot INT NULL,
    ADD COLUMN owner_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN planner_accepted BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE design_requirements SET status = 'pending_acceptance'
WHERE status IN ('pending_self_score', 'pending_review');
