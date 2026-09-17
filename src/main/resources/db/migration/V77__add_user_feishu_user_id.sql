ALTER TABLE users
    ADD COLUMN feishu_user_id VARCHAR(255) NULL COMMENT '飞书用户 ID';

CREATE UNIQUE INDEX uk_users_feishu_user_id ON users (feishu_user_id);
