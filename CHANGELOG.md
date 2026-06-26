# 更新日志

## v2.1 (2026-06-26)

### ✨ 新功能

#### 系统操作日志
- 新增 `ActivityLogRepository` 数据访问层，支持日志持久化查询
- 登录/登出、项目查询等操作自动记录日志
- 管理后台新增「日志」查看 tab，支持按日期范围筛选
- 新增 `LogArchiveService` 归档服务：每月 1 日凌晨自动将上月日志压缩归档到 `logs/archive/`，查询时自动合并数据库与归档文件
- 管理员可通过 `POST /api/system/archive` 手动触发归档

#### 静止页面超时退出
- 60 分钟无操作自动退出登录，确保系统安全
- 最后 1 分钟在页面顶部显示倒计时提示
- 超时后弹出模态框提醒，需重新登录
- 支持键盘、鼠标、触摸、滚动等多种活动检测

### 🐛 Bug 修复

- 清理 `@Repository` 多余注解 — Spring Data JPA 自动实现，无需显式声明
- 移除 `DashboardController`、`ProjectController` 中未使用的依赖注入
- 移除 `ProjectService`、`UserService`、`ProjectSummaryDTO` 中未使用的 import
- 废弃导入清理，减少编译警告

### ⚙️ 工程优化

- 新增 `SchedulingConfig` 启用 Spring 定时任务支持
- 日志归档目录路径可配置（`app.log-archive.dir`）
- 添加 `logs/` 运行时目录到 `.gitignore`
