ALTER TABLE material_market_items
    ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'visual' AFTER ip_name;

CREATE INDEX idx_material_category ON material_market_items(category);
