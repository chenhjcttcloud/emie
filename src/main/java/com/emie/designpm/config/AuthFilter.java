package com.emie.designpm.config;

import com.emie.designpm.controller.AuthController;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 简单认证过滤器。
 * 对 /api/** 路径进行 token 校验，排除 /api/auth/login 和静态资源。
 */
@Component
@Order(1)
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();

        // 统一安全响应头；不设置严格 CSP，避免破坏现有前端内联脚本和资源加载。
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "SAMEORIGIN");
        res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        if (path.startsWith("/api/")) {
            res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            res.setHeader("Pragma", "no-cache");
        }

        // 允许无需认证的路径
        if (path.equals("/api/auth/login") ||
            path.equals("/api/auth/register") ||
            path.equals("/api/auth/logout") ||
            path.equals("/api/auth/feishu/callback") ||
            path.equals("/api/auth/feishu/config") ||
            path.equals("/api/auth/feishu/auto-login") ||
            path.equals("/api/captcha/image") ||
            path.equals("/api/sms/send") ||
            path.equals("/api/email-code/send") ||
            path.equals("/api/admin/public-config") ||
            path.equals("/favicon.ico") ||
            path.startsWith("/css/") ||
            path.startsWith("/js/") ||
            path.startsWith("/api/files/download/admin/") ||
            path.equals("/") ||
            path.equals("/index.html")) {
            chain.doFilter(request, response);
            return;
        }

        // 对 /api/** 路径进行认证
        if (path.startsWith("/api/")) {
            String token = req.getHeader("X-Auth-Token");
            // 允许 token 通过查询参数传递（用于 <img> 等无法设置 Header 的场景）
            if (token == null || token.isBlank()) {
                token = req.getParameter("token");
            }
            AuthController.AuthSession session = AuthController.validateToken(token);
            if (token == null || session == null) {
                res.setStatus(401);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":\"未登录或会话已过期，请重新登录\"}");
                return;
            }
            req.setAttribute("authSession", session);
            if (path.startsWith("/api/admin/") && !path.equals("/api/admin/public-config")
                    && !"admin".equals(session.role())) {
                res.setStatus(403);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":\"仅管理员可访问\"}");
                return;
            }
        }

        // H2 Console 仅用于开发诊断，不对应用路由开放，避免误暴露数据库管理入口。
        if (path.equals("/h2-console") || path.startsWith("/h2-console/")) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        chain.doFilter(request, response);
    }
}
