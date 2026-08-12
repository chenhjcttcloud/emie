ALTER TABLE point_rules
    ADD COLUMN quality_top_threshold INT NOT NULL DEFAULT 97,
    ADD COLUMN quality_top_ratio DOUBLE NOT NULL DEFAULT 0.60,
    ADD COLUMN max_total_multiplier DOUBLE NOT NULL DEFAULT 3.0;

ALTER TABLE sub_tasks
    ADD COLUMN quality_top_threshold_snapshot INT NULL,
    ADD COLUMN quality_top_ratio_snapshot DOUBLE NULL,
    ADD COLUMN max_total_multiplier_snapshot DOUBLE NULL;

INSERT INTO point_rules
    (rule_code, points, enabled, description, category, difficulty_multiplier,
     quality_bonus_threshold, quality_bonus_ratio, quality_top_threshold, quality_top_ratio,
     max_total_multiplier, count_in_performance, created_at, updated_at)
VALUES
('B1',25,TRUE,'原创产品设计（全新概念，含建模渲染工艺）','B',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('B2',10,TRUE,'外采产品IP化设计','B',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('B3',4,TRUE,'新增SKU / 配色衍生','B',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('B4',10,TRUE,'展会样品 / 客户定制产品','B',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('B5',3,TRUE,'3D建模渲染出图（非B1配套）','B',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('B6',5,TRUE,'3D公仔建模 / 输出','B',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('A1',10,TRUE,'包装整套（彩盒+贴纸+说明书+箱规）','A',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('A2',6,TRUE,'包装单项（仅彩盒或礼盒）','A',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('A3',3,TRUE,'包装修改 / 刀模 / 箱规 / 执行标准','A',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('A4',3,TRUE,'包装多语言版','A',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('A5',10,TRUE,'详情页全套（主图+长图+切片）','A',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('A6',4,TRUE,'详情页局部 / 改版','A',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('A7',3,TRUE,'主图 / 单张卖点图','A',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('A8',3,TRUE,'海报 / 立牌 / 单页','A',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('A9',8,TRUE,'展会物料整套（图册展架名片）','A',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('A10',4,TRUE,'UI界面 / 灯珠图案 / 待机页','A',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('A11',3,TRUE,'AI生图 / 场景图 / 推广图','A',1,95,.30,97,.60,3,TRUE,NOW(),NOW()),
('E1',2,TRUE,'送审文件 / 送审调整','E',1,101,0,101,0,3,TRUE,NOW(),NOW()),
('E2',2,TRUE,'打样文件输出','E',1,101,0,101,0,3,TRUE,NOW(),NOW()),
('E3',2,TRUE,'报价文件','E',1,101,0,101,0,3,TRUE,NOW(),NOW()),
('E4',3,TRUE,'工厂跟单调色 / 大货文件','E',1,101,0,101,0,3,TRUE,NOW(),NOW()),
('S1',3,TRUE,'内部建设（素材库/模板库/提示词库/竞品图库）','S',1,101,0,101,0,3,TRUE,NOW(),NOW())
ON DUPLICATE KEY UPDATE rule_code = VALUES(rule_code);

UPDATE sub_tasks t JOIN point_rules r ON r.rule_code = t.point_rule_code
SET t.quality_top_threshold_snapshot = COALESCE(t.quality_top_threshold_snapshot, r.quality_top_threshold),
    t.quality_top_ratio_snapshot = COALESCE(t.quality_top_ratio_snapshot, r.quality_top_ratio),
    t.max_total_multiplier_snapshot = COALESCE(t.max_total_multiplier_snapshot, r.max_total_multiplier);

INSERT INTO system_configs
    (config_key, config_value, config_group, description, value_type, sort_order, updated_at, updated_by)
VALUES
('points.program.mode','TRIAL','points','积分制度模式：TRIAL试运行 / ACTIVE正式挂钩','text',20,NOW(),'migration'),
('points.program.trial_start','2026-08-01','points','积分试运行开始日期','text',21,NOW(),'migration'),
('points.program.trial_end','2026-09-30','points','积分试运行结束日期','text',22,NOW(),'migration'),
('points.program.active_start','2026-10-01','points','积分正式挂钩日期','text',23,NOW(),'migration'),
('points.appeal.sla_workdays','3','points','积分异议处理时限（工作日）','number',24,NOW(),'migration')
ON DUPLICATE KEY UPDATE config_key = VALUES(config_key);
