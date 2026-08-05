SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE notification_deliveries ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notification_deliveries' AND column_name = 'created_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE notification_deliveries ADD COLUMN first_attempt_at DATETIME NULL', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notification_deliveries' AND column_name = 'first_attempt_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE notification_deliveries ADD COLUMN last_attempt_at DATETIME NULL', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notification_deliveries' AND column_name = 'last_attempt_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE notification_deliveries ADD COLUMN failed_at DATETIME NULL', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notification_deliveries' AND column_name = 'failed_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
