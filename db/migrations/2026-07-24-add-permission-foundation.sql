CREATE TABLE IF NOT EXISTS permissions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(120) NOT NULL,
    name VARCHAR(100) NOT NULL,
    module VARCHAR(50) NOT NULL,
    description TEXT NULL,
    risk_level VARCHAR(20) NOT NULL DEFAULT 'normal',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_permissions_code UNIQUE (code),
    INDEX idx_permission_module_enabled (module, enabled)
);

CREATE TABLE IF NOT EXISTS role_permissions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    effect VARCHAR(10) NOT NULL DEFAULT 'allow',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE,
    INDEX idx_role_permission_role_effect (role_id, effect),
    INDEX idx_role_permission_permission (permission_id)
);

CREATE TABLE IF NOT EXISTS role_permission_scopes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    role_permission_id BIGINT NOT NULL,
    scope_type VARCHAR(40) NOT NULL,
    scope_value VARCHAR(255) NOT NULL DEFAULT '',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_role_permission_scope UNIQUE (role_permission_id, scope_type, scope_value),
    CONSTRAINT fk_role_permission_scope_assignment FOREIGN KEY (role_permission_id)
        REFERENCES role_permissions (id) ON DELETE CASCADE,
    INDEX idx_role_permission_scope_assignment (role_permission_id)
);

CREATE TABLE IF NOT EXISTS permission_versions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    subject_type VARCHAR(30) NOT NULL,
    subject_key VARCHAR(120) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_permission_version_subject UNIQUE (subject_type, subject_key)
);

CREATE TABLE IF NOT EXISTS permission_audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id VARCHAR(255) NOT NULL,
    actor_name VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_key VARCHAR(120) NOT NULL,
    reason TEXT NOT NULL,
    before_data LONGTEXT NULL,
    after_data LONGTEXT NULL,
    source_ip VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_permission_audit_target_time (target_type, target_key, created_at),
    INDEX idx_permission_audit_actor_time (actor_user_id, created_at)
);

INSERT INTO permissions
    (code, name, module, description, risk_level, enabled, created_at, updated_at)
VALUES
    ('page.dashboard.view', '查看工作台', '页面', '进入工作台页面', 'normal', 1, NOW(6), NOW(6)),
    ('page.projects.view', '查看全部项目页', '页面', '进入全部项目页面', 'normal', 1, NOW(6), NOW(6)),
    ('page.projects.channel.view', '查看渠道定制单页', '页面', '进入渠道定制单页面', 'normal', 1, NOW(6), NOW(6)),
    ('page.projects.regular.view', '查看公司常规品页', '页面', '进入公司常规品页面', 'normal', 1, NOW(6), NOW(6)),
    ('page.design_requirements.view', '查看设计送审需求页', '页面', '进入设计/送审需求页面', 'normal', 1, NOW(6), NOW(6)),
    ('page.subtasks.mine.view', '查看我的子任务页', '页面', '进入我的子任务页面', 'normal', 1, NOW(6), NOW(6)),
    ('page.subtasks.department.view', '查看其他子任务页', '页面', '进入部门或其他子任务页面，仍需数据范围校验', 'important', 1, NOW(6), NOW(6)),
    ('page.scoring.view', '查看评分页', '页面', '进入评分页面', 'normal', 1, NOW(6), NOW(6)),
    ('page.admin.view', '进入系统管理', '页面', '进入系统管理页面', 'important', 1, NOW(6), NOW(6)),

    ('project.view', '查看项目', '项目', '查看权限范围内的项目', 'normal', 1, NOW(6), NOW(6)),
    ('project.detail.view', '查看项目详情', '项目', '查看权限范围内的项目详情', 'normal', 1, NOW(6), NOW(6)),
    ('project.channel.create', '新建渠道定制单', '项目', '创建渠道定制项目', 'normal', 1, NOW(6), NOW(6)),
    ('project.regular.create', '新建公司常规品', '项目', '创建公司常规品项目', 'normal', 1, NOW(6), NOW(6)),
    ('project.channel.edit', '编辑渠道定制单', '项目', '编辑权限范围内的渠道定制项目', 'important', 1, NOW(6), NOW(6)),
    ('project.regular.edit', '编辑公司常规品', '项目', '编辑权限范围内的公司常规品项目', 'important', 1, NOW(6), NOW(6)),
    ('project.accept', '项目接单', '项目', '在业务状态允许时接单', 'normal', 1, NOW(6), NOW(6)),
    ('project.pause', '暂停项目', '项目', '暂停权限范围内的项目', 'important', 1, NOW(6), NOW(6)),
    ('project.resume', '恢复项目', '项目', '恢复权限范围内的项目', 'important', 1, NOW(6), NOW(6)),
    ('project.terminate', '终止项目', '项目', '终止权限范围内的项目', 'high', 1, NOW(6), NOW(6)),
    ('project.delete', '删除项目', '项目', '删除符合业务条件的项目', 'high', 1, NOW(6), NOW(6)),
    ('project.audit.view', '查看项目操作日志', '项目', '查看项目操作与变更历史', 'important', 1, NOW(6), NOW(6)),
    ('project.share.create', '创建项目分享链接', '项目', '为可管理项目创建外部分享链接', 'important', 1, NOW(6), NOW(6)),
    ('project.workflow.advance', '推进项目阶段', '项目流程', '推进项目执行或送审阶段', 'important', 1, NOW(6), NOW(6)),
    ('project.workflow.review', '审核项目阶段', '项目流程', '审核项目送审阶段', 'important', 1, NOW(6), NOW(6)),

    ('subtask.view', '查看子任务', '子任务', '查看权限范围内的子任务', 'normal', 1, NOW(6), NOW(6)),
    ('subtask.create', '新建子任务', '子任务', '在负责项目中创建子任务', 'important', 1, NOW(6), NOW(6)),
    ('subtask.edit', '编辑子任务', '子任务', '编辑权限范围内的子任务', 'important', 1, NOW(6), NOW(6)),
    ('subtask.delete', '删除子任务', '子任务', '删除符合业务条件的子任务', 'high', 1, NOW(6), NOW(6)),
    ('subtask.accept', '子任务接单', '子任务', '负责人接单', 'normal', 1, NOW(6), NOW(6)),
    ('subtask.deliver', '提交子任务成果', '子任务', '负责人首次提交成果', 'normal', 1, NOW(6), NOW(6)),
    ('subtask.redeliver', '重新提交子任务成果', '子任务', '负责人重新提交成果', 'normal', 1, NOW(6), NOW(6)),
    ('subtask.review.first.approve', '子任务首审通过', '子任务审核', '产品企划首审通过', 'important', 1, NOW(6), NOW(6)),
    ('subtask.review.first.reject', '子任务首审驳回', '子任务审核', '产品企划首审驳回', 'important', 1, NOW(6), NOW(6)),
    ('subtask.review.channel.approve', '渠道子任务二审通过', '子任务审核', '渠道项目销售二审通过', 'important', 1, NOW(6), NOW(6)),
    ('subtask.review.channel.reject', '渠道子任务二审驳回', '子任务审核', '渠道项目销售二审驳回', 'important', 1, NOW(6), NOW(6)),
    ('subtask.review.regular.approve', '常规品子任务二审通过', '子任务审核', '常规品管理员二审通过', 'important', 1, NOW(6), NOW(6)),
    ('subtask.review.regular.reject', '常规品子任务二审驳回', '子任务审核', '常规品管理员二审驳回', 'important', 1, NOW(6), NOW(6)),
    ('subtask.rejection_history.view', '查看子任务驳回记录', '子任务', '查看子任务历次提交与驳回意见', 'normal', 1, NOW(6), NOW(6)),

    ('design_requirement.view', '查看设计送审需求', '设计需求', '查看权限范围内的设计/送审需求', 'normal', 1, NOW(6), NOW(6)),
    ('design_requirement.detail.view', '查看设计需求详情', '设计需求', '查看权限范围内的设计需求详情', 'normal', 1, NOW(6), NOW(6)),
    ('design_requirement.create', '新建设计送审需求', '设计需求', '创建设计/送审需求', 'normal', 1, NOW(6), NOW(6)),
    ('design_requirement.deliver', '提交设计需求成果', '设计需求', '指定设计师提交交付成果', 'normal', 1, NOW(6), NOW(6)),
    ('design_requirement.score.self', '设计需求自评', '设计需求评分', '指定设计师完成自评', 'normal', 1, NOW(6), NOW(6)),
    ('design_requirement.score.review', '设计需求复评', '设计需求评分', '本轮指定复评人提交评分', 'normal', 1, NOW(6), NOW(6)),
    ('scoring.view', '查看评分任务', '评分', '查看权限范围内的评分任务', 'normal', 1, NOW(6), NOW(6)),
    ('scoring.submit', '提交评分', '评分', '提交本人负责的评分', 'normal', 1, NOW(6), NOW(6)),
    ('scoring.result.view', '查看评分结果', '评分', '查看权限范围内的最终评分', 'normal', 1, NOW(6), NOW(6)),

    ('file.upload', '上传业务文件', '文件', '上传业务附件', 'normal', 1, NOW(6), NOW(6)),
    ('file.download', '下载业务文件', '文件', '下载权限范围内的业务附件', 'normal', 1, NOW(6), NOW(6)),
    ('file.preview', '预览业务文件', '文件', '预览权限范围内的业务附件', 'normal', 1, NOW(6), NOW(6)),

    ('admin.config.view', '查看系统配置', '系统管理', '查看系统配置', 'important', 1, NOW(6), NOW(6)),
    ('admin.config.edit', '修改系统配置', '系统管理', '修改系统配置', 'high', 1, NOW(6), NOW(6)),
    ('admin.config.asset.upload', '上传系统资源', '系统管理', '上传 Logo 和登录背景', 'high', 1, NOW(6), NOW(6)),
    ('admin.user.manage', '管理用户', '系统管理', '新增、编辑、停用和删除用户', 'high', 1, NOW(6), NOW(6)),
    ('admin.user.role.assign', '分配用户角色', '系统管理', '修改用户角色', 'high', 1, NOW(6), NOW(6)),
    ('admin.role.manage', '管理角色', '系统管理', '新增、编辑和删除角色', 'high', 1, NOW(6), NOW(6)),
    ('admin.permission.manage', '配置权限', '系统管理', '修改角色及用户权限', 'high', 1, NOW(6), NOW(6)),
    ('admin.department.manage', '管理组织架构', '系统管理', '管理部门和负责人', 'high', 1, NOW(6), NOW(6)),
    ('admin.identity.switch', '管理员身份切换', '系统管理', '切换为其他用户身份', 'high', 1, NOW(6), NOW(6)),
    ('admin.notification.test', '发送通知测试', '系统通知', '向当前管理员发送渠道测试', 'important', 1, NOW(6), NOW(6)),
    ('admin.notification.broadcast', '发送全员通知', '系统通知', '向全部系统用户发送通知', 'high', 1, NOW(6), NOW(6)),
    ('admin.notification.failure.manage', '管理通知失败记录', '系统通知', '查看并重试通知失败记录', 'important', 1, NOW(6), NOW(6)),
    ('admin.catalog.manage', '管理基础字典', '系统管理', '管理类目、IP、合规和价格区间', 'important', 1, NOW(6), NOW(6)),
    ('admin.scoring_weight.manage', '管理评分权重', '系统管理', '修改项目评分权重', 'high', 1, NOW(6), NOW(6)),
    ('admin.workload.view', '查看工作量', '系统管理', '查看用户工作量及时间线', 'normal', 1, NOW(6), NOW(6)),
    ('admin.system_monitor.view', '查看系统监控', '系统管理', '查看指标和日志', 'important', 1, NOW(6), NOW(6)),
    ('admin.file_archive.manage', '管理文件归档', '系统管理', '归档和恢复业务文件', 'high', 1, NOW(6), NOW(6)),
    ('admin.feishu_sync.execute', '执行飞书同步', '系统管理', '执行飞书全量同步与结构初始化', 'high', 1, NOW(6), NOW(6)),
    ('admin.data.clear', '清空业务数据', '系统管理', '清空测试业务数据', 'critical', 1, NOW(6), NOW(6)),
    ('admin.share.manage', '管理全部分享链接', '系统管理', '查看、修改和撤销全部分享链接', 'high', 1, NOW(6), NOW(6)),
    ('admin.project_import.execute', '导入历史项目', '系统管理', '预览并执行历史项目导入', 'high', 1, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    module = VALUES(module),
    description = VALUES(description),
    risk_level = VALUES(risk_level),
    enabled = VALUES(enabled),
    updated_at = VALUES(updated_at);

INSERT IGNORE INTO role_permissions (role_id, permission_id, effect, created_at, updated_at)
SELECT r.id, p.id, 'allow', NOW(6), NOW(6)
FROM roles r
JOIN permissions p ON p.code IN (
    'page.dashboard.view',
    'page.projects.view',
    'page.projects.channel.view',
    'page.projects.regular.view',
    'page.design_requirements.view',
    'page.subtasks.mine.view',
    'page.scoring.view',
    'project.view',
    'project.detail.view',
    'subtask.view',
    'scoring.view',
    'file.upload',
    'file.download',
    'file.preview'
)
WHERE CASE
    WHEN LOWER(r.name) IN ('promotion', 'product_promotion', 'product-promotion') THEN 'promotion'
    WHEN LOWER(r.name) IN ('supply', 'supply_chain', 'supply-chain', 'supplychain') THEN 'supplychain'
    ELSE LOWER(r.name)
END IN ('admin', 'sales', 'planner', 'promotion', 'designer', 'supplychain');

INSERT IGNORE INTO role_permissions (role_id, permission_id, effect, created_at, updated_at)
SELECT r.id, p.id, 'allow', NOW(6), NOW(6)
FROM roles r
JOIN permissions p ON p.code IN (
    'page.subtasks.department.view',
    'project.channel.create',
    'project.channel.edit',
    'project.pause',
    'project.resume',
    'project.terminate',
    'project.workflow.review',
    'project.share.create',
    'design_requirement.create',
    'design_requirement.score.review',
    'subtask.review.channel.approve',
    'subtask.review.channel.reject',
    'scoring.submit'
)
WHERE LOWER(r.name) = 'sales';

INSERT IGNORE INTO role_permissions (role_id, permission_id, effect, created_at, updated_at)
SELECT r.id, p.id, 'allow', NOW(6), NOW(6)
FROM roles r
JOIN permissions p ON p.code IN (
    'page.subtasks.department.view',
    'project.regular.create',
    'design_requirement.create',
    'project.accept',
    'project.channel.edit',
    'project.regular.edit',
    'project.workflow.advance',
    'project.share.create',
    'project.pause',
    'project.resume',
    'project.terminate',
    'subtask.create',
    'subtask.edit',
    'subtask.delete',
    'subtask.review.first.approve',
    'subtask.review.first.reject',
    'design_requirement.score.review',
    'scoring.submit'
)
WHERE LOWER(r.name) = 'planner';

INSERT IGNORE INTO role_permissions (role_id, permission_id, effect, created_at, updated_at)
SELECT r.id, p.id, 'allow', NOW(6), NOW(6)
FROM roles r
JOIN permissions p ON p.code IN (
    'design_requirement.create',
    'design_requirement.score.review',
    'scoring.submit',
    'subtask.accept',
    'subtask.deliver',
    'subtask.redeliver'
)
WHERE LOWER(r.name) IN ('promotion', 'product_promotion', 'product-promotion');

INSERT IGNORE INTO role_permissions (role_id, permission_id, effect, created_at, updated_at)
SELECT r.id, p.id, 'allow', NOW(6), NOW(6)
FROM roles r
JOIN permissions p ON p.code IN (
    'subtask.accept',
    'subtask.deliver',
    'subtask.redeliver',
    'design_requirement.deliver',
    'design_requirement.score.self'
)
WHERE LOWER(r.name) = 'designer';

INSERT IGNORE INTO role_permissions (role_id, permission_id, effect, created_at, updated_at)
SELECT r.id, p.id, 'allow', NOW(6), NOW(6)
FROM roles r
JOIN permissions p ON p.code IN (
    'subtask.accept',
    'subtask.deliver',
    'subtask.redeliver'
)
WHERE LOWER(r.name) IN ('supply', 'supply_chain', 'supply-chain', 'supplychain');

INSERT IGNORE INTO role_permissions (role_id, permission_id, effect, created_at, updated_at)
SELECT r.id, p.id, 'allow', NOW(6), NOW(6)
FROM roles r
JOIN permissions p ON p.code IN (
    'page.subtasks.department.view',
    'page.admin.view',
    'project.accept',
    'project.pause',
    'project.resume',
    'project.terminate',
    'project.delete',
    'project.audit.view',
    'project.share.create',
    'project.workflow.review',
    'subtask.review.regular.approve',
    'subtask.review.regular.reject',
    'design_requirement.score.review',
    'scoring.submit',
    'scoring.result.view',
    'admin.config.view',
    'admin.config.edit',
    'admin.config.asset.upload',
    'admin.user.manage',
    'admin.user.role.assign',
    'admin.role.manage',
    'admin.permission.manage',
    'admin.department.manage',
    'admin.identity.switch',
    'admin.notification.test',
    'admin.notification.broadcast',
    'admin.notification.failure.manage',
    'admin.catalog.manage',
    'admin.scoring_weight.manage',
    'admin.workload.view',
    'admin.system_monitor.view',
    'admin.file_archive.manage',
    'admin.feishu_sync.execute',
    'admin.data.clear',
    'admin.share.manage',
    'admin.project_import.execute'
)
WHERE LOWER(r.name) = 'admin';

-- 首批数据范围：保持迁移前可见行为。后续由权限配置后台按单项权限调整。
INSERT IGNORE INTO role_permission_scopes
    (role_permission_id, scope_type, scope_value, created_at)
SELECT rp.id,
       CASE
           WHEN LOWER(r.name) = 'admin' THEN 'all'
           WHEN LOWER(r.name) = 'planner' THEN 'role_team'
           WHEN LOWER(r.name) IN ('sales', 'designer', 'supply', 'supply_chain', 'supply-chain', 'supplychain')
               THEN 'department'
           ELSE 'participated'
       END,
       '',
       NOW(6)
FROM role_permissions rp
JOIN roles r ON r.id = rp.role_id
JOIN permissions p ON p.id = rp.permission_id
WHERE rp.effect = 'allow'
  AND p.code IN ('project.view', 'project.detail.view', 'subtask.view')
  AND CASE
      WHEN LOWER(r.name) IN ('promotion', 'product_promotion', 'product-promotion') THEN 'promotion'
      WHEN LOWER(r.name) IN ('supply', 'supply_chain', 'supply-chain', 'supplychain') THEN 'supplychain'
      ELSE LOWER(r.name)
  END IN ('admin', 'sales', 'planner', 'promotion', 'designer', 'supplychain');

INSERT INTO permission_versions (subject_type, subject_key, version, updated_at)
SELECT 'role',
       CASE
           WHEN LOWER(r.name) IN ('promotion', 'product_promotion', 'product-promotion') THEN 'promotion'
           WHEN LOWER(r.name) IN ('supply', 'supply_chain', 'supply-chain', 'supplychain') THEN 'supplychain'
           ELSE LOWER(r.name)
       END,
       1,
       NOW(6)
FROM roles r
WHERE CASE
    WHEN LOWER(r.name) IN ('promotion', 'product_promotion', 'product-promotion') THEN 'promotion'
    WHEN LOWER(r.name) IN ('supply', 'supply_chain', 'supply-chain', 'supplychain') THEN 'supplychain'
    ELSE LOWER(r.name)
END IN ('admin', 'sales', 'planner', 'promotion', 'designer', 'supplychain')
ON DUPLICATE KEY UPDATE subject_key = VALUES(subject_key);
