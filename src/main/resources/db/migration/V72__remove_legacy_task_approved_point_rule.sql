-- 子任务积分改为创建时选择具体规则；旧的通用验收规则不再参与新任务配置。
-- 历史子任务保存的是积分快照，不依赖该规则记录，因此可以安全移除。
DELETE FROM point_rules WHERE rule_code = 'TASK_APPROVED';
