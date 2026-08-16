package com.emie.designpm.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/** 轻量接口耗时监控：所有 API 返回耗时头，慢请求记录结构化日志。 */
public class RequestTimingInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RequestTimingInterceptor.class);
    private static final String START = RequestTimingInterceptor.class.getName() + ".start";
    private static final long SLOW_MS = 500L;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START, System.nanoTime());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           org.springframework.web.servlet.ModelAndView modelAndView) {
        Object started = request.getAttribute(START);
        if (started instanceof Long && request.getRequestURI().startsWith("/api/")) {
            response.setHeader("X-Response-Time-Ms", String.valueOf((System.nanoTime() - (Long) started) / 1_000_000L));
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object started = request.getAttribute(START);
        if (!(started instanceof Long)) return;
        long elapsedMs = (System.nanoTime() - (Long) started) / 1_000_000L;
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("X-Response-Time-Ms", String.valueOf(elapsedMs));
            // 只记录 URI path，不记录 query：query 中可能携带 token/password/code 等敏感参数。
            if (elapsedMs >= SLOW_MS) log.warn("慢接口 path={} method={} status={} elapsedMs={}",
                    request.getRequestURI(), request.getMethod(), response.getStatus(), elapsedMs);
        }
    }
}
