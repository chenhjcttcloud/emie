ALTER TABLE material_market_adoptions
    ADD CONSTRAINT uk_material_adoption_type UNIQUE (material_id, adoption_type);
