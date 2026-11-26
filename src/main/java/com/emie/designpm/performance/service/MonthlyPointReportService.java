package com.emie.designpm.performance.service;

import com.emie.designpm.entity.PointRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
                l.sub_task_id AS sourceId,p.id AS projectId,t.status AS itemStatus,t.point_rule_code AS itemRuleCode,
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
                l.source_id AS sourceId,COALESCE(p.id,mp.id) AS projectId,
                CASE WHEN l.source_type='DESIGN_REQUIREMENT' THEN dr.status ELSE t.status END AS itemStatus,
                CASE WHEN l.source_type='DESIGN_REQUIREMENT' THEN dr.point_rule_code ELSE t.point_rule_code END AS itemRuleCode,
                CASE WHEN l.source_type='DESIGN_REQUIREMENT' THEN
                    CASE WHEN dr.status='completed' THEN COALESCE(dr.delivered_at,dr.updated_at) END
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

    private record ItemKey(String userId, String kind, String id) {}

    private static ItemKey itemKey(String userId, Map<String, Object> item) {
        return new ItemKey(userId, Objects.toString(item.get("kind")), Objects.toString(item.get("id")));
    }

    private static ItemKey entryKey(Map<String, Object> entry) {
        String kind = entry.get("requirementId") != null
                ? "requirement"
                : entry.get("taskId") != null ? "task" : "adjustment";
        Object id = entry.get("requirementId") != null
                ? entry.get("requirementId")
                : entry.get("taskId") != null ? entry.get("taskId") : entry.get("id");
        return new ItemKey(Objects.toString(entry.get("userId")), kind, Objects.toString(id));
    }

    private Map<String, String> categories() {
        var categories = new HashMap<String, String>();
        for (var row : jdbc.queryForList("SELECT rule_code AS code,category FROM point_rules", Map.of())) {
            String category = Objects.toString(row.get("category"), "");
            categories.put(
                    Objects.toString(row.get("code"), "").toUpperCase(Locale.ROOT),
                    category.isBlank() ? "未分类" : category);
        }
        return categories;
    }

    private Map<String, Object> fromEntry(Map<String, Object> entry, Map<String, String> categories) {
        ItemKey key = entryKey(entry);
        var item = new LinkedHashMap<String, Object>();
        boolean independent = "adjustment".equals(key.kind());
        String ruleCode = Objects.toString(entry.get("itemRuleCode"), "");
        if (independent) ruleCode = Objects.toString(entry.get("ruleCode"), "");
        String name =
                Objects.toString(entry.get("requirement".equals(key.kind()) ? "requirementName" : "taskName"), "");
        if (name.isBlank())
            name = independent
                    ? Objects.toString(entry.get("reason"), "")
                    : ("requirement".equals(key.kind()) ? "设计/送审需求 #" : "子任务 #") + key.id();
        if (name.isBlank()) name = Objects.toString(entry.get("source"), "独立积分") + " #" + key.id();
        item.put("kind", key.kind());
        item.put("id", independent ? key.id() : Long.valueOf(key.id()));
        item.put("name", name);
        item.put("project", Objects.toString(entry.get("project"), ""));
        item.put("projectId", entry.get("projectId"));
        item.put("source", entry.get("source"));
        item.put("sourceId", entry.get("sourceId"));
        item.put("status", Objects.toString(entry.get("itemStatus"), ""));
        item.put("completedAt", entry.get("completedAt"));
        item.put("ruleCode", ruleCode);
        item.put("ruleVersion", ruleVersion(ruleCode));
        item.put("category", categories.getOrDefault(ruleCode.toUpperCase(Locale.ROOT), "未分类"));
        return item;
    }

    private static String ruleVersion(String ruleCode) {
        String code = ruleCode.strip().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) return "unbound";
        if (code.startsWith("D20_")) return "2.0";
        if (code.startsWith(PointRule.VISUAL_V3_PREFIX)) return "3.0";
        return "legacy";
    }

    private static void points(Map<String, Object> item, List<Map<String, Object>> history, String period) {
        BigDecimal cumulative = total(history);
        BigDecimal inPeriod = total(history.stream()
                .filter(entry -> inPeriod(period, entry.get("accountingMonth")))
                .toList());
        item.put("score", cumulative);
        item.put("periodPoints", inPeriod);
        item.put("otherPeriodPoints", cumulative.subtract(inPeriod));
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
        for (var entry : entries) {
            if (entry.get("taskId") instanceof Number id) taskIds.add(id.longValue());
            if (entry.get("requirementId") instanceof Number id) requirementIds.add(id.longValue());
        }
        var history = itemEntries(taskIds, requirementIds);
        var histories = new HashMap<ItemKey, List<Map<String, Object>>>();
        for (var entry : history) {
            histories
                    .computeIfAbsent(entryKey(entry), ignored -> new ArrayList<>())
                    .add(entry);
        }
        var categories = categories();
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
            var related = new LinkedHashMap<ItemKey, Map<String, Object>>();
            for (var original : (List<Map<String, Object>>) designer.get("tasks")) {
                var task = new LinkedHashMap<>(original);
                ItemKey key = itemKey(userId, task);
                var matching = histories.getOrDefault(key, List.of());
                points(task, matching, period);
                task.put("periodStatus", "completed_in_period");
                task.put("status", "completed");
                completed.add(task);
                related.put(key, task);
            }
            for (var entry : personal) {
                ItemKey key = entryKey(entry);
                if (related.containsKey(key)) continue;
                var item = fromEntry(entry, categories);
                boolean independent = "adjustment".equals(key.kind());
                points(item, independent ? List.of(entry) : histories.getOrDefault(key, List.of()), period);
                item.put(
                        "periodStatus",
                        independent ? "independent" : completionStatus(period, item.get("completedAt")));
                related.put(key, item);
            }
            designer.put("tasks", completed);
            var associated = new ArrayList<>(related.values());
            BigDecimal relatedTotal = associated.stream()
                    .map(item -> amount(item.get("periodPoints")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (relatedTotal.compareTo(total(personal)) != 0) throw new IllegalStateException("关联事项积分与月度明细不一致，请核对积分流水");
            designer.put("relatedItems", associated);
            designer.put("relatedCount", associated.size());
        }
    }

    private static boolean inPeriod(String period, Object month) {
        return month instanceof String key
                && (period.length() == 4
                        ? key.compareTo(period + "-01") >= 0 && key.compareTo(period + "-12") <= 0
                        : key.equals(period));
    }

    private static String completionStatus(String period, Object completedAt) {
        if (!(completedAt instanceof LocalDateTime date)) return "pending";
        return inPeriod(period, YearMonth.from(date).toString()) ? "completed_in_period" : "completed_other_period";
    }
}
