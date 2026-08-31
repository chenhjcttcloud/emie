CREATE TABLE image_library_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(160) NOT NULL,
    ip_name VARCHAR(100) NOT NULL,
    sub_options_json TEXT NULL,
    notes VARCHAR(1000) NULL,
    images_json TEXT NOT NULL,
    owner_user_id VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_image_library_created (created_at),
    INDEX idx_image_library_owner (owner_user_id)
);

INSERT INTO image_library_items (name, ip_name, sub_options_json, notes, images_json, owner_user_id, created_at)
SELECT original_name, '未分类', '[]', NULL,
       JSON_ARRAY(JSON_OBJECT('name', original_name, 'storedName', stored_name, 'size', file_size,
                              'url', CONCAT('/api/files/download/', stored_name))),
       COALESCE(owner_user_id, 'unknown'), created_at
FROM file_records
WHERE target_type = 'image_library' AND target_id IS NULL;

UPDATE file_records f
JOIN image_library_items i ON JSON_UNQUOTE(JSON_EXTRACT(i.images_json, '$[0].storedName')) = f.stored_name
SET f.target_id = i.id
WHERE f.target_type = 'image_library' AND f.target_id IS NULL;
