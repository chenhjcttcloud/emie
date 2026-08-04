package com.emie.designpm.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 为静态资源（CSS/JS/图片）设置长期浏览器缓存头，
 * 带版本号参数的文件可安全缓存 30 天。
 */
@Component
public class StaticResourceCacheFilter implements Filter, Ordered {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();

        // HTML 入口必须始终重新校验，否则飞书 WebView 可能一直使用旧入口。
        if (path.equals("/") || path.equals("/index.html")) {
            res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            res.setHeader("Pragma", "no-cache");
            res.setHeader("Expires", "0");
        // 静态资源带版本号参数（如 ?v=84）时设置长期缓存
        } else if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/img/")) {
            String query = req.getQueryString();
            if (query != null && query.contains("v=")) {
                res.setHeader("Cache-Control", "public, max-age=2592000, immutable");
            } else {
                res.setHeader("Cache-Control", "public, max-age=3600");
            }
        }

        chain.doFilter(request, response);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
