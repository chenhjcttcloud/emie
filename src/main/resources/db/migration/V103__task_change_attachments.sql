ALTER TABLE sub_tasks
    ADD COLUMN pending_change_attachments_json LONGTEXT NULL;

UPDATE sub_tasks
SET pending_change_attachments_json = '[]'
WHERE pending_change_attachments_json IS NULL;
