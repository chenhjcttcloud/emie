package com.emie.designpm.project.controller;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.admin.service.UserService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.dto.ApiErrorResponse;
import com.emie.designpm.dto.PageResponse;
import com.emie.designpm.dto.ProjectDetailDTO;
import com.emie.designpm.dto.ProjectListQuery;
import com.emie.designpm.dto.ProjectSummaryDTO;
import com.emie.designpm.entity.*;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.project.service.ProjectAccessService;
import com.emie.designpm.project.service.ProjectLifecycleCommandService;
import com.emie.designpm.project.service.ProjectService;
import com.emie.designpm.project.service.ProjectViewSupport;
import com.emie.designpm.project.service.ProjectWorkflowService;
import com.emie.designpm.project.service.SubTaskCommandService;
import com.emie.designpm.scoring.repository.ScoringRepository;
import com.emie.designpm.util.ProjectAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final SubTaskCommandService subTaskCommandService;
    private final ProjectLifecycleCommandService projectLifecycleCommandService;
    private final ActivityLogRepository activityLogRepository;
    private final SubTaskRepository subTaskRepository;
    private final ProjectAccessService projectAccessService;
    private final ProjectWorkflowService projectWorkflowService;
    private final PermissionService permissionService;
    private final ProjectViewSupport view;
    private final ProjectRequestSupport req;

    @Autowired(required = false)
    private UserService userService;

    @Autowired(required = false)
    private com.emie.designpm.feishu.service.FeishuChatService feishuChatService;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    public ProjectController(
            ProjectService projectService,
            ActivityLogRepository activityLogRepository,
            SubTaskRepository subTaskRepository,
            ProjectAccessService projectAccessService,
            ProjectWorkflowService projectWorkflowService,
            PermissionService permissionService,
            SubTaskCommandService subTaskCommandService,
            ProjectLifecycleCommandService projectLifecycleCommandService,
            ProjectViewSupport view,
            ProjectRequestSupport req) {
        this.projectService = projectService;
        this.subTaskCommandService = subTaskCommandService;
        this.projectLifecycleCommandService = projectLifecycleCommandService;
        this.activityLogRepository = activityLogRepository;
        this.subTaskRepository = subTaskRepository;
        this.projectAccessService = projectAccessService;
        this.projectWorkflowService = projectWorkflowService;
        this.permissionService = permissionService;
        this.view = view;
        this.req = req;
    }

    ProjectController(
            ProjectService projectService,
            ScoringRepository scoringRepository,
            ActivityLogRepository activityLogRepository,
            SubTaskRepository subTaskRepository,
            ProjectAccessService projectAccessService,
            ProjectWorkflowService projectWorkflowService,
            PermissionService permissionService,
            SubTaskCommandService subTaskCommandService,
            ProjectLifecycleCommandService projectLifecycleCommandService) {
        this(
                projectService,
                activityLogRepository,
                subTaskRepository,
                projectAccessService,
                projectWorkflowService,
                permissionService,
                subTaskCommandService,
                projectLifecycleCommandService,
                new ProjectViewSupport(
                        projectService,
                        scoringRepository,
                        activityLogRepository,
                        subTaskRepository,
                        projectAccessService,
                        projectWorkflowService,
                        subTaskCommandService),
                new ProjectRequestSupport(permissionService));
    }

    /** 保留给轻量 Controller 单元测试；生产运行始终使用完整依赖构造器。 */
    ProjectController(
            ProjectService projectService,
            ScoringRepository scoringRepository,
            ActivityLogRepository activityLogRepository,
            SubTaskRepository subTaskRepository,
            ProjectAccessService projectAccessService,
            ProjectWorkflowService projectWorkflowService,
            PermissionService permissionService) {
        this(
                projectService,
                scoringRepository,
                activityLogRepository,
                subTaskRepository,
                projectAccessService,
                projectWorkflowService,
                permissionService,
                ProjectTaskController.unsupportedSubTaskCommands(),
                unsupportedProjectLifecycle());
    }

    /** 保留给轻量 Controller 单元测试；生产运行始终使用完整依赖构造器。 */
    ProjectController(
            ProjectService projectService,
            ScoringRepository scoringRepository,
            ActivityLogRepository activityLogRepository,
            SubTaskRepository subTaskRepository,
            ProjectAccessService projectAccessService,
            ProjectWorkflowService projectWorkflowService) {
        this(
                projectService,
                scoringRepository,
                activityLogRepository,
                subTaskRepository,
                projectAccessService,
                projectWorkflowService,
                null,
                ProjectTaskController.unsupportedSubTaskCommands(),
                unsupportedProjectLifecycle());
    }

    private static ProjectLifecycleCommandService unsupportedProjectLifecycle() {
        return new ProjectLifecycleCommandService() {
            private UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException("轻量测试未注入生命周期命令服务");
            }

            public Project terminateProject(Long id, Map<String, Object> body) {
                throw unsupported();
            }

            public Project cancelTerminate(Long id, Map<String, Object> body) {
                throw unsupported();
            }

            public Project pauseProject(Long id, Map<String, Object> body) {
                throw unsupported();
            }

            public Project resumeProject(Long id, Map<String, Object> body) {
                throw unsupported();
            }

            public void deleteProject(Long id) {
                throw unsupported();
            }
        };
    }

    /** 获取所有项目列表（轻量版：计数查询代替 JOIN FETCH） */
    @GetMapping
    public ResponseEntity<List<ProjectSummaryDTO>> getProjects(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "false") boolean participating,
            HttpServletRequest request) {

        AuthSession session = getSession(request);
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
            projects = projects.stream().filter(p -> type.equals(p.getType())).collect(Collectors.toList());
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
        AuthSession session = getSession(request);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        try {
            ProjectListQuery query = new ProjectListQuery(
                    normalizeType(type),
                    normalizeStatus(status),
                    trimToNull(category, 50),
                    normalizeMarket(market),
                    trimToNull(keyword, 100),
                    normalizeDate(deadlineStart),
                    normalizeDate(deadlineEnd),
                    participating,
                    PageRequest.of(safePage, safeSize),
                    normalizeOwnerRole(ownerRole),
                    trimToNull(ownerId, 100));
            if (query.deadlineStart() != null
                    && query.deadlineEnd() != null
                    && query.deadlineStart().compareTo(query.deadlineEnd()) > 0) {
                return ResponseEntity.badRequest().body(ApiErrorResponse.invalidQuery("开始日期不能晚于结束日期"));
            }
            Page<Project> projectPage = projectService.getProjectsPage(session.role(), session.userId(), query);
            List<Project> projects = projectPage.getContent();
            Map<Long, int[]> taskCountMap = projectService.getTaskCountMap(projects);
            Map<Long, Double> scoreMap = projectService.computeProjectScoresBatch(projects);
            Map<Long, String> statusMap = projectService.computeProjectStatusMap(projects);
            List<ProjectSummaryDTO> items = projects.stream()
                    .map(p -> toSummary(p, taskCountMap, scoreMap, statusMap))
                    .toList();
            return ResponseEntity.ok(new PageResponse<>(
                    items,
                    projectPage.getNumber(),
                    projectPage.getSize(),
                    projectPage.getTotalElements(),
                    projectPage.getTotalPages()));
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
        Set<String> allowed = Set.of(
                "draft",
                "in_progress",
                "paused",
                "completed",
                "completed_pending_score",
                "pending_planner",
                "pending_terminate",
                "terminated");
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
        AuthSession session = getSession(request);
        if (session == null
                || !("admin".equals(session.role())
                        || "designer".equals(session.role())
                        || "supplychain".equals(session.role())
                        || "planner".equals(session.role()))) {
            return ResponseEntity.status(403).build();
        }
        List<Project> projects = projectAccessService.findVisibleProjectsWithTasks(session);
        List<Long> taskIds = projects.stream()
                .flatMap(p -> p.getTasks().stream())
                .map(SubTask::getId)
                .toList();
        Map<Long, List<Map<String, Object>>> scoringByTask = loadScoringDetails(taskIds);
        return ResponseEntity.ok(
                projects.stream().map(p -> toDetail(p, scoringByTask, false)).toList());
    }

    /** 设计师接单市场：仅返回仍开放、未指定负责人的设计师子任务。 */
    @GetMapping("/task-market")
    public ResponseEntity<?> getTaskMarket(HttpServletRequest request) {
        AuthSession session = getSession(request);
        if (session == null) return ResponseEntity.status(401).build();
        if (!List.of("designer", "planner", "admin").contains(session.role())) {
            return ResponseEntity.status(403).body(Map.of("error", "当前角色无权查看接单市场"));
        }
        List<Map<String, Object>> result = subTaskRepository.findOpenDesignerMarketTasks().stream()
                .map(task -> {
                    Project project = task.getProject();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", task.getId());
                    item.put("name", task.getName());
                    item.put("status", task.getStatus());
                    item.put(
                            "createdAt",
                            task.getCreatedAt() == null
                                    ? null
                                    : task.getCreatedAt().format(DTF));
                    item.put("plannedDate", task.getPlannedDate());
                    item.put("designerId", task.getDesignerId());
                    item.put("pointRuleCode", task.getPointRuleCode());
                    item.put("basePointSnapshot", task.getBasePointSnapshot());
                    item.put("designerName", task.getDesignerName());
                    item.put("publisherId", task.getPublisherId());
                    item.put("publisherName", task.getPublisherName());
                    item.put("publisherRole", task.getPublisherRole());
                    item.put("assigneeRole", task.getAssigneeRole());
                    item.put("allocationStatus", task.getAllocationStatus());
                    item.put("marketPublishedAt", task.getMarketPublishedAt());
                    item.put("details", task.getDetails());
                    item.put("referenceImagesJson", task.getReferenceImagesJson());
                    item.put("attachmentsJson", task.getAttachmentsJson());
                    item.put("projectId", project.getId());
                    item.put("projectType", project.getType());
                    item.put("projectStatus", project.getStatus());
                    item.put("projectName", projectDisplayName(project));
                    item.put("relation", "market");
                    return item;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    /** 获取项目详情 */
    @GetMapping("/{id}")
    public ResponseEntity<ProjectDetailDTO> getProjectDetail(@PathVariable Long id, HttpServletRequest request) {
        AuthSession session = getSession(request);
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
        activityLogRepository.save(new ActivityLog("查询项目 #" + id, session.name(), session.role()));
        return ResponseEntity.ok(toDetailWithLogs(projectOpt.get(), detailLogs));
    }

    /** 新建项目 */
    @PostMapping
    public ResponseEntity<?> createProject(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            AuthSession session = getSession(request);
            if (session == null) return ResponseEntity.status(401).build();
            String type = Objects.toString(body.get("type"), "");
            String permission =
                    switch (type) {
                        case "channel_custom" -> "project.channel.create";
                        case "regular" -> "project.regular.create";
                        default -> null;
                    };
            if (permission == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "不支持的项目类型"));
            }
            if (permissionService != null && !permissionService.has(session.role(), permission)) {
                return ResponseEntity.status(403).body(Map.of("error", "当前账号没有新建此类项目的权限", "permission", permission));
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
            AuthSession session = getSession(request);
            Project p = projectService.getProjectById(id).orElseThrow(() -> new RuntimeException("项目不存在"));
            if (!canCreateProjectChat(p, session))
                return ResponseEntity.status(403).body(Map.of("error", "无权创建该项目群"));
            if (feishuChatService == null || !feishuChatService.enabled())
                return ResponseEntity.badRequest().body(Map.of("error", "飞书应用配置未完成"));
            if (p.getFeishuChatId() != null && !p.getFeishuChatId().isBlank()) return ResponseEntity.ok(toDetail(p));
            String owner = "channel_custom".equals(p.getType())
                    ? (p.getSalesName() + "（销售）")
                    : (p.getPlannerName() + "（产品企划）");
            String name = ("channel_custom".equals(p.getType()) ? "定制" : "常规") + "-#" + p.getId() + "-" + owner;
            String chatId = feishuChatService.createChat(name, List.of());
            p.setFeishuChatEnabled(true);
            p.setFeishuChatId(chatId);
            p.setFeishuChatStatus("created");
            p.setFeishuChatError(null);
            p.setFeishuChatCreatedAt(java.time.LocalDateTime.now());
            syncProjectChatMembers(p);
            return ResponseEntity.ok(toDetail(projectService.saveProject(p)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/feishu-chat/dissolve")
    public ResponseEntity<?> dissolveProjectChat(@PathVariable Long id, HttpServletRequest request) {
        try {
            AuthSession session = getSession(request);
            Project p = projectService.getProjectById(id).orElseThrow(() -> new RuntimeException("项目不存在"));
            if (!canManageProjectChat(p, session))
                return ResponseEntity.status(403).body(Map.of("error", "无权解散该项目群"));
            if (!List.of("completed", "terminated", "pending_terminate").contains(p.getStatus()))
                return ResponseEntity.badRequest().body(Map.of("error", "项目未完成或未终止，不能解散群聊"));
            feishuChatService.dissolve(p.getFeishuChatId());
            p.setFeishuChatStatus("dissolved");
            p.setFeishuChatDissolvedAt(java.time.LocalDateTime.now());
            return ResponseEntity.ok(toDetail(projectService.saveProject(p)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean canManageProjectChat(Project p, AuthSession s) {
        if (s == null) return false;
        return "admin".equals(s.role())
                || ("channel_custom".equals(p.getType())
                        ? Objects.equals(p.getSalesId(), s.userId())
                        : Objects.equals(p.getPlannerId(), s.userId()));
    }

    /** 产品企划可为任意渠道定制或公司常规品项目补建群；原项目负责人权限保持不变。 */
    private boolean canCreateProjectChat(Project p, AuthSession s) {
        if (s == null || p == null || !List.of("channel_custom", "regular").contains(p.getType())) return false;
        return "planner".equals(s.role()) || canManageProjectChat(p, s);
    }

    /** 编辑已创建项目的基础资料。权限不复用通用管理权限，严格限制为项目归属创建人。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateProjectInformation(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            AuthSession session = getSession(request);
            if (session == null) return ResponseEntity.status(401).build();
            Project project = projectService.getProjectById(id).orElseThrow(() -> new RuntimeException("项目不存在"));
            String permission =
                    switch (project.getType()) {
                        case "channel_custom" -> "project.channel.edit";
                        case "regular" -> "project.regular.edit";
                        default -> null;
                    };
            if (permission == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "不支持的项目类型"));
            }
            if (permissionService != null && !permissionService.has(session.role(), permission)) {
                return ResponseEntity.status(403).body(Map.of("error", "当前账号没有编辑此类项目的权限", "permission", permission));
            }
            if (!ProjectAccessPolicy.canEditProjectInformation(project, session)) {
                return ResponseEntity.status(403)
                        .body(Map.of(
                                "error",
                                "仅该项目的" + ("channel_custom".equals(project.getType()) ? "销售" : "产品企划") + "可编辑项目信息"));
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
            @PathVariable Long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        ResponseEntity<?> denied = denyUnless(request, "project.accept");
        if (denied != null) return denied;
        Map<String, Object> safeBody = withSessionContext(new LinkedHashMap<>(body), request);
        Project p = projectService.plannerAccept(
                id,
                (String) safeBody.getOrDefault("currentUser", ""),
                (String) safeBody.getOrDefault("currentRole", ""),
                (String) safeBody.getOrDefault("userId", ""));
        return ResponseEntity.ok(toDetail(p));
    }

    /** 添加子任务 */
    @PostMapping("/{id}/tasks")
    public ResponseEntity<?> addTask(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            AuthSession session = getSession(request);
            if (permissionService != null && !permissionService.has(session.role(), "subtask.create")) {
                return ResponseEntity.status(403)
                        .body(Map.of(
                                "error", "当前账号没有新建子任务的权限",
                                "permission", "subtask.create"));
            }
            Project p = subTaskCommandService.addSubTask(id, withSessionContext(body, request));
            if (p.isFeishuChatEnabled()) {
                ensureProjectChat(p);
                syncProjectChatMembers(p);
            }
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 建群与业务写入解耦：飞书暂时不可用时不阻断项目/子任务保存。 */
    private void ensureProjectChat(Project p) {
        if (p == null
                || feishuChatService == null
                || !feishuChatService.enabled()
                || (p.getFeishuChatId() != null && !p.getFeishuChatId().isBlank())) return;
        try {
            String owner = "channel_custom".equals(p.getType())
                    ? (p.getSalesName() + "（销售）")
                    : (p.getPlannerName() + "（产品企划）");
            String name = ("channel_custom".equals(p.getType()) ? "定制" : "常规") + "-#" + p.getId() + "-" + owner;
            String chatId = feishuChatService.createChat(name, collectProjectOpenIds(p));
            p.setFeishuChatId(chatId);
            p.setFeishuChatStatus("created");
            p.setFeishuChatError(null);
            p.setFeishuChatCreatedAt(java.time.LocalDateTime.now());
            projectService.saveProject(p);
        } catch (Exception e) {
            p.setFeishuChatStatus("failed");
            p.setFeishuChatError(e.getMessage());
            projectService.saveProject(p);
        }
    }

    private void syncProjectChatMembers(Project p) {
        if (p == null
                || feishuChatService == null
                || p.getFeishuChatId() == null
                || p.getFeishuChatId().isBlank()) return;
        try {
            feishuChatService.addMembers(p.getFeishuChatId(), collectProjectOpenIds(p));
        } catch (Exception e) {
            p.setFeishuChatStatus("failed");
            p.setFeishuChatError(e.getMessage());
            projectService.saveProject(p);
        }
    }

    private Collection<String> collectProjectOpenIds(Project p) {
        Set<String> ids = new LinkedHashSet<>();
        if (userService == null) return ids;
        for (String uid : Arrays.asList(p.getPlannerId(), p.getSalesId())) {
            if (uid == null || uid.isBlank()) continue;
            User u = userService.getUserByUserId(uid);
            if (u != null && u.getFeishuOpenId() != null && !u.getFeishuOpenId().isBlank())
                ids.add(u.getFeishuOpenId());
        }
        if (p.getTasks() != null)
            for (SubTask t : p.getTasks()) {
                for (String uid : Arrays.asList(t.getDesignerId(), t.getPublisherId())) {
                    if (uid == null || uid.isBlank()) continue;
                    User u = userService.getUserByUserId(uid);
                    if (u != null
                            && u.getFeishuOpenId() != null
                            && !u.getFeishuOpenId().isBlank()) ids.add(u.getFeishuOpenId());
                }
            }
        return ids;
    }

    @PostMapping("/{id}/workflow/complete-execution")
    public ResponseEntity<?> completeWorkflowExecution(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<?> denied = denyUnless(request, "project.workflow.advance");
        if (denied != null) return denied;
        AuthSession session = getSession(request);
        try {
            Project project =
                    projectWorkflowService.completeExecution(id, session.userId(), session.name(), session.role());
            return ResponseEntity.ok(projectWorkflowService.build(project));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/workflow/submit-review")
    public ResponseEntity<?> submitWorkflowReview(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<?> denied = denyUnless(request, "project.workflow.advance");
        if (denied != null) return denied;
        AuthSession session = getSession(request);
        try {
            return ResponseEntity.ok(
                    projectWorkflowService.submitReview(id, session.userId(), session.name(), session.role()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/workflow/review")
    public ResponseEntity<?> reviewWorkflow(
            @PathVariable Long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        ResponseEntity<?> denied = denyUnless(request, "project.workflow.review");
        if (denied != null) return denied;
        AuthSession session = getSession(request);
        try {
            return ResponseEntity.ok(projectWorkflowService.review(
                    id, body.get("decision"), body.get("comment"), session.userId(), session.name(), session.role()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 通用角色状态看板（sales/planner/supplychain/designer） */
    @GetMapping("/role-status")
    public ResponseEntity<Map<String, Object>> roleStatus(
            @RequestParam String role, @RequestParam(defaultValue = "all") String scope, HttpServletRequest request) {
        AuthSession session = getSession(request);
        if (session == null) return ResponseEntity.status(401).build();
        boolean allowed = "admin".equals(session.role())
                || Objects.equals(session.role(), role)
                || ("planner".equals(session.role())
                        && Set.of("planner", "promotion", "designer", "supplychain")
                                .contains(role));
        if (!allowed) {
            return ResponseEntity.status(403).body(Map.of("error", "无权查看该角色的状态看板"));
        }
        return ResponseEntity.ok(projectService.getRoleStatus(role, session.role(), session.userId(), scope));
    }

    /** 设计师状态看板（兼容旧版） */
    @GetMapping("/designer-status")
    public ResponseEntity<Map<String, Object>> designerStatus(HttpServletRequest request) {
        AuthSession session = getSession(request);
        if (session == null) return ResponseEntity.status(401).build();
        if (!List.of("admin", "designer").contains(session.role())) {
            return ResponseEntity.status(403).body(Map.of("error", "无权查看设计师状态看板"));
        }
        return ResponseEntity.ok(projectService.getDesignerStatus(session.role(), session.userId()));
    }

    /** 左侧导航徽章统计：角色与用户由会话确定，禁止客户端伪造统计范围。 */
    @GetMapping("/badge-stats")
    public ResponseEntity<Map<String, Long>> badgeStats(HttpServletRequest request) {
        AuthSession session = getSession(request);
        return ResponseEntity.ok(projectService.getNavigationBadgeStats(session.role(), session.userId()));
    }

    /** 终止项目 */
    @PostMapping("/{id}/terminate")
    public ResponseEntity<?> terminateProject(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "project.terminate");
            if (denied != null) return denied;
            ResponseEntity<?> projectDenied = denyUnlessProjectManager(id, request);
            if (projectDenied != null) return projectDenied;
            Project p = projectLifecycleCommandService.terminateProject(id, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 暂停项目 */
    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pauseProject(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "project.pause");
            if (denied != null) return denied;
            ResponseEntity<?> projectDenied = denyUnlessProjectManager(id, request);
            if (projectDenied != null) return projectDenied;
            Project p = projectLifecycleCommandService.pauseProject(id, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 取消终止 */
    @PostMapping("/{id}/cancel-terminate")
    public ResponseEntity<?> cancelTerminate(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "project.resume");
            if (denied != null) return denied;
            ResponseEntity<?> projectDenied = denyUnlessProjectManager(id, request);
            if (projectDenied != null) return projectDenied;
            Project p = projectLifecycleCommandService.cancelTerminate(id, withSessionContext(body, request));
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 继续项目 */
    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resumeProject(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = denyUnless(request, "project.resume");
            if (denied != null) return denied;
            ResponseEntity<?> projectDenied = denyUnlessProjectManager(id, request);
            if (projectDenied != null) return projectDenied;
            Project p = projectLifecycleCommandService.resumeProject(id, withSessionContext(body, request));
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
            ResponseEntity<?> projectDenied = denyUnlessProjectManager(id, request);
            if (projectDenied != null) return projectDenied;
            projectLifecycleCommandService.deleteProject(id);
            return ResponseEntity.ok(Map.of("message", "项目已删除"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> denyUnless(HttpServletRequest request, String permission) {
        return req.denyUnless(request, permission);
    }

    private ResponseEntity<?> denyUnlessProjectManager(Long projectId, HttpServletRequest request) {
        AuthSession session = getSession(request);
        Project project = projectService.getProjectById(projectId).orElseThrow(() -> new RuntimeException("项目不存在"));
        return ProjectAccessPolicy.canManage(project, session)
                ? null
                : ResponseEntity.status(403).body(Map.of("error", "无权操作该项目"));
    }

    // ==================== DTO Mappers ====================

    private ProjectSummaryDTO toSummary(
            Project p, Map<Long, int[]> taskCountMap, Map<Long, Double> scoreMap, Map<Long, String> statusMap) {
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
        dto.setProductCategory(
                p.getProductCategory() != null ? p.getProductCategory().getName() : null);
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

    private ProjectDetailDTO toDetail(Project project) {
        return view.toDetail(project);
    }

    private ProjectDetailDTO toDetailWithLogs(Project project, List<ActivityLog> logs) {
        return view.toDetailWithLogs(project, logs);
    }

    private ProjectDetailDTO toDetail(
            Project project, Map<Long, List<Map<String, Object>>> scoring, boolean includeLogs) {
        return view.toDetail(project, scoring, includeLogs);
    }

    private Map<Long, List<Map<String, Object>>> loadScoringDetails(List<Long> taskIds) {
        return view.loadScoringDetails(taskIds);
    }

    private String projectDisplayName(Project project) {
        return view.projectDisplayName(project);
    }

    private AuthSession getSession(HttpServletRequest request) {
        return req.getSession(request);
    }

    private Map<String, Object> withSessionContext(Map<String, Object> body, HttpServletRequest request) {
        return req.withSessionContext(body, request);
    }
}
