CREATE TABLE material_market_adoptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    adoption_type VARCHAR(20) NOT NULL,
    selected_by VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_material_adoption_material FOREIGN KEY (material_id) REFERENCES material_market_items(id) ON DELETE CASCADE,
    INDEX idx_material_adoption_material (material_id, created_at),
    INDEX idx_material_adoption_project (project_id)
);

INSERT INTO material_market_adoptions (material_id, project_id, adoption_type, selected_by, created_at)
SELECT id, project_id, COALESCE(adoption_type, 'direct'), COALESCE(selected_by, creator_id), COALESCE(selected_at, created_at)
FROM material_market_items
WHERE project_id IS NOT NULL;
