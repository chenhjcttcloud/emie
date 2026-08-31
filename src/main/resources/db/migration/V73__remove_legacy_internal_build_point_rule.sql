-- 内部建设不再作为子任务积分项；素材广场采纳规则仍保留给自动采纳流程使用。
DELETE FROM point_rules WHERE rule_code = 'S1';
