ALTER TABLE departments
    ADD COLUMN business_owner_department_id BIGINT NULL,
    ADD COLUMN business_owner_user_id VARCHAR(255) NULL;
