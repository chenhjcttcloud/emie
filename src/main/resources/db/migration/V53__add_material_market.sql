CREATE TABLE IF NOT EXISTS material_market_items (id BIGINT AUTO_INCREMENT PRIMARY KEY,title VARCHAR(200) NOT NULL,creator_id VARCHAR(100) NOT NULL,creator_name VARCHAR(200) NOT NULL,ip_name VARCHAR(100) NOT NULL,material_files_json LONGTEXT NOT NULL,product_description TEXT NOT NULL,proposal_ppt_json TEXT NULL,status VARCHAR(20) NOT NULL,project_id BIGINT NULL,selected_by VARCHAR(100),selected_at DATETIME(6),created_at DATETIME(6) NOT NULL,CONSTRAINT uk_material_project UNIQUE(project_id));
ALTER TABLE projects ADD COLUMN creative_author_id VARCHAR(100) NULL;
ALTER TABLE projects ADD COLUMN creative_author_name VARCHAR(200) NULL;
ALTER TABLE projects ADD COLUMN source VARCHAR(50) NULL;
INSERT INTO point_rules(rule_code,points,category,difficulty_multiplier,quality_bonus_threshold,quality_bonus_ratio,quality_top_threshold,quality_top_ratio,max_total_multiplier,count_in_performance,enabled,description,created_at,updated_at)
SELECT 'MATERIAL_MARKET_LAUNCH',30,'material_market',1,0,0,97,0.6,3,true,true,'素材广场立项创意作者积分',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM point_rules WHERE rule_code='MATERIAL_MARKET_LAUNCH');
