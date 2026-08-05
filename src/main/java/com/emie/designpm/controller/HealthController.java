package com.emie.designpm.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 进程存活检查：不访问数据库，避免连接池繁忙时被误判为整站不可用。 */
@RestController
public class HealthController {
    @GetMapping("/api/health/live")
    public ResponseEntity<Map<String, String>> live() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
