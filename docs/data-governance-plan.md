# EMIE 数据治理实施方案

## 目标

让文件、交付版本和驳回记录具备独立身份、可查询、可审计、可恢复，同时保留现有 JSON 字段作为兼容读取来源，避免一次性重写历史数据。

## 第一批治理对象

### 1. 文件引用表 `file_references`

建议字段：

- `id`
- `stored_name`
- `original_name`
- `owner_user_id`
- `target_type`：project / sub_task / design_requirement / rejection / delivery_version
- `target_id`
- `file_kind`：reference_image / attachment / delivery_image / delivery_attachment
- `source_json_path`
- `created_at`

约束：`stored_name`、`target_type`、`target_id`、`file_kind` 建索引；删除业务记录时不直接删除文件，只解除引用并进入清理候选。

### 2. 子任务交付版本表

现有 `sub_task_delivery_versions` 继续保留，并补齐唯一约束：`sub_task_id + version_no`。JSON 字段暂时保留为快照，不能覆盖历史版本。

### 3. 驳回记录表

从活动日志中的驳回快照逐步迁移为独立记录：

- `sub_task_id`
- `round_no`
- `reviewer_id`
- `reviewer_name`
- `comments`
- `required_completion_date`
- `submitted_deliverables_snapshot`
- `submitted_files_snapshot`
- `rejection_files_snapshot`
- `created_at`

## 迁移策略

1. 只新增表和索引，不修改旧 JSON 字段。
2. 先执行只读完整性扫描，输出缺失文件、重复引用、无法解析 JSON。
3. 新增数据双写旧 JSON 和新表。
4. 历史数据分批回填，每批可重试、可记录进度。
5. 新旧数据对账一致后，读取优先切换到新表。
6. 观察一个完整发布周期后，才考虑停止旧字段写入。

## 回滚规则

- 回滚应用时保留新表，不删除迁移数据。
- 旧版本继续读取 JSON 快照。
- 任何回填失败只标记该条记录，不中断整批任务。
- 生产迁移前必须备份数据库并保存迁移版本号。

## 当前阶段

本阶段只完成方案设计，尚未修改业务表、尚未回填数据、尚未执行生产迁移。下一步先实现只读完整性扫描和报告，不改变业务行为。
