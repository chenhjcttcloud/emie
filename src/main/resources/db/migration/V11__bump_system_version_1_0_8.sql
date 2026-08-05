-- 本次通知流程与待接单提醒修复版本
UPDATE system_configs
SET config_value = '1.0.8', updated_at = CURRENT_TIMESTAMP
WHERE config_key = 'system.version'
  AND CAST(SUBSTRING_INDEX(config_value, '.', 1) AS UNSIGNED) = 1
  AND CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(config_value, '.', 2), '.', -1) AS UNSIGNED) = 0
  AND CAST(SUBSTRING_INDEX(config_value, '.', -1) AS UNSIGNED) < 8;
