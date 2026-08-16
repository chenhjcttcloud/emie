-- 积分异议重复入账防护（任务1）：
-- 1) 清理存量重复：同 (point_ledger_id, applicant_user_id) 存在多条处理中异议（SUBMITTED/PLANNER_PROCESSED）时，
--    保留最早一条（id 最小），其余置为 REJECTED 并写 admin_comment 注明系统清理。
-- 2) 处理中状态部分唯一索引：新增生成列 active_ledger_user_key，仅对 status IN ('SUBMITTED','PLANNER_PROCESSED')
--    的行生成 CONCAT(point_ledger_id, ':', applicant_user_id)，其余为 NULL（MySQL 唯一索引对 NULL 不参与唯一），
--    在该生成列上建唯一索引兜底并发双提交/重复入账。
--    说明：不使用 MySQL 函数索引（CASE WHEN 表达式索引），因为 Hibernate 6.6 的 ddl-auto
--    update/validate 在读取函数索引元数据时列名为 NULL 会抛 NPE（null was passed as an object name）。

-- 1) 清理存量重复
UPDATE point_appeals a
JOIN (
    SELECT point_ledger_id, applicant_user_id, MIN(id) AS keep_id
    FROM point_appeals
    WHERE status IN ('SUBMITTED', 'PLANNER_PROCESSED')
    GROUP BY point_ledger_id, applicant_user_id
    HAVING COUNT(*) > 1
) dup ON a.point_ledger_id = dup.point_ledger_id
    AND a.applicant_user_id = dup.applicant_user_id
    AND a.id <> dup.keep_id
SET a.status = 'REJECTED',
    a.admin_decision = 'REJECT',
    a.admin_comment = '系统清理重复异议',
    a.admin_reviewed_at = NOW(6);

-- 2) 生成列（幂等：已存在则跳过）
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE point_appeals ADD COLUMN active_ledger_user_key VARCHAR(160) GENERATED ALWAYS AS (CASE WHEN status IN (''SUBMITTED'',''PLANNER_PROCESSED'') THEN CONCAT(point_ledger_id, '':'', applicant_user_id) ELSE NULL END) STORED',
    'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'point_appeals'
      AND column_name = 'active_ledger_user_key');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) 生成列唯一索引（幂等）
SET @sql = (SELECT IF(COUNT(*) = 0,
    'CREATE UNIQUE INDEX uk_point_appeal_active_ledger_user ON point_appeals (active_ledger_user_key)',
    'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'point_appeals'
      AND index_name = 'uk_point_appeal_active_ledger_user');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
