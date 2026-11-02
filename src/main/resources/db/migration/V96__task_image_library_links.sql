CREATE TABLE IF NOT EXISTS task_image_library_links (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    library_item_id BIGINT NOT NULL,
    actor_user_id VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_task_image_library_link UNIQUE (task_id, library_item_id)
);
