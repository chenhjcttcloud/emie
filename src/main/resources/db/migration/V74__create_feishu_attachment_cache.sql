CREATE TABLE feishu_attachment_cache (
    id BIGINT NOT NULL AUTO_INCREMENT,
    app_token VARCHAR(100) NOT NULL,
    stored_name VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL,
    modified_millis BIGINT NOT NULL,
    file_token VARCHAR(255) NOT NULL,
    uploaded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_feishu_attachment_base_file UNIQUE (app_token, stored_name)
);
