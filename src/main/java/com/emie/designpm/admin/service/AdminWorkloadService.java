package com.emie.designpm.admin.service;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.User;
import com.emie.designpm.performance.service.PerformanceService;
import com.emie.designpm.util.TextEncodingUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * 管理后台的工作量统计。原为 {@code AdminService} 的一部分（约 300 行、7 个方法），
 * 是纯只读的报表逻辑，与用户/角色/配置管理无共享状态，拆出以缩小 AdminService。
 */
@Service
public class AdminWorkloadService {

    private final UserRepository userRepository;
    private final PerformanceService performanceService;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminWorkloadService(UserRepository userRepository, PerformanceService performanceService) {
        this.userRepository = userRepository;
        this.performanceService = performanceService;
    }

    private List<User> workloadUsers() {
        return Stream.of("sales", "promotion", "planner", "designer", "supplychain")
                .flatMap(role -> userRepository.findByRole(role).stream())
                // 冒烟测试账号只用于回归，不应进入管理工作量统计。
                .filter(u -> u.getUserId() == null || !u.getUserId().startsWith("smoke_"))
                .filter(u -> u.getStatus() == null || "active".equalsIgnoreCase(u.getStatus()))
                .toList();
    }

    /** 获取各角色各员工的工作量统计 */
    public Map<String, Object> getWorkloadStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<User> allUsers = workloadUsers().stream()
                .filter(u -> !"promotion".equals(u.getRole()))
                .toList();

        // 按角色分组
        Map<String, List<User>> byRole = allUsers.stream()
                .filter(u ->
                        Set.of("sales", "planner", "designer", "supplychain").contains(u.getRole()))
                .filter(u -> u.getStatus() == null || "active".equalsIgnoreCase(u.getStatus()))
                .collect(Collectors.groupingBy(User::getRole));

        // 定义角色显示信息
        Map<String, String> roleLabels =
                Map.of("sales", "销售", "planner", "产品企划", "designer", "设计师", "supplychain", "供应链");
        Map<String, String> roleIcons = Map.of("sales", "📊", "planner", "📋", "designer", "🎨", "supplychain", "📦");

        Map<String, Map<String, Long>> projectCountsBySales =
                workloadCounts("SELECT sales_id, status, COUNT(*) FROM projects GROUP BY sales_id, status");
        Map<String, Map<String, Long>> projectCountsByPlanner =
                workloadCounts("SELECT planner_id, status, COUNT(*) FROM projects GROUP BY planner_id, status");
        Map<String, Map<String, Long>> taskCountsByDesigner = workloadCounts(
                "SELECT designer_id, status, COUNT(*) FROM sub_tasks WHERE assignee_role = 'designer' OR assignee_role IS NULL GROUP BY designer_id, status");
        Map<String, Map<String, Long>> taskCountsBySupplychain = workloadCounts(
                "SELECT designer_id, status, COUNT(*) FROM sub_tasks WHERE assignee_role = 'supplychain' GROUP BY designer_id, status");

        for (Map.Entry<String, List<User>> entry : byRole.entrySet()) {
            String role = entry.getKey();
            List<User> users = entry.getValue();

            List<Map<String, Object>> userStats = new ArrayList<>();
            for (User u : users) {
                Map<String, Object> us = new LinkedHashMap<>();
                us.put("userId", u.getUserId());
                us.put("name", TextEncodingUtil.repairUtf8Mojibake(u.getName()));
                us.put("title", u.getTitle() != null ? u.getTitle() : "");

                // 按角色类型聚合
                switch (role) {
                    case "sales" -> {
                        Map<String, Long> counts = projectCountsBySales.getOrDefault(u.getUserId(), Map.of());
                        long total = counts.values().stream()
                                .mapToLong(Long::longValue)
                                .sum();
                        us.put("totalProjects", total);
                        us.put("projectCounts", counts);
                    }
                    case "planner" -> {
                        Map<String, Long> counts = projectCountsByPlanner.getOrDefault(u.getUserId(), Map.of());
                        long total = counts.values().stream()
                                .mapToLong(Long::longValue)
                                .sum();
                        us.put("totalProjects", total);
                        us.put("projectCounts", counts);
                    }
                    case "designer", "supplychain" -> {
                        Map<String, Long> counts = ("supplychain".equals(role)
                                        ? taskCountsBySupplychain
                                        : taskCountsByDesigner)
                                .getOrDefault(u.getUserId(), Map.of());
                        long total = counts.values().stream()
                                .mapToLong(Long::longValue)
                                .sum();
                        us.put("totalTasks", total);
                        us.put("taskCounts", counts);
                    }
                }

                userStats.add(us);
            }

            // 用户按总量排序
            Map<String, Object> roleEntry = new LinkedHashMap<>();
            roleEntry.put("label", roleLabels.getOrDefault(role, role));
            roleEntry.put("icon", roleIcons.getOrDefault(role, "👤"));
            roleEntry.put("totalUsers", users.size());
            roleEntry.put("users", userStats);
            result.put(role, roleEntry);
        }

        // 汇总统计
        long totalProjects = ((Number) entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM projects")
                        .getSingleResult())
                .longValue();
        long totalTasks = ((Number) entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM sub_tasks")
                        .getSingleResult())
                .longValue();
        long activeProjects = ((Number) entityManager
                        .createNativeQuery(
                                "SELECT COUNT(*) FROM projects WHERE status NOT IN ('completed','terminated','draft')")
                        .getSingleResult())
                .longValue();
        long pendingTasks = ((Number) entityManager
                        .createNativeQuery(
                                "SELECT COUNT(*) FROM sub_tasks WHERE status IN ('pending','accepted','delivered')")
                        .getSingleResult())
                .longValue();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalUsers", allUsers.size());
        summary.put("totalProjects", totalProjects);
        summary.put("totalTasks", totalTasks);
        summary.put("activeProjects", activeProjects);
        summary.put("pendingTasks", pendingTasks);
        result.put("_summary", summary);

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Long>> workloadCounts(String sql) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();
        for (Object[] row : rows) {
            if (row[0] == null || row[1] == null) continue;
            result.computeIfAbsent(String.valueOf(row[0]), ignored -> new LinkedHashMap<>())
                    .put(String.valueOf(row[1]), ((Number) row[2]).longValue());
        }
        return result;
    }

    /** 获取指定时间范围内各角色的工作量统计 */
    public Map<String, Object> getWorkloadTimeline(String range) {
        return getWorkloadTimeline(range, null, null);
    }

    public Map<String, Object> getWorkloadTimeline(String range, LocalDate startDate, LocalDate endDate) {
        boolean custom = startDate != null && endDate != null;
        if (custom && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        LocalDateTime cutoff = custom
                ? startDate.atStartOfDay()
                : switch (range) {
                    case "day" -> LocalDate.now().atStartOfDay();
                    case "week" -> LocalDate.now()
                            .with(java.time.DayOfWeek.MONDAY)
                            .atStartOfDay();
                    case "month" -> LocalDate.now().withDayOfMonth(1).atStartOfDay();
                    case "quarter" -> LocalDate.now()
                            .withMonth((LocalDate.now().getMonthValue() - 1) / 3 * 3 + 1)
                            .withDayOfMonth(1)
                            .atStartOfDay();
                    case "half-year" -> LocalDate.now()
                            .withMonth(LocalDate.now().getMonthValue() <= 6 ? 1 : 7)
                            .withDayOfMonth(1)
                            .atStartOfDay();
                    case "year" -> LocalDate.now().withDayOfYear(1).atStartOfDay();
                    case "all" -> LocalDateTime.of(1970, 1, 1, 0, 0);
                    default -> LocalDateTime.now().minusDays(30);
                };
        LocalDateTime endExclusive = custom
                ? endDate.plusDays(1).atStartOfDay()
                : LocalDate.now().plusDays(1).atStartOfDay();
        if (custom) range = "custom";
        String performanceMonth = performanceMonth(range, startDate, endDate, cutoff);
        Map<String, Double> performancePoints = performanceMonth == null
                ? Map.of()
                : performanceService.leaderboard(performanceMonth).stream()
                        .collect(Collectors.toMap(
                                row -> String.valueOf(row.get("userId")),
                                row -> ((Number) row.get("points")).doubleValue()));

        Map<String, Object> result = new LinkedHashMap<>();
        List<User> allUsers = workloadUsers();

        Map<String, List<User>> byRole = allUsers.stream()
                .filter(u -> Set.of("sales", "promotion", "planner", "designer", "supplychain")
                        .contains(u.getRole()))
                .filter(u -> u.getStatus() == null || "active".equalsIgnoreCase(u.getStatus()))
                .collect(Collectors.groupingBy(User::getRole));

        Map<String, String> roleLabels =
                Map.of("sales", "销售", "promotion", "产品推广", "planner", "产品企划", "designer", "设计师", "supplychain", "供应链");
        Map<String, String> roleIcons =
                Map.of("sales", "📊", "promotion", "📣", "planner", "📋", "designer", "🎨", "supplychain", "📦");

        Map<String, long[]> projectTimelineBySales = workloadTimelineCounts(
                "SELECT sales_id, SUM(CASE WHEN created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN completed_at >= ?1 AND completed_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN type = 'channel_custom' AND created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN type <> 'channel_custom' AND created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN completed_at >= ?1 AND completed_at < ?2 AND type = 'channel_custom' THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN completed_at >= ?1 AND completed_at < ?2 AND (type <> 'channel_custom' OR type IS NULL) THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN created_at < ?2 AND (completed_at IS NULL OR completed_at >= ?2) THEN 1 ELSE 0 END) FROM projects WHERE created_at < ?2 GROUP BY sales_id",
                cutoff,
                endExclusive);
        Map<String, long[]> taskTimelineByPlanner = workloadTimelineCounts(
                "SELECT s.publisher_id, SUM(CASE WHEN s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN s.completed_at >= ?1 AND s.completed_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN p.type = 'channel_custom' AND s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN (p.type <> 'channel_custom' OR p.type IS NULL) AND s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN s.completed_at >= ?1 AND s.completed_at < ?2 AND p.type = 'channel_custom' THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN s.completed_at >= ?1 AND s.completed_at < ?2 AND (p.type <> 'channel_custom' OR p.type IS NULL) THEN 1 ELSE 0 END) "
                        + "FROM sub_tasks s LEFT JOIN projects p ON p.id = s.project_id WHERE s.created_at < ?2 AND s.publisher_role = 'planner' GROUP BY s.publisher_id",
                cutoff,
                endExclusive);
        Map<String, Long> projectsCreatedByPlanner = workloadCountByUser(
                "SELECT planner_id, COUNT(*) FROM projects WHERE created_at >= ?1 AND created_at < ?2 GROUP BY planner_id",
                cutoff,
                endExclusive);
        Map<String, long[]> taskTimelineByDesigner = workloadTimelineCounts(
                "SELECT s.designer_id, SUM(CASE WHEN s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), SUM(CASE WHEN s.completed_at >= ?1 AND s.completed_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN p.type = 'channel_custom' AND s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN (p.type <> 'channel_custom' OR p.type IS NULL) AND s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), SUM(CASE WHEN s.completed_at >= ?1 AND s.completed_at < ?2 AND p.type = 'channel_custom' THEN 1 ELSE 0 END), SUM(CASE WHEN s.completed_at >= ?1 AND s.completed_at < ?2 AND (p.type <> 'channel_custom' OR p.type IS NULL) THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN s.created_at < ?2 AND (s.completed_at IS NULL OR s.completed_at >= ?2) THEN 1 ELSE 0 END) "
                        + "FROM sub_tasks s LEFT JOIN projects p ON p.id = s.project_id WHERE s.created_at < ?2 AND (s.assignee_role = 'designer' OR s.assignee_role IS NULL) GROUP BY s.designer_id",
                cutoff,
                endExclusive);
        Map<String, long[]> taskTimelineBySupplychain = workloadTimelineCounts(
                "SELECT s.designer_id, SUM(CASE WHEN s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), SUM(CASE WHEN s.completed_at >= ?1 AND s.completed_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN p.type = 'channel_custom' AND s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN (p.type <> 'channel_custom' OR p.type IS NULL) AND s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), SUM(CASE WHEN s.completed_at >= ?1 AND s.completed_at < ?2 AND p.type = 'channel_custom' THEN 1 ELSE 0 END), SUM(CASE WHEN s.completed_at >= ?1 AND s.completed_at < ?2 AND (p.type <> 'channel_custom' OR p.type IS NULL) THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN s.created_at < ?2 AND (s.completed_at IS NULL OR s.completed_at >= ?2) THEN 1 ELSE 0 END) "
                        + "FROM sub_tasks s LEFT JOIN projects p ON p.id = s.project_id WHERE s.created_at < ?2 AND s.assignee_role = 'supplychain' GROUP BY s.designer_id",
                cutoff,
                endExclusive);
        Map<String, Long> designRequirementsByOwner = workloadCountByUser(
                "SELECT owner_id, COUNT(*) FROM design_requirements WHERE created_at >= ?1 AND created_at < ?2 GROUP BY owner_id",
                cutoff,
                endExclusive);
        Map<String, Long> designRequirementsByPlanner = workloadCountByUser(
                "SELECT planner_id, COUNT(*) FROM design_requirements WHERE created_at >= ?1 AND created_at < ?2 GROUP BY planner_id",
                cutoff,
                endExclusive);
        Map<String, Long> outstandingDesignRequirementsByDesigner = workloadCountByUserAtEnd(
                "SELECT designer_id, COUNT(*) FROM design_requirements WHERE created_at < ?1 AND status NOT IN ('completed', 'terminated') GROUP BY designer_id",
                endExclusive);
        // 设计/送审需求没有 completed_at；完成时会更新需求状态，因此用该状态变更时间作为完成时间。
        Map<String, Long> completedDesignRequirementsByDesigner = workloadCountByUser(
                "SELECT designer_id, COUNT(*) FROM design_requirements WHERE status = 'completed' AND updated_at >= ?1 AND updated_at < ?2 GROUP BY designer_id",
                cutoff,
                endExclusive);

        for (Map.Entry<String, List<User>> entry : byRole.entrySet()) {
            String role = entry.getKey();
            List<User> users = entry.getValue();

            List<Map<String, Object>> userStats = new ArrayList<>();
            for (User u : users) {
                Map<String, Object> us = new LinkedHashMap<>();
                us.put("userId", u.getUserId());
                us.put("name", TextEncodingUtil.repairUtf8Mojibake(u.getName()));
                us.put("title", u.getTitle() != null ? u.getTitle() : "");
                us.put("performancePoints", performancePoints.getOrDefault(u.getUserId(), 0d));

                switch (role) {
                    case "sales" -> {
                        long[] counts = projectTimelineBySales.getOrDefault(u.getUserId(), new long[6]);
                        long created = counts[0];
                        long completed = counts[1];
                        us.put("created", created);
                        us.put("completed", completed);
                        us.put("channelCustomProjects", counts[2]);
                        us.put("regularProjects", counts[3]);
                        us.put("completedChannelProjects", counts[4]);
                        us.put("completedRegularProjects", counts[5]);
                        us.put("outstanding", counts.length > 6 ? counts[6] : 0);
                        us.put("designRequirements", designRequirementsByOwner.getOrDefault(u.getUserId(), 0L));
                    }
                    case "promotion" -> {
                        us.put("created", 0L);
                        us.put("completed", 0L);
                        us.put("outstanding", 0L);
                        us.put("designRequirements", designRequirementsByOwner.getOrDefault(u.getUserId(), 0L));
                    }
                    case "planner" -> {
                        long[] counts = taskTimelineByPlanner.getOrDefault(u.getUserId(), new long[6]);
                        long created = counts[0];
                        long completed = counts[1];
                        us.put("created", created);
                        us.put("completed", completed);
                        us.put("channelCustomProjects", counts[2]);
                        us.put("regularProjects", counts[3]);
                        us.put("completedChannelProjects", counts[4]);
                        us.put("completedRegularProjects", counts[5]);
                        us.put("createdProjects", projectsCreatedByPlanner.getOrDefault(u.getUserId(), 0L));
                        us.put("outstanding", counts.length > 6 ? counts[6] : 0);
                        us.put("designRequirements", designRequirementsByPlanner.getOrDefault(u.getUserId(), 0L));
                    }
                    case "designer", "supplychain" -> {
                        long[] counts = ("supplychain".equals(role)
                                        ? taskTimelineBySupplychain
                                        : taskTimelineByDesigner)
                                .getOrDefault(u.getUserId(), new long[6]);
                        long assigned = counts[0];
                        long completed = counts[1];
                        us.put("assigned", assigned);
                        us.put("completed", completed);
                        us.put("channelCustomProjects", counts[2]);
                        us.put("regularProjects", counts[3]);
                        us.put("completedChannelProjects", counts[4]);
                        us.put("completedRegularProjects", counts[5]);
                        us.put("outstanding", counts.length > 6 ? counts[6] : 0);
                        if ("designer".equals(role)) {
                            us.put(
                                    "designRequirements",
                                    outstandingDesignRequirementsByDesigner.getOrDefault(u.getUserId(), 0L));
                            us.put(
                                    "completedDesignRequirements",
                                    completedDesignRequirementsByDesigner.getOrDefault(u.getUserId(), 0L));
                        }
                    }
                }
                userStats.add(us);
            }

            Map<String, Object> roleEntry = new LinkedHashMap<>();
            roleEntry.put("label", roleLabels.getOrDefault(role, role));
            roleEntry.put("icon", roleIcons.getOrDefault(role, "👤"));
            roleEntry.put("totalUsers", users.size());
            roleEntry.put("users", userStats);
            result.put(role, roleEntry);
        }

        // 汇总
        Object[] projectSummary = (Object[]) entityManager
                .createNativeQuery("SELECT "
                        + "SUM(CASE WHEN created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN completed_at >= ?1 AND completed_at < ?2 THEN 1 ELSE 0 END) "
                        + "FROM projects")
                .setParameter(1, cutoff)
                .setParameter(2, endExclusive)
                .getSingleResult();
        Object[] taskSummary = (Object[]) entityManager
                .createNativeQuery("SELECT "
                        + "SUM(CASE WHEN created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN completed_at >= ?1 AND completed_at < ?2 THEN 1 ELSE 0 END) "
                        + "FROM sub_tasks")
                .setParameter(1, cutoff)
                .setParameter(2, endExclusive)
                .getSingleResult();
        long totalCreated = numberOrZero(projectSummary[0]);
        long totalCompleted = numberOrZero(projectSummary[1]);
        long totalTasksAssigned = numberOrZero(taskSummary[0]);
        long totalTasksCompleted = numberOrZero(taskSummary[1]);
        long totalProjectsOutstanding = ((Number) entityManager
                        .createNativeQuery(
                                "SELECT COUNT(*) FROM projects WHERE created_at < ?1 AND (completed_at IS NULL OR completed_at >= ?1)")
                        .setParameter(1, endExclusive)
                        .getSingleResult())
                .longValue();
        long totalTasksOutstanding = ((Number) entityManager
                        .createNativeQuery(
                                "SELECT COUNT(*) FROM sub_tasks WHERE created_at < ?1 AND (completed_at IS NULL OR completed_at >= ?1)")
                        .setParameter(1, endExclusive)
                        .getSingleResult())
                .longValue();
        long totalDesignRequirements = ((Number) entityManager
                        .createNativeQuery(
                                "SELECT COUNT(*) FROM design_requirements WHERE created_at >= ?1 AND created_at < ?2")
                        .setParameter(1, cutoff)
                        .setParameter(2, endExclusive)
                        .getSingleResult())
                .longValue();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("range", range);
        summary.put(
                "rangeLabel",
                switch (range) {
                    case "day" -> "今日";
                    case "week" -> "本周";
                    case "month" -> "本月";
                    case "quarter" -> "本季度";
                    case "half-year" -> "本半年";
                    case "year" -> "本年度";
                    case "all" -> "总览";
                    case "custom" -> startDate + " 至 " + endDate;
                    default -> range;
                });
        summary.put("cutoff", cutoff.toString());
        summary.put("endExclusive", endExclusive.toString());
        summary.put("totalProjectsCreated", totalCreated);
        summary.put("totalProjectsCompleted", totalCompleted);
        summary.put("totalTasksAssigned", totalTasksAssigned);
        summary.put("totalTasksCompleted", totalTasksCompleted);
        summary.put("totalProjectsOutstanding", totalProjectsOutstanding);
        summary.put("totalTasksOutstanding", totalTasksOutstanding);
        summary.put("totalDesignRequirements", totalDesignRequirements);
        summary.put("performanceMonth", performanceMonth);
        summary.put(
                "performanceSource",
                performanceMonth == null
                        ? "绩效积分仅按完整自然月归属；请选择“本月”或完整自然月自定义日期。"
                        : performanceMonth + " 绩效积分：积分流水与调账流水按归属月汇总。");
        result.put("_summary", summary);

        return result;
    }

    private String performanceMonth(String range, LocalDate startDate, LocalDate endDate, LocalDateTime cutoff) {
        if ("month".equals(range)) return YearMonth.from(cutoff).toString();
        if ("custom".equals(range)
                && startDate.getDayOfMonth() == 1
                && endDate.equals(YearMonth.from(startDate).atEndOfMonth()))
            return YearMonth.from(startDate).toString();
        return null;
    }

    private long numberOrZero(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> workloadCountByUser(String sql, LocalDateTime cutoff, LocalDateTime endExclusive) {
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : (List<Object[]>) entityManager
                .createNativeQuery(sql)
                .setParameter(1, cutoff)
                .setParameter(2, endExclusive)
                .getResultList()) {
            if (row[0] != null) result.put(String.valueOf(row[0]), numberOrZero(row[1]));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> workloadCountByUserAtEnd(String sql, LocalDateTime endExclusive) {
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : (List<Object[]>) entityManager
                .createNativeQuery(sql)
                .setParameter(1, endExclusive)
                .getResultList()) {
            if (row[0] != null) result.put(String.valueOf(row[0]), numberOrZero(row[1]));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, long[]> workloadTimelineCounts(String sql, LocalDateTime cutoff, LocalDateTime endExclusive) {
        Map<String, long[]> result = new HashMap<>();
        for (Object[] row : (List<Object[]>) entityManager
                .createNativeQuery(sql)
                .setParameter(1, cutoff)
                .setParameter(2, endExclusive)
                .getResultList()) {
            if (row[0] == null) continue;
            long[] counts = new long[Math.max(2, row.length - 1)];
            for (int i = 1; i < row.length; i++) counts[i - 1] = numberOrZero(row[i]);
            result.put(String.valueOf(row[0]), counts);
        }
        return result;
    }
}
