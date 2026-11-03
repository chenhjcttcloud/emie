-- 查看同步状态与执行同步分开；不修改任何现有角色的 execute 授权或显式 deny。
INSERT IGNORE INTO permissions
    (code, name, module, description, risk_level, enabled, created_at, updated_at)
VALUES
    ('admin.feishu_sync.view', '查看飞书同步', '系统管理', '查看飞书同步状态、队列统计与字段结构', 'normal', 1, NOW(6), NOW(6));
