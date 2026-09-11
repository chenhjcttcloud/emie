package com.emie.designpm.project.controller;

import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.auth.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * project 域 controller 之间共享的 HTTP 请求上下文助手：解析 session、权限拒绝、
 * 把当前用户信息补进命令 body。只依赖 {@link PermissionService}，不碰 repository。
 * 视图组装（toDetail、评分明细等）在 {@code project.service.ProjectViewSupport}。
 */
@Component
class ProjectRequestSupport {

    private final PermissionService permissionService;

    ProjectRequestSupport(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    AuthSession getSession(HttpServletRequest request) {
        return (AuthSession) request.getAttribute("authSession");
    }

    ResponseEntity<?> denyUnless(HttpServletRequest request, String permission) {
        AuthSession session = getSession(request);
        if (permission != null && (permissionService == null || permissionService.has(session.role(), permission))) {
            return null;
        }
        return ResponseEntity.status(403)
                .body(Map.of(
                        "error",
                        "当前账号没有执行此操作的权限",
                        "permission",
                        permission == null ? "unsupported.action" : permission));
    }

    Map<String, Object> withSessionContext(Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> safeBody = new LinkedHashMap<>();
        if (body != null) safeBody.putAll(body);
        AuthSession session = getSession(request);
        safeBody.put("currentUser", session.name());
        safeBody.put("currentRole", session.role());
        safeBody.put("currentUserId", session.userId());
        safeBody.put("userId", session.userId());
        safeBody.put("role", session.role());
        if (safeBody.containsKey("designerUserId")
                || "designer".equals(session.role())
                || "supplychain".equals(session.role())
                || "planner".equals(session.role())) {
            safeBody.put("designerUserId", session.userId());
        }
        return safeBody;
    }
}
