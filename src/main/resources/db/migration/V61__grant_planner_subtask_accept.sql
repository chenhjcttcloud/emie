UPDATE role_permissions rp
JOIN roles r ON r.id = rp.role_id
JOIN permissions p ON p.id = rp.permission_id
SET rp.effect = 'allow', rp.updated_at = NOW(6)
WHERE p.code = 'subtask.accept'
  AND LOWER(r.name) IN ('planner', 'product_planner', 'product-planner', '产品企划', '企划');
