ALTER TABLE standard_point_configs
    ADD COLUMN performance_base DOUBLE NOT NULL DEFAULT 0,
    ADD COLUMN department_type VARCHAR(20) NOT NULL DEFAULT 'SUPPORT';
ALTER TABLE monthly_performance_configs ADD COLUMN sales_amount DOUBLE NULL;

INSERT INTO system_configs
    (config_key, config_value, config_group, description, value_type, sort_order, updated_at, updated_by)
VALUES
('points.performance.sales.lt200.max','200','points','业绩系数档位：低于该销售额（万元）所有岗位系数为0','number',30,NOW(),'migration'),
('points.performance.sales.mid.max','300','points','业绩系数档位：第二档销售额上限（万元）','number',31,NOW(),'migration'),
('points.performance.sales.normal.max','350','points','业绩系数档位：第三档销售额上限（万元）','number',32,NOW(),'migration'),
('points.performance.sales.high.max','400','points','业绩系数档位：第四档销售额上限（万元）','number',33,NOW(),'migration'),
('points.performance.coefficient.mid.support','1.0','points','200-300万支撑平台中心系数','number',34,NOW(),'migration'),
('points.performance.coefficient.mid.business','0.6','points','200-300万业务涉及部门系数','number',35,NOW(),'migration'),
('points.performance.coefficient.normal','1.0','points','300-350万所有岗位系数','number',36,NOW(),'migration'),
('points.performance.coefficient.high','1.5','points','350-400万所有岗位系数','number',37,NOW(),'migration'),
('points.performance.coefficient.top','2.0','points','400万及以上所有岗位系数','number',38,NOW(),'migration')
ON DUPLICATE KEY UPDATE config_key=VALUES(config_key);
