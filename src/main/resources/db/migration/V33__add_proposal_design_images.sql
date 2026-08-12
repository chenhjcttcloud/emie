SET @proposal_image_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'point_task_proposals'
      AND column_name = 'reference_images_json'
);
SET @proposal_image_ddl = IF(
    @proposal_image_column_exists = 0,
    'ALTER TABLE point_task_proposals ADD COLUMN reference_images_json LONGTEXT NULL AFTER description',
    'SELECT 1'
);
PREPARE proposal_image_statement FROM @proposal_image_ddl;
EXECUTE proposal_image_statement;
DEALLOCATE PREPARE proposal_image_statement;
