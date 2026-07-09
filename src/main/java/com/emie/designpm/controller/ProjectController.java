package com.emie.designpm.controller;

import com.emie.designpm.dto.ProjectDetailDTO;
import com.emie.designpm.dto.ProjectSummaryDTO;
import com.emie.designpm.dto.TaskDetailDTO;
import com.emie.designpm.entity.*;
import com.emie.designpm.repository.ActivityLogRepository;
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
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ScoringRepository scoringRepository;
    private final ActivityLogRepository activityLogRepository;
    private final SubTaskRepository subTaskRepository;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public ProjectController(ProjectService projectService,
                             ScoringRepository scoringRepository,
                             ActivityLogRepository activityLogRepository,
                             SubTaskRepository subTaskRepository) {
        this.projectService = projectService;
        this.scoringRepository = scoringRepository;
        this.activityLogRepository = activityLogRepository;
        this.subTaskRepository = subTaskRepository;
    }

    /** 获取所有项目列表（轻量版：计数查询代替 JOIN FETCH） */
    @GetMapping
    public ResponseEntity<List<ProjectSummaryDTO>> getProjects(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "false") boolean participating,
            HttpServletRequest request) {

        AuthController.AuthSession session = getSession(request);
        role = session.role();
        userId = session.userId();

        List<Project> projects;
        // 设计师/供应链查看渠道/常规品页面时，只显示已参与的项目
        if (participating && ("designer".equals(role) || "supplychain".equals(role))) {
            projects = projectService.getDesignerParticipatingProjects(userId);
        } else {
            projects = projectService.getProjectsByRoleAndUser(role, userId);
        }

        if (type != null) {
            projects = projects.stream()
                    .filter(p -> type.equals(p.getType()))
                    .collect(Collectors.toList());
        }

        // 批量预计算子任务计数（避免 toSummary 中逐个调用 p.getTasks()）
        Map<Long, int[]> taskCountMap = projectService.getTaskCountMap(projects);
        // 批量计算项目评分（1次SQL替代N×M次）
        Map<Long, Double> scoreMap = projectService.computeProjectScoresBatch(projects);

        List<ProjectSummaryDTO> result = projects.stream()
                .map(p -> toSummary(p, taskCountMap, scoreMap))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /** 获取项目详情 */
    @GetMapping("/{id}")
    public ResponseEntity<ProjectDetailDTO> getProjectDetail(@PathVariable Long id, HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        // 记录查询日志
        activityLogRepository.save(new ActivityLog(
            "查询项目 #" + id, session.name(), session.role()));
        Optional<Project> projectOpt = projectService.getProjectById(id);
        if (projectOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!canAccessProject(projectOpt.get(), session)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(toDetail(projectOpt.get()));
    }

    /** 新建项目 */
    @PostMapping
    public ResponseEntity<?> createProject(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            Project p = projectService.createProject(withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 企划接单 */
    @PostMapping("/{id}/accept")
    public ResponseEntity<ProjectDetailDTO> plannerAccept(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        Map<String, Object> safeBody = withSessionContext(new LinkedHashMap<>(body), request);
        Project p = projectService.plannerAccept(id,
                (String) safeBody.getOrDefault("currentUser", ""),
                (String) safeBody.getOrDefault("currentRole", ""),
                (String) safeBody.getOrDefault("userId", ""));
        return ResponseEntity.ok(toDetail(p));
    }

    /** 添加子任务 */
    @PostMapping("/{id}/tasks")
    public ResponseEntity<ProjectDetailDTO> addTask(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        Project p = projectService.addSubTask(id, withSessionContext(body, request));
        return ResponseEntity.ok(toDetail(p));
    }

    /** 编辑子任务 */
    @PutMapping("/{projectId}/tasks/{taskId}")
    public ResponseEntity<?> updateTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            Project p = projectService.updateSubTask(projectId, taskId, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 设计师接单 */
    @PostMapping("/{projectId}/tasks/{taskId}/accept")
    public ResponseEntity<?> taskAccept(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            Project p = projectService.taskAccept(projectId, taskId, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 设计师交付 */
    @PostMapping("/{projectId}/tasks/{taskId}/deliver")
    public ResponseEntity<?> taskDeliver(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            Project p = projectService.taskDeliver(projectId, taskId, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 设计师重新交付 */
    @PostMapping("/{projectId}/tasks/{taskId}/redeliver")
    public ResponseEntity<?> taskRedeliver(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            Project p = projectService.taskRedeliver(projectId, taskId, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 验收通过 */
    @PostMapping("/{projectId}/tasks/{taskId}/approve")
    public ResponseEntity<?> taskApprove(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            Project p = projectService.taskApprove(projectId, taskId, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 驳回 */
    @PostMapping("/{projectId}/tasks/{taskId}/reject")
    public ResponseEntity<?> taskReject(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            Project p = projectService.taskReject(projectId, taskId, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 提交评分 */
    @PostMapping("/{projectId}/tasks/{taskId}/score")
    public ResponseEntity<?> submitScore(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            Project p = projectService.submitScoring(projectId, taskId, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 通用角色状态看板（sales/planner/supplychain/designer） */
    @GetMapping("/role-status")
    public ResponseEntity<Map<String, Object>> roleStatus(@RequestParam String role) {
        return ResponseEntity.ok(projectService.getRoleStatus(role));
    }

    /** 设计师状态看板（兼容旧版） */
    @GetMapping("/designer-status")
    public ResponseEntity<Map<String, Object>> designerStatus() {
        return ResponseEntity.ok(projectService.getDesignerStatus());
    }

    /** 徽章统计（避免前端循环 N 次 API 调用） */
    @GetMapping("/badge-stats")
    public ResponseEntity<Map<String, Object>> badgeStats(
            @RequestParam String role,
            @RequestParam String userId,
            HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        role = session.role();
        userId = session.userId();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("myTaskCount", subTaskRepository.countByDesignerIdAndStatusIn(userId));

        long pendingScoreCount;
        if ("admin".equals(role)) {
            pendingScoreCount = scoringRepository.countAllPendingScores();
        } else if ("sales".equals(role) || "planner".equals(role)) {
            pendingScoreCount = scoringRepository.countPendingByRole(role);
        } else {
            pendingScoreCount = 0;
        }
        stats.put("pendingScoreCount", pendingScoreCount);

        return ResponseEntity.ok(stats);
    }

    /** 终止项目 */
    @PostMapping("/{id}/terminate")
    public ResponseEntity<?> terminateProject(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            Project p = projectService.terminateProject(id, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 暂停项目 */
    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pauseProject(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            Project p = projectService.pauseProject(id, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 取消终止 */
    @PostMapping("/{id}/cancel-terminate")
    public ResponseEntity<?> cancelTerminate(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            Project p = projectService.cancelTerminate(id, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 继续项目 */
    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resumeProject(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            Project p = projectService.resumeProject(id, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除子任务 */
    @DeleteMapping("/{projectId}/tasks/{taskId}")
    public ResponseEntity<?> deleteTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {
        try {
            Project p = projectService.deleteSubTask(projectId, taskId);
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除整个项目（含子任务、日志、评分记录） */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProject(@PathVariable Long id) {
        try {
            projectService.deleteProject(id);
            return ResponseEntity.ok(Map.of("message", "项目已删除"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== DTO Mappers ====================

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
        dto.setDeadline(p.getDeadline());
        dto.setProductRequirements(p.getProductRequirements());
        dto.setProductCategory(p.getProductCategory() != null ? p.getProductCategory().getName() : null);
        dto.setTargetMarket(p.getTargetMarket());
        dto.setComplianceItems(p.getComplianceItems());
        dto.setPriceRange(p.getPriceRange());

        // 使用预计算的计数，避免加载子任务
        int[] counts = taskCountMap != null ? taskCountMap.get(p.getId()) : null;
        int taskCount = counts != null ? counts[0] : 0;
        int doneCount = counts != null ? counts[1] : 0;
        dto.setTaskCount(taskCount);
        dto.setApprovedTaskCount(doneCount);
        dto.setProgressPercent(taskCount > 0 ? (int) (doneCount * 100 / taskCount) : 0);
        dto.setScore(scoreMap != null ? scoreMap.get(p.getId()) : null);
        dto.setCreatedAt(p.getCreatedAt().format(DTF));
        dto.setUpdatedAt(p.getUpdatedAt().format(DTF));
        return dto;
    }

    private ProjectDetailDTO toDetail(Project p) {
        ProjectDetailDTO dto = new ProjectDetailDTO();
        dto.setId(p.getId());
        dto.setType(p.getType());
        String computedStatus = projectService.computeProjectStatus(p);
        dto.setStatus(computedStatus);
        Map<String, String> statusInfo = ProjectService.getProjectStatusInfo(computedStatus);
        dto.setStatusLabel(statusInfo.get("label"));
        dto.setStatusCls(statusInfo.get("cls"));
        dto.setSalesName(p.getSalesName());
        dto.setSalesId(p.getSalesId());
        dto.setPlannerName(p.getPlannerName());
        dto.setPlannerId(p.getPlannerId());
        dto.setDeadline(p.getDeadline());
        dto.setProductRequirements(p.getProductRequirements());
        dto.setDescription(p.getDescription());
        dto.setProductCategory(p.getProductCategory() != null ? p.getProductCategory().getName() : null);
        dto.setProductCategoryNote(p.getProductCategoryNote());
        dto.setTargetMarket(p.getTargetMarket());
        dto.setComplianceItems(p.getComplianceItems());
        dto.setPriceRange(p.getPriceRange());
        dto.setReferenceImagesJson(p.getReferenceImagesJson());
        dto.setAttachmentsJson(p.getAttachmentsJson());

        // Logs
        dto.setLogs(p.getLogs().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", l.getTime().format(DTF));
            m.put("action", l.getAction());
            m.put("user", l.getUsername());
            m.put("role", l.getRole());
            return m;
        }).collect(Collectors.toList()));

        // Tasks with batch-loaded scoring records
        List<SubTask> taskList = p.getTasks();
        // 批量加载评分记录
        List<Long> taskIds = taskList.stream().map(SubTask::getId).collect(Collectors.toList());
        Map<Long, List<Map<String, Object>>> scoringMap = new HashMap<>();
        if (!taskIds.isEmpty()) {
            scoringRepository.findBySubTaskIds(taskIds).forEach(sr -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", sr.getId());
                m.put("role", sr.getRole());
                m.put("scoreType", sr.getScoreType());
                m.put("score", sr.getScore());
                m.put("comment", sr.getComment());
                m.put("aesthetics", sr.getAesthetics());
                m.put("innovation", sr.getInnovation());
                m.put("weight", sr.getWeight());
                scoringMap.computeIfAbsent(sr.getSubTask().getId(), k -> new java.util.ArrayList<>()).add(m);
            });
        }
        dto.setTasks(taskList.stream().map(t -> {
            TaskDetailDTO tDto = toTaskDetail(t);
            tDto.setScoringRecords(scoringMap.getOrDefault(t.getId(), List.of()));
            return tDto;
        }).collect(Collectors.toList()));

        // Progress（基于子任务完成状态，不依赖评分完成度）
        int taskCount = p.getTasks().size();
        long doneCount = p.getTasks().stream().filter(t -> List.of("delivered", "planner_approved", "sales_approved", "admin_approved", "completed").contains(t.getStatus())).count();
        dto.setProgressPercent(taskCount > 0 ? (int) (doneCount * 100 / taskCount) : 0);

        dto.setCreatedAt(p.getCreatedAt().format(DTF));
        dto.setUpdatedAt(p.getUpdatedAt().format(DTF));

        return dto;
    }

    private TaskDetailDTO toTaskDetail(SubTask t) {
        TaskDetailDTO dto = new TaskDetailDTO();
        dto.setId(t.getId());
        dto.setName(t.getName());
        dto.setStatus(t.getStatus());
        Map<String, String> statusInfo = ProjectService.getTaskStatusInfo(t.getStatus());
        dto.setStatusLabel(statusInfo.get("label"));
        dto.setStatusCls(statusInfo.get("cls"));
        dto.setStatusIcon(statusInfo.get("icon"));
        dto.setPlannedDate(t.getPlannedDate());
        dto.setActualDate(t.getActualDate());
        dto.setDesignerId(t.getDesignerId());
        dto.setDesignerName(t.getDesignerName());
        dto.setAssigneeRole(t.getAssigneeRole());
        dto.setDetails(t.getDetails());
        dto.setDeliverables(t.getDeliverables());
        dto.setAttachmentsJson(t.getAttachmentsJson());
        dto.setReferenceImagesJson(t.getReferenceImagesJson());
        dto.setReviewComments(t.getReviewComments());
        dto.setSelfScore(t.getSelfScore());
        dto.setSelfAesthetics(t.getSelfAesthetics());
        dto.setSelfInnovation(t.getSelfInnovation());
        dto.setCreatedAt(t.getCreatedAt().format(DTF));

        // Scoring records
        List<ScoringRecord> records = scoringRepository.findBySubTaskId(t.getId());
        dto.setScoringRecords(records.stream().map(sr -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sr.getId());
            m.put("role", sr.getRole());
            m.put("scoreType", sr.getScoreType());
            m.put("score", sr.getScore());
            m.put("comment", sr.getComment());
            // 兼容旧数据
            m.put("aesthetics", sr.getAesthetics());
            m.put("innovation", sr.getInnovation());
            m.put("weight", sr.getWeight());
            return m;
        }).collect(Collectors.toList()));

        return dto;
    }

    private AuthController.AuthSession getSession(HttpServletRequest request) {
        return (AuthController.AuthSession) request.getAttribute("authSession");
    }

    private Map<String, Object> withSessionContext(Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> safeBody = new LinkedHashMap<>();
        if (body != null) {
            safeBody.putAll(body);
        }
        AuthController.AuthSession session = getSession(request);
        safeBody.put("currentUser", session.name());
        safeBody.put("currentRole", session.role());
        safeBody.put("currentUserId", session.userId());
        safeBody.put("userId", session.userId());
        safeBody.put("role", session.role());
        if (safeBody.containsKey("designerUserId") || "designer".equals(session.role())
                || "supplychain".equals(session.role()) || "planner".equals(session.role())) {
            safeBody.put("designerUserId", session.userId());
        }
        return safeBody;
    }

    private boolean canAccessProject(Project project, AuthController.AuthSession session) {
        if (project == null || session == null) {
            return false;
        }
        if ("admin".equals(session.role())) {
            return true;
        }
        if ("sales".equals(session.role())) {
            return Objects.equals(session.userId(), project.getSalesId());
        }
        if ("planner".equals(session.role())) {
            return Objects.equals(session.userId(), project.getPlannerId())
                    || project.getTasks().stream().anyMatch(t -> Objects.equals(session.userId(), t.getDesignerId()));
        }
        return project.getTasks().stream().anyMatch(t -> Objects.equals(session.userId(), t.getDesignerId()));
    }
}
