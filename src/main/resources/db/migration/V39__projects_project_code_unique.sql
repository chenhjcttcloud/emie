-- P2-1：projects.project_code 唯一索引兜底。
-- nextProjectCode 注释原称“数据库唯一约束负责最终兜底”，但存量库并无该约束；
-- 本迁移先清理存量脏数据，再加唯一索引，服务层并发冲突时捕获唯一约束异常重试一次。

-- 1) 空字符串编号与 NULL 同等处理（NULL 不参与 MySQL 唯一索引），避免历史脏数据阻塞建索引。
UPDATE projects SET project_code = NULL WHERE project_code = '';

-- 2) 加宽编号列（原 VARCHAR(14) 无法容纳 -DUP-<id> 后缀），与实体 @Column(length=40) 保持一致。
ALTER TABLE projects MODIFY COLUMN project_code VARCHAR(40) NULL;

-- 3) 清理存量重复编号：保留最早一条（id 最小），其余追加 -DUP-<id> 后缀。
UPDATE projects p
JOIN (
    SELECT project_code, MIN(id) AS keep_id
    FROM projects
    WHERE project_code IS NOT NULL
    GROUP BY project_code
    HAVING COUNT(*) > 1
) dup ON p.project_code = dup.project_code AND p.id <> dup.keep_id
SET p.project_code = CONCAT(p.project_code, '-DUP-', p.id);

-- 4) 加唯一索引（幂等：Hibernate ddl-auto:update 可能已按实体 @Column(unique=true)
--    创建了同名唯一索引，先查 information_schema 再创建，避免重复键名报错。）
SET @sql = (SELECT IF(COUNT(*) = 0,
    'CREATE UNIQUE INDEX uk_projects_project_code ON projects (project_code)',
    'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'projects'
      AND index_name = 'uk_projects_project_code');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
