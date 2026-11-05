package com.emie.designpm.performance.controller;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.GrowthOsScoreService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/performance/growth-scores")
public class GrowthOsScoreController {
    private final GrowthOsScoreService service;

    public GrowthOsScoreController(GrowthOsScoreService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> month(@RequestParam String month, HttpServletRequest request) {
        ResponseEntity<?> denied = denied(request);
        if (denied != null) return denied;
        try {
            return ResponseEntity.ok(service.month(month));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{month}/{userId}")
    public ResponseEntity<?> user(@PathVariable String month, @PathVariable String userId, HttpServletRequest request) {
        ResponseEntity<?> denied = denied(request);
        if (denied != null) return denied;
        try {
            return ResponseEntity.ok(service.forUser(month, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> denied(HttpServletRequest request) {
        AuthSession session = (AuthSession) request.getAttribute("authSession");
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        if (!"admin".equals(session.role())) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可查看成长周报评分"));
        return null;
    }
}
