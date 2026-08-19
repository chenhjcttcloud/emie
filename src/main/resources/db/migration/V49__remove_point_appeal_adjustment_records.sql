-- 积分异议流程已下线，清理历史异议调账记录。
DELETE FROM point_adjustment_ledgers WHERE source_type = 'APPEAL';
