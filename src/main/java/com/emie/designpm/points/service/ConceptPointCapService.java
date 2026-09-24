package com.emie.designpm.points.service;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 2.0 纯概念探索月度上限：草图与 AI 概念图合计最多 20 分。 */
@Service
public class ConceptPointCapService {
    private final JdbcTemplate jdbc;

    public ConceptPointCapService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean cappedRule(String ruleCode) {
        return "D20_27".equals(ruleCode) || "D20_28".equals(ruleCode);
    }

    /** 调用方须在积分写入事务中使用；锁住设计师账户以串行化同月不同任务的提交。 */
    public double remaining(String userId, String month) {
        jdbc.queryForObject("SELECT id FROM users WHERE user_id = ? FOR UPDATE", Long.class, userId);
        BigDecimal taskPoints = jdbc.queryForObject(
                "SELECT COALESCE(SUM(points), 0) FROM point_ledgers "
                        + "WHERE user_id = ? AND accounting_month = ? "
                        + "AND rule_code IN ('D20_27:BASE', 'D20_28:BASE') "
                        + "AND (reason IS NULL OR reason NOT LIKE '专项概念储备%')",
                BigDecimal.class, userId, month);
        BigDecimal requirementPoints = jdbc.queryForObject(
                "SELECT COALESCE(SUM(a.points), 0) FROM point_adjustment_ledgers a "
                        + "JOIN design_requirements d ON d.id = a.source_id "
                        + "WHERE a.source_type = 'DESIGN_REQUIREMENT' AND a.user_id = ? "
                        + "AND a.accounting_month = ? AND d.point_rule_code IN ('D20_27', 'D20_28') "
                        + "AND d.concept_reserve_exempt = FALSE",
                BigDecimal.class,
                userId,
                month);
        return Math.max(0d, 20d - taskPoints.doubleValue() - requirementPoints.doubleValue());
    }
}
