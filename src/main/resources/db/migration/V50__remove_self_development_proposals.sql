-- Remove the retired self-development proposal feature and only the projects/tasks
-- that were created from those proposals. Ordinary projects are not targeted.
CREATE TABLE IF NOT EXISTS point_task_proposals (
  id BIGINT PRIMARY KEY,
  project_id BIGINT NULL,
  created_task_id BIGINT NULL
);
DROP TEMPORARY TABLE IF EXISTS retired_self_proposal_projects;
CREATE TEMPORARY TABLE retired_self_proposal_projects AS
SELECT DISTINCT project_id AS id
FROM point_task_proposals
WHERE project_id IS NOT NULL;

DROP TEMPORARY TABLE IF EXISTS retired_self_proposal_tasks;
CREATE TEMPORARY TABLE retired_self_proposal_tasks AS
SELECT DISTINCT created_task_id AS id
FROM point_task_proposals
WHERE created_task_id IS NOT NULL;

SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM file_records
WHERE (target_type = 'project' AND target_id IN (SELECT id FROM retired_self_proposal_projects))
   OR (target_type = 'sub_task' AND target_id IN (SELECT id FROM retired_self_proposal_tasks));
DELETE FROM point_ledgers WHERE sub_task_id IN (SELECT id FROM retired_self_proposal_tasks);
DELETE FROM scoring_records WHERE sub_task_id IN (SELECT id FROM retired_self_proposal_tasks);
DELETE FROM task_withdrawals WHERE sub_task_id IN (SELECT id FROM retired_self_proposal_tasks);
DELETE FROM sub_tasks WHERE id IN (SELECT id FROM retired_self_proposal_tasks);
DELETE FROM projects WHERE id IN (SELECT id FROM retired_self_proposal_projects);
DELETE FROM point_task_proposals;
DROP TABLE point_task_proposals;
SET FOREIGN_KEY_CHECKS = 1;

SET @drop_self_initiated = (
  SELECT IF(COUNT(*) > 0,
    'ALTER TABLE sub_tasks DROP COLUMN self_initiated',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'sub_tasks'
    AND column_name = 'self_initiated'
);
PREPARE drop_self_initiated_stmt FROM @drop_self_initiated;
EXECUTE drop_self_initiated_stmt;
DEALLOCATE PREPARE drop_self_initiated_stmt;

SET @drop_self_initiated_approved = (
  SELECT IF(COUNT(*) > 0,
    'ALTER TABLE sub_tasks DROP COLUMN self_initiated_approved',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'sub_tasks'
    AND column_name = 'self_initiated_approved'
);
PREPARE drop_self_initiated_approved_stmt FROM @drop_self_initiated_approved;
EXECUTE drop_self_initiated_approved_stmt;
DEALLOCATE PREPARE drop_self_initiated_approved_stmt;
