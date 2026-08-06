package com.emie.designpm.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 统一系统异常边界：记录完整堆栈，向客户端返回稳定且不泄露内部实现的错误结构。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return response(HttpStatus.BAD_REQUEST, "请求参数不合法", ex.getMessage(), null);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException ex, HttpServletRequest request) {
        String traceId = traceId();
        log.error("数据库访问失败 traceId={} path={}", traceId, request.getRequestURI(), ex);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "系统暂时繁忙，请稍后重试", null, traceId);
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleAsyncTimeout() {
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = traceId();
        log.error("未处理系统异常 traceId={} path={}", traceId, request.getRequestURI(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "系统处理失败，请稍后重试", null, traceId);
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String error, String detail, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        if (detail != null && !detail.isBlank()) body.put("message", detail);
        if (traceId != null) body.put("traceId", traceId);
        return ResponseEntity.status(status).body(body);
    }

    private String traceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
