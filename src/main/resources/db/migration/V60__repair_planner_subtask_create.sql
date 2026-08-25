-- 修复历史角色配置将企划“新建子任务”权限误保存为 deny 的问题。
-- 项目负责人归属仍由 ProjectService.addSubTask() 继续校验。
UPDATE role_permissions rp
JOIN roles r ON r.id = rp.role_id
JOIN permissions p ON p.id = rp.permission_id
SET rp.effect = 'allow', rp.updated_at = NOW(6)
WHERE p.code = 'subtask.create'
  AND LOWER(r.name) IN ('planner', 'product_planner', 'product-planner', '产品企划', '企划');
