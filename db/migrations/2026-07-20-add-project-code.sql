-- 项目对外编号：EMIEyyyyMM####
-- 生产执行前请先按发布手册备份；脚本可重复执行。
SET @column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'projects' AND column_name = 'project_code');
SET @sql := IF(@column_exists = 0, 'ALTER TABLE projects ADD COLUMN project_code VARCHAR(14) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE projects p
JOIN (
    SELECT id, CONCAT('EMIE', DATE_FORMAT(created_at, '%Y%m'), LPAD(ROW_NUMBER() OVER (PARTITION BY DATE_FORMAT(created_at, '%Y%m') ORDER BY created_at, id), 4, '0')) AS code
    FROM projects WHERE project_code IS NULL OR project_code = ''
) x ON x.id = p.id
SET p.project_code = x.code
WHERE p.project_code IS NULL OR p.project_code = '';

SET @index_exists := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'projects' AND index_name = 'uk_projects_project_code');
SET @sql := IF(@index_exists = 0, 'ALTER TABLE projects ADD UNIQUE KEY uk_projects_project_code (project_code)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
