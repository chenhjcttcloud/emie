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
import com.emie.designpm.service.ProjectWorkflowService;
import com.emie.designpm.service.PermissionService;
import com.emie.designpm.service.UserService;
import com.emie.designpm.util.ProjectAccessPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

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
    private final ProjectWorkflowService projectWorkflowService;
    private final PermissionService permissionService;
    @Autowired(required = false)
    private UserService userService;
    @Autowired(required = false)
    private com.emie.designpm.repository.PointLedgerRepository pointLedgerRepository;
    @Autowired(required = false)
    private com.emie.designpm.service.FeishuChatService feishuChatService;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    public ProjectController(ProjectService projectService,
                             ScoringRepository scoringRepository,
                             ActivityLogRepository activityLogRepository,
                             SubTaskRepository subTaskRepository,
                             ProjectAccessService projectAccessService,
                             ProjectWorkflowService projectWorkflowService,
                             PermissionService permissionService) {
        this.projectService = projectService;
        this.scoringRepository = scoringRepository;
        this.activityLogRepository = activityLogRepository;
        this.subTaskRepository = subTaskRepository;
        this.projectAccessService = projectAccessService;
        this.projectWorkflowService = projectWorkflowService;
        this.permissionService = permissionService;
    }

    /** 保留给轻量 Controller 单元测试；生产运行始终使用完整依赖构造器。 */
    ProjectController(ProjectService projectService,
                      ScoringRepository scoringRepository,
                      ActivityLogRepository activityLogRepository,
                      SubTaskRepository subTaskRepository,
                      ProjectAccessService projectAccessService,
                      ProjectWorkflowService projectWorkflowService) {
        this(projectService, scoringRepository, activityLogRepository, subTaskRepository,
                projectAccessService, projectWorkflowService, null);
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
        Map<Long, String> statusMap = projectService.computeProjectStatusMap(projects);

        List<ProjectSummaryDTO> result = projects.stream()
                .map(p -> toSummary(p, taskCountMap, scoreMap, statusMap))
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
            @RequestParam(required = false) String ownerRole,
            @RequestParam(required = false) String ownerId,
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
                    PageRequest.of(safePage, safeSize), normalizeOwnerRole(ownerRole), trimToNull(ownerId, 100));
            if (query.deadlineStart() != null && query.deadlineEnd() != null
                    && query.deadlineStart().compareTo(query.deadlineEnd()) > 0) {
                return ResponseEntity.badRequest().body(ApiErrorResponse.invalidQuery("开始日期不能晚于结束日期"));
            }
            Page<Project> projectPage = projectService.getProjectsPage(session.role(), session.userId(), query);
            List<Project> projects = projectPage.getContent();
            Map<Long, int[]> taskCountMap = projectService.getTaskCountMap(projects);
            Map<Long, Double> scoreMap = projectService.computeProjectScoresBatch(projects);
            Map<Long, String> statusMap = projectService.computeProjectStatusMap(projects);
            List<ProjectSummaryDTO> items = projects.stream()
                    .map(p -> toSummary(p, taskCountMap, scoreMap, statusMap)).toList();
            return ResponseEntity.ok(new PageResponse<>(items, projectPage.getNumber(), projectPage.getSize(),
                    projectPage.getTotalElements(), projectPage.getTotalPages()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.invalidQuery(e.getMessage()));
        }
    }

    private String normalizeOwnerRole(String role) {
        String value = trimToNull(role, 30);
        if (value == null || "all".equals(value)) return null;
        if (!List.of("sales", "planner").contains(value)) {
            throw new IllegalArgumentException("负责人类型不合法");
        }
        return value;
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

    /** 执行角色工作台一次性读取可见项目及子任务，避免前端逐项目请求详情。 */
    @GetMapping("/my-tasks")
    public ResponseEntity<List<ProjectDetailDTO>> getMyTaskProjects(HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        if (session == null || !("admin".equals(session.role()) || "designer".equals(session.role())
                || "supplychain".equals(session.role()) || "planner".equals(session.role()))) {
            return ResponseEntity.status(403).build();
        }
        List<Project> projects = projectAccessService.findVisibleProjectsWithTasks(session);
        List<Long> taskIds = projects.stream().flatMap(p -> p.getTasks().stream()).map(SubTask::getId).toList();
        Map<Long, List<Map<String, Object>>> scoringByTask = loadScoringDetails(taskIds);
        return ResponseEntity.ok(projects.stream().map(p -> toDetail(p, scoringByTask, false)).toList());
    }

    /** 独立的“我的子任务”查询：只返回当前用户作为负责人或发布人关联的任务。 */
    @GetMapping("/my-subtasks")
    public ResponseEntity<List<Map<String, Object>>> getMySubTasks(HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        if (session == null) return ResponseEntity.status(401).build();
        List<SubTask> tasks = subTaskRepository.findMySubTasks(session.userId());
        Map<Long, List<Map<String, Object>>> scoringByTask = loadScoringDetails(tasks.stream().map(SubTask::getId).toList());
        Map<Long, List<ActivityLog>> logsByProject = new HashMap<>();
        List<Map<String, Object>> result = tasks.stream().map(task -> {
            Project project = task.getProject();
            List<ActivityLog> projectLogs = logsByProject.computeIfAbsent(project.getId(),
                    id -> activityLogRepository.findTop200ByProjectIdOrderByTimeDesc(id));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", task.getId());
            item.put("name", task.getName());
            item.put("status", task.getStatus());
            item.put("plannedDate", task.getPlannedDate());
            item.put("actualDate", task.getActualDate());
            item.put("designerId", task.getDesignerId());
            item.put("designerName", task.getDesignerName());
            item.put("allocationStatus", task.getAllocationStatus());
            item.put("marketPublishedAt", task.getMarketPublishedAt());
            item.put("claimedAt", task.getClaimedAt());
            item.put("publisherId", task.getPublisherId());
            item.put("publisherName", task.getPublisherName());
            item.put("publisherRole", task.getPublisherRole());
            item.put("assigneeRole", task.getAssigneeRole());
            item.put("details", task.getDetails());
            item.put("deliverables", task.getDeliverables());
            item.put("referenceImagesJson", task.getReferenceImagesJson());
            item.put("attachmentsJson", task.getAttachmentsJson());
            item.put("reviewComments", task.getReviewComments());
            item.put("selfScore", task.getSelfScore());
            item.put("selfAesthetics", task.getSelfAesthetics());
            item.put("selfInnovation", task.getSelfInnovation());
            item.put("projectId", project.getId());
            item.put("projectType", project.getType());
            item.put("projectStatus", project.getStatus());
            item.put("projectName", projectDisplayName(project));
            item.put("plannerId", project.getPlannerId());
            item.put("plannerName", project.getPlannerName());
            item.put("scoringRecords", scoringByTask.getOrDefault(task.getId(), List.of()));
            item.put("rejectionRecords", rejectionRecords(project, task, projectLogs));
            item.put("deliveryVersions", projectService.getDeliveryVersions(task.getId()));
            item.put("relation", session.userId().equals(task.getDesignerId()) ? "assignee" : "publisher");
            item.put("issuedPoints", pointLedgerRepository == null ? 0d : pointLedgerRepository.findBySubTaskId(task.getId()).stream()
                    .map(PointLedger::getPoints).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum());
            item.put("issuedLedgerCount", pointLedgerRepository == null ? 0 : pointLedgerRepository.findBySubTaskId(task.getId()).size());
            return item;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /** 设计师接单市场：仅返回仍开放、未指定负责人的设计师子任务。 */
    @GetMapping("/task-market")
    public ResponseEntity<?> getTaskMarket(HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        if (session == null) return ResponseEntity.status(401).build();
        if (!List.of("designer", "planner", "admin").contains(session.role())) {
            return ResponseEntity.status(403).body(Map.of("error", "当前角色无权查看接单市场"));
        }
        List<Map<String, Object>> result = subTaskRepository.findOpenDesignerMarketTasks().stream().map(task -> {
            Project project = task.getProject();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", task.getId()); item.put("name", task.getName()); item.put("status", task.getStatus());
            item.put("plannedDate", task.getPlannedDate()); item.put("designerId", task.getDesignerId());
            item.put("designerName", task.getDesignerName()); item.put("publisherId", task.getPublisherId());
            item.put("publisherName", task.getPublisherName()); item.put("publisherRole", task.getPublisherRole());
            item.put("assigneeRole", task.getAssigneeRole()); item.put("allocationStatus", task.getAllocationStatus());
            item.put("marketPublishedAt", task.getMarketPublishedAt()); item.put("details", task.getDetails());
            item.put("referenceImagesJson", task.getReferenceImagesJson()); item.put("attachmentsJson", task.getAttachmentsJson());
            item.put("projectId", project.getId()); item.put("projectType", project.getType());
            item.put("projectStatus", project.getStatus()); item.put("projectName", projectDisplayName(project));
            item.put("relation", "market");
            return item;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /** 部门负责人/管理员只读查看部门成员关联任务。 */
    @GetMapping("/department-subtasks")
    public ResponseEntity<List<Map<String, Object>>> getDepartmentSubTasks(HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        if (session == null) return ResponseEntity.status(401).build();
        List<String> userIds = projectAccessService.departmentTaskUserIds(session.role(), session.userId());
        // 普通成员没有部门负责人范围时返回空列表，而不是让页面出现权限错误。
        // 真正的部门范围仍由 departmentTaskUserIds 在服务端严格计算，不会扩大可见数据。
        if (userIds.isEmpty()) return ResponseEntity.ok(List.of());
        List<SubTask> tasks = subTaskRepository.findDepartmentSubTasks(userIds);
        Map<Long, List<ActivityLog>> logsByProject = new HashMap<>();
        return ResponseEntity.ok(tasks.stream().map(task -> {
            Project project = task.getProject();
            List<ActivityLog> projectLogs = logsByProject.computeIfAbsent(project.getId(),
                    id -> activityLogRepository.findTop200ByProjectIdOrderByTimeDesc(id));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", task.getId()); item.put("name", task.getName()); item.put("status", task.getStatus());
            item.put("plannedDate", task.getPlannedDate()); item.put("actualDate", task.getActualDate());
            item.put("designerId", task.getDesignerId()); item.put("designerName", task.getDesignerName());
            item.put("publisherId", task.getPublisherId()); item.put("publisherName", task.getPublisherName());
            item.put("publisherRole", task.getPublisherRole()); item.put("assigneeRole", task.getAssigneeRole());
            item.put("details", task.getDetails()); item.put("deliverables", task.getDeliverables());
            item.put("referenceImagesJson", task.getReferenceImagesJson());
            item.put("attachmentsJson", task.getAttachmentsJson());
            item.put("reviewComments", task.getReviewComments());
            item.put("projectId", project.getId()); item.put("projectType", project.getType());
            item.put("projectName", projectDisplayName(project)); item.put("readOnly", true);
            item.put("plannerId", project.getPlannerId()); item.put("plannerName", project.getPlannerName());
            item.put("rejectionRecords", rejectionRecords(project, task, projectLogs));
            item.put("deliveryVersions", projectService.getDeliveryVersions(task.getId()));
            String uid = session.userId();
            item.put("relation", uid.equals(task.getPublisherId()) ? "publisher" : "department_member");
            item.put("relationLabel", uid.equals(task.getPublisherId()) ? "我发布的任务" : "部门成员关联任务");
            return item;
        }).toList());
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
        // 日志单独查询，避免项目详情同时 fetch 两个集合导致连接和结果集膨胀；
        // 不替换实体的 orphanRemoval 集合，避免 Hibernate 误判为删除全部日志。
        List<ActivityLog> detailLogs = new ArrayList<>(activityLogRepository.findTop200ByProjectIdOrderByTimeDesc(id));
        Collections.reverse(detailLogs);
        // 仅记录成功访问，避免把未授权探测误记为正常查询。
        activityLogRepository.save(new ActivityLog(
            "查询项目 #" + id, session.name(), session.role()));
        return ResponseEntity.ok(toDetailWithLogs(projectOpt.get(), detailLogs));
    }

    /** 新建项目 */
    @PostMapping
    public ResponseEntity<?> createProject(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            AuthController.AuthSession session = getSession(request);
            String type = Objects.toString(body.get("type"), "");
            String permission = switch (type) {
                case "channel_custom" -> "project.channel.create";
                case "regular" -> "project.regular.create";
                default -> null;
            };
            if (permission == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "不支持的项目类型"));
            }
            if (permissionService != null && !permissionService.has(session.role(), permission)) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "当前账号没有新建此类项目的权限",
                        "permission", permission));
            }
            Project p = projectService.createProject(withSessionContext(body, request));
            if (p.isFeishuChatEnabled()) ensureProjectChat(p);
            return ResponseEntity.ok(toDetail(p));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/feishu-chat/create")
    public ResponseEntity<?> createProjectChat(@PathVariable Long id, HttpServletRequest request) {
        try {
            AuthController.AuthSession session = getSession(request);
            Project p = projectService.getProjectById(id).orElseThrow(() -> new RuntimeException("项目不存在"));
            if (!canCreateProjectChat(p, session)) return ResponseEntity.status(403).body(Map.of("error", "无权创建该项目群"));
            if (feishuChatService == null || !feishuChatService.enabled()) return ResponseEntity.badRequest().body(Map.of("error", "飞书应用配置未完成"));
            if (p.getFeishuChatId() != null && !p.getFeishuChatId().isBlank()) return ResponseEntity.ok(toDetail(p));
            String owner = "channel_custom".equals(p.getType()) ? (p.getSalesName() + "（销售）") : (p.getPlannerName() + "（产品企划）");
            String name = ("channel_custom".equals(p.getType()) ? "定制" : "常规") + "-#" + p.getId() + "-" + owner;
            String chatId = feishuChatService.createChat(name, List.of());
            p.setFeishuChatEnabled(true);
            p.setFeishuChatId(chatId); p.setFeishuChatStatus("created"); p.setFeishuChatError(null); p.setFeishuChatCreatedAt(java.time.LocalDateTime.now());
            syncProjectChatMembers(p);
            return ResponseEntity.ok(toDetail(projectService.saveProject(p)));
        } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/{id}/feishu-chat/dissolve")
    public ResponseEntity<?> dissolveProjectChat(@PathVariable Long id, HttpServletRequest request) {
        try {
            AuthController.AuthSession session = getSession(request);
            Project p = projectService.getProjectById(id).orElseThrow(() -> new RuntimeException("项目不存在"));
            if (!canManageProjectChat(p, session)) return ResponseEntity.status(403).body(Map.of("error", "无权解散该项目群"));
            if (!List.of("completed", "terminated", "pending_terminate").contains(p.getStatus())) return ResponseEntity.badRequest().body(Map.of("error", "项目未完成或未终止，不能解散群聊"));
            feishuChatService.dissolve(p.getFeishuChatId());
            p.setFeishuChatStatus("dissolved"); p.setFeishuChatDissolvedAt(java.time.LocalDateTime.now());
            return ResponseEntity.ok(toDetail(projectService.saveProject(p)));
        } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    private boolean canManageProjectChat(Project p, AuthController.AuthSession s) {
        if (s == null) return false;
        return "admin".equals(s.role()) || ("channel_custom".equals(p.getType()) ? Objects.equals(p.getSalesId(), s.userId()) : Objects.equals(p.getPlannerId(), s.userId()));
    }

    /** 产品企划可为任意渠道定制或公司常规品项目补建群；原项目负责人权限保持不变。 */
    private boolean canCreateProjectChat(Project p, AuthController.AuthSession s) {
        if (s == null || p == null || !List.of("channel_custom", "regular").contains(p.getType())) return false;
        return "planner".equals(s.role()) || canManageProjectChat(p, s);
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
            String permission = switch (project.getType()) {
                case "channel_custom" -> "project.channel.edit";
                case "regular" -> "project.regular.edit";
                default -> null;
            };
            if (permission == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "不支持的项目类型"));
            }
            if (permissionService != null && !permissionService.has(session.role(), permission)) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "当前账号没有编辑此类项目的权限",
                        "permission", permission));
            }
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
    public ResponseEntity<?> plannerAccept(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        ResponseEntity<?> denied = denyUnless(request, "project.accept");
        if (denied != null) return denied;
        Map<String, Object> safeBody = withSessionContext(new LinkedHashMap<>(body), request);
        Project p = projectService.plannerAccept(id,
                (String) safeBody.getOrDefault("currentUser", ""),
                (String) safeBody.getOrDefault("currentRole", ""),
                (String) safeBody.getOrDefault("userId", ""));
        return ResponseEntity.ok(toDetail(p));
    }

    /** 添加子任务 */
    @PostMapping("/{id}/tasks")
    public ResponseEntity<?> addTask(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            AuthController.AuthSession session = getSession(request);
            if (permissionService != null && !permissionService.has(session.role(), "subtask.create")) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "当前账号没有新建子任务的权限",
                        "permission", "subtask.create"));
            }
            Project p = projectService.addSubTask(id, withSessionContext(body, request));
            if (p.isFeishuChatEnabled()) { ensureProjectChat(p); syncProjectChatMembers(p); }
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 建群与业务写入解耦：飞书暂时不可用时不阻断项目/子任务保存。 */
    private void ensureProjectChat(Project p) {
        if (p == null || feishuChatService == null || !feishuChatService.enabled()
                || (p.getFeishuChatId() != null && !p.getFeishuChatId().isBlank())) return;
        try {
            String owner = "channel_custom".equals(p.getType()) ? (p.getSalesName() + "（销售）") : (p.getPlannerName() + "（产品企划）");
            String name = ("channel_custom".equals(p.getType()) ? "定制" : "常规") + "-#" + p.getId() + "-" + owner;
            String chatId = feishuChatService.createChat(name, collectProjectOpenIds(p));
            p.setFeishuChatId(chatId); p.setFeishuChatStatus("created"); p.setFeishuChatError(null);
            p.setFeishuChatCreatedAt(java.time.LocalDateTime.now());
            projectService.saveProject(p);
        } catch (Exception e) {
            p.setFeishuChatStatus("failed"); p.setFeishuChatError(e.getMessage()); projectService.saveProject(p);
        }
    }

    private void syncProjectChatMembers(Project p) {
        if (p == null || feishuChatService == null || p.getFeishuChatId() == null || p.getFeishuChatId().isBlank()) return;
        try { feishuChatService.addMembers(p.getFeishuChatId(), collectProjectOpenIds(p)); }
        catch (Exception e) { p.setFeishuChatStatus("failed"); p.setFeishuChatError(e.getMessage()); projectService.saveProject(p); }
    }

    private Collection<String> collectProjectOpenIds(Project p) {
        Set<String> ids = new LinkedHashSet<>();
        if (userService == null) return ids;
        for (String uid : Arrays.asList(p.getPlannerId(), p.getSalesId())) {
            if (uid == null || uid.isBlank()) continue;
            User u = userService.getUserByUserId(uid); if (u != null && u.getFeishuOpenId() != null && !u.getFeishuOpenId().isBlank()) ids.add(u.getFeishuOpenId());
        }
        if (p.getTasks() != null) for (SubTask t : p.getTasks()) {
            for (String uid : Arrays.asList(t.getDesignerId(), t.getPublisherId())) {
                if (uid == null || uid.isBlank()) continue;
                User u = userService.getUserByUserId(uid); if (u != null && u.getFeishuOpenId() != null && !u.getFeishuOpenId().isBlank()) ids.add(u.getFeishuOpenId());
            }
        }
        return ids;
    }

    @PostMapping("/{id}/workflow/complete-execution")
    public ResponseEntity<?> completeWorkflowExecution(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<?> denied = denyUnless(request, "project.workflow.advance");
        if (denied != null) return denied;
        AuthController.AuthSession session = getSession(request);
        try {
            Project project = projectWorkflowService.completeExecution(
                    id, session.userId(), session.name(), session.role());
            return ResponseEntity.ok(projectWorkflowService.build(project));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/workflow/submit-review")
    public ResponseEntity<?> submitWorkflowReview(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<?> denied = denyUnless(request, "project.workflow.advance");
        if (denied != null) return denied;
        AuthController.AuthSession session = getSession(request);
        try {
            return ResponseEntity.ok(projectWorkflowService.submitReview(
                    id, session.userId(), session.name(), session.role()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/workflow/review")
    public ResponseEntity<?> reviewWorkflow(@PathVariable Long id,
                                             @RequestBody Map<String, String> body,
                                             HttpServletRequest request) {
        ResponseEntity<?> denied = denyUnless(request, "project.workflow.review");
        if (denied != null) return denied;
        AuthController.AuthSession session = getSession(request);
        try {
            return ResponseEntity.ok(projectWorkflowService.review(
                    id, body.get("decision"), body.get("comment"),
                    session.userId(), session.name(), session.role()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 编辑子任务 */
    @PutMapping("/{projectId}/tasks/{taskId}")
    public ResponseEntity<?> updateTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "subtask.edit");
            if (denied != null) return denied;
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
            ResponseEntity<?> denied = denyUnless(request, "subtask.accept");
            if (denied != null) return denied;
            Project p = projectService.taskAccept(projectId, taskId, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && (e.getMessage().contains("已被接单") || e.getMessage().contains("已处理"))) {
                return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 企划在无人接单时将任务撤出接单市场。 */
    @PostMapping("/{projectId}/tasks/{taskId}/withdraw-market")
    public ResponseEntity<?> withdrawMarketTask(@PathVariable Long projectId, @PathVariable Long taskId,
                                                 HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "subtask.edit");
            if (denied != null) return denied;
            return ResponseEntity.ok(toDetail(projectService.withdrawMarketTask(
                    projectId, taskId, withSessionContext(new LinkedHashMap<>(), request))));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 设计师退单（接单后一小时内免罚，超时按比例扣分）。 */
    @PostMapping("/{projectId}/tasks/{taskId}/withdraw")
    public ResponseEntity<?> withdrawAcceptedTask(@PathVariable Long projectId, @PathVariable Long taskId,
                                                   @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "subtask.accept");
            if (denied != null) return denied;
            return ResponseEntity.ok(toDetail(projectService.withdrawAcceptedTask(projectId, taskId, withSessionContext(body, request))));
        } catch (RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    /** 设计师交付 */
    @PostMapping("/{projectId}/tasks/{taskId}/deliver")
    public ResponseEntity<?> taskDeliver(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "subtask.deliver");
            if (denied != null) return denied;
            Project p = projectService.taskDeliver(projectId, taskId, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 设计师/供应链提交已交付成果进入企划送审。 */
    @PostMapping("/{projectId}/tasks/{taskId}/submit-review")
    public ResponseEntity<?> taskSubmitReview(@PathVariable Long projectId, @PathVariable Long taskId,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "subtask.review.first.submit");
            if (denied != null) return denied;
            return ResponseEntity.ok(toDetail(projectService.taskSubmitReview(projectId, taskId, withSessionContext(body, request))));
        } catch (RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    /** 设计师重新交付 */
    @PostMapping("/{projectId}/tasks/{taskId}/redeliver")
    public ResponseEntity<?> taskRedeliver(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "subtask.redeliver");
            if (denied != null) return denied;
            Project p = projectService.taskRedeliver(projectId, taskId, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 被驳回负责人确认开始修改。 */
    @PostMapping("/{projectId}/tasks/{taskId}/confirm-revision")
    public ResponseEntity<?> taskConfirmRevision(@PathVariable Long projectId, @PathVariable Long taskId,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "subtask.redeliver");
            if (denied != null) return denied;
            return ResponseEntity.ok(toDetail(projectService.taskConfirmRevision(projectId, taskId, withSessionContext(body, request))));
        } catch (RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    /** 审核完成前，负责人主动修正漏交或错交文件；生成新版本并使旧审核失效。 */
    @PostMapping("/{projectId}/tasks/{taskId}/correct-delivery")
    public ResponseEntity<?> taskCorrectDelivery(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "subtask.redeliver");
            if (denied != null) return denied;
            Project p = projectService.taskCorrectDelivery(projectId, taskId, withSessionContext(body, request));
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
            String permission = reviewPermission(getSession(request), true);
            ResponseEntity<?> denied = denyUnless(request, permission);
            if (denied != null) return denied;
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
            String permission = reviewPermission(getSession(request), false);
            ResponseEntity<?> denied = denyUnless(request, permission);
            if (denied != null) return denied;
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
            ResponseEntity<?> denied = denyUnless(request, "scoring.submit");
            if (denied != null) return denied;
            Project p = projectService.submitScoring(projectId, taskId, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 通用角色状态看板（sales/planner/supplychain/designer） */
    @GetMapping("/role-status")
    public ResponseEntity<Map<String, Object>> roleStatus(@RequestParam String role,
                                                          @RequestParam(defaultValue = "all") String scope,
                                                          HttpServletRequest request) {
        AuthController.AuthSession session = getSession(request);
        if (session == null) return ResponseEntity.status(401).build();
        boolean allowed = "admin".equals(session.role())
                || Objects.equals(session.role(), role)
                || ("planner".equals(session.role()) && Set.of("planner", "designer", "supplychain").contains(role));
        if (!allowed) {
            return ResponseEntity.status(403).body(Map.of("error", "无权查看该角色的状态看板"));
        }
        return ResponseEntity.ok(projectService.getRoleStatus(role, session.role(), session.userId(), scope));
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
            ResponseEntity<?> denied = denyUnless(request, "project.terminate");
            if (denied != null) return denied;
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
            ResponseEntity<?> denied = denyUnless(request, "project.pause");
            if (denied != null) return denied;
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
            ResponseEntity<?> denied = denyUnless(request, "project.resume");
            if (denied != null) return denied;
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
            ResponseEntity<?> denied = denyUnless(request, "project.resume");
            if (denied != null) return denied;
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
            ResponseEntity<?> denied = denyUnless(request, "subtask.delete");
            if (denied != null) return denied;
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
            ResponseEntity<?> denied = denyUnless(request, "project.delete");
            if (denied != null) return denied;
            AuthController.AuthSession session = getSession(request);
            Project project = projectService.getProjectById(id)
                    .orElseThrow(() -> new RuntimeException("项目不存在"));
            if (!ProjectAccessPolicy.canManage(project, session)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权操作该项目"));
            }
            projectService.deleteProject(id);
            return ResponseEntity.ok(Map.of("message", "项目已删除"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> denyUnless(HttpServletRequest request, String permission) {
        AuthController.AuthSession session = getSession(request);
        if (permission != null && (permissionService == null || permissionService.has(session.role(), permission))) {
            return null;
        }
        return ResponseEntity.status(403).body(Map.of(
                "error", "当前账号没有执行此操作的权限",
                "permission", permission == null ? "unsupported.action" : permission));
    }

    private String reviewPermission(AuthController.AuthSession session, boolean approve) {
        String action = approve ? "approve" : "reject";
        return switch (session.role()) {
            case "planner" -> "subtask.review.first." + action;
            case "sales" -> "subtask.review.channel." + action;
            case "admin" -> "subtask.review.regular." + action;
            default -> null;
        };
    }

    // ==================== DTO Mappers ====================

    private ProjectSummaryDTO toSummary(Project p, Map<Long, int[]> taskCountMap, Map<Long, Double> scoreMap,
                                        Map<Long, String> statusMap) {
        ProjectSummaryDTO dto = new ProjectSummaryDTO();
        dto.setId(p.getId());
        dto.setProjectCode(p.getProjectCode());
        dto.setType(p.getType());
        String computedStatus = statusMap != null ? statusMap.getOrDefault(p.getId(), p.getStatus()) : p.getStatus();
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
        Project loaded = p.getId() == null ? p : projectService.getProjectById(p.getId()).orElse(p);
        List<ActivityLog> logs = loaded.getId() == null ? List.of()
                : activityLogRepository.findTop200ByProjectIdOrderByTimeDesc(loaded.getId());
        return toDetail(loaded, null, logs);
    }

    private ProjectDetailDTO toDetailWithLogs(Project p, List<ActivityLog> logs) {
        return toDetail(p, null, logs);
    }

    private ProjectDetailDTO toDetail(Project p, Map<Long, List<Map<String, Object>>> preloadedScoring) {
        return toDetail(p, preloadedScoring, true);
    }

    private ProjectDetailDTO toDetail(Project p, Map<Long, List<Map<String, Object>>> preloadedScoring,
                                      boolean includeLogs) {
        return toDetail(p, preloadedScoring, includeLogs, null);
    }

    private ProjectDetailDTO toDetail(Project p, Map<Long, List<Map<String, Object>>> preloadedScoring,
                                      List<ActivityLog> preloadedLogs) {
        return toDetail(p, preloadedScoring, true, preloadedLogs);
    }

    private ProjectDetailDTO toDetail(Project p, Map<Long, List<Map<String, Object>>> preloadedScoring,
                                      boolean includeLogs, List<ActivityLog> preloadedLogs) {
        ProjectDetailDTO dto = new ProjectDetailDTO();
        List<ActivityLog> effectiveLogs = preloadedLogs != null ? preloadedLogs
                : (p.getId() == null ? List.of()
                : activityLogRepository.findTop200ByProjectIdOrderByTimeDesc(p.getId()));
        dto.setId(p.getId());
        dto.setProjectCode(p.getProjectCode());
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
        dto.setProductArchiveJson(p.getProductArchiveJson());

        // 详情页需要日志；工作台聚合接口不加载日志，避免历史日志膨胀拖慢响应。
        if (includeLogs) dto.setLogs(effectiveLogs.stream().map(l -> {
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
        Map<Long, List<Map<String, Object>>> scoringMap = preloadedScoring != null
                ? preloadedScoring : new HashMap<>();
        if (preloadedScoring == null && !taskIds.isEmpty()) {
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
            tDto.setRejectionRecords(rejectionRecords(p, t, effectiveLogs));
            tDto.setDeliveryVersions(projectService.getDeliveryVersions(t.getId()));
            return tDto;
        }).collect(Collectors.toList()));
        dto.setSubTaskWorkflow(projectWorkflowService.build(p));

        // Progress（基于子任务完成状态，不依赖评分完成度）
        int taskCount = p.getTasks().size();
        long doneCount = p.getTasks().stream().filter(t -> List.of("delivered", "planner_approved", "sales_approved", "admin_approved", "completed").contains(t.getStatus())).count();
        dto.setProgressPercent(taskCount > 0 ? (int) (doneCount * 100 / taskCount) : 0);

        dto.setCreatedAt(p.getCreatedAt().format(DTF));
        dto.setUpdatedAt(p.getUpdatedAt().format(DTF));
        dto.setFeishuChatId(p.getFeishuChatId());
        dto.setFeishuChatStatus(p.getFeishuChatStatus());
        dto.setFeishuChatError(p.getFeishuChatError());
        dto.setFeishuChatEnabled(p.isFeishuChatEnabled());

        return dto;
    }

    private List<Map<String, Object>> rejectionRecords(Project project, SubTask task,
                                                        List<ActivityLog> preloadedLogs) {
        String legacyPrefix = "子任务驳回：" + task.getName() + "（意见：";
        List<ActivityLog> logs = (preloadedLogs != null ? preloadedLogs : project.getLogs()).stream()
                .filter(log -> log.getAction() != null && log.getAction().startsWith("子任务驳回："))
                .filter(log -> ("sub_task".equals(log.getEntityType()) && Objects.equals(log.getEntityId(), task.getId()))
                        || (!"sub_task".equals(log.getEntityType()) && log.getAction().startsWith(legacyPrefix)))
                .sorted(Comparator.comparing(ActivityLog::getTime))
                .toList();
        List<Map<String, Object>> records = new ArrayList<>();
        for (int index = 0; index < logs.size(); index++) {
            ActivityLog log = logs.get(index);
            Map<String, Object> snapshot = parseJsonMap(log.getBeforeData());
            Map<String, Object> rejection = parseJsonMap(log.getAfterData());
            String action = log.getAction();
            int reasonStart = action.indexOf("（意见：");
            String reason = reasonStart >= 0
                    ? action.substring(reasonStart + 4, action.endsWith("）") ? action.length() - 1 : action.length())
                    : "";
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", log.getId());
            record.put("attemptNo", index + 1);
            record.put("reviewerName", log.getUsername());
            record.put("reviewerRole", log.getRole());
            record.put("reviewedAt", log.getTime().format(DTF));
            record.put("reason", reason);
            record.put("requiredCompletionDate", rejection.getOrDefault("requiredCompletionDate", snapshot.getOrDefault("plannedDate", "")));
            record.put("rejectionReferenceImagesJson", rejection.getOrDefault("rejectionReferenceImagesJson", "[]"));
            record.put("rejectionAttachmentsJson", rejection.getOrDefault("rejectionAttachmentsJson", "[]"));
            record.put("deliverables", snapshot.getOrDefault("deliverables", task.getDeliverables()));
            record.put("referenceImagesJson", snapshot.getOrDefault("referenceImagesJson", task.getReferenceImagesJson()));
            record.put("attachmentsJson", snapshot.getOrDefault("attachmentsJson", task.getAttachmentsJson()));
            record.put("actualDate", snapshot.getOrDefault("actualDate", task.getActualDate()));
            record.put("submittedByName", snapshot.getOrDefault("submittedByName", task.getDesignerName()));
            record.put("legacy", snapshot.isEmpty());
            records.add(record);
        }
        return records;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<Long, List<Map<String, Object>>> loadScoringDetails(List<Long> taskIds) {
        Map<Long, List<Map<String, Object>>> scoringMap = new HashMap<>();
        if (taskIds.isEmpty()) return scoringMap;
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
            scoringMap.computeIfAbsent(sr.getSubTask().getId(), ignored -> new ArrayList<>()).add(m);
        });
        return scoringMap;
    }

    /** 子任务卡片优先展示正式产品名称，兼容迁移前没有 productName 的历史项目。 */
    private String projectDisplayName(Project project) {
        if (project.getProductName() != null && !project.getProductName().isBlank()) {
            return project.getProductName().trim();
        }
        return project.getProductRequirements();
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
        dto.setWorkflowStage(t.getWorkflowStage());
        dto.setPlannedDate(t.getPlannedDate());
        dto.setActualDate(t.getActualDate());
        dto.setDesignerId(t.getDesignerId());
        dto.setDesignerName(t.getDesignerName());
        dto.setPointRuleCode(t.getPointRuleCode());
        dto.setDifficultyCode(t.getDifficultyCode());
        dto.setDifficultyMultiplierSnapshot(t.getDifficultyMultiplierSnapshot());
        dto.setBasePointSnapshot(t.getBasePointSnapshot());
        dto.setQualityBonusThresholdSnapshot(t.getQualityBonusThresholdSnapshot());
        dto.setQualityBonusRatioSnapshot(t.getQualityBonusRatioSnapshot());
        dto.setQualityTopThresholdSnapshot(t.getQualityTopThresholdSnapshot());
        dto.setQualityTopRatioSnapshot(t.getQualityTopRatioSnapshot());
        dto.setMaxTotalMultiplierSnapshot(t.getMaxTotalMultiplierSnapshot());
        dto.setCollaboratorAllocationsJson(t.getCollaboratorAllocationsJson());
        dto.setMilestoneMonth(t.getMilestoneMonth());
        dto.setAssignmentReason(t.getAssignmentReason());
        dto.setSelfInitiated(t.isSelfInitiated());
        dto.setSelfInitiatedApproved(t.isSelfInitiatedApproved());
        dto.setCountInPerformanceSnapshot(t.getCountInPerformanceSnapshot());
        dto.setRequiredSkillTagsJson(t.getRequiredSkillTagsJson());
        dto.setAllocationStatus(t.getAllocationStatus());
        dto.setMarketPublishedAt(t.getMarketPublishedAt() == null ? null : t.getMarketPublishedAt().format(DTF));
        dto.setClaimedAt(t.getClaimedAt() == null ? null : t.getClaimedAt().format(DTF));
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
