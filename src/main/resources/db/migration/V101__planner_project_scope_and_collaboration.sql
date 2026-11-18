-- NULL preserves the role's existing visibility until an administrator sets an override.
ALTER TABLE users ADD COLUMN project_data_scope VARCHAR(20) NULL;

CREATE TABLE project_collaborators (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    assigned_by VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_project_collaborator UNIQUE (project_id, user_id),
    CONSTRAINT fk_project_collaborator_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE CASCADE,
    INDEX idx_project_collaborator_user (user_id, project_id)
);
