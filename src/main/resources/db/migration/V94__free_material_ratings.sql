CREATE TABLE material_market_ratings (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    material_id BIGINT NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    stars INT NOT NULL,
    CONSTRAINT uk_material_rating_user UNIQUE (material_id, user_id),
    CONSTRAINT chk_material_rating_stars CHECK (stars BETWEEN 0 AND 5)
);
