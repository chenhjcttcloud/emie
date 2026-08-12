UPDATE system_configs SET config_value = '订单编号' WHERE config_key = 'feishu.sales.orderField' AND config_value IN ('订单号', '订单编号');
UPDATE system_configs SET config_value = '日期' WHERE config_key = 'feishu.sales.dateField' AND config_value IN ('订单日期', '日期');
UPDATE system_configs SET config_value = '收款金额' WHERE config_key = 'feishu.sales.amountField' AND config_value IN ('销售额', '收款金额');
UPDATE system_configs SET config_value = '回款状态' WHERE config_key = 'feishu.sales.statusField' AND config_value IN ('订单状态', '回款状态');
UPDATE system_configs SET config_value = '已回款' WHERE config_key = 'feishu.sales.validStatuses' AND config_value IN ('已完成,已回款,完成', '已回款');
