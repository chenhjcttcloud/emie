package com.emie.designpm.performance.controller;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.MonthlyDesignerEvaluationService;
import com.emie.designpm.performance.service.PerformanceService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/performance/my-report")
public class PersonalDesignerPerformanceController {
    private final PerformanceService performance;
    private final MonthlyDesignerEvaluationService evaluations;

    public PersonalDesignerPerformanceController(
            PerformanceService performance, MonthlyDesignerEvaluationService evaluations) {
        this.performance = performance;
        this.evaluations = evaluations;
    }

    @GetMapping
    public ResponseEntity<?> report(@RequestParam String period, HttpServletRequest request) {
        var session = (AuthSession) request.getAttribute("authSession");
        if (session == null || session.userId() == null || session.userId().isBlank())
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        if (!"designer".equals(session.role()))
            return ResponseEntity.status(403).body(Map.of("error", "仅设计师可查看个人绩效"));
        try {
            MonthlyDesignerEvaluationService.validatePeriod(period, false);
            Map<String, Object> report =
                    new LinkedHashMap<>(performance.designerMonthlyReport(period, session.userId()));
            report.put("userId", session.userId());
            var history = evaluations.listForUser(period, session.userId());
            var completion = period.length() == 7 ? evaluations.completion(period, session.userId()) : null;
            report.put(
                    "designers",
                    ((List<Map<String, Object>>) report.get("designers"))
                            .stream()
                                    .map(row -> {
                                        Map<String, Object> personal = new LinkedHashMap<>(row);
                                        personal.put("evaluations", history);
                                        if (completion != null) personal.put("completionPreview", completion);
                                        return personal;
                                    })
                                    .toList());
            return ResponseEntity.ok(report);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
