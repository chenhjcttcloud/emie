ALTER TABLE material_market_items
    MODIFY COLUMN ip_name VARCHAR(100) NULL,
    ADD COLUMN post_type VARCHAR(20) NOT NULL DEFAULT 'idea' AFTER creator_name,
    ADD COLUMN free_category VARCHAR(30) NULL AFTER post_type,
    ADD COLUMN free_content_json LONGTEXT NULL AFTER free_category;
