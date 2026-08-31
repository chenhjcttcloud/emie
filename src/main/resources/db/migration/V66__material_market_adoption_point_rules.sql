INSERT INTO point_rules(rule_code,points,category,difficulty_multiplier,quality_bonus_threshold,quality_bonus_ratio,quality_top_threshold,quality_top_ratio,max_total_multiplier,count_in_performance,enabled,description,created_at,updated_at)
SELECT 'MATERIAL_MARKET_DIRECT_ADOPTION',20,'material_market',1,0,0,97,0.6,3,true,true,'素材广场直接采纳奖励',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM point_rules WHERE rule_code='MATERIAL_MARKET_DIRECT_ADOPTION');
INSERT INTO point_rules(rule_code,points,category,difficulty_multiplier,quality_bonus_threshold,quality_bonus_ratio,quality_top_threshold,quality_top_ratio,max_total_multiplier,count_in_performance,enabled,description,created_at,updated_at)
SELECT 'MATERIAL_MARKET_DESIGN_ADOPTION',10,'material_market',1,0,0,97,0.6,3,true,true,'素材广场设计采纳奖励',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM point_rules WHERE rule_code='MATERIAL_MARKET_DESIGN_ADOPTION');
