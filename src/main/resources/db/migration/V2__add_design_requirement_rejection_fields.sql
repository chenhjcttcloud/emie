ALTER TABLE design_requirements
    ADD COLUMN IF NOT EXISTS rejection_comments TEXT NULL,
    ADD COLUMN IF NOT EXISTS rejection_deadline VARCHAR(255) NULL;
