package com.emie.designpm.performance.service;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.*;
import com.emie.designpm.performance.repository.MonthlyPerformanceConfigRepository;
import com.emie.designpm.performance.repository.MonthlyUserPointTargetRepository;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.repository.PointRuleRepository;
import com.emie.designpm.points.repository.StandardPointConfigRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.util.TextEncodingUtil;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PerformanceService {
    private final PointLedgerRepository ledgers;
    private final PointAdjustmentLedgerRepository adjustments;
    private final UserRepository users;
    private final StandardPointConfigRepository standards;
    private final MonthlyPerformanceConfigRepository months;
    private final SystemConfigRepository configs;
    private final SubTaskRepository subTasks;
    private final PointRuleRepository rules;
    private MonthlyUserPointTargetRepository userTargets;

    public PerformanceService(
            PointLedgerRepository ledgers,
            PointAdjustmentLedgerRepository adjustments,
            UserRepository users,
            StandardPointConfigRepository standards,
            MonthlyPerformanceConfigRepository months,
            SystemConfigRepository configs,
            SubTaskRepository subTasks,
            PointRuleRepository rules) {
        this.ledgers = ledgers;
        this.adjustments = adjustments;
        this.users = users;
        this.standards = standards;
        this.months = months;
        this.configs = configs;
        this.subTasks = subTasks;
        this.rules = rules;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void monthlyUserTargets(MonthlyUserPointTargetRepository userTargets) {
        this.userTargets = userTargets;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> leaderboard(String month) {
        YearMonth selected = month == null || month.isBlank() ? null : YearMonth.parse(month);
        LocalDateTime from = selected == null ? null : selected.atDay(1).atStartOfDay();
        LocalDateTime to =
                selected == null ? null : selected.plusMonths(1).atDay(1).atStartOfDay();
        Map<String, Double> sums = new HashMap<>();
        ledgers.sumPerformancePointsByMonth(month, from, to)
                .forEach(row -> sums.put((String) row[0], ((Number) row[1]).doubleValue()));
        // P1-4：调账与流水统一按 accounting_month 归月，月度统计/排行榜口径一致。
        // 调账缺省即入账当月（APPEAL 异议调账、TASK_WITHDRAWAL 退单扣分为当月事件，
        // 实体 @PrePersist 兜底）；PO_PROGRESS 履职积分按进展所属月 month_key 落账。
        adjustments
                .sumPointsByMonth(month, from, to)
                .forEach(row -> sums.merge((String) row[0], ((Number) row[1]).doubleValue(), Double::sum));
        // 保留对旧仓储 mock/自定义实现的兼容；正常 JPA 实现会走上面的聚合查询。
        if (sums.isEmpty()) {
            ledgers.findAll().stream()
                    .filter(PointLedger::isCountInPerformance)
                    .filter(item -> selected == null
                            ? within(item.getCreatedAt(), from, to)
                            : month.equals(item.getAccountingMonth()))
                    .forEach(item -> sums.merge(
                            item.getUserId(), item.getPoints() == null ? 0d : item.getPoints(), Double::sum));
            adjustments.findAll().stream()
                    .filter(item -> selected == null
                            ? within(item.getCreatedAt(), from, to)
                            : month.equals(item.getAccountingMonth()))
                    .forEach(item -> sums.merge(
                            item.getUserId(),
                            item.getPoints() == null ? 0d : item.getPoints().doubleValue(),
                            Double::sum));
        }
        return sums.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId", entry.getKey());
                    row.put("points", entry.getValue());
                    users.findByUserId(entry.getKey()).ifPresent(user -> row.put("name", user.getName()));
                    return row;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> designerMonthlyReport(String month) {
        YearMonth selected = YearMonth.parse(month);
        LocalDateTime from = selected.atDay(1).atStartOfDay();
        LocalDateTime to = selected.plusMonths(1).atDay(1).atStartOfDay();
        List<User> designers = users.findByRole("designer").stream()
                .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                .toList();
        Map<String, Map<String, Object>> reports = new LinkedHashMap<>();
        designers.forEach(user -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", user.getUserId());
            row.put("designer", TextEncodingUtil.repairUtf8Mojibake(user.getName()));
            row.put("month", month);
            row.put("completedCount", 0);
            row.put("score", 0d);
            row.put("categoryCounts", new LinkedHashMap<String, Integer>());
            row.put("tasks", new ArrayList<Map<String, Object>>());
            reports.put(user.getUserId(), row);
        });
        List<SubTask> completed = subTasks.findDesignerTasksCompletedBetween(from, to).stream()
                .filter(task -> task.getDesignerId() != null && reports.containsKey(task.getDesignerId()))
                .toList();
        if (!completed.isEmpty()) {
            Map<Long, Map<String, Double>> taskPoints = new HashMap<>();
            ledgers.findBySubTaskIdIn(completed.stream().map(SubTask::getId).toList()).stream()
                    .filter(PointLedger::isCountInPerformance)
                    .forEach(ledger -> taskPoints
                            .computeIfAbsent(ledger.getSubTaskId(), ignored -> new HashMap<>())
                            .merge(
                                    ledger.getUserId(),
                                    Optional.ofNullable(ledger.getPoints()).orElse(0d),
                                    Double::sum));
            Map<String, String> categories = rules.findAll().stream()
                    .collect(Collectors.toMap(
                            rule -> rule.getRuleCode().toUpperCase(Locale.ROOT),
                            rule -> Optional.ofNullable(rule.getCategory())
                                    .filter(value -> !value.isBlank())
                                    .orElse("未分类"),
                            (first, ignored) -> first));
            for (SubTask task : completed) {
                Map<String, Object> report = reports.get(task.getDesignerId());
                String category = categories.getOrDefault(
                        Optional.ofNullable(task.getPointRuleCode()).orElse("").toUpperCase(Locale.ROOT), "未分类");
                Map<String, Integer> categoryCounts = (Map<String, Integer>) report.get("categoryCounts");
                categoryCounts.merge(category, 1, Integer::sum);
                double score = taskPoints.getOrDefault(task.getId(), Map.of()).getOrDefault(task.getDesignerId(), 0d);
                report.put("completedCount", (Integer) report.get("completedCount") + 1);
                report.put("score", (Double) report.get("score") + score);
                ((List<Map<String, Object>>) report.get("tasks"))
                        .add(Map.of(
                                "id",
                                task.getId(),
                                "name",
                                task.getName(),
                                "completedAt",
                                task.getCompletedAt(),
                                "category",
                                category,
                                "ruleCode",
                                Optional.ofNullable(task.getPointRuleCode()).orElse(""),
                                "project",
                                Optional.ofNullable(task.getProject().getProductName())
                                        .orElse(""),
                                "projectType",
                                Optional.ofNullable(task.getProject().getType()).orElse(""),
                                "score",
                                score));
            }
        }
        return Map.of("month", month, "from", from, "to", to, "designers", new ArrayList<>(reports.values()));
    }

    public byte[] designerMonthlyReportExcel(String month) {
        Map<String, Object> report = designerMonthlyReport(month);
        List<Map<String, Object>> designers = (List<Map<String, Object>>) report.get("designers");
        try (Workbook workbook = new XSSFWorkbook();
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            CellStyle header = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);
            Sheet summary = workbook.createSheet("设计师月度绩效");
            String[] summaryHeaders = {"设计师", "月份", "完成子任务数", "类别数量占比", "分数（积分）", "完成子任务"};
            Row headerRow = summary.createRow(0);
            for (int i = 0; i < summaryHeaders.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(summaryHeaders[i]);
                cell.setCellStyle(header);
            }
            Sheet details = workbook.createSheet("完成子任务明细");
            String[] detailHeaders = {"设计师", "月份", "完成时间", "子任务", "项目", "项目类型", "类别", "规则编号", "分数（积分）"};
            Row detailHeader = details.createRow(0);
            for (int i = 0; i < detailHeaders.length; i++) {
                Cell cell = detailHeader.createCell(i);
                cell.setCellValue(detailHeaders[i]);
                cell.setCellStyle(header);
            }
            int detailRow = 1;
            for (int i = 0; i < designers.size(); i++) {
                Map<String, Object> designer = designers.get(i);
                List<Map<String, Object>> tasks = (List<Map<String, Object>>) designer.get("tasks");
                Row row = summary.createRow(i + 1);
                row.createCell(0).setCellValue(String.valueOf(designer.get("designer")));
                row.createCell(1).setCellValue(month);
                row.createCell(2).setCellValue(((Number) designer.get("completedCount")).intValue());
                row.createCell(3)
                        .setCellValue(reportRatio((Map<String, Integer>) designer.get("categoryCounts"), tasks.size()));
                row.createCell(4).setCellValue(((Number) designer.get("score")).doubleValue());
                row.createCell(5)
                        .setCellValue(tasks.stream()
                                .map(task -> String.valueOf(task.get("name")))
                                .collect(Collectors.joining("、")));
                for (Map<String, Object> task : tasks) {
                    Row item = details.createRow(detailRow++);
                    item.createCell(0).setCellValue(String.valueOf(designer.get("designer")));
                    item.createCell(1).setCellValue(month);
                    item.createCell(2)
                            .setCellValue(
                                    String.valueOf(task.get("completedAt")).replace('T', ' '));
                    item.createCell(3).setCellValue(String.valueOf(task.get("name")));
                    item.createCell(4).setCellValue(String.valueOf(task.get("project")));
                    item.createCell(5).setCellValue(String.valueOf(task.get("projectType")));
                    item.createCell(6).setCellValue(String.valueOf(task.get("category")));
                    item.createCell(7).setCellValue(String.valueOf(task.get("ruleCode")));
                    item.createCell(8).setCellValue(((Number) task.get("score")).doubleValue());
                }
            }
            for (int i = 0; i < summaryHeaders.length; i++) summary.autoSizeColumn(i);
            for (int i = 0; i < detailHeaders.length; i++) details.autoSizeColumn(i);
            workbook.write(output);
            return output.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("生成月度绩效 Excel 失败", e);
        }
    }

    private String reportRatio(Map<String, Integer> counts, int total) {
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue() + " ("
                        + (total == 0 ? 0 : Math.round(entry.getValue() * 100f / total)) + "%)")
                .collect(Collectors.joining("、"));
    }

    private boolean within(LocalDateTime created, LocalDateTime from, LocalDateTime to) {
        return from == null || (created != null && !created.isBefore(from) && created.isBefore(to));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(String userId, String month) {
        double points = month == null
                ? ledgers.sumPerformancePointsByUserId(userId) + adjustments.sumPointsByUserId(userId)
                : leaderboard(month).stream()
                        .filter(row -> userId.equals(row.get("userId")))
                        .mapToDouble(row -> ((Number) row.get("points")).doubleValue())
                        .findFirst()
                        .orElse(0);
        StandardPointConfig personal = standards
                .findByConfigCode(userId)
                .filter(StandardPointConfig::isEnabled)
                .orElse(null);
        MonthlyUserPointTarget assignedTarget =
                userTargets == null ? null : userTargets.findByUserId(userId).orElse(null);
        int target =
                assignedTarget != null ? assignedTarget.getTargetPoints() : personal != null ? personal.getPoints() : 0;
        double companyCoefficient = 1d;
        double attainmentRate = target > 0 ? (double) points / target : 0d;
        double performanceBase =
                personal == null || personal.getPerformanceBase() == null ? 0d : personal.getPerformanceBase();
        double simulatedSalary = performanceBase * companyCoefficient * attainmentRate;
        String mode = config("points.program.mode", "TRIAL").toUpperCase(Locale.ROOT);
        boolean officiallyApplied = "ACTIVE".equals(mode)
                || ("AUTO".equals(mode)
                        && !LocalDate.now()
                                .isBefore(LocalDate.parse(config("points.program.active_start", "2026-10-01"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("month", month);
        out.put("points", points);
        // 保留接口兼容字段；前端不再展示或使用自动绩效结果。
        out.put("targetPoints", target);
        out.put("attainmentRate", attainmentRate);
        out.put("multiplier", companyCoefficient);
        out.put("companyCoefficient", companyCoefficient);
        out.put("performanceFactor", attainmentRate * companyCoefficient);
        out.put("performanceBase", performanceBase);
        out.put("simulatedPerformanceSalary", simulatedSalary);
        out.put("officiallyApplied", officiallyApplied);
        out.put("payablePerformanceSalary", officiallyApplied ? simulatedSalary : null);
        out.put("programMode", mode);
        out.put("performanceDisabled", true);
        out.put("manualCalculationMessage", "当前仅记录积分，绩效由管理员根据积分人工核算");
        return out;
    }

    private double companyCoefficient(double salesAmount, String departmentType) {
        double low = numberConfig("points.performance.sales.lt200.max", 200);
        double mid = numberConfig("points.performance.sales.mid.max", 300);
        double normal = numberConfig("points.performance.sales.normal.max", 350);
        double high = numberConfig("points.performance.sales.high.max", 400);
        if (salesAmount < low) return 0d;
        if (salesAmount < mid)
            return numberConfig(
                    "BUSINESS".equalsIgnoreCase(departmentType)
                            ? "points.performance.coefficient.mid.business"
                            : "points.performance.coefficient.mid.support",
                    "BUSINESS".equalsIgnoreCase(departmentType) ? .6 : 1d);
        if (salesAmount < normal) return numberConfig("points.performance.coefficient.normal", 1d);
        if (salesAmount < high) return numberConfig("points.performance.coefficient.high", 1.5d);
        return numberConfig("points.performance.coefficient.top", 2d);
    }

    private String config(String key, String fallback) {
        return configs.findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .filter(value -> !value.isBlank())
                .orElse(fallback);
    }

    private double numberConfig(String key, double fallback) {
        try {
            return Double.parseDouble(config(key, String.valueOf(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public StandardPointConfig saveStandard(StandardPointConfig config) {
        if (config.getConfigCode() == null || config.getConfigCode().isBlank())
            throw new IllegalArgumentException("配置编码不能为空");
        users.findByUserId(config.getConfigCode())
                .filter(user -> "designer".equalsIgnoreCase(user.getRole()))
                .orElseThrow(() -> new IllegalArgumentException("个人积分配置仅支持设计师"));
        if (config.getPoints() == null || config.getPoints() < 0) throw new IllegalArgumentException("标准积分不能小于0");
        if (config.getPerformanceBase() == null || config.getPerformanceBase() < 0)
            throw new IllegalArgumentException("绩效基数不能小于0");
        String type = Optional.ofNullable(config.getDepartmentType())
                .orElse("SUPPORT")
                .toUpperCase(Locale.ROOT);
        if (!List.of("SUPPORT", "BUSINESS").contains(type))
            throw new IllegalArgumentException("岗位类型仅支持SUPPORT或BUSINESS");
        config.setDepartmentType(type);
        if (config.getCreatedAt() == null) config.setCreatedAt(LocalDateTime.now());
        if (config.getUpdatedAt() == null) config.setUpdatedAt(LocalDateTime.now());
        return standards.save(config);
    }

    public List<StandardPointConfig> standards() {
        Map<String, StandardPointConfig> existing = standards.findAll().stream()
                .collect(Collectors.toMap(
                        StandardPointConfig::getConfigCode, item -> item, (a, b) -> a, LinkedHashMap::new));
        List<User> activeDesigners = users.findByRole("designer").stream()
                .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                .toList();
        Set<String> activeDesignerIds =
                activeDesigners.stream().map(User::getUserId).collect(Collectors.toSet());
        existing.keySet().removeIf(id -> !activeDesignerIds.contains(id));
        activeDesigners.forEach(user -> existing.computeIfAbsent(user.getUserId(), id -> {
            StandardPointConfig config = new StandardPointConfig();
            config.setConfigCode(id);
            config.setPoints(100);
            config.setPerformanceBase(0d);
            config.setDepartmentType("SUPPORT");
            config.setEnabled(true);
            config.setDescription("自动生成的设计师个人绩效配置");
            return standards.save(config);
        }));
        return new ArrayList<>(existing.values());
    }

    public void deleteStandard(Long id) {
        if (id == null || !standards.existsById(id)) throw new IllegalArgumentException("配置不存在");
        standards.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> designerTargets(String month) {
        Map<String, MonthlyUserPointTarget> configured = userTargets.findAll().stream()
                .collect(Collectors.toMap(MonthlyUserPointTarget::getUserId, item -> item, (a, b) -> b));
        return users.findByRole("designer").stream()
                .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                .map(user -> {
                    MonthlyUserPointTarget target = configured.get(user.getUserId());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", target == null ? null : target.getId());
                    row.put("userId", user.getUserId());
                    row.put("feishuUserId", user.getFeishuUserId());
                    row.put("userName", TextEncodingUtil.repairUtf8Mojibake(user.getName()));
                    row.put("targetPoints", target == null ? 0 : target.getTargetPoints());
                    row.put("configured", target != null);
                    return row;
                })
                .toList();
    }

    public MonthlyUserPointTarget saveDesignerTarget(
            String month, String userId, Integer targetPoints, String adminId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("设计师不能为空");
        if (targetPoints == null || targetPoints < 0) throw new IllegalArgumentException("目标积分不能小于0");
        User user = users.findByUserId(userId)
                .filter(item -> "designer".equalsIgnoreCase(item.getRole()))
                .orElseThrow(() -> new IllegalArgumentException("请选择有效的设计师"));
        MonthlyUserPointTarget target = userTargets.findByUserId(userId).orElseGet(MonthlyUserPointTarget::new);
        target.setMonthKey("PERMANENT");
        target.setUserId(userId);
        target.setUserName(user.getName());
        target.setTargetPoints(targetPoints);
        target.setUpdatedBy(adminId);
        return userTargets.save(target);
    }

    private void validateMonth(String month) {
        if (month == null || !month.matches("\\d{4}-\\d{2}")) throw new IllegalArgumentException("月份格式应为YYYY-MM");
    }

    /** 月度绩效配置（供单不足标记/目标积分/销售额/系数）列表，供管理端维护入口展示。 */
    @Transactional(readOnly = true)
    public List<MonthlyPerformanceConfig> monthlyConfigs() {
        return months.findAll().stream()
                .sorted(Comparator.comparing(MonthlyPerformanceConfig::getMonthKey)
                        .reversed())
                .toList();
    }

    /** 新增或更新某月的绩效配置；核心是供单不足（supplyShortage）标记，供月度归档保护计算使用。 */
    public MonthlyPerformanceConfig saveMonthlyConfig(
            String month, Integer targetPoints, Double multiplier, Double salesAmount, Boolean supplyShortage) {
        validateMonth(month);
        MonthlyPerformanceConfig config = months.findByMonthKey(month).orElseGet(() -> {
            MonthlyPerformanceConfig created = new MonthlyPerformanceConfig();
            created.setMonthKey(month);
            return created;
        });
        if (targetPoints != null) {
            if (targetPoints < 0) throw new IllegalArgumentException("目标积分不能小于0");
            config.setTargetPoints(targetPoints);
        }
        if (multiplier != null) {
            if (multiplier < 0) throw new IllegalArgumentException("绩效系数不能小于0");
            config.setMultiplier(multiplier);
        }
        if (salesAmount != null && salesAmount < 0) throw new IllegalArgumentException("销售额不能小于0");
        config.setSalesAmount(salesAmount);
        if (supplyShortage != null) config.setSupplyShortage(supplyShortage);
        return months.save(config);
    }
}
