INSERT INTO system_configs (config_key, config_value, config_group, description, value_type, sort_order, updated_at, updated_by)
VALUES
('feishu.sales.enabled','false','feishu_base','启用飞书销售额自动同步','boolean',20,NOW(),'migration'),
('feishu.sales.appToken','','feishu_base','销售数据飞书 Base App Token（留空使用独立销售表配置）','text',21,NOW(),'migration'),
('feishu.sales.tableId','','feishu_base','销售订单数据表 Table ID','text',22,NOW(),'migration'),
('feishu.sales.orderField','订单号','feishu_base','销售订单唯一编号字段','text',23,NOW(),'migration'),
('feishu.sales.dateField','订单日期','feishu_base','销售订单日期字段','text',24,NOW(),'migration'),
('feishu.sales.amountField','销售额','feishu_base','销售订单金额字段（元或万元需保持统一）','text',25,NOW(),'migration'),
('feishu.sales.refundField','退款金额','feishu_base','退款金额字段','text',26,NOW(),'migration'),
('feishu.sales.statusField','订单状态','feishu_base','订单状态字段','text',27,NOW(),'migration'),
('feishu.sales.validStatuses','已完成,已回款,完成','feishu_base','计入绩效的有效订单状态，逗号分隔','text',28,NOW(),'migration')
ON DUPLICATE KEY UPDATE config_key = VALUES(config_key);
