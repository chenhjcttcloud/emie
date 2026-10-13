package com.emie.designpm.performance.controller;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.MonthlyDesignerEvaluationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/performance/designer-evaluations")
public class MonthlyDesignerEvaluationController {
    private final MonthlyDesignerEvaluationService service;

    public MonthlyDesignerEvaluationController(MonthlyDesignerEvaluationService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String period, HttpServletRequest request) {
        var denied = denied(request);
        if (denied != null) return denied;
        try {
            return ResponseEntity.ok(service.list(period));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/completion")
    public ResponseEntity<?> completion(
            @RequestParam String month, @RequestParam String userId, HttpServletRequest request) {
        var denied = denied(request);
        if (denied != null) return denied;
        try {
            return ResponseEntity.ok(service.completion(month, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{month}/{userId}")
    public ResponseEntity<?> save(
            @PathVariable String month,
            @PathVariable String userId,
            @RequestBody MonthlyDesignerEvaluationService.Scores scores,
            HttpServletRequest request) {
        var denied = denied(request);
        if (denied != null) return denied;
        var session = (AuthSession) request.getAttribute("authSession");
        try {
            return ResponseEntity.ok(service.save(month, userId, scores, session.userId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(Map.of("error", "评分已被修改，请关闭评分窗口，刷新页面后重试"));
        }
    }

    private ResponseEntity<?> denied(HttpServletRequest request) {
        var session = (AuthSession) request.getAttribute("authSession");
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        if (!"admin".equals(session.role())) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可评价设计师"));
        return null;
    }
}
