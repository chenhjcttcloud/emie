UPDATE point_rules SET category='通用'
WHERE rule_code='TASK_APPROVED' AND (category IS NULL OR TRIM(category)='');
