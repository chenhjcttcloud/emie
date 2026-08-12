INSERT INTO system_configs (config_key, config_value, config_group, description, value_type, sort_order, updated_at, updated_by)
VALUES ('feishu.sales.writeEnabled','false','feishu_base','允许管理员写入飞书销售表（默认关闭）','boolean',29,NOW(),'migration')
ON DUPLICATE KEY UPDATE config_key = VALUES(config_key);
