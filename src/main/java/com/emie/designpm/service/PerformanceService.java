package com.emie.designpm.service;

import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class PerformanceService {
    private final PointLedgerRepository ledgers;
    private final PointAdjustmentLedgerRepository adjustments;
    private final UserRepository users;
    private final StandardPointConfigRepository standards;
    private final SystemConfigRepository configs;
    private MonthlyUserPointTargetRepository userTargets;

    public PerformanceService(PointLedgerRepository ledgers, PointAdjustmentLedgerRepository adjustments,
                              UserRepository users, StandardPointConfigRepository standards,
                              MonthlyPerformanceConfigRepository months, SystemConfigRepository configs) {
        this.ledgers = ledgers; this.adjustments = adjustments; this.users = users;
        this.standards = standards; this.configs = configs;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void monthlyUserTargets(MonthlyUserPointTargetRepository userTargets) { this.userTargets = userTargets; }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> leaderboard(String month) {
        YearMonth selected = month == null || month.isBlank() ? null : YearMonth.parse(month);
        LocalDateTime from = selected == null ? null : selected.atDay(1).atStartOfDay();
        LocalDateTime to = selected == null ? null : selected.plusMonths(1).atDay(1).atStartOfDay();
        Map<String, Double> sums = new HashMap<>();
        ledgers.sumPerformancePointsByMonth(month, from, to).forEach(row ->
                sums.put((String) row[0], ((Number) row[1]).doubleValue()));
        adjustments.sumPointsByPeriod(from, to).forEach(row ->
                sums.merge((String) row[0], ((Number) row[1]).doubleValue(), Double::sum));
        // 保留对旧仓储 mock/自定义实现的兼容；正常 JPA 实现会走上面的聚合查询。
        if (sums.isEmpty()) {
            ledgers.findAll().stream().filter(PointLedger::isCountInPerformance)
                    .filter(item -> selected == null ? within(item.getCreatedAt(), from, to) : month.equals(item.getAccountingMonth()))
                    .forEach(item -> sums.merge(item.getUserId(), item.getPoints() == null ? 0d : item.getPoints(), Double::sum));
            adjustments.findAll().stream().filter(item -> within(item.getCreatedAt(), from, to))
                    .forEach(item -> sums.merge(item.getUserId(), item.getPoints() == null ? 0d : item.getPoints().doubleValue(), Double::sum));
        }
        return sums.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).map(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", entry.getKey()); row.put("points", entry.getValue());
            users.findByUserId(entry.getKey()).ifPresent(user -> row.put("name", user.getName()));
            return row;
        }).toList();
    }

    private boolean within(LocalDateTime created, LocalDateTime from, LocalDateTime to) {
        return from == null || (created != null && !created.isBefore(from) && created.isBefore(to));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(String userId, String month) {
        double points = month == null ? ledgers.sumPerformancePointsByUserId(userId) + adjustments.sumPointsByUserId(userId)
                : leaderboard(month).stream().filter(row -> userId.equals(row.get("userId")))
                .mapToDouble(row -> ((Number) row.get("points")).doubleValue()).findFirst().orElse(0);
        StandardPointConfig personal = standards.findByConfigCode(userId).filter(StandardPointConfig::isEnabled).orElse(null);
        MonthlyUserPointTarget assignedTarget = month == null || userTargets == null ? null
                : userTargets.findByMonthKeyAndUserId(month, userId).orElse(null);
        int target = assignedTarget != null ? assignedTarget.getTargetPoints()
                : personal != null ? personal.getPoints() : 0;
        double companyCoefficient = 1d;
        double attainmentRate = target > 0 ? (double) points / target : 0d;
        double performanceBase = personal == null || personal.getPerformanceBase() == null ? 0d : personal.getPerformanceBase();
        double simulatedSalary = performanceBase * companyCoefficient * attainmentRate;
        String mode = config("points.program.mode", "TRIAL").toUpperCase(Locale.ROOT);
        boolean officiallyApplied = "ACTIVE".equals(mode) || ("AUTO".equals(mode) && !LocalDate.now().isBefore(LocalDate.parse(config("points.program.active_start", "2026-10-01"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId); out.put("month", month); out.put("points", points); out.put("targetPoints", target);
        out.put("attainmentRate", attainmentRate); out.put("multiplier", companyCoefficient);
        out.put("companyCoefficient", companyCoefficient); out.put("performanceFactor", attainmentRate * companyCoefficient);
        out.put("performanceBase", performanceBase); out.put("simulatedPerformanceSalary", simulatedSalary);
        out.put("officiallyApplied", officiallyApplied); out.put("payablePerformanceSalary", officiallyApplied ? simulatedSalary : null);
        out.put("programMode", mode);
        return out;
    }

    private double companyCoefficient(double salesAmount, String departmentType) {
        double low = numberConfig("points.performance.sales.lt200.max", 200);
        double mid = numberConfig("points.performance.sales.mid.max", 300);
        double normal = numberConfig("points.performance.sales.normal.max", 350);
        double high = numberConfig("points.performance.sales.high.max", 400);
        if (salesAmount < low) return 0d;
        if (salesAmount < mid) return numberConfig("BUSINESS".equalsIgnoreCase(departmentType)
                ? "points.performance.coefficient.mid.business" : "points.performance.coefficient.mid.support",
                "BUSINESS".equalsIgnoreCase(departmentType) ? .6 : 1d);
        if (salesAmount < normal) return numberConfig("points.performance.coefficient.normal", 1d);
        if (salesAmount < high) return numberConfig("points.performance.coefficient.high", 1.5d);
        return numberConfig("points.performance.coefficient.top", 2d);
    }

    private String config(String key, String fallback) {
        return configs.findByConfigKey(key).map(SystemConfig::getConfigValue).filter(value -> !value.isBlank()).orElse(fallback);
    }
    private double numberConfig(String key, double fallback) {
        try { return Double.parseDouble(config(key, String.valueOf(fallback))); } catch (Exception ignored) { return fallback; }
    }

    public StandardPointConfig saveStandard(StandardPointConfig config) {
        if (config.getConfigCode() == null || config.getConfigCode().isBlank()) throw new IllegalArgumentException("配置编码不能为空");
        users.findByUserId(config.getConfigCode()).filter(user -> "designer".equalsIgnoreCase(user.getRole()))
                .orElseThrow(() -> new IllegalArgumentException("个人积分配置仅支持设计师"));
        if (config.getPoints() == null || config.getPoints() < 0) throw new IllegalArgumentException("标准积分不能小于0");
        if (config.getPerformanceBase() == null || config.getPerformanceBase() < 0) throw new IllegalArgumentException("绩效基数不能小于0");
        String type = Optional.ofNullable(config.getDepartmentType()).orElse("SUPPORT").toUpperCase(Locale.ROOT);
        if (!List.of("SUPPORT", "BUSINESS").contains(type)) throw new IllegalArgumentException("岗位类型仅支持SUPPORT或BUSINESS");
        config.setDepartmentType(type);
        if (config.getCreatedAt() == null) config.setCreatedAt(LocalDateTime.now());
        if (config.getUpdatedAt() == null) config.setUpdatedAt(LocalDateTime.now());
        return standards.save(config);
    }


    public List<StandardPointConfig> standards() {
        Map<String, StandardPointConfig> existing = standards.findAll().stream()
                .collect(Collectors.toMap(StandardPointConfig::getConfigCode, item -> item, (a, b) -> a, LinkedHashMap::new));
        users.findByRole("designer").stream()
                .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                .forEach(user -> existing.computeIfAbsent(user.getUserId(), id -> {
                    StandardPointConfig config = new StandardPointConfig();
                    config.setConfigCode(id); config.setPoints(100); config.setPerformanceBase(0d);
                    config.setDepartmentType("SUPPORT"); config.setEnabled(true); config.setDescription("自动生成的设计师个人绩效配置");
                    return standards.save(config);
                }));
        return new ArrayList<>(existing.values());
    }
    public void deleteStandard(Long id) { if (id == null || !standards.existsById(id)) throw new IllegalArgumentException("配置不存在"); standards.deleteById(id); }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> designerTargets(String month) {
        validateMonth(month);
        Map<String, MonthlyUserPointTarget> configured = userTargets.findByMonthKeyOrderByUserNameAscUserIdAsc(month)
                .stream().collect(Collectors.toMap(MonthlyUserPointTarget::getUserId, item -> item));
        return users.findByRole("designer").stream().filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                .map(user -> {
                    MonthlyUserPointTarget target = configured.get(user.getUserId());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", target == null ? null : target.getId());
                    row.put("monthKey", month); row.put("userId", user.getUserId()); row.put("userName", user.getName());
                    row.put("targetPoints", target == null ? 0 : target.getTargetPoints());
                    row.put("configured", target != null);
                    return row;
                }).toList();
    }

    public MonthlyUserPointTarget saveDesignerTarget(String month, String userId, Integer targetPoints, String adminId) {
        validateMonth(month);
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("设计师不能为空");
        if (targetPoints == null || targetPoints < 0) throw new IllegalArgumentException("目标积分不能小于0");
        User user = users.findByUserId(userId).filter(item -> "designer".equalsIgnoreCase(item.getRole()))
                .orElseThrow(() -> new IllegalArgumentException("请选择有效的设计师"));
        MonthlyUserPointTarget target = userTargets.findByMonthKeyAndUserId(month, userId).orElseGet(MonthlyUserPointTarget::new);
        target.setMonthKey(month); target.setUserId(userId); target.setUserName(user.getName());
        target.setTargetPoints(targetPoints); target.setUpdatedBy(adminId);
        return userTargets.save(target);
    }

    private void validateMonth(String month) {
        if (month == null || !month.matches("\\d{4}-\\d{2}")) throw new IllegalArgumentException("月份格式应为YYYY-MM");
    }
}
