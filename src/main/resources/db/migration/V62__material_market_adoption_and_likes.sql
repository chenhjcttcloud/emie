ALTER TABLE material_market_items
    ADD COLUMN adoption_type VARCHAR(20) NULL AFTER selected_at,
    ADD COLUMN like_count INT NOT NULL DEFAULT 0 AFTER adoption_type;

UPDATE material_market_items
SET adoption_type = 'direct'
WHERE status = 'selected' AND adoption_type IS NULL;

CREATE TABLE material_market_likes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id BIGINT NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_material_market_like UNIQUE (material_id, user_id),
    CONSTRAINT fk_material_market_like_material FOREIGN KEY (material_id) REFERENCES material_market_items(id) ON DELETE CASCADE,
    INDEX idx_material_market_like_user (user_id)
);

UPDATE point_rules
SET enabled = FALSE,
    description = '旧素材广场统一立项奖励（已由直接采纳20分、设计采纳10分替代）',
    updated_at = CURRENT_TIMESTAMP
WHERE rule_code = 'MATERIAL_MARKET_LAUNCH';
