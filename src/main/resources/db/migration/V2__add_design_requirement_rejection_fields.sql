SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE design_requirements ADD COLUMN rejection_comments TEXT NULL',
    'SELECT 1') FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'design_requirements'
      AND column_name = 'rejection_comments');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE design_requirements ADD COLUMN rejection_deadline VARCHAR(255) NULL',
    'SELECT 1') FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'design_requirements'
      AND column_name = 'rejection_deadline');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
