-- 项目IP配置：生产环境使用 ddl-auto=validate，部署新版本前需先执行本脚本。

CREATE TABLE ip_options (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    sort_order INT DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ip_options_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE projects
    ADD COLUMN ip_name VARCHAR(100) NULL;
