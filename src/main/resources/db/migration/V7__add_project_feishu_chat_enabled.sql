SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE projects ADD COLUMN feishu_chat_enabled BOOLEAN NOT NULL DEFAULT FALSE', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'projects' AND column_name = 'feishu_chat_enabled');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
