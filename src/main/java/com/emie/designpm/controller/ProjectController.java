package com.emie.designpm.controller;

import com.emie.designpm.dto.ProjectDetailDTO;
import com.emie.designpm.dto.ApiErrorResponse;
import com.emie.designpm.dto.PageResponse;
import com.emie.designpm.dto.ProjectListQuery;
import com.emie.designpm.dto.ProjectSummaryDTO;
import com.emie.designpm.dto.TaskDetailDTO;
import com.emie.designpm.entity.*;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.service.ProjectService;
import com.emie.designpm.service.ProjectAccessService;
import com.emie.designpm.util.ProjectAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ScoringRepository scoringRepository;
    private final ActivityLogRepository activityLogRepository;
    private final SubTaskRepository subTaskRepository;
    private final ProjectAccessService projectAccessService;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public ProjectController(ProjectService projectService,
                             ScoringRepository scoringRepository,
                             ActivityLogRepository activityLogRepository,
                             SubTaskRepository subTaskRepository,
                             ProjectAccessService projectAccessService) {
        this.projectService = projectService;
        this.scoringRepository = scoringRepository;
        this.activityLogRepository = activityLogRepository;
        this.subTaskRepository = subTaskRepository;
        this.projectAccessService = projectAccessService;
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
            projects = projectService.getAssigneeParticipatingProjects(userId, role);
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

    /** 项目列表分页接口：默认每页 15 条，供全部项目、渠道定制单和公司常规品页面使用。 */
    @GetMapping("/page")
    public ResponseEntity<?> getProjectsPage(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String deadlineStart,
            @RequestParam(required = false) String deadlineEnd,
            @RequestParam(required = false, defaultValue = "false") boolean participating,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "15") int size,
            HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        try {
            ProjectListQuery query = new ProjectListQuery(
                    normalizeType(type), normalizeStatus(status), trimToNull(category, 50), normalizeMarket(market),
                    trimToNull(keyword, 100), normalizeDate(deadlineStart), normalizeDate(deadlineEnd), participating,
                    PageRequest.of(safePage, safeSize));
            if (query.deadlineStart() != null && query.deadlineEnd() != null
                    && query.deadlineStart().compareTo(query.deadlineEnd()) > 0) {
                return ResponseEntity.badRequest().body(ApiErrorResponse.invalidQuery("开始日期不能晚于结束日期"));
            }
            Page<Project> projectPage = projectService.getProjectsPage(session.role(), session.userId(), query);
            List<Project> projects = projectPage.getContent();
            Map<Long, int[]> taskCountMap = projectService.getTaskCountMap(projects);
            Map<Long, Double> scoreMap = projectService.computeProjectScoresBatch(projects);
            List<ProjectSummaryDTO> items = projects.stream().map(p -> toSummary(p, taskCountMap, scoreMap)).toList();
            return ResponseEntity.ok(new PageResponse<>(items, projectPage.getNumber(), projectPage.getSize(),
                    projectPage.getTotalElements(), projectPage.getTotalPages()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.invalidQuery(e.getMessage()));
        }
    }

    private String normalizeType(String type) {
        String value = trimToNull(type, 30);
        if (value == null) return null;
        if (!Set.of("channel_custom", "regular").contains(value)) throw new IllegalArgumentException("项目类型参数无效");
        return value;
    }

    private String normalizeStatus(String status) {
        String value = trimToNull(status, 40);
        if (value == null || "all".equals(value)) return null;
        Set<String> allowed = Set.of("draft", "in_progress", "paused", "completed", "completed_pending_score",
                "pending_planner", "pending_terminate", "terminated");
        if (!allowed.contains(value)) throw new IllegalArgumentException("项目状态参数无效");
        return value;
    }

    private String normalizeMarket(String market) {
        String value = trimToNull(market, 20);
        if (value == null || "all".equals(value)) return null;
        if (!Set.of("国内", "海外").contains(value)) throw new IllegalArgumentException("目标市场参数无效");
        return value;
    }

    private String normalizeDate(String value) {
        String date = trimToNull(value, 10);
        if (date == null) return null;
        try {
            return LocalDate.parse(date).toString();
        } catch (Exception ignored) {
            throw new IllegalArgumentException("日期必须使用 yyyy-MM-dd 格式");
        }
    }

    private String trimToNull(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("查询参数长度超出限制");
        return normalized;
    }

    /** 获取项目详情 */
    @GetMapping("/{id}")
    public ResponseEntity<ProjectDetailDTO> getProjectDetail(@PathVariable Long id, HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        Optional<Project> projectOpt = projectService.getProjectById(id);
        if (projectOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!projectAccessService.canView(projectOpt.get(), session)) {
            return ResponseEntity.status(403).build();
        }
        // 仅记录成功访问，避免把未授权探测误记为正常查询。
        activityLogRepository.save(new ActivityLog(
            "查询项目 #" + id, session.name(), session.role()));
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

    /** 编辑已创建项目的基础资料。权限不复用通用管理权限，严格限制为项目归属创建人。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateProjectInformation(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            AuthController.AuthSession session = getSession(request);
            Project project = projectService.getProjectById(id).orElseThrow(() -> new RuntimeException("项目不存在"));
            if (!ProjectAccessPolicy.canEditProjectInformation(project, session)) {
                return ResponseEntity.status(403).body(Map.of("error", "仅该项目的"
                        + ("channel_custom".equals(project.getType()) ? "销售" : "产品企划") + "可编辑项目信息"));
            }
            Project updated = projectService.updateProjectInformation(id, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(updated));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
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
    public ResponseEntity<Map<String, Object>> roleStatus(@RequestParam String role,
                                                          HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        if (session == null) return ResponseEntity.status(401).build();
        if (!"admin".equals(session.role()) && !Objects.equals(session.role(), role)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权查看其他角色的状态看板"));
        }
        return ResponseEntity.ok(projectService.getRoleStatus(role, session.role(), session.userId()));
    }

    /** 设计师状态看板（兼容旧版） */
    @GetMapping("/designer-status")
    public ResponseEntity<Map<String, Object>> designerStatus(HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        if (session == null) return ResponseEntity.status(401).build();
        if (!List.of("admin", "designer").contains(session.role())) {
            return ResponseEntity.status(403).body(Map.of("error", "无权查看设计师状态看板"));
        }
        return ResponseEntity.ok(projectService.getDesignerStatus(session.role(), session.userId()));
    }

    /** 左侧导航徽章统计：角色与用户由会话确定，禁止客户端伪造统计范围。 */
    @GetMapping("/badge-stats")
    public ResponseEntity<Map<String, Long>> badgeStats(HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        return ResponseEntity.ok(projectService.getNavigationBadgeStats(session.role(), session.userId()));
    }

    /** 终止项目 */
    @PostMapping("/{id}/terminate")
    public ResponseEntity<?> terminateProject(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            AuthController.AuthSession session = getSession(request);
            Project project = projectService.getProjectById(id).orElseThrow(() -> new RuntimeException("项目不存在"));
            if (!ProjectAccessPolicy.canManage(project, session)) return ResponseEntity.status(403).body(Map.of("error", "无权操作该项目"));
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
            AuthController.AuthSession session = getSession(request);
            Project project = projectService.getProjectById(id).orElseThrow(() -> new RuntimeException("项目不存在"));
            if (!ProjectAccessPolicy.canManage(project, session)) return ResponseEntity.status(403).body(Map.of("error", "无权操作该项目"));
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
            AuthController.AuthSession session = getSession(request);
            Project project = projectService.getProjectById(id).orElseThrow(() -> new RuntimeException("项目不存在"));
            if (!ProjectAccessPolicy.canManage(project, session)) return ResponseEntity.status(403).body(Map.of("error", "无权操作该项目"));
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
            AuthController.AuthSession session = getSession(request);
            Project project = projectService.getProjectById(id).orElseThrow(() -> new RuntimeException("项目不存在"));
            if (!ProjectAccessPolicy.canManage(project, session)) return ResponseEntity.status(403).body(Map.of("error", "无权操作该项目"));
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
            @PathVariable Long taskId,
            HttpServletRequest request) {
        try {
            AuthController.AuthSession session = getSession(request);
            Project project = projectService.getProjectById(projectId).orElseThrow(() -> new RuntimeException("项目不存在"));
            if (session == null || !("admin".equals(session.role()) ||
                    ("planner".equals(session.role()) && Objects.equals(session.userId(), project.getPlannerId())))) {
                return ResponseEntity.status(403).body(Map.of("error", "仅项目企划或管理员可删除子任务"));
            }
            Project p = projectService.deleteSubTask(projectId, taskId);
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除整个项目（含子任务、日志、评分记录） */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProject(@PathVariable Long id, HttpServletRequest request) {
        try {
            if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可删除项目"));
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
        dto.setProductName(p.getProductName());
        dto.setDeadline(p.getDeadline());
        dto.setProductRequirements(p.getProductRequirements());
        dto.setProductCategory(p.getProductCategory() != null ? p.getProductCategory().getName() : null);
        dto.setTargetMarket(p.getTargetMarket());
        dto.setComplianceItems(p.getComplianceItems());
        dto.setPriceRange(p.getPriceRange());
        dto.setIpName(p.getIpName());
        dto.setIpSubOptions(p.getIpSubOptions());

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
        dto.setProductName(p.getProductName());
        dto.setDeadline(p.getDeadline());
        dto.setProductRequirements(p.getProductRequirements());
        dto.setDescription(p.getDescription());
        dto.setProductCategory(p.getProductCategory() != null ? p.getProductCategory().getName() : null);
        dto.setProductCategoryNote(p.getProductCategoryNote());
        dto.setTargetMarket(p.getTargetMarket());
        dto.setComplianceItems(p.getComplianceItems());
        dto.setPriceRange(p.getPriceRange());
        dto.setIpName(p.getIpName());
        dto.setIpSubOptions(p.getIpSubOptions());
        dto.setReferenceImagesJson(p.getReferenceImagesJson());
        dto.setAttachmentsJson(p.getAttachmentsJson());

        // Logs
        dto.setLogs(p.getLogs().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", l.getTime().format(DTF));
            m.put("action", l.getAction());
            m.put("user", l.getUsername());
            m.put("role", l.getRole());
            m.put("entityType", l.getEntityType());
            m.put("entityId", l.getEntityId());
            m.put("beforeData", l.getBeforeData());
            m.put("afterData", l.getAfterData());
            m.put("changedFields", l.getChangedFields());
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
                m.put("reviewStage", sr.getReviewStage());
                m.put("reviewStatus", sr.getReviewStatus());
                m.put("reviewerId", sr.getReviewerId());
                m.put("reviewerName", sr.getReviewerName());
                m.put("reviewedAt", sr.getReviewedAt() != null ? sr.getReviewedAt().format(DTF) : null);
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

}
