package com.emie.designpm.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 第一阶段权限编码目录。
 *
 * <p>当前处于兼容接入期：新点号权限与历史冒号权限并行解析，最终业务授权仍保持
 * 现有角色和数据归属规则。完成权限矩阵比对后，再逐步由统一权限服务接管后端写接口。</p>
 */
public final class PermissionCatalog {

    public static final String MODE = "compatibility";

    private static final Set<String> COMMON_PAGES = Set.of(
            "page.dashboard.view",
            "page.projects.view",
            "page.projects.channel.view",
            "page.projects.regular.view",
            "page.design_requirements.view",
            "page.subtasks.mine.view",
            "page.scoring.view"
    );

    private static final Map<String, String> LEGACY_ALIASES;

    static {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("dashboard:view", "page.dashboard.view");
        aliases.put("project:view", "project.view");
        aliases.put("project:create", "project.create.legacy");
        aliases.put("project:edit", "project.edit.legacy");
        aliases.put("task:view", "subtask.view");
        aliases.put("task:assign", "subtask.create");
        aliases.put("task:execute", "subtask.execute.legacy");
        aliases.put("task:approve", "subtask.review.approve.legacy");
        aliases.put("task:reject", "subtask.review.reject.legacy");
        aliases.put("scoring:view", "scoring.view");
        aliases.put("scoring:submit", "scoring.submit");
        aliases.put("admin:dashboard", "page.admin.view");
        aliases.put("admin:config", "admin.config.edit");
        aliases.put("admin:users", "admin.user.manage");
        aliases.put("admin:roles", "admin.role.manage");
        aliases.put("file:upload", "file.upload");
        LEGACY_ALIASES = Map.copyOf(aliases);
    }

    private PermissionCatalog() {
    }

    public static String normalizeRole(String role) {
        if (role == null) return "";
        return switch (role.trim().toLowerCase(Locale.ROOT)) {
            case "promotion", "product_promotion", "product-promotion" -> "promotion";
            case "供应链", "supply", "supply_chain", "supply-chain" -> "supplychain";
            case "管理员", "administrator" -> "admin";
            case "销售" -> "sales";
            case "产品企划", "企划" -> "planner";
            case "设计师" -> "designer";
            default -> role.trim().toLowerCase(Locale.ROOT);
        };
    }

    /** 保持现有页面和新建入口行为不变的兼容权限模板。 */
    public static Set<String> compatibilityPermissions(String rawRole) {
        String role = normalizeRole(rawRole);
        if (role.isBlank() || "pending".equals(role)) return Set.of();

        LinkedHashSet<String> permissions = new LinkedHashSet<>(COMMON_PAGES);
        switch (role) {
            case "admin" -> {
                permissions.add("page.subtasks.department.view");
                permissions.add("page.admin.view");
                // 身份切换是管理员验证各角色权限的恢复入口；兼容模式避免新测试库
                // 尚未执行权限迁移时把管理员意外锁死，显式 deny 仍由 PermissionService 优先移除。
                permissions.add("admin.identity.switch");
            }
            case "sales" -> {
                permissions.add("page.subtasks.department.view");
                permissions.add("project.channel.create");
                permissions.add("project.channel.edit");
                permissions.add("project.pause");
                permissions.add("project.resume");
                permissions.add("project.terminate");
                permissions.add("project.workflow.review");
                permissions.add("project.share.create");
                permissions.add("design_requirement.create");
                permissions.add("design_requirement.score.review");
            }
            case "planner" -> {
                permissions.add("page.subtasks.department.view");
                permissions.add("project.regular.create");
                permissions.add("project.channel.edit");
                permissions.add("project.regular.edit");
                permissions.add("project.pause");
                permissions.add("project.resume");
                permissions.add("project.terminate");
                permissions.add("project.share.create");
                permissions.add("subtask.create");
                permissions.add("design_requirement.create");
                permissions.add("design_requirement.score.review");
            }
            case "promotion" -> {
                permissions.add("design_requirement.create");
                permissions.add("design_requirement.score.review");
            }
            case "designer" -> {
                permissions.add("subtask.accept");
                permissions.add("subtask.deliver");
                permissions.add("subtask.redeliver");
                permissions.add("design_requirement.deliver");
                permissions.add("design_requirement.score.self");
            }
            default -> {
                // 设计师、供应链及自定义已授权角色沿用公共页面行为。
            }
        }
        return Set.copyOf(permissions);
    }

    public static Set<String> translateConfiguredPermissions(String rawPermissions) {
        if (rawPermissions == null || rawPermissions.isBlank()) return Set.of();
        LinkedHashSet<String> translated = new LinkedHashSet<>();
        for (String item : rawPermissions.split(",")) {
            String permission = item.trim();
            if (permission.isEmpty()) continue;
            translated.add(permission);
            String alias = LEGACY_ALIASES.get(permission);
            if (alias != null) translated.add(alias);
        }
        return Set.copyOf(translated);
    }

    /** 兼容期默认数据范围；规范化范围表一旦有配置便覆盖这里的角色模板。 */
    public static Set<String> compatibilityScopes(String rawRole, String permission) {
        if (!Set.of("project.view", "project.detail.view", "subtask.view").contains(permission)) {
            return Set.of();
        }
        return switch (normalizeRole(rawRole)) {
            case "admin" -> Set.of("all");
            case "sales", "designer", "supplychain" -> Set.of("department");
            case "planner" -> Set.of("role_team");
            case "promotion" -> Set.of("participated");
            default -> Set.of("own");
        };
    }
}
