-- Workbook rows 5–34, identified separately from the prior D30 catalog.
ALTER TABLE point_rules ADD COLUMN small_change_points DECIMAL(12,2) NULL;
ALTER TABLE point_rules ADD COLUMN large_change_points DECIMAL(12,2) NULL;
ALTER TABLE point_rules ADD COLUMN small_change_note VARCHAR(500) NULL;
ALTER TABLE point_rules ADD COLUMN large_change_note VARCHAR(500) NULL;

ALTER TABLE sub_tasks ADD COLUMN small_change_points_snapshot DECIMAL(12,2) NULL;
ALTER TABLE sub_tasks ADD COLUMN large_change_points_snapshot DECIMAL(12,2) NULL;
ALTER TABLE sub_tasks ADD COLUMN small_change_note_snapshot VARCHAR(500) NULL;
ALTER TABLE sub_tasks ADD COLUMN large_change_note_snapshot VARCHAR(500) NULL;

INSERT INTO point_rules
    (rule_code, category, subcategory, description, points, small_change_points, large_change_points,
     small_change_note, large_change_note, quality_bonus_threshold, quality_bonus_ratio,
     quality_top_threshold, quality_top_ratio, max_total_multiplier, count_in_performance, enabled, created_at, updated_at)
VALUES
    ('VIS_V3_05', '包装设计', '包装设计', '常规包装设计｜原有固定模板/刀模，对产品重新排版（无配件、无 UI）', 4, 1, 3, '更新 CPR、版权方信息、调整包装 Logo 大小、增删元素、调整文字或信息栏、微调渲染图', '重新排版、重新定义设计风格', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_06', '包装设计', '包装设计', '常规包装设计｜原有固定模板/刀模，对产品重新排版（有配件、有 UI）', 6, 1, 3, '更新 CPR、版权方信息、调整包装 Logo 大小、增删元素、调整文字或信息栏、微调渲染图', '重新排版、重新定义设计风格', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_07', '包装设计', '包装设计', '重新定制包装设计｜改刀模、版式及元素，需要更新颖的设计，有新想法（有配件）', 8, 1, 3, '更新 CPR、版权方信息、调整包装 Logo 大小、增删元素、调整文字或信息栏、微调渲染图', '重新排版、重新定义设计风格', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_08', '包装设计', '包装送审', '包装送审｜输出包装送审文件，包含用于送审的文件修改和包装渲染图', 2, 1, 3, '更新 CPR、版权方信息、调整包装 Logo 大小、增删元素、调整文字或信息栏、微调渲染图', '重新排版、重新定义设计风格', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_09', '包装设计', '打烊', '打烊文件｜含整理并调整到样品通过为止', 2, 1, 3, '更新 CPR、版权方信息、调整包装 Logo 大小、增删元素、调整文字或信息栏、微调渲染图', '比较少', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_10', '包装设计', '大货', '大货文件｜输出规格箱、铭牌、包装、说明书、配件等文件，单个计件', 5, 1, 3, '修改参数、数量或版权信息', '重新排版、重新定义设计风格', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_11', '包装设计', '其他（特殊任务）', '配件设计｜相机挂绳、风扇挂绳等挂绳图案与丝印排版，含图案构思、适配可印区域及交付稿', 4, 1, 3, '图案、排版、文字或颜色调整', '重新设计', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_12', '包装设计', '其他（特殊任务）', '相机/牙刷 UI 设计｜静态 UI 基础上增加表情动态图、动态背景图及适配导出', 6, 1, 3, '图案、排版、文字、颜色或动态顺序调整', '重新设计', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_13', '包装设计', '其他（特殊任务）', '以上任务完结后需修改｜单个计件补修改分（例如更新已做货文件）', 2, 1, 2, NULL, NULL, 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_14', '宝描', '宝描设计', '完整详情页｜一整套详情页+主图/白底/SKU/直通车+切图', 20, 3, 6, '产品细节、配件、文字、页面顺序或增减、整体色调、渲染图等调整', '设计风格不符，需重新设计', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_15', '宝描', '宝描设计', '同款延展详情｜已有视觉体系，不同 IP/颜色/款式延展', 10, 3, 6, '产品细节、配件、文字、页面顺序或增减、整体色调、渲染图等调整', '设计风格不符，需重新设计', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_16', '宝描', '宝描设计', '独立第二套详情｜同产品，但视觉、场景、构图重新设计', 15, 3, 6, '产品细节、配件、文字、页面顺序或增减、整体色调、渲染图等调整', '设计风格不符，需重新设计', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_17', '宝描', '其他（特殊任务）', '修改详情（之前的详情修改）｜复用之前的详情，替换素材', 5, 1, 3, '产品细节、配件、文字、页面顺序或增减、整体色调、渲染图等调整', '设计风格不符，需重新设计', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_18', '宝描', '其他（特殊任务）', '精细渲染图｜单独进行高质量产品渲染/合成，按张计分', 2, 1, 3, NULL, NULL, 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_19', '海报视觉', '海报', '简单运营图｜简单排版，换图', 1, 1, 2, '颜色、排版或文字信息调整', '风格问题', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_20', '海报视觉', '海报', '标准海报｜独立构思+AI 生成+后期+排版', 4, 1, 2, '颜色、排版、文字信息或产品布局调整', '风格问题', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_21', '展位物料', '其他（特殊任务）', '展架/立牌｜单张独立展示设计，线下商超展台设计', 6, 1, 3, '尺寸、产品、渲染图、颜色、字体或位置调整，结合线下细节修改', '设计风格不符，需重新设计', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_22', '展位物料', '展会物料', '小型展会｜基础展墙+展架+少量平面+延展', 15, 3, 6, '尺寸、产品、渲染图、颜色、字体或位置调整，结合线下细节修改', '设计风格不符，需重新设计', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_23', '展位物料', '展会物料', '标准展会｜需要重新搭建场景（无标准）', 20, 3, 6, '尺寸、产品、渲染图、颜色、字体或位置调整，结合线下细节修改', '设计风格不符，需重新设计', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_24', '展位物料', '展会物料', '大型重点展会｜完整空间视觉体系+多区域+立体效果', 30, 3, 6, '尺寸、产品、渲染图、颜色、字体或位置调整，结合线下细节修改', '设计风格不符，需重新设计', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_25', '产品设计', '概念设计', '原创设计方案输出｜独立构思+AI 生成+建模落地+工艺输出等', 40, 3, 6, '颜色、丝印等调整', '模型调整', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_26', '产品设计', '概念设计', '外采设计方向产品初步设计输出｜AI 辅助效果图生成，效果图质量、AI 使用合理性、与草图一致性', 20, 1, 3, '颜色、丝印等调整', '方向微调整', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_27', '产品设计', '概念设计', '现有产品延伸设计｜其它 IP 款从 0 到 1 设计', 10, 1, 3, '颜色、丝印等调整', '图档更换', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_28', '产品设计', '3D 设计送审', '原创设计方向输出｜输出对应设计送审文件', 5, 1, 3, '效果图、颜色、丝印等调整', '模型调整或更换图档', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_29', '产品设计', '3D 设计送审', '外采设计方向输出｜输出对应设计送审文件', 5, 1, 2, '效果图、颜色、丝印等调整', '模型调整或更换图档', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_30', '产品设计', '3D 设计送审', '现有产品延伸设计', 5, 1, 3, '效果图、颜色、丝印等调整', '模型调整或更换图档', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_31', '产品设计', '3D 送审', '3D 文件输出｜送审前 3D 建模与调整/结合 AI 建模，3D 模型和细节调整、六视图送审及反馈处理', 5, 1, 3, '模型微调，耗时较短', '模型大幅调整，耗时较长', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_32', '产品设计', '报价文件', '首次打样文件输出/报价｜3D 模型文件、色号文件、丝印文件、配件文件等一整套', 4, 1, 3, '色号、丝印调整', '一般较少', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_33', '产品设计', '打烊文件', '最终打样方案输出｜3D 模型文件、色号文件、丝印文件、配件文件等一整套', 6, 1, 3, '色号、丝印调整', '一般较少', 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW()),
    ('VIS_V3_34', '产品设计', '其他（特殊任务）', '以上任务完结后需修改｜单个计件补修改分（例如更新已做货文件）', 2, 1, 2, NULL, NULL, 0, 0, 0, 0, 1, TRUE, TRUE, NOW(), NOW());

-- Keep previous definitions addressable by historical task and ledger references.
UPDATE point_rules
SET enabled = FALSE, updated_at = NOW()
WHERE SUBSTRING(rule_code, 1, 4) = 'D30_' AND enabled = TRUE;
