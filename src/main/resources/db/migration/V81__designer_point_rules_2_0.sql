-- 设计部积分规则 2.0 主表：每个有效行独立成规则；保留旧规则和历史快照、流水。
ALTER TABLE point_rules MODIFY COLUMN points DECIMAL(12,2) NOT NULL;
ALTER TABLE sub_tasks MODIFY COLUMN base_point_snapshot DECIMAL(12,2) NULL;
ALTER TABLE sub_tasks ADD COLUMN concept_reserve_exempt BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE design_requirements MODIFY COLUMN base_point_snapshot DECIMAL(12,2) NULL;
ALTER TABLE design_requirements ADD COLUMN difficulty_multiplier_snapshot DECIMAL(3,1) NOT NULL DEFAULT 1.0;
ALTER TABLE design_requirements ADD COLUMN concept_reserve_exempt BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE point_rules SET enabled = FALSE, updated_at = NOW() WHERE category IN ('A', 'B', 'E');

INSERT INTO point_rules
    (rule_code, points, enabled, description, category, quality_bonus_threshold, quality_bonus_ratio, quality_top_threshold, quality_top_ratio, max_total_multiplier, count_in_performance, created_at, updated_at)
VALUES
    ('D20_6', 2.5, TRUE, '常规包装：固定模板/刀模，替换IP、颜色、文字、元素；含首稿、约2轮正常调整及最终生产文件', '包装设计', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_7', 6, TRUE, '定制包装：改刀模、版式及元素，需要重新设计；含首稿、约2轮正常调整及最终生产文件', '包装设计', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_8', 15, TRUE, '原创包装：新包装形式+视觉设计+效果图；含首稿、约2轮正常调整及最终生产文件', '包装设计', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_9', 1, TRUE, '包装小改：已有包装文字、位置、颜色等调整', '包装设计', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_10', 3, TRUE, '包装大改：主要版式/视觉重新调整', '包装设计', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_11', 15, TRUE, '完整详情页：7–12P，AI设计+人工完善+主图/白底/SKU/直通车+切图', '电商视觉', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_12', 7, TRUE, '同款延展详情：已有视觉体系，不同IP/颜色/款式延展', '电商视觉', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_13', 11, TRUE, '独立第二套详情：同产品，但视觉、场景、构图重新设计', '电商视觉', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_14', 1.5, TRUE, '详情小改：文字、图片、卖点、局部版式', '电商视觉', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_15', 4, TRUE, '详情大改：多页面重新设计', '电商视觉', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_16', 3, TRUE, '精细渲染图：单独进行高质量产品渲染/合成；按张计分；须为单独精细渲染任务', '电商视觉', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_17', 2, TRUE, '简单运营图：AI背景/已有素材+简单排版', '海报视觉', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_18', 3, TRUE, '标准海报：独立构思+AI生成+后期+排版', '海报视觉', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_19', 5, TRUE, '重点KV：活动核心视觉/较高创意要求', '海报视觉', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_20', 3, TRUE, '展架/立牌：单张独立展示设计', '海报视觉', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_21', 0.5, TRUE, 'KV尺寸延展：已有设计，仅适配尺寸/版式', '海报视觉', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_22', 12, TRUE, '小型展会：基础展墙+展架+少量平面；按整体项目计分，包含正常调整', '展会物料', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_23', 20, TRUE, '标准展会：场景+展墙+展架+综合平面+效果表现；按整体项目计分，包含正常调整', '展会物料', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_24', 30, TRUE, '大型重点展会：完整空间视觉体系+多区域+立体效果；按整体项目计分，包含正常调整', '展会物料', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_26', 30, TRUE, '原创设计方向：创意性、市场需求契合度、可行性分析', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_27', 1, TRUE, '草图输出/2D矢量图：完整性、表达清晰度、图档运用合理', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_28', 2, TRUE, 'AI辅助效果图生成：效果图质量、AI使用合理性、与草图一致性', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_29', 2, TRUE, '产品提案：概念+卖点+视觉方向+使用逻辑+多角度', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_30', 3, TRUE, '送审前3D建模与调整/结合AI建模： 模型完整性、细节表现', '产品设计-深化', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_31', 2, TRUE, '六视图送审及反馈处理：3D模型/细节调整', '产品设计-优化', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_32', 3, TRUE, '首次打样文件输出：3D模型文件，色号文件，丝印文件，配件文件等一整套', '产品设计-落地', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_33', 1, TRUE, '样品送审及反馈处理：工艺，颜色，细节同步确认，对接/项目/采购/工厂', '产品设计-落地', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_34', 2, TRUE, '最终做货文件输出：3D模型文件，色号文件，丝印文件，配件文件等一整套', '产品设计-量产', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_35', 2, TRUE, '全套产品细节确认：量产产品完整性审核，IP公仔、logo及版权标、外观颜色等', '产品设计-量产', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_36', 10, TRUE, '产品迭代：增加IP', '产品设计-优化', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_37', 2, TRUE, '产品改良：调整颜色，增加配件', '产品设计-优化', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_39', 1, TRUE, '定制项目／定制项目：对客户需求的把握、可行性评估', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_40', 10, TRUE, '定制项目／方案设计与输出：设计完整性、客户满意度', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_41', 2, TRUE, '定制项目／送审文件整理：符合版权方要求的内容', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_42', 3, TRUE, '定制项目／打样文件输出/报价：3D模型文件，色号文件，丝印文件，配件文件等一整套', '产品设计-落地', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_43', 2, TRUE, '定制项目／样品送审及反馈处理：修改次数、迭代响应速度', '产品设计-落地', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_44', 2, TRUE, '定制项目／最终打样方案输出：3D模型文件，色号文件，丝印文件，配件文件等一整套', '产品设计-量产', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_46', 2, TRUE, '外采改良／外采改良设计：对外采设计评估与分析，现有设计的前期评估、优缺点总结', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_47', 3, TRUE, '外采改良／改良方案提出：改进合理性和创新性', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_48', 15, TRUE, '外采改良／方案输出：改良后的设计方案是否完整、准确', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_49', 2, TRUE, '外采改良／送审文件整理：符合版权方要求的内容', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_50', 2, TRUE, '外采改良／送审及反馈处理：修改次数、迭代响应速度', '产品设计-概念', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_51', 3, TRUE, '外采改良／打样文件输出/报价：3D模型文件，色号文件，丝印文件，配件文件等一整套', '产品设计-落地', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_52', 2, TRUE, '外采改良／样品送审及反馈处理：修改次数、迭代响应速度', '产品设计-落地', 0, 0, 101, 0, 3, TRUE, NOW(), NOW()),
    ('D20_53', 2, TRUE, '外采改良／最终打样方案输出：3D模型文件，色号文件，丝印文件，配件文件等一整套', '产品设计-量产', 0, 0, 101, 0, 3, TRUE, NOW(), NOW())
ON DUPLICATE KEY UPDATE rule_code = VALUES(rule_code);
