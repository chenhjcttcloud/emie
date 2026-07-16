package com.emie.designpm.util;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;

import java.util.Objects;

/** 项目可见性与项目级管理权限的统一规则。 */
public final class ProjectAccessPolicy {

    private ProjectAccessPolicy() {
    }

    public static boolean canView(Project project, AuthController.AuthSession session) {
        if (project == null || session == null) return false;
        return switch (session.role()) {
            case "admin" -> true;
            case "sales" -> Objects.equals(session.userId(), project.getSalesId());
            case "planner" -> Objects.equals(session.userId(), project.getPlannerId())
                    || isUnclaimedChannelProject(project);
            case "designer", "supplychain" -> project.getTasks().stream()
                    .anyMatch(task -> canViewTask(task, session.userId(), session.role()));
            default -> false;
        };
    }

    public static boolean canManage(Project project, AuthController.AuthSession session) {
        if (project == null || session == null) return false;
        return switch (session.role()) {
            case "admin" -> true;
            case "sales" -> Objects.equals(session.userId(), project.getSalesId());
            case "planner" -> Objects.equals(session.userId(), project.getPlannerId())
                    || isUnclaimedChannelProject(project);
            default -> false;
        };
    }

    /**
     * 编辑项目基础资料的专用规则。
     * 渠道定制项目只能由项目所属销售编辑，常规品只能由项目所属产品企划编辑；
     * 此规则有意不为管理员提供绕过权限，避免代替创建人修改项目归属资料。
     */
    public static boolean canEditProjectInformation(Project project, AuthController.AuthSession session) {
        if (project == null || session == null) return false;
        return ("channel_custom".equals(project.getType())
                && "sales".equals(session.role())
                && Objects.equals(session.userId(), project.getSalesId()))
                || ("regular".equals(project.getType())
                && "planner".equals(session.role())
                && Objects.equals(session.userId(), project.getPlannerId()));
    }

    static boolean canViewTask(SubTask task, String userId, String role) {
        if (!matchesAssigneeRole(task, role)) return false;
        if (Objects.equals(userId, task.getDesignerId())) return true;
        return (task.getDesignerId() == null || task.getDesignerId().isBlank())
                && "pending".equals(task.getStatus());
    }

    static boolean matchesAssigneeRole(SubTask task, String role) {
        if (Objects.equals(role, task.getAssigneeRole())) return true;
        return "designer".equals(role)
                && (task.getAssigneeRole() == null || task.getAssigneeRole().isBlank());
    }

    private static boolean isUnclaimedChannelProject(Project project) {
        return "channel_custom".equals(project.getType())
                && "pending_planner".equals(project.getStatus())
                && (project.getPlannerId() == null || project.getPlannerId().isBlank());
    }
}
