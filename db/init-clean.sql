-- EMIE 数据库基础初始化（与当前 JPA 实体兼容）
-- 仅创建数据库和用户基础表；其他业务表由 Hibernate ddl-auto=update 创建/补齐。
-- 不写入默认账号，避免明文或过期密码进入数据库。

CREATE DATABASE IF NOT EXISTS designpm
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE designpm;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(50) NOT NULL,
    role_level INT,
    title VARCHAR(100),
    password VARCHAR(255),
    phone VARCHAR(20),
    email VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    department_id BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 让应用启动时负责创建其余实体表并生成 BCrypt 初始化账号。
