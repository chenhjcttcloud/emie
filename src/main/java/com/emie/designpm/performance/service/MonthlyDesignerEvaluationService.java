package com.emie.designpm.performance.service;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.MonthlyDesignerEvaluation;
import com.emie.designpm.performance.repository.MonthlyDesignerEvaluationRepository;
import com.emie.designpm.performance.repository.MonthlyUserPointTargetRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MonthlyDesignerEvaluationService {
    public record Scores(
            Long version,
            BigDecimal completionScore,
            BigDecimal collaborationScore,
            BigDecimal attendanceScore,
            BigDecimal weeklyReportScore,
            String comment,
            BigDecimal weeklyReportDeduction,
            String completionReason,
            String collaborationReason,
            String attendanceReason,
            String weeklyReportReason,
            String weeklyReportDeductionReason) {}

    public record Completion(
            BigDecimal completionScore, BigDecimal earnedPoints, Integer targetPoints, String completionReason) {}

    public record View(
            String userId,
            String monthKey,
            Long version,
            BigDecimal completionScore,
            BigDecimal collaborationScore,
            BigDecimal attendanceScore,
            BigDecimal weeklyReportScore,
            String comment,
            BigDecimal weeklyReportDeduction,
            BigDecimal totalScore,
            String updatedBy,
            LocalDateTime updatedAt,
            String completionReason,
            String collaborationReason,
            String attendanceReason,
            String weeklyReportReason,
            String weeklyReportDeductionReason) {}

    private final MonthlyDesignerEvaluationRepository evaluations;
    private final UserRepository users;
    private final PerformanceService performance;
    private final MonthlyUserPointTargetRepository targets;

    public MonthlyDesignerEvaluationService(
            MonthlyDesignerEvaluationRepository evaluations,
            UserRepository users,
            PerformanceService performance,
            MonthlyUserPointTargetRepository targets) {
        this.evaluations = evaluations;
        this.users = users;
        this.performance = performance;
        this.targets = targets;
    }

    public Completion completion(String month, String userId) {
        validatePeriod(month, true);
        validateDesigner(userId);
        BigDecimal earned = performance.leaderboard(month).stream()
                .filter(row -> userId.equals(row.get("userId")))
                .findFirst()
                .map(row -> BigDecimal.valueOf(((Number) row.get("points")).doubleValue()))
                .orElse(BigDecimal.ZERO);
        var target = targets.findByMonthKeyAndUserId(month, userId)
                .or(() -> targets.findByMonthKeyAndUserId("PERMANENT", userId))
                .map(com.emie.designpm.entity.MonthlyUserPointTarget::getTargetPoints)
                .orElse(0);
        if (target <= 0) return new Completion(null, earned, target, "目标积分未配置，暂无法计算完成率评分。");
        BigDecimal score = earned.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(target), 1, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO)
                .min(BigDecimal.valueOf(150));
        String explanation = "月度所得积分 " + earned.stripTrailingZeros().toPlainString() + " ÷ 目标积分 " + target + " × 100 = "
                + score.toPlainString() + " 分（最高 150 分）。";
        return new Completion(score, earned, target, explanation);
    }

    private void validateDesigner(String userId) {
        var user = users.findByUserId(userId).orElseThrow(() -> new IllegalArgumentException("设计师不存在"));
        if (!"designer".equals(user.getRole())
                || (user.getStatus() != null && !"active".equalsIgnoreCase(user.getStatus())))
            throw new IllegalArgumentException("仅可评价在职设计师");
    }

    private BigDecimal total(
            BigDecimal completion,
            BigDecimal collaboration,
            BigDecimal attendance,
            BigDecimal weekly,
            BigDecimal deduction) {
        return completion
                .multiply(BigDecimal.valueOf(70))
                .add(collaboration.multiply(BigDecimal.TEN))
                .add(attendance.multiply(BigDecimal.TEN))
                .add(weekly.multiply(BigDecimal.TEN))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .subtract(deduction);
    }

    private View view(MonthlyDesignerEvaluation e) {
        return new View(
                e.getUserId(),
                e.getMonthKey(),
                e.getVersion(),
                e.getCompletionScore(),
                e.getCollaborationScore(),
                e.getAttendanceScore(),
                e.getWeeklyReportScore(),
                e.getComment(),
                e.getWeeklyReportDeduction(),
                total(
                        e.getCompletionScore(),
                        e.getCollaborationScore(),
                        e.getAttendanceScore(),
                        e.getWeeklyReportScore(),
                        e.getWeeklyReportDeduction()),
                e.getUpdatedBy(),
                e.getUpdatedAt(),
                e.getCompletionReason(),
                e.getCollaborationReason(),
                e.getAttendanceReason(),
                e.getWeeklyReportReason(),
                e.getWeeklyReportDeductionReason());
    }

    public static void validatePeriod(String period, boolean monthOnly) {
        if (period == null || !period.matches(monthOnly ? "[0-9]{4}-[0-9]{2}" : "[0-9]{4}(-[0-9]{2})?"))
            throw new IllegalArgumentException("期间格式应为YYYY或YYYY-MM");
        int year = Integer.parseInt(period.substring(0, 4));
        if (year < 1) throw new IllegalArgumentException("年份应为1至9999");
        if (period.length() == 7) {
            try {
                YearMonth.parse(period);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("月份不合法");
            }
        }
    }

    private String reason(String value, String label) {
        if (value == null) return null;
        int start = 0, end = value.length();
        while (start < end && space(value.charAt(start))) start++;
        while (end > start && space(value.charAt(end - 1))) end--;
        String result = value.substring(start, end);
        if (result.length() > 2000) throw new IllegalArgumentException(label + "最多2000字");
        return result.isEmpty() ? null : result;
    }

    private boolean space(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }

    public List<View> list(String period) {
        validatePeriod(period, false);
        return evaluations
                .findByMonthKeyBetweenOrderByMonthKeyAscUserIdAsc(
                        period.length() == 4 ? period + "-01" : period, period.length() == 4 ? period + "-12" : period)
                .stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public View save(String month, String userId, Scores scores, String adminId) {
        validatePeriod(month, true);
        if (scores == null) throw new IllegalArgumentException("请填写全部评分");
        var completion = completion(month, userId);
        var automatic = completion.completionScore();
        if (automatic == null) throw new IllegalArgumentException("请先配置设计师目标积分");
        BigDecimal[] values = {
            automatic, scores.collaborationScore(), scores.attendanceScore(), scores.weeklyReportScore()
        };
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null
                    || values[i].signum() < 0
                    || values[i].compareTo(BigDecimal.valueOf(i == 0 ? 150 : 100)) > 0)
                throw new IllegalArgumentException("评分超出范围或未填写");
            values[i] = values[i].setScale(2, RoundingMode.HALF_UP);
        }
        if (scores.comment() != null && scores.comment().length() > 2000)
            throw new IllegalArgumentException("评语最多2000字");
        BigDecimal deduction =
                scores.weeklyReportDeduction() == null ? BigDecimal.ZERO : scores.weeklyReportDeduction();
        if (deduction.signum() != 0
                && (deduction.compareTo(BigDecimal.valueOf(5)) < 0 || deduction.compareTo(BigDecimal.TEN) > 0))
            throw new IllegalArgumentException("周报扣分应为0或5至10分");
        deduction = deduction.setScale(2, RoundingMode.HALF_UP);
        String completionReason = completion.completionReason();
        String collaborationReason = reason(scores.collaborationReason(), "协作能力说明");
        String attendanceReason = reason(scores.attendanceReason(), "工作态度和考勤说明");
        String weeklyReportReason = reason(scores.weeklyReportReason(), "周报考核说明");
        String deductionReason =
                deduction.signum() == 0 ? null : reason(scores.weeklyReportDeductionReason(), "周报额外扣分说明");
        var existing = evaluations.findByUserIdAndMonthKey(userId, month);
        if ((existing.isPresent() && !Objects.equals(existing.get().getVersion(), scores.version()))
                || (existing.isEmpty() && scores.version() != null))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "评分已被修改，请关闭评分窗口，刷新页面后重试");
        var result = existing.orElseGet(MonthlyDesignerEvaluation::new);
        result.setUserId(userId);
        result.setMonthKey(month);
        result.setCompletionScore(values[0]);
        if (existing.isEmpty()) {
            result.setAdjustmentScore(BigDecimal.ZERO);
            result.setAestheticScore(BigDecimal.ZERO);
            result.setInitiativeScore(BigDecimal.ZERO);
        }
        result.setCollaborationScore(values[1]);
        result.setAttendanceScore(values[2]);
        result.setWeeklyReportScore(values[3]);
        result.setCompletionReason(completionReason);
        result.setCollaborationReason(collaborationReason);
        result.setAttendanceReason(attendanceReason);
        result.setWeeklyReportReason(weeklyReportReason);
        result.setWeeklyReportDeductionReason(deductionReason);
        result.setComment(scores.comment());
        result.setWeeklyReportDeduction(deduction);
        result.setUpdatedBy(adminId);
        result.setUpdatedAt(LocalDateTime.now());
        result.setTotalScore(total(values[0], values[1], values[2], values[3], deduction));
        return view(evaluations.saveAndFlush(result));
    }
}
