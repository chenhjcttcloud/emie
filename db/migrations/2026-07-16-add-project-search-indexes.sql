-- 项目服务端筛选：常用“归属 + 类型 + 状态 + 创建时间”组合，按当前读路径新增。
CREATE INDEX idx_project_type_status_created ON projects (type, status, created_at);
CREATE INDEX idx_project_sales_type_status_created ON projects (sales_id, type, status, created_at);
CREATE INDEX idx_project_planner_type_status_created ON projects (planner_id, type, status, created_at);
