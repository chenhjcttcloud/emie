SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE notification_events MODIFY COLUMN idempotency_key VARCHAR(128) NOT NULL', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notification_events' AND column_name = 'idempotency_key' AND character_maximum_length >= 128);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
