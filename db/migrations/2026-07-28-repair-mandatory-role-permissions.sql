-- 修复旧版角色编辑将未展示权限保存为 deny 的历史数据。
-- 管理员必须保留身份切换恢复入口；标准业务角色必须可以处理业务文件。
UPDATE role_permissions rp
JOIN roles r ON r.id = rp.role_id
JOIN permissions p ON p.id = rp.permission_id
SET rp.effect = 'allow', rp.updated_at = NOW(6)
WHERE p.code = 'admin.identity.switch'
  AND LOWER(r.name) IN ('admin', 'administrator', '管理员');

UPDATE role_permissions rp
JOIN roles r ON r.id = rp.role_id
JOIN permissions p ON p.id = rp.permission_id
SET rp.effect = 'allow', rp.updated_at = NOW(6)
WHERE p.code IN ('file.upload', 'file.download', 'file.preview')
  AND LOWER(r.name) IN ('admin', 'administrator', '管理员', 'sales', 'planner', 'designer',
                        'promotion', 'product_promotion', 'product-promotion',
                        'supplychain', 'supply_chain', 'supply-chain', 'supply', '供应链', '产品企划', '设计师');
