-- P1-4 修复：point_adjustment_ledgers 增加 accounting_month（会计归属月），与 point_ledgers 归月口径统一。
-- 背景：月度排行榜/统计对积分流水按 accounting_month 归月，而调账表没有该列，只能按 created_at 归月，
-- 两者口径不一致导致月度数据漂移（例如 PO 履职积分按管理员确认时间落月，而非进展所属月）。
-- 方案：新增 accounting_month 列；存量数据按 created_at 回填（与旧口径完全一致，存量月度统计零漂移）；
-- 新写入的调账由服务层设置归属月（PO_PROGRESS 按进展所属月 month_key，APPEAL/退单缺省即入账当月，
-- 实体 @PrePersist 兜底）。索引与 V24 对 point_ledgers 的 user_month 索引保持一致。
ALTER TABLE point_adjustment_ledgers ADD COLUMN accounting_month VARCHAR(7) NULL;
UPDATE point_adjustment_ledgers SET accounting_month = DATE_FORMAT(created_at, '%Y-%m') WHERE accounting_month IS NULL;
CREATE INDEX idx_point_adjustment_user_month ON point_adjustment_ledgers(user_id, accounting_month);
