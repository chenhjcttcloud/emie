# 文件冷热分层存储 + NAS 归档方案

> 适用场景：服务器磁盘空间有限（40G），上传文件平均 100-200MB，需归档至本地 NAS

---

## 架构总览

```
服务器本地磁盘 (热存储)         NAS (冷存储)
  ┌─────────────────┐          ┌──────────────────┐
  │ uploads/         │  ─归档→  │ emie-archive/     │
  │ 最近3个月的文件  │  ←恢复─  │ 压缩后的历史文件   │
  └─────────────────┘          └──────────────────┘
        │                             │
        └──────────┬──────────────────┘
                   │
          file_records 表（索引）
```

---

## 数据库设计

### 新增 `file_records` 表

```sql
CREATE TABLE file_records (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stored_name     VARCHAR(255) NOT NULL,        -- 磁盘文件名，如 uuid.png
    original_name   VARCHAR(500) NOT NULL,        -- 原始文件名
    file_size       BIGINT NOT NULL,              -- 文件大小（字节）
    mime_type       VARCHAR(100),                 -- MIME 类型
    storage_tier    VARCHAR(20) NOT NULL DEFAULT 'local',
                    -- local=本地 / archived=已归档 / restoring=恢复中
    archive_path    VARCHAR(1000),                -- NAS 上的路径，如 nas:/emie-archive/2026/04/uuid.png.gz
    archive_size    BIGINT,                       -- 压缩后大小
    created_at      DATETIME NOT NULL,            -- 上传时间
    archived_at     DATETIME,                     -- 归档时间
    INDEX idx_tier (storage_tier),
    INDEX idx_created (created_at),
    INDEX idx_stored (stored_name)
);
```

---

## 核心流程

### 1. 上传文件
```
用户上传 → FileController 保存到 uploads/
         → 同时写入 file_records (storage_tier = 'local')
         → 返回 URL
```

### 2. 定时归档（每日凌晨 3:00）
```
查询条件：
  - storage_tier = 'local'
  - created_at < 当前时间 - 3个月

对每个符合条件的文件：
  1. gzip 压缩（压缩率约 60-70%）
  2. scp 推送到 NAS 的 /emie-archive/YYYY/MM/ 目录
  3. 更新 DB：storage_tier = 'archived', archive_path
  4. 删除本地原文件
```

### 3. 文件恢复（用户无感）
```
用户点击查看/下载 → FileController 处理：

  if (storage_tier == 'local')
    → 直接返回本地文件（与现在一样）

  if (storage_tier == 'archived')
    → scp 从 NAS 拉回 → gunzip 解压
    → 保存到本地临时缓存
    → 更新 DB：storage_tier = 'local'
    → 返回文件内容
```

### 4. 冷缓存淘汰（防止磁盘再次被撑满）
```
恢复的文件存到 /app/uploads/restore-cache/
当该目录超过 2GB 时，删除最久未访问的文件（LRU）
```

---

## NAS 配置

存储在 `system_configs` 表：

| 配置键 | 说明 | 示例值 |
|--------|------|--------|
| `nas.host` | NAS IP 地址 | `192.168.1.100` |
| `nas.user` | SSH 用户名 | `root` |
| `nas.password` | SSH 密码 | `xxx` |
| `nas.path` | NAS 存储路径 | `/volume1/emie-archive` |
| `nas.enabled` | 是否启用 NAS 归档 | `true` |

NAS 侧要求：仅需开启 SSH，无需安装任何额外软件。

---

## 新增文件清单

| 文件 | 说明 |
|------|------|
| `entity/FileRecord.java` | 文件记录实体 |
| `repository/FileRecordRepository.java` | 文件记录仓库 |
| `service/FileArchiveService.java` | 归档核心逻辑 |
| `service/FileArchiveTask.java` | 定时归档任务 |
| `controller/FileArchiveController.java` | 管理端归档接口 |
| 修改 `controller/FileController.java` | 上传/下载加入冷热切换 |

---

## 前端改动

- 系统管理新增"文件存储"标签页
- 显示磁盘使用率、归档文件统计
- 可手动触发归档 / 恢复指定文件
- 管理员可查看每个文件的存储层级（本地/NAS）

---

## 异常处理

| 场景 | 处理方式 |
|------|---------|
| NAS 离线 | 归档任务跳过，记录日志，次日重试 |
| 恢复时 NAS 离线 | 返回友好提示，不中断用户操作 |
| 磁盘空间不足 | 提前告警，缩短短期保留时间 |
| 并发恢复同一文件 | restoring 状态锁，防止重复拉取 |
