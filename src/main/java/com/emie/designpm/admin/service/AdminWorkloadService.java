package com.emie.designpm.admin.service;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.User;
import com.emie.designpm.util.TextEncodingUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 管理后台的工作量统计。原为 {@code AdminService} 的一部分（约 300 行、7 个方法），
 * 是纯只读的报表逻辑，与用户/角色/配置管理无共享状态，拆出以缩小 AdminService。
 */
@Service
public class AdminWorkloadService {

    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminWorkloadService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    private List<User> workloadUsers() {
        return Stream.of("sales", "planner", "designer", "supplychain")
                .flatMap(role -> userRepository.findByRole(role).stream())
                // 冒烟测试账号只用于回归，不应进入管理工作量统计。
                .filter(u -> u.getUserId() == null || !u.getUserId().startsWith("smoke_"))
                .filter(u -> u.getStatus() == null || "active".equalsIgnoreCase(u.getStatus()))
                .toList();
    }

    /** 获取各角色各员工的工作量统计 */
    public Map<String, Object> getWorkloadStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<User> allUsers = workloadUsers();

        // 按角色分组
        Map<String, List<User>> byRole = allUsers.stream()
                .filter(u -> Set.of("sales", "planner", "designer", "supplychain").contains(u.getRole()))
                .filter(u -> u.getStatus() == null || "active".equalsIgnoreCase(u.getStatus()))
                .collect(Collectors.groupingBy(User::getRole));

        // 定义角色显示信息
        Map<String, String> roleLabels = Map.of(
                "sales", "销售", "planner", "产品企划",
                "designer", "设计师", "supplychain", "供应链"
        );
        Map<String, String> roleIcons = Map.of(
                "sales", "📊", "planner", "📋",
                "designer", "🎨", "supplychain", "📦"
        );

        Map<String, Map<String, Long>> projectCountsBySales = workloadCounts(
                "SELECT sales_id, status, COUNT(*) FROM projects GROUP BY sales_id, status");
        Map<String, Map<String, Long>> projectCountsByPlanner = workloadCounts(
                "SELECT planner_id, status, COUNT(*) FROM projects GROUP BY planner_id, status");
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
                        long total = counts.values().stream().mapToLong(Long::longValue).sum();
                        us.put("totalProjects", total);
                        us.put("projectCounts", counts);
                    }
                    case "planner" -> {
                        Map<String, Long> counts = projectCountsByPlanner.getOrDefault(u.getUserId(), Map.of());
                        long total = counts.values().stream().mapToLong(Long::longValue).sum();
                        us.put("totalProjects", total);
                        us.put("projectCounts", counts);
                    }
                    case "designer", "supplychain" -> {
                        Map<String, Long> counts = ("supplychain".equals(role) ? taskCountsBySupplychain : taskCountsByDesigner)
                                .getOrDefault(u.getUserId(), Map.of());
                        long total = counts.values().stream().mapToLong(Long::longValue).sum();
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
                .createNativeQuery("SELECT COUNT(*) FROM projects").getSingleResult()).longValue();
        long totalTasks = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM sub_tasks").getSingleResult()).longValue();
        long activeProjects = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM projects WHERE status NOT IN ('completed','terminated','draft')")
                .getSingleResult()).longValue();
        long pendingTasks = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM sub_tasks WHERE status IN ('pending','accepted','delivered')")
                .getSingleResult()).longValue();

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
        LocalDateTime cutoff = custom ? startDate.atStartOfDay() : switch (range) {
            case "day" -> LocalDateTime.now().minusDays(1);
            case "week" -> LocalDateTime.now().minusDays(7);
            case "month" -> LocalDateTime.now().minusDays(30);
            case "quarter" -> LocalDateTime.now().minusDays(90);
            case "half-year" -> LocalDateTime.now().minusDays(180);
            case "year" -> LocalDateTime.now().minusDays(365);
            case "all" -> LocalDateTime.of(1970, 1, 1, 0, 0);
            default -> LocalDateTime.now().minusDays(30);
        };
        LocalDateTime endExclusive = custom ? endDate.plusDays(1).atStartOfDay() : LocalDateTime.now().plusSeconds(1);
        if (custom) range = "custom";

        Map<String, Object> result = new LinkedHashMap<>();
        List<User> allUsers = workloadUsers();

        Map<String, List<User>> byRole = allUsers.stream()
                .filter(u -> Set.of("sales", "planner", "designer", "supplychain").contains(u.getRole()))
                .filter(u -> u.getStatus() == null || "active".equalsIgnoreCase(u.getStatus()))
                .collect(Collectors.groupingBy(User::getRole));

        Map<String, String> roleLabels = Map.of(
                "sales", "销售", "planner", "产品企划",
                "designer", "设计师", "supplychain", "供应链"
        );
        Map<String, String> roleIcons = Map.of(
                "sales", "📊", "planner", "📋",
                "designer", "🎨", "supplychain", "📦"
        );

        Map<String, long[]> projectTimelineBySales = workloadTimelineCounts(
                "SELECT sales_id, SUM(CASE WHEN created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 'completed' AND updated_at >= ?1 AND updated_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN type = 'channel_custom' AND created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN type <> 'channel_custom' AND created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN status = 'completed' AND type = 'channel_custom' AND updated_at >= ?1 AND updated_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN status = 'completed' AND type <> 'channel_custom' AND updated_at >= ?1 AND updated_at < ?2 THEN 1 ELSE 0 END) "
                        + "FROM projects WHERE (created_at >= ?1 AND created_at < ?2) OR (updated_at >= ?1 AND updated_at < ?2) GROUP BY sales_id", cutoff, endExclusive);
        Map<String, long[]> projectTimelineByPlanner = workloadTimelineCounts(
                "SELECT planner_id, SUM(CASE WHEN created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 'completed' AND updated_at >= ?1 AND updated_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN type = 'channel_custom' AND created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN type <> 'channel_custom' AND created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN status = 'completed' AND type = 'channel_custom' AND updated_at >= ?1 AND updated_at < ?2 THEN 1 ELSE 0 END) "
                        + ", SUM(CASE WHEN status = 'completed' AND type <> 'channel_custom' AND updated_at >= ?1 AND updated_at < ?2 THEN 1 ELSE 0 END) "
                        + "FROM projects WHERE (created_at >= ?1 AND created_at < ?2) OR (updated_at >= ?1 AND updated_at < ?2) GROUP BY planner_id", cutoff, endExclusive);
        Map<String, long[]> taskTimelineByDesigner = workloadTimelineCounts(
                "SELECT s.designer_id, SUM(CASE WHEN s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), SUM(CASE WHEN s.status = 'approved' AND s.updated_at >= ?1 AND s.updated_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN p.type = 'channel_custom' AND s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN (p.type <> 'channel_custom' OR p.type IS NULL) AND s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), SUM(CASE WHEN s.status = 'approved' AND p.type = 'channel_custom' AND s.updated_at >= ?1 AND s.updated_at < ?2 THEN 1 ELSE 0 END), SUM(CASE WHEN s.status = 'approved' AND (p.type <> 'channel_custom' OR p.type IS NULL) AND s.updated_at >= ?1 AND s.updated_at < ?2 THEN 1 ELSE 0 END) "
                        + "FROM sub_tasks s LEFT JOIN projects p ON p.id = s.project_id "
                        + "WHERE ((s.created_at >= ?1 AND s.created_at < ?2) OR (s.updated_at >= ?1 AND s.updated_at < ?2)) AND (s.assignee_role = 'designer' OR s.assignee_role IS NULL) GROUP BY s.designer_id", cutoff, endExclusive);
        Map<String, long[]> taskTimelineBySupplychain = workloadTimelineCounts(
                "SELECT s.designer_id, SUM(CASE WHEN s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), SUM(CASE WHEN s.status = 'approved' AND s.updated_at >= ?1 AND s.updated_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN p.type = 'channel_custom' AND s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN (p.type <> 'channel_custom' OR p.type IS NULL) AND s.created_at >= ?1 AND s.created_at < ?2 THEN 1 ELSE 0 END), SUM(CASE WHEN s.status = 'approved' AND p.type = 'channel_custom' AND s.updated_at >= ?1 AND s.updated_at < ?2 THEN 1 ELSE 0 END), SUM(CASE WHEN s.status = 'approved' AND (p.type <> 'channel_custom' OR p.type IS NULL) AND s.updated_at >= ?1 AND s.updated_at < ?2 THEN 1 ELSE 0 END) "
                        + "FROM sub_tasks s LEFT JOIN projects p ON p.id = s.project_id "
                        + "WHERE ((s.created_at >= ?1 AND s.created_at < ?2) OR (s.updated_at >= ?1 AND s.updated_at < ?2)) AND s.assignee_role = 'supplychain' GROUP BY s.designer_id", cutoff, endExclusive);

        for (Map.Entry<String, List<User>> entry : byRole.entrySet()) {
            String role = entry.getKey();
            List<User> users = entry.getValue();

            List<Map<String, Object>> userStats = new ArrayList<>();
            for (User u : users) {
                Map<String, Object> us = new LinkedHashMap<>();
                us.put("userId", u.getUserId());
                us.put("name", TextEncodingUtil.repairUtf8Mojibake(u.getName()));
                us.put("title", u.getTitle() != null ? u.getTitle() : "");

                switch (role) {
                    case "sales" -> {
                        long[] counts = projectTimelineBySales.getOrDefault(u.getUserId(), new long[6]);
                        long created = counts[0];
                        long completed = counts[1];
                        us.put("created", created);
                        us.put("completed", completed);
                        us.put("channelCustomProjects", counts[2]);
                        us.put("regularProjects", counts[3]);
                        us.put("completedChannelProjects", counts[4]); us.put("completedRegularProjects", counts[5]);
                    }
                    case "planner" -> {
                        long[] counts = projectTimelineByPlanner.getOrDefault(u.getUserId(), new long[6]);
                        long created = counts[0];
                        long completed = counts[1];
                        us.put("created", created);
                        us.put("completed", completed);
                        us.put("channelCustomProjects", counts[2]);
                        us.put("regularProjects", counts[3]);
                        us.put("completedChannelProjects", counts[4]); us.put("completedRegularProjects", counts[5]);
                    }
                    case "designer", "supplychain" -> {
                        long[] counts = ("supplychain".equals(role) ? taskTimelineBySupplychain : taskTimelineByDesigner)
                                .getOrDefault(u.getUserId(), new long[6]);
                        long assigned = counts[0];
                        long completed = counts[1];
                        us.put("assigned", assigned);
                        us.put("completed", completed);
                        us.put("channelCustomProjects", counts[2]);
                        us.put("regularProjects", counts[3]);
                        us.put("completedChannelProjects", counts[4]); us.put("completedRegularProjects", counts[5]);
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
                        + "SUM(CASE WHEN status = 'completed' AND updated_at >= ?1 AND updated_at < ?2 THEN 1 ELSE 0 END) "
                        + "FROM projects")
                .setParameter(1, cutoff).setParameter(2, endExclusive).getSingleResult();
        Object[] taskSummary = (Object[]) entityManager
                .createNativeQuery("SELECT "
                        + "SUM(CASE WHEN created_at >= ?1 AND created_at < ?2 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 'approved' AND updated_at >= ?1 AND updated_at < ?2 THEN 1 ELSE 0 END) "
                        + "FROM sub_tasks")
                .setParameter(1, cutoff).setParameter(2, endExclusive).getSingleResult();
        long totalCreated = numberOrZero(projectSummary[0]);
        long totalCompleted = numberOrZero(projectSummary[1]);
        long totalTasksAssigned = numberOrZero(taskSummary[0]);
        long totalTasksCompleted = numberOrZero(taskSummary[1]);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("range", range);
        summary.put("rangeLabel", switch (range) {
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
        result.put("_summary", summary);

        return result;
    }

    private long numberOrZero(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private Map<String, long[]> workloadTimelineCounts(String sql, LocalDateTime cutoff, LocalDateTime endExclusive) {
        Map<String, long[]> result = new HashMap<>();
        for (Object[] row : (List<Object[]>) entityManager.createNativeQuery(sql)
                .setParameter(1, cutoff).setParameter(2, endExclusive).getResultList()) {
            if (row[0] == null) continue;
            long[] counts = new long[Math.max(2, row.length - 1)];
            for (int i = 1; i < row.length; i++) counts[i - 1] = numberOrZero(row[i]);
            result.put(String.valueOf(row[0]), counts);
        }
        return result;
    }
}
