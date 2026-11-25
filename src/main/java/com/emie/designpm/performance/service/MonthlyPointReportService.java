package com.emie.designpm.performance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read existing awards by their accounting month, independently of when a task finishes. */
@Service
@Transactional(readOnly = true)
public class MonthlyPointReportService {
    private final NamedParameterJdbcTemplate jdbc;

    public MonthlyPointReportService(@Qualifier("dataSource") DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    private static final String TASK_ENTRIES =
            """
            SELECT CONCAT('L-',l.id) AS id,l.user_id AS userId,l.accounting_month AS accountingMonth,
                l.created_at AS createdAt,l.confirmed_at AS confirmedAt,l.points,l.reason,
                l.rule_code AS ruleCode,l.rule_description AS ruleDescription,
                CASE WHEN l.rule_code LIKE 'LEGACY_MANUAL:APPROVAL:%' THEN 'TASK_MANUAL'
                    WHEN l.rule_code LIKE '%:CHANGE:%' THEN 'TASK_REVISION'
                    WHEN l.rule_code LIKE '%:BASE' THEN 'TASK_BASE' ELSE 'TASK_QUALITY' END AS source,
                l.sub_task_id AS taskId,t.name AS taskName,NULL AS requirementId,NULL AS requirementName,
                p.product_name AS taskProject,NULL AS materialProject,t.completed_at AS completedAt
            FROM point_ledgers l
                LEFT JOIN sub_tasks t ON t.id=l.sub_task_id
                LEFT JOIN projects p ON p.id=t.project_id
            """;

    private static final String ADJUSTMENT_ENTRIES =
            """
            SELECT CONCAT('A-',l.id) AS id,l.user_id AS userId,l.accounting_month AS accountingMonth,
                l.created_at AS createdAt,NULL AS confirmedAt,l.points,l.reason,
                l.rule_code AS ruleCode,l.rule_description AS ruleDescription,l.source_type AS source,
                COALESCE(w.sub_task_id,original.sub_task_id) AS taskId,
                t.name AS taskName,
                CASE WHEN l.source_type='DESIGN_REQUIREMENT' THEN l.source_id END AS requirementId,
                dr.name AS requirementName,p.product_name AS taskProject,mp.product_name AS materialProject,
                CASE WHEN l.source_type='DESIGN_REQUIREMENT' THEN COALESCE(dr.delivered_at,dr.updated_at)
                    ELSE t.completed_at END AS completedAt
            FROM point_adjustment_ledgers l
                LEFT JOIN task_withdrawals w ON l.source_type='TASK_WITHDRAWAL'
                    AND w.id=l.source_id AND w.user_id=l.user_id
                LEFT JOIN point_appeals ap ON l.source_type='APPEAL'
                    AND ap.id=l.source_id AND ap.applicant_user_id=l.user_id
                LEFT JOIN point_ledgers original ON original.id=ap.point_ledger_id AND original.user_id=l.user_id
                LEFT JOIN sub_tasks t ON t.id=COALESCE(w.sub_task_id,original.sub_task_id)
                LEFT JOIN projects p ON p.id=t.project_id
                LEFT JOIN design_requirements dr ON l.source_type='DESIGN_REQUIREMENT' AND dr.id=l.source_id
                LEFT JOIN projects mp ON l.source_type='MATERIAL_MARKET' AND mp.id=l.source_id
            """;

    public List<Map<String, Object>> entries(String period, Collection<String> userIds) {
        MonthlyDesignerEvaluationService.validatePeriod(period, false);
        if (userIds.isEmpty()) return List.of();
        String first = period.length() == 4 ? period + "-01" : period;
        String last = period.length() == 4 ? period + "-12" : period;
        var params = Map.of("users", userIds, "first", first, "last", last);
        String where = " WHERE l.user_id IN (:users) AND l.accounting_month BETWEEN :first AND :last";
        var result =
                new ArrayList<>(jdbc.queryForList(TASK_ENTRIES + where + " AND l.count_in_performance=TRUE", params));
        result.addAll(jdbc.queryForList(ADJUSTMENT_ENTRIES + where, params));
        return normalize(result);
    }

    public List<Map<String, Object>> itemEntries(Collection<Long> taskIds, Collection<Long> requirementIds) {
        var result = new ArrayList<Map<String, Object>>();
        var params = new HashMap<String, Object>();
        var conditions = new ArrayList<String>();
        if (!taskIds.isEmpty()) {
            params.put("taskIds", taskIds);
            conditions.add("taskId IN (:taskIds)");
            result.addAll(jdbc.queryForList(
                    TASK_ENTRIES + " WHERE l.sub_task_id IN (:taskIds) AND l.count_in_performance=TRUE", params));
        }
        if (!requirementIds.isEmpty()) {
            params.put("requirementIds", requirementIds);
            conditions.add("requirementId IN (:requirementIds)");
        }
        if (!conditions.isEmpty()) {
            result.addAll(jdbc.queryForList(
                    "SELECT * FROM (" + ADJUSTMENT_ENTRIES + ") x WHERE " + String.join(" OR ", conditions), params));
        }
        return normalize(result);
    }

    private List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        for (var row : rows) {
            row.replaceAll((key, value) -> value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : value);
            String project = Objects.toString(row.remove("taskProject"), "");
            String material = Objects.toString(row.remove("materialProject"), "");
            row.put(
                    "project",
                    "DESIGN_REQUIREMENT".equals(row.get("source"))
                            ? "设计/送审需求"
                            : project.isBlank() ? material : project);
        }
        rows.sort(Comparator.comparing((Map<String, Object> row) -> Objects.toString(row.get("accountingMonth"), ""))
                .thenComparing(row -> Objects.toString(row.get("createdAt"), ""))
                .thenComparing(row -> Objects.toString(row.get("id"), "")));
        return rows;
    }

    public static BigDecimal total(List<Map<String, Object>> items) {
        return items.stream().map(item -> amount(item.get("points"))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal amount(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    @SuppressWarnings("unchecked")
    public void enrich(Map<String, Object> report) {
        String period = (String) report.get("month");
        var designers = (List<Map<String, Object>>) report.get("designers");
        var entries = entries(
                period,
                designers.stream().map(row -> (String) row.get("userId")).toList());
        var taskIds = new HashSet<Long>();
        var requirementIds = new HashSet<Long>();
        for (var designer : designers) {
            for (var item : (List<Map<String, Object>>) designer.get("tasks")) {
                ("requirement".equals(item.get("kind")) ? requirementIds : taskIds)
                        .add(((Number) item.get("id")).longValue());
            }
        }
        var history = itemEntries(taskIds, requirementIds);
        for (var designer : designers) {
            String userId = (String) designer.get("userId");
            var personal = entries.stream()
                    .filter(item -> userId.equals(item.get("userId")))
                    .toList();
            if (amount(designer.get("score"))
                            .setScale(2, RoundingMode.HALF_UP)
                            .compareTo(total(personal).setScale(2, RoundingMode.HALF_UP))
                    != 0) throw new IllegalStateException("月度积分与明细不一致，请核对积分流水");
            designer.put("pointItems", personal);
            var completed = new ArrayList<Map<String, Object>>();
            for (var original : (List<Map<String, Object>>) designer.get("tasks")) {
                var task = new LinkedHashMap<>(original);
                String key = "requirement".equals(task.get("kind")) ? "requirementId" : "taskId";
                long id = ((Number) task.get("id")).longValue();
                var matching = history.stream()
                        .filter(item -> userId.equals(item.get("userId"))
                                && item.get(key) instanceof Number n
                                && n.longValue() == id)
                        .toList();
                BigDecimal cumulative = total(matching);
                BigDecimal inPeriod = total(matching.stream()
                        .filter(item -> inPeriod(period, item.get("accountingMonth")))
                        .toList());
                task.put("score", cumulative);
                task.put("periodPoints", inPeriod);
                task.put("otherPeriodPoints", cumulative.subtract(inPeriod));
                completed.add(task);
            }
            designer.put("tasks", completed);
        }
    }

    private static boolean inPeriod(String period, Object month) {
        return month instanceof String key
                && (period.length() == 4
                        ? key.compareTo(period + "-01") >= 0 && key.compareTo(period + "-12") <= 0
                        : key.equals(period));
    }
}
