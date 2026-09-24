ALTER TABLE sub_tasks ADD COLUMN base_point_processed_at DATETIME(6) NULL;
ALTER TABLE design_requirements ADD COLUMN points_awarded BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE design_requirements d
SET points_awarded = TRUE
WHERE EXISTS (
    SELECT 1 FROM point_adjustment_ledgers a
    WHERE a.source_type = 'DESIGN_REQUIREMENT' AND a.source_id = d.id
);
