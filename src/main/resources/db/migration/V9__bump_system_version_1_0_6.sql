UPDATE system_configs
SET config_value = '1.0.6'
WHERE config_key = 'system.version'
  AND config_value <> '1.0.6';
