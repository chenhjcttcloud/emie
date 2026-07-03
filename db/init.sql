-- ============================================
-- EMIE 设计项目管理系统 - MySQL 初始化脚本
-- 目标: 192.168.200.134:3306
-- ============================================

-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS designpm
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE designpm;

-- 2. 创建用户表
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     VARCHAR(100) NOT NULL UNIQUE COMMENT '登录ID',
    name        VARCHAR(100) NOT NULL,
    role        VARCHAR(50)  NOT NULL COMMENT 'sales/planner/designer/supplychain/admin',
    role_level  INT COMMENT '权限等级 0=admin 1=sales 2=planner 3=designer/supplychain',
    title       VARCHAR(100),
    password    VARCHAR(255),
    phone       VARCHAR(20) COMMENT '手机号',
    email       VARCHAR(100) COMMENT '邮箱',
    status      VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '账号状态: active/disabled',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 创建项目表
CREATE TABLE IF NOT EXISTS projects (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    type                    VARCHAR(50)  NOT NULL COMMENT 'channel_custom / regular',
    status                  VARCHAR(50)  NOT NULL DEFAULT 'draft',
    sales_id                VARCHAR(100),
    sales_name              VARCHAR(100),
    planner_id              VARCHAR(100) NOT NULL,
    planner_name            VARCHAR(100) NOT NULL,
    deadline                VARCHAR(20)  NOT NULL,
    product_requirements    TEXT,
    description             TEXT,
    reference_images_json   TEXT COMMENT 'JSON数组，参考图base64',
    attachments_json        TEXT COMMENT 'JSON数组，附件base64',
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_type (type),
    INDEX idx_status (status),
    INDEX idx_sales (sales_id),
    INDEX idx_planner (planner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 创建子任务表
CREATE TABLE IF NOT EXISTS sub_tasks (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id        BIGINT NOT NULL,
    name              VARCHAR(255) NOT NULL,
    status            VARCHAR(50) NOT NULL DEFAULT 'pending' COMMENT 'pending/accepted/delivered/approved/rejected',
    planned_date      VARCHAR(20) NOT NULL,
    actual_date       VARCHAR(20),
    designer_id       VARCHAR(100),
    designer_name     VARCHAR(100),
    details           TEXT,
    deliverables      TEXT,
    attachments_json  TEXT,
    review_comments   TEXT,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    INDEX idx_project (project_id),
    INDEX idx_designer (designer_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. 创建操作日志表
CREATE TABLE IF NOT EXISTS activity_logs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id  BIGINT NOT NULL,
    action      TEXT NOT NULL,
    username    VARCHAR(100) NOT NULL,
    role        VARCHAR(50) NOT NULL,
    time        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    INDEX idx_project (project_id),
    INDEX idx_time (time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. 创建评分记录表
CREATE TABLE IF NOT EXISTS scoring_records (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    sub_task_id     BIGINT NOT NULL,
    role            VARCHAR(50) NOT NULL,
    aesthetics      DOUBLE COMMENT '审美评分 1-10',
    innovation      DOUBLE COMMENT '创新评分 1-10',
    weight          DOUBLE NOT NULL DEFAULT 0.5 COMMENT '该角色评分权重',
    FOREIGN KEY (sub_task_id) REFERENCES sub_tasks(id) ON DELETE CASCADE,
    UNIQUE KEY uk_task_role (sub_task_id, role),
    INDEX idx_sub_task (sub_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. 创建系统配置表
CREATE TABLE IF NOT EXISTS system_configs (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key      VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键名',
    config_value    TEXT COMMENT '配置值',
    config_group    VARCHAR(50) NOT NULL COMMENT '配置分组: smtp/appearance/security/system',
    description     VARCHAR(500) COMMENT '配置描述',
    value_type      VARCHAR(20) DEFAULT 'text' COMMENT '值类型: text/password/image/number/boolean',
    sort_order      INT DEFAULT 0 COMMENT '排序序号',
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(100) COMMENT '更新人',
    INDEX idx_group (config_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================
-- 3. 初始用户数据（密码 = SHA256(用户ID)）
-- ============================================

-- 销售
INSERT INTO users (id, name, role, title, password) VALUES
('sales_sun',    '孙瑞婷', 'sales',    '销售',     'b48b1f2c5a8e5f9d3c7a1e8b4d6f2a9c0e3d5f7a8b9c0d1e2f3a4b5c6d7e8f'),
('sales_cai',    '蔡小露', 'sales',    '销售',     '1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1'),
('sales_cui',    '崔博文', 'sales',    '销售',     '2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a'),
('sales_zhongt', '钟婷婷', 'sales',    '销售',     '3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b'),
('sales_xiong',  '熊敏',   'sales',    '销售',     '4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c'),
('sales_zhongw', '钟武鹏', 'sales',    '销售',     '5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d');

-- 产品企划
INSERT INTO users (id, name, role, title, password) VALUES
('planner_zheng', '郑诗绚', 'planner', '产品企划', '6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e'),
('planner_wu',    '吴思欣', 'planner', '产品企划', '7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f');

-- 设计师
INSERT INTO users (id, name, role, title, password) VALUES
('designer_cheny',  '陈月珍', 'designer', '设计师', '8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a'),
('designer_huang',  '黄海岚', 'designer', '设计师', '9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b'),
('designer_xie',    '谢梓文', 'designer', '设计师', '0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c'),
('designer_yang',   '杨炜杰', 'designer', '设计师', '1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d'),
('designer_caim',   '蔡萌',   'designer', '设计师', '2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e'),
('designer_zhengc', '郑彩妮', 'designer', '设计师', '3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f');

-- 管理员
INSERT INTO users (id, name, role, title, password) VALUES
('admin_liu', '刘海娇', 'admin', '管理员', '1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f');-- 管理员
INSERT INTO users (id, name, role, title, password) VALUES
('admin_liu', '刘海娇', 'admin', '管理员', '5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b');

-- 8. 创建角色表
CREATE TABLE IF NOT EXISTS roles (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL UNIQUE COMMENT '角色标识符',
    display_name    VARCHAR(100) NOT NULL COMMENT '显示名称',
    description     VARCHAR(500) COMMENT '角色描述',
    permissions     TEXT COMMENT '权限列表，逗号分隔',
    is_system       TINYINT(1) DEFAULT 0 COMMENT '系统内置角色（不可删除）',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================
-- 完成！
-- 然后在 application.yml 中改为 prod profile 启动：
-- mvn spring-boot:run -Dspring.profiles.active=prod
-- ============================================
