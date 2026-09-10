ALTER TABLE material_market_items
    ADD COLUMN ai_assisted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否 AI 参与制图';
