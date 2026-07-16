package com.emie.designpm.controller;

import com.emie.designpm.dto.ProjectSummaryDTO;
import com.emie.designpm.entity.Department;
import com.emie.designpm.entity.Project;
import com.emie.designpm.repository.DepartmentRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ProjectRepository projectRepository;
    private final ScoringRepository scoringRepository;
    private final ProjectService projectService;
    private final SubTaskRepository subTaskRepository;
    private final DepartmentRepository departmentRepository;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // 角色状态面板所需角色列表
    private static final List<String> ALL_ROLES = List.of("sales", "planner", "supplychain", "designer");

    public DashboardController(ProjectRepository projectRepository,
                               ScoringRepository scoringRepository,
                               ProjectService projectService,
                               SubTaskRepository subTaskRepository,
                               DepartmentRepository departmentRepository) {
        this.projectRepository = projectRepository;
        this.scoringRepository = scoringRepository;
        this.projectService = projectService;
        this.subTaskRepository = subTaskRepository;
        this.departmentRepository = departmentRepository;
    }

    /**
     * 聚合端点：一次请求返回 Dashboard 全部数据
     * 合并原来 8+ 次 API 调用为 1 次
     */
    @GetMapping("/full")
    public ResponseEntity<Map<String, Object>> getFullDashboard(
            @RequestParam String role,
            @RequestParam String userId,
            HttpServletRequest request) {
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        role = session.role();
        userId = session.userId();

        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 项目列表
        List<Project> projects = projectService.getProjectsByRoleAndUser(role, userId);
        Map<Long, int[]> taskCountMap = projectService.getTaskCountMap(projects);
        Map<Long, Double> scoreMap = projectService.computeProjectScoresBatch(projects);
        List<ProjectSummaryDTO> orders = projects.stream()
                .map(p -> toSummary(p, taskCountMap, scoreMap))
                .collect(Collectors.toList());
        result.put("orders", orders);

        // 2. Dashboard stats
        result.put("stats", computeStats(projects));

        // 3. 四个角色状态看板
        Map<String, Object> roleStatus = new LinkedHashMap<>();
        for (String r : ALL_ROLES) {
            roleStatus.put(r, projectService.getRoleStatus(r, role, userId));
        }
        result.put("roleStatus", roleStatus);

        // 4. 部门列表
        result.put("departments", departmentRepository.findAllByOrderBySortOrderAsc());

        // 5. 徽章统计
        result.put("badgeStats", projectService.getNavigationBadgeStats(role, userId));

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> computeStats(List<Project> projects) {
        long totalProjects = projects.size();
        long channelProjects = projects.stream().filter(p -> "channel_custom".equals(p.getType())).count();
        long regularProjects = projects.stream().filter(p -> "regular".equals(p.getType())).count();
        long inProgress = projects.stream()
                .filter(p -> "in_progress".equals(projectService.computeProjectStatus(p))
                        || "completed_pending_score".equals(projectService.computeProjectStatus(p))).count();

        List<Long> projectIds = projects.stream().map(Project::getId).collect(Collectors.toList());
        long allTasks = 0, approvedTasks = 0, pendingTasks = 0, pendingScore = 0;

        if (!projectIds.isEmpty()) {
            List<Object[]> statusCounts = subTaskRepository.countStatusByProjectIds(projectIds);
            for (Object[] row : statusCounts) {
                String status = (String) row[0];
                long count = ((Number) row[1]).longValue();
                allTasks += count;
                if ("approved".equals(status) || "completed".equals(status)) approvedTasks += count;
                if ("pending".equals(status) || "delivered".equals(status)) pendingTasks += count;
            }
            pendingScore = subTaskRepository.countPendingScoresByProjectIds(projectIds);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalProjects", totalProjects);
        stats.put("channelProjects", channelProjects);
        stats.put("regularProjects", regularProjects);
        stats.put("inProgress", inProgress);
        stats.put("allTasks", allTasks);
        stats.put("approvedTasks", approvedTasks);
        stats.put("pendingTasks", pendingTasks);
        stats.put("pendingScore", pendingScore);
        return stats;
    }

    private ProjectSummaryDTO toSummary(Project p, Map<Long, int[]> taskCountMap, Map<Long, Double> scoreMap) {
        ProjectSummaryDTO dto = new ProjectSummaryDTO();
        dto.setId(p.getId());
        dto.setType(p.getType());
        String computedStatus = projectService.computeProjectStatus(p);
        dto.setStatus(computedStatus);
        Map<String, String> statusInfo = ProjectService.getProjectStatusInfo(computedStatus);
        dto.setStatusLabel(statusInfo.get("label"));
        dto.setStatusCls(statusInfo.get("cls"));
        dto.setSalesName(p.getSalesName());
        dto.setPlannerName(p.getPlannerName());
        dto.setProductName(p.getProductName());
        dto.setDeadline(p.getDeadline());
        dto.setProductRequirements(p.getProductRequirements());
        dto.setProductCategory(p.getProductCategory() != null ? p.getProductCategory().getName() : null);
        dto.setTargetMarket(p.getTargetMarket());
        dto.setComplianceItems(p.getComplianceItems());
        dto.setPriceRange(p.getPriceRange());

        int[] counts = taskCountMap != null ? taskCountMap.get(p.getId()) : null;
        int taskCount = counts != null ? counts[0] : 0;
        int doneCount = counts != null ? counts[1] : 0;
        dto.setTaskCount(taskCount);
        dto.setApprovedTaskCount(doneCount);
        dto.setProgressPercent(taskCount > 0 ? (int) (doneCount * 100 / taskCount) : 0);
        dto.setScore(scoreMap != null ? scoreMap.get(p.getId()) : null);
        dto.setCreatedAt(p.getCreatedAt() != null ? p.getCreatedAt().format(DTF) : null);
        dto.setUpdatedAt(p.getUpdatedAt() != null ? p.getUpdatedAt().format(DTF) : null);
        return dto;
    }

    // ==================== 原有端点保留 ====================

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String userId,
            HttpServletRequest request) {
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        role = session.role();
        userId = session.userId();
        List<Project> projects = projectService.getProjectsByRoleAndUser(role, userId);

        return ResponseEntity.ok(computeStats(projects));
    }
}
