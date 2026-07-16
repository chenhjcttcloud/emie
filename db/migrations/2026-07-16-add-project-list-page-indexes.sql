-- 项目列表分页：覆盖角色归属、类型筛选与创建时间倒序查询。
CREATE INDEX idx_project_type_created ON projects (type, created_at);
CREATE INDEX idx_project_sales_type_created ON projects (sales_id, type, created_at);
CREATE INDEX idx_project_planner_type_created ON projects (planner_id, type, created_at);

-- 设计师/供应链参与项目分页：覆盖负责人角色、负责人和任务状态筛选。
CREATE INDEX idx_sub_task_assignee_designer_status ON sub_tasks (assignee_role, designer_id, status);
