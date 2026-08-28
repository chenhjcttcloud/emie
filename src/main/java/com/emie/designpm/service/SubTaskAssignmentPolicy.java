package com.emie.designpm.service;

import com.emie.designpm.entity.User;
import org.springframework.stereotype.Component;

import java.util.List;

/** 子任务负责人角色规则，供命令服务及兼容迁移代码共同使用。 */
@Component
public class SubTaskAssignmentPolicy {
    private final UserService userService;

    public SubTaskAssignmentPolicy(UserService userService) {
        this.userService = userService;
    }

    public String normalizeRole(String role) {
        if (role == null) return "";
        if ("promotion".equalsIgnoreCase(role)
                || "product_promotion".equalsIgnoreCase(role)
                || "product-promotion".equalsIgnoreCase(role)) return "promotion";
        return role;
    }

    public void validate(String userId, String role) {
        String normalized = normalizeRole(role);
        if (!List.of("designer", "supplychain", "planner", "sales", "promotion").contains(normalized)) {
            throw new RuntimeException("不支持的子任务负责人类型");
        }
        if (userId == null || userId.isBlank()) return;
        User assignee = userService.getUserByUserId(userId);
        if (assignee == null || !normalized.equals(normalizeRole(assignee.getRole()))) {
            throw new RuntimeException("子任务负责人和负责人类型不匹配");
        }
    }
}
