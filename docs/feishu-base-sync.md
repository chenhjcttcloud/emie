# EMIE → 飞书多维表格同步方案

## 架构

```
EMIE 业务操作
    │
    ▼
@TransactionalEventListener
    │
    ▼
sync_queue 表 (pending)
    │
    ▼
SyncWorker (@Scheduled fixedDelay=30s)
    │
    ▼
FeishuBaseService → 飞书 OpenAPI → Base (bitable)
```

## 飞书 Base 表结构

### 表1: 项目总表
- 项目ID(文本), 类型(单选), 状态(单选), 销售(文本), 产品企划(文本)
- 截止日期(日期), 产品类目(文本), 参考价格(文本)
- 子任务数(数字), 完成进度(百分比), 创建时间(日期)

### 表2: 子任务表
- 子任务ID(文本), 任务名称(文本), 状态(单选), 负责人(文本)
- 计划日期(日期), 实际完成(日期), 自评分(数字)
- 所属项目(关联→项目总表), 创建时间(日期)

### 表3: 评分记录表
- 评分ID(文本), 评分角色(单选), 评分(数字), 权重(百分比)
- 所属子任务(关联→子任务表)

### 表4: 操作日志表
- 日志ID(文本), 时间(日期), 操作人(文本), 操作内容(文本)
- 所属项目(关联→项目总表)

## 配置项（系统管理 → 飞书多维表格）

| 配置键 | 说明 |
|--------|------|
| feishu.base.syncEnabled | 启用同步 |
| feishu.base.appToken | Base App Token |
| feishu.base.tableProjects | 项目总表 Table ID |
| feishu.base.tableTasks | 子任务表 Table ID |
| feishu.base.tableScoring | 评分记录表 Table ID |
| feishu.base.tableLogs | 操作日志表 Table ID |

## 前提条件
1. 在飞书开放平台为应用添加 bitable 权限
2. 创建一个多维表格，建好 4 张表并配置字段
3. 在系统管理 → 飞书多维表格 中填入配置
