-- 复审修复：积分异议 APPROVED 窄 TOCTOU 兜底（V40）。
-- 背景：submit() 的 existsBy(APPROVED) 检查与 INSERT 之间存在竞态——管理员恰在此时把同
-- (ledger,user) 的另一笔异议 PLANNER_PROCESSED→APPROVED（V38 生成列 active_ledger_user_key
-- 变 NULL），新 SUBMITTED 异议可绕过 V38 唯一索引插入，之后被再次 APPROVE 造成第二笔调账。
-- 方案：新增第二生成列 approved_ledger_user_key，status='APPROVED' 时生成非 NULL 键
-- （CONCAT('A:', point_ledger_id, ':', applicant_user_id)），其余状态为 NULL（NULL 不参与唯一），
-- 在其上建唯一索引，保证同 (ledger,user) 最多一笔 APPROVED，第二次 APPROVE 会被索引拒绝。
-- 采用新增 V40 而非改写已应用的 V38：V38 已在共享测试库应用（记录旧 checksum），改写会触发
-- checksum 校验失败需 repair；V40 为纯增量、幂等，测试库下次启动直接应用，无需任何手工修复。

-- 1) 清理存量重复 APPROVED：同 (point_ledger_id, applicant_user_id) 存在多条 APPROVED 时
--    （旧版代码允许 APPROVED 后重复申诉的历史数据），保留最早一条（id 最小），其余置为
--    REJECTED 并写 admin_comment 注明系统清理。
UPDATE point_appeals a
JOIN (
    SELECT point_ledger_id, applicant_user_id, MIN(id) AS keep_id
    FROM point_appeals
    WHERE status = 'APPROVED'
    GROUP BY point_ledger_id, applicant_user_id
    HAVING COUNT(*) > 1
) dup ON a.point_ledger_id = dup.point_ledger_id
    AND a.applicant_user_id = dup.applicant_user_id
    AND a.id <> dup.keep_id
SET a.status = 'REJECTED',
    a.admin_decision = 'REJECT',
    a.admin_comment = '系统清理重复终审异议',
    a.admin_reviewed_at = NOW(6);

-- 2) APPROVED 生成列（幂等：已存在则跳过）
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE point_appeals ADD COLUMN approved_ledger_user_key VARCHAR(170) GENERATED ALWAYS AS (CASE WHEN status = ''APPROVED'' THEN CONCAT(''A:'', point_ledger_id, '':'', applicant_user_id) ELSE NULL END) STORED',
    'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'point_appeals'
      AND column_name = 'approved_ledger_user_key');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) APPROVED 生成列唯一索引（幂等）
SET @sql = (SELECT IF(COUNT(*) = 0,
    'CREATE UNIQUE INDEX uk_point_appeal_approved_ledger_user ON point_appeals (approved_ledger_user_key)',
    'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'point_appeals'
      AND index_name = 'uk_point_appeal_approved_ledger_user');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
