# 飞书 V2 单向数据镜像

飞书 V2 以系统数据库为唯一数据源，只允许系统向飞书写入，不提供飞书反向同步。

## 数据表与同步策略

- `项目总表_V2`、`子任务表_V2`、`评分记录表_V2`、`操作日志表_V2`是当前状态主表。
- 四张同名 `_backup` 表只随业务事件增量写入，定时对账不会覆盖备份。
- 数据删除时，主表记录删除，备份记录保留并标记“源数据已删除”和删除时间。
- 项目编号使用 `projects.project_code`；子任务编号使用 `sub_tasks.id`。
- 图片和附件先上传到飞书云空间，再以 `file_token` 写入附件字段。上传结果按 Base 和系统文件缓存，避免全量对账重复上传。

## 安全切换流程

以下接口均要求管理员登录：

1. `POST /api/admin/sync/v2/prepare`：创建或续建独立 V2 Base 和八张表，只写 staging 配置，不影响当前同步。
2. 在飞书中人工确认八张表字段、关联列和应用权限。
3. `POST /api/admin/sync/v2/activate`：保存旧 Base 配置后切换 V2。
4. `POST /api/admin/sync/full-resync`：按项目、子任务、评分、日志顺序入队首次全量同步。
5. 观察 `/api/admin/sync/stats`，失败记录归零后再次执行 `full-resync`，补齐首次写入时尚未建立的双向关联。

切换后发现问题可调用 `POST /api/admin/sync/v2/rollback`。回滚只恢复旧配置，不删除 V2 Base 或其中的数据。

## 发布要求

应用启动前必须先执行 Flyway 迁移 `V74__create_feishu_attachment_cache.sql`。飞书应用需要多维表格建表/字段/记录权限，以及云空间素材上传权限。单个附件当前限制为 20 MB，超过限制的队列项会失败并显示具体文件名。
