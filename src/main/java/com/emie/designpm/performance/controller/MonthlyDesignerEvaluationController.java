package com.emie.designpm.performance.controller;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.GrowthOsScoreService;
import com.emie.designpm.performance.service.MonthlyDesignerEvaluationService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/performance/designer-evaluations")
public class MonthlyDesignerEvaluationController {
    private final MonthlyDesignerEvaluationService service;
    private final GrowthOsScoreService growthScores;

    public MonthlyDesignerEvaluationController(MonthlyDesignerEvaluationService service) {
        this(service, null);
    }

    @Autowired
    public MonthlyDesignerEvaluationController(
            MonthlyDesignerEvaluationService service, GrowthOsScoreService growthScores) {
        this.service = service;
        this.growthScores = growthScores;
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
            return ResponseEntity.ok(
                    service.save(month, userId, trustedScores(month, userId, scores), session.userId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(Map.of("error", "评分已被修改，请关闭评分窗口，刷新页面后重试"));
        }
    }

    // Fetch outside the evaluation service's write transaction. Submitted form
    // values cannot substitute for the scores published by Growth OS.
    private MonthlyDesignerEvaluationService.Scores trustedScores(
            String month, String userId, MonthlyDesignerEvaluationService.Scores submitted) {
        if (growthScores == null || !growthScores.isConfigured() || submitted == null) return submitted;
        MonthlyDesignerEvaluationService.validatePeriod(month, true);
        var saved = service.list(month).stream()
                .filter(row -> userId.equals(row.userId()))
                .findFirst()
                .orElse(null);
        var current = growthScores.forUser(month, userId);
        boolean matched = "matched".equals(current.matchStatus());
        var collaboration = published(matched, current.collaboration360())
                ? current.collaboration360().score()
                : saved == null ? null : saved.collaborationScore();
        var weekly = published(matched, current.weeklyQuality())
                ? current.weeklyQuality().score()
                : saved == null ? null : saved.weeklyReportScore();
        if (collaboration == null || weekly == null)
            throw new IllegalArgumentException("成长周报评分尚未发布或人员未匹配，暂不能保存本月绩效；请先核对飞书身份和评分状态");
        String collaborationReason = published(matched, current.collaboration360())
                ? provenance("360协同", current, current.collaboration360())
                : saved.collaborationReason();
        String weeklyReason = published(matched, current.weeklyQuality())
                ? provenance("周报质量", current, current.weeklyQuality())
                : saved.weeklyReportReason();
        return new MonthlyDesignerEvaluationService.Scores(
                submitted.version(),
                submitted.completionScore(),
                collaboration,
                submitted.attendanceScore(),
                weekly,
                submitted.comment(),
                submitted.weeklyReportDeduction(),
                submitted.completionReason(),
                collaborationReason,
                submitted.attendanceReason(),
                weeklyReason,
                submitted.weeklyReportDeductionReason());
    }

    private boolean published(boolean matched, GrowthOsScoreService.Score score) {
        return matched
                && score != null
                && score.isFinal()
                && "published".equals(score.status())
                && score.score() != null
                && score.score().signum() >= 0
                && score.score().compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    private String provenance(String label, GrowthOsScoreService.UserScores user, GrowthOsScoreService.Score score) {
        return "成长周报 OS · " + label + "；飞书 user_id=" + user.feishuUserId() + "；来源=" + score.source() + "；版本="
                + score.revision() + "；更新时间=" + score.updatedAt();
    }

    private ResponseEntity<?> denied(HttpServletRequest request) {
        var session = (AuthSession) request.getAttribute("authSession");
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        if (!"admin".equals(session.role())) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可评价设计师"));
        return null;
    }
}
