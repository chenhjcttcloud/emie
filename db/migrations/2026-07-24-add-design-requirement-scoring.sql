ALTER TABLE design_requirements
    ADD COLUMN designer_id VARCHAR(255) NULL,
    ADD COLUMN designer_name VARCHAR(255) NULL,
    ADD COLUMN reference_images_json LONGTEXT NULL,
    ADD COLUMN delivery_content TEXT NULL,
    ADD COLUMN delivery_attachments_json LONGTEXT NULL,
    ADD COLUMN delivery_reference_images_json LONGTEXT NULL,
    ADD COLUMN delivered_at DATETIME(6) NULL,
    ADD INDEX idx_design_requirement_designer (designer_id);

CREATE TABLE IF NOT EXISTS design_requirement_scores (
    id BIGINT NOT NULL AUTO_INCREMENT,
    requirement_id BIGINT NOT NULL,
    stage VARCHAR(20) NOT NULL,
    role VARCHAR(40) NOT NULL,
    reviewer_id VARCHAR(255) NULL,
    reviewer_name VARCHAR(255) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'waiting',
    score INT NULL,
    scored_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dr_score_requirement FOREIGN KEY (requirement_id)
        REFERENCES design_requirements (id) ON DELETE CASCADE,
    INDEX idx_dr_score_requirement (requirement_id),
    INDEX idx_dr_score_reviewer (reviewer_id, status),
    INDEX idx_dr_score_role (role, status)
);
