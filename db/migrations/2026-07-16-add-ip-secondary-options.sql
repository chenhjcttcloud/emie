-- IP 二级选项：一级 IP 可配置角色/子系列，并指定单选或多选。
ALTER TABLE ip_options
    ADD COLUMN sub_options_json TEXT NULL,
    ADD COLUMN sub_option_selection_mode VARCHAR(16) NOT NULL DEFAULT 'multiple';

-- 项目保存实际选中的二级 IP，保留历史项目的完整归属。
ALTER TABLE projects
    ADD COLUMN ip_sub_options TEXT NULL;
