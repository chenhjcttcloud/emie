package com.emie.designpm.config;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.service.PermissionService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 简单认证过滤器。
 * 对 /api/** 路径进行 token 校验，排除 /api/auth/login 和静态资源。
 */
@Component
@Order(1)
public class AuthFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    private final PermissionService permissionService;

    /**
     * 全站 CSP（P3 加固）：
     * - script-src 必须显式含 'unsafe-eval'：事件运行时（event-runtime.js）用 new Function
     *   编译 data-emie-on* 处理器；若 script-src 回退到 default-src 'self'（无 unsafe-eval），
     *   浏览器禁止 new Function 执行会导致全站点击失效（2026-08-16 生产回归根因）。
     * - script-src-attr 'none'：禁止原生内联事件处理器（onclick= 等）注入，纵深防御；
     *   data-emie-on* 是自定义属性，由 addEventListener + new Function 统一处理，不受影响。
     * - style-src 显式放行 'unsafe-inline'：前端（含 JS 模板字符串）有大量内联 style 属性，
     *   若省略该指令会回退到 default-src 'self' 反而破坏样式。
     * - img-src 显式允许 http(s)/data：safeImageSrc 允许外域图片（登录页/头部 logo、背景图）。
     * - /share/** 由 PublicShareController 自行设置分享页 CSP，此处跳过避免双头叠加歧义。
     */
    private static final String SITE_CSP = "default-src 'self'; script-src 'self' 'unsafe-eval'; script-src-attr 'none'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' https: http: data:; "
            + "object-src 'none'; base-uri 'self'";

    @Autowired
    public AuthFilter(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** 保留给过滤器轻量单元测试。 */
    public AuthFilter() {
        this.permissionService = null;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();

        // 统一安全响应头。
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "SAMEORIGIN");
        res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        // 全站 CSP；/share/** 由 PublicShareController 设置分享页自身的 CSP（此处跳过）。
        if (!path.startsWith("/share/")) {
            res.setHeader("Content-Security-Policy", SITE_CSP);
        }
        if (path.startsWith("/api/")) {
            res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            res.setHeader("Pragma", "no-cache");
        }

        // 允许无需认证的路径
        if (path.equals("/api/auth/login") ||
            path.equals("/api/auth/logout") ||
            path.equals("/api/auth/feishu/callback") ||
            path.equals("/api/auth/feishu/config") ||
            path.equals("/api/auth/feishu/auto-login") ||
            path.equals("/api/auth/feishu/exchange") ||
            path.equals("/api/admin/public-config") ||
            path.equals("/api/admin/version/stream") ||
            path.equals("/api/health/live") ||
            path.equals("/favicon.ico") ||
            // P1-7 遗留闭环：uploads/admin 下的管理图片（logo/login-bg）需匿名可访问。
            // 精确前缀白名单 + FileController.checkDownloadAccess 的 ADMIN_MANAGED_IMAGE
            // 正则兜底（匿名 + 非白名单文件名 → 401），不重新打开 P1-7 的口子。
            path.startsWith("/api/files/download/admin/") ||
            path.startsWith("/css/") ||
            path.startsWith("/js/") ||
            path.equals("/") ||
            path.equals("/index.html")) {
            chain.doFilter(request, response);
            return;
        }

        // 对 /api/** 路径进行认证
        if (path.startsWith("/api/")) {
            String token = req.getHeader("X-Auth-Token");
            if (token == null || token.isBlank()) token = cookieToken(req);
            AuthController.AuthSession session = AuthController.validateToken(token);
            if (token == null || session == null) {
                res.setStatus(401);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":\"未登录或会话已过期，请重新登录\"}");
                return;
            }
            req.setAttribute("authSession", session);
            if ("pending".equals(session.role()) && !path.equals("/api/auth/me")) {
                log.warn("认证过滤器拒绝请求 path={} userId={} role={} reason=pending-role",
                        path, session.userId(), session.role());
                res.setStatus(403);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":\"账号等待管理员分配角色\"}");
                return;
            }
            String adminPermission = requiredPermission(req.getMethod(), path);
            if (adminPermission != null && !hasPermission(session, adminPermission)) {
                log.warn("认证过滤器拒绝请求 path={} userId={} role={} permission={}",
                        path, session.userId(), session.role(), adminPermission);
                res.setStatus(403);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":\"当前账号没有访问此系统管理功能的权限\","
                        + "\"permission\":\"" + adminPermission + "\"}");
                return;
            }
            if (adminPermission != null) {
                req.setAttribute("permissionGranted", true);
                req.setAttribute("requiredPermission", adminPermission);
            }
        }

        // H2 Console 仅用于开发诊断，不对应用路由开放，避免误暴露数据库管理入口。
        if (path.equals("/h2-console") || path.startsWith("/h2-console/")) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        chain.doFilter(request, response);
    }

    private String cookieToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (AuthController.AUTH_COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private boolean hasPermission(AuthController.AuthSession session, String permission) {
        return permissionService == null
                ? "admin".equals(session.role())
                : permissionService.has(session.role(), permission);
    }

    private String requiredPermission(String method, String path) {
        if (!"GET".equals(method) && path.startsWith("/api/departments")) return "admin.department.manage";
        if (!"GET".equals(method) && (path.startsWith("/api/categories")
                || path.startsWith("/api/compliance")
                || path.startsWith("/api/price-ranges")
                || path.startsWith("/api/ip-options"))) return "admin.catalog.manage";
        if (!"GET".equals(method) && path.startsWith("/api/users/org/")) return "admin.department.manage";
        if (path.equals("/api/system/archive") && "POST".equals(method)) return "admin.system_monitor.manage";
        if (path.startsWith("/api/system/")) return "admin.system_monitor.view";
        if (path.startsWith("/api/project-import/")) return "admin.project_import.execute";
        if (path.startsWith("/api/share/admin/")) return "admin.share.manage";
        if (!path.startsWith("/api/admin/") || path.equals("/api/admin/public-config")) return null;
        if (path.startsWith("/api/admin/configs")) {
            return "GET".equals(method) ? "admin.config.view" : "admin.config.edit";
        }
        if (path.equals("/api/admin/upload-image")) return "admin.config.asset.upload";
        if (path.equals("/api/admin/notifications/test")) return "admin.notification.test";
        if (path.contains("/notifications/temporary-broadcast")) return "admin.notification.broadcast";
        if (path.contains("/notifications/failures") || path.contains("/notifications/deliveries/")) {
            return "admin.notification.failure.manage";
        }
        if (path.startsWith("/api/admin/users/") && path.endsWith("/role")) return "admin.user.role.assign";
        if (path.startsWith("/api/admin/users")) return "admin.user.manage";
        if (path.startsWith("/api/admin/permissions/")) return "admin.permission.manage";
        if (path.startsWith("/api/admin/permission-defs") || path.startsWith("/api/admin/roles")) {
            return "admin.role.manage";
        }
        if (path.startsWith("/api/admin/scoring-weights")) return "admin.scoring_weight.manage";
        if (path.startsWith("/api/admin/clear-data")) return "admin.data.clear";
        if (path.startsWith("/api/admin/workload")) return "admin.workload.view";
        if (path.startsWith("/api/admin/files")) return "admin.file_archive.manage";
        if (path.startsWith("/api/admin/sync")) return "admin.feishu_sync.execute";
        return "page.admin.view";
    }
}
