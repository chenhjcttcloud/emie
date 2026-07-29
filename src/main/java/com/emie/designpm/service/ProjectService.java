package com.emie.designpm.service;

import com.emie.designpm.dto.ProjectListQuery;
import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import com.emie.designpm.util.SecurityUtil;
import com.emie.designpm.util.ProjectAccessPolicy;
import com.emie.designpm.controller.AuthController;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Service
@Transactional
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final Object PROJECT_CODE_LOCK = new Object();

    private final ProjectRepository projectRepository;
    private final SubTaskRepository subTaskRepository;
    private final ScoringRepository scoringRepository;
    private final SubTaskDeliveryVersionRepository deliveryVersionRepository;
    private final UserService userService;
    private final ProductCategoryRepository productCategoryRepository;
    private final IpOptionRepository ipOptionRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final SyncQueueService syncQueueService;
    private final FileArchiveService fileArchiveService;
    private final ProjectAccessService projectAccessService;
    private final NotificationWorkflowService notificationWorkflowService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProjectService(ProjectRepository projectRepository,
                          SubTaskRepository subTaskRepository,
                          ScoringRepository scoringRepository,
                          SubTaskDeliveryVersionRepository deliveryVersionRepository,
                          UserService userService,
                          ProductCategoryRepository productCategoryRepository,
                          IpOptionRepository ipOptionRepository,
                          SystemConfigRepository systemConfigRepository,
                          SyncQueueService syncQueueService,
                          FileArchiveService fileArchiveService,
                          ProjectAccessService projectAccessService, NotificationWorkflowService notificationWorkflowService) {
        this.projectRepository = projectRepository;
        this.subTaskRepository = subTaskRepository;
        this.scoringRepository = scoringRepository;
        this.deliveryVersionRepository = deliveryVersionRepository;
        this.userService = userService;
        this.productCategoryRepository = productCategoryRepository;
        this.ipOptionRepository = ipOptionRepository;
        this.systemConfigRepository = systemConfigRepository;
        this.syncQueueService = syncQueueService;
        this.fileArchiveService = fileArchiveService;
        this.projectAccessService = projectAccessService;
        this.notificationWorkflowService = notificationWorkflowService;
    }

    // ==================== Query ====================

    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    public Optional<Project> getProjectById(Long id) {
        return projectRepository.findById(id);
    }

    /** 获取项目列表（轻量版，不使用 JOIN FETCH tasks） */
    public List<Project> getProjectsByRoleAndUser(String role, String userId) {
        return projectAccessService.findVisibleProjectsLight(role, userId);
    }

    /** 设计师已参与的项目（轻量版） */
    public List<Project> getAssigneeParticipatingProjects(String userId, String role) {
        return projectAccessService.findParticipatingProjectsLight(role, userId);
    }

    public Page<Project> getProjectsPage(String role, String userId, String type, boolean participating, Pageable pageable) {
        return projectAccessService.findVisibleProjectsPage(role, userId, type, participating, pageable);
    }

    public Page<Project> getProjectsPage(String role, String userId, ProjectListQuery query) {
        return projectAccessService.findVisibleProjectsPage(role, userId, query);
    }

    public long countVisibleProjects(String role, String userId, String type, boolean participating) {
        return projectAccessService.countVisibleProjects(role, userId, type, participating);
    }

    public List<Long> findVisibleProjectIds(String role, String userId) {
        return projectAccessService.findVisibleProjectIds(role, userId);
    }

    /**
     * 左侧导航唯一统计口径：项目数量与项目列表一致，待评分数量与评分中心一致。
     * 执行角色的项目页只展示已参与项目，因此此处不混入待认领的公共项目。
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getNavigationBadgeStats(String role, String userId) {
        boolean participating = List.of("designer", "supplychain", "sales").contains(role);
        long channelCount = projectAccessService.countVisibleProjects(role, userId, "channel_custom", participating);
        long regularCount = projectAccessService.countVisibleProjects(role, userId, "regular", participating);
        long totalCount = channelCount + regularCount;
        long myTaskCount = List.of("designer", "supplychain", "sales").contains(role)
                ? subTaskRepository.countByDesignerIdAndRoleAndActionableStatus(userId, role)
                : 0;
        long pendingScoreCount = countPendingScoresForBadge(role, userId);

        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("totalCount", totalCount);
        stats.put("channelCount", channelCount);
        stats.put("regularCount", regularCount);
        stats.put("myTaskCount", myTaskCount);
        stats.put("pendingScoreCount", pendingScoreCount);
        return stats;
    }

    private long countPendingScoresForBadge(String role, String userId) {
        if (!List.of("admin", "planner", "sales").contains(role)) return 0L;
        List<String> visibleUserIds = projectAccessService.visibleUserIds(role, userId, role);
        if ("admin".equals(role)) return scoringRepository.countPendingForRole(role);
        if (visibleUserIds.isEmpty()) return 0L;
        return "sales".equals(role)
                ? scoringRepository.countPendingForSales(role, visibleUserIds)
                : scoringRepository.countPendingForPlanners(role, visibleUserIds);
    }

    /** 批量获取子任务统计（projectId → {taskCount, approvedCount}） */
    public Map<Long, int[]> getTaskCountMap(List<Project> projects) {
        if (projects == null || projects.isEmpty()) return Collections.emptyMap();
        List<Long> ids = projects.stream().map(Project::getId).collect(Collectors.toList());
        List<Object[]> rows = subTaskRepository.countTasksByProjectIds(ids);
        Map<Long, int[]> map = new HashMap<>(ids.size());
        for (Object[] row : rows) {
            Long pid = (Long) row[0];
            int total = ((Number) row[1]).intValue();
            int done = ((Number) row[2]).intValue();
            int actionable = row.length > 3 ? ((Number) row[3]).intValue() : 0;
            map.put(pid, new int[]{total, done, actionable});
        }
        // 没有子任务的项目也补 0
        for (Project p : projects) {
            map.putIfAbsent(p.getId(), new int[]{0, 0, 0});
        }
        return map;
    }

    public List<Project> getProjectsByType(String type) {
        return projectRepository.findByTypeOrderByCreatedAtDesc(type);
    }

    // ==================== Create ====================

    /**
     * 验证并清理文件上传JSON，移除非法文件
     */
    private String validateAndCleanFiles(String json, boolean isImage) {
        if (json == null || json.isBlank()) return "[]";
        // 整体JSON过大直接拒绝，防止OOM
        if (json.length() > 700_000_000) return "[]"; // ~500MB原始文件总量

        final int maxCount = isImage ? 9 : 5;

        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> files = mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            List<Map<String, Object>> cleaned = files.stream()
                .filter(f -> {
                    String name = (String) f.get("name");
                    if (name == null) return false;
                    return isImage ? SecurityUtil.isValidImageFile(name) : SecurityUtil.isValidAttachmentFile(name);
                })
                .filter(f -> {
                    // 只保留有url引用的文件（已上传到服务端）
                    String url = (String) f.get("url");
                    return url != null && !url.isEmpty();
                })
                .limit(maxCount)
                .collect(Collectors.toList());
            return mapper.writeValueAsString(cleaned);
        } catch (Exception e) {
            return "[]";
        }
    }

    public Project createProject(Map<String, Object> body) {
        return createProject(body, false);
    }

    /** 仅供受控历史批量导入使用；避免对既有负责人一次性发送大量实时通知。 */
    public Project createImportedProject(Map<String, Object> body) {
        return createProject(body, true);
    }

    private Project createProject(Map<String, Object> body, boolean suppressNotifications) {
        String type = (String) body.get("type");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        String plannerId = SecurityUtil.sanitizeText((String) body.get("plannerId"), 100);
        String salesId = SecurityUtil.sanitizeText((String) body.get("salesId"), 100);
        String productName = SecurityUtil.sanitizeText((String) body.get("productName"), 200);
        String deadline = (String) body.get("deadline");
        String productRequirements = SecurityUtil.sanitizeText((String) body.get("productRequirements"), 2000);
        String description = SecurityUtil.sanitizeText((String) body.getOrDefault("description", ""), 2000);

        if ("channel_custom".equals(type) && !List.of("sales", "admin").contains(currentRole)) {
            throw new RuntimeException("仅销售或管理员可创建渠道定制项目");
        }
        if ("regular".equals(type) && !List.of("planner", "admin").contains(currentRole)) {
            throw new RuntimeException("仅企划或管理员可创建常规品项目");
        }
        if (productName == null || productName.isBlank()) {
            throw new RuntimeException("产品名称不能为空");
        }

        // 普通用户只能把项目归属到自己；管理员才可以代其他成员创建。
        if ("sales".equals(currentRole) && "channel_custom".equals(type)) {
            salesId = currentUserId;
        }
        if ("planner".equals(currentRole) && "regular".equals(type)) {
            plannerId = currentUserId;
        }

        // 验证并清理文件上传
        String refImagesJson = validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true);
        String attsJson = validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false);

        Project p = new Project();
        p.setProjectCode(nextProjectCode(LocalDateTime.now()));
        p.setType(type);
        p.setPlannerId(plannerId);
        p.setPlannerName(plannerId != null && !plannerId.isEmpty() ? userService.getUserName(plannerId) : "");

        if ("channel_custom".equals(type)) {
            p.setSalesId(salesId);
            p.setSalesName(userService.getUserName(salesId));
            p.setStatus("pending_planner");
        } else {
            p.setStatus("planner_accepted");
        }

        p.setDeadline(deadline);
        p.setProductName(productName.trim());
        p.setProductRequirements(productRequirements);
        p.setDescription(description);

        // 通用字段（所有项目类型）
        String catName = (String) body.get("productCategory");
        if (catName != null && !catName.isBlank()) {
            productCategoryRepository.findByName(catName).ifPresent(p::setProductCategory);
        }
        String note = (String) body.get("productCategoryNote");
        if (note != null && !note.isBlank()) {
            p.setProductCategoryNote(SecurityUtil.sanitizeText(note, 500));
        }
        p.setTargetMarket(SecurityUtil.sanitizeText((String) body.get("targetMarket"), 100));
        String complianceStr = (String) body.get("complianceItems");
        if (complianceStr != null && !complianceStr.isBlank()) {
            p.setComplianceItems(SecurityUtil.sanitizeText(complianceStr, 500));
        }
        String priceRangeStr = (String) body.get("priceRange");
        if (priceRangeStr != null && !priceRangeStr.isBlank()) {
            p.setPriceRange(priceRangeStr);
        }
        String ipName = SecurityUtil.sanitizeText((String) body.get("ipName"), 100);
        if (ipName != null && !ipName.isBlank()) {
            IpOption ipOption = ipOptionRepository.findByName(ipName.trim())
                    .filter(option -> Boolean.TRUE.equals(option.getActive()))
                    .orElseThrow(() -> new RuntimeException("请选择有效的IP"));
            p.setIpName(ipOption.getName());
            p.setIpSubOptions(validateIpSubOptions((String) body.get("ipSubOptions"), ipOption));
        }

        p.setReferenceImagesJson(refImagesJson);
        p.setAttachmentsJson(attsJson);

        // Log
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String logAction = "channel_custom".equals(type)
                ? "销售提交渠道定制项目" : "产品企划新建常规品设计项目";
        p.getLogs().add(new ActivityLog(logAction, currentUser, currentRole, p));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(refImagesJson, "project", saved.getId());
        fileArchiveService.bindFilesFromJson(attsJson, "project", saved.getId());
        // 批量历史导入不应向每位负责人逐条发送即时通知；导入本身仍保留操作日志与同步记录。
        if (!suppressNotifications) {
            safeNotify("PROJECT_ASSIGNED", saved.getPlannerId(), "project", saved.getId(), currentUserId,
                    notificationContext(saved, null, currentUser, ""));
        }
        return saved;
    }

    /** 单实例下串行分配月序号；数据库唯一约束负责最终兜底。 */
    private String nextProjectCode(LocalDateTime now) {
        synchronized (PROJECT_CODE_LOCK) {
            LocalDateTime start = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
            LocalDateTime end = start.plusMonths(1);
            long sequence = projectRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(start, end) + 1;
            if (sequence > 9999) throw new RuntimeException("本月项目编号已超过 9999 个");
            return String.format("EMIE%04d%02d%04d", now.getYear(), now.getMonthValue(), sequence);
        }
    }

    // ==================== Project Information Edit ====================

    /** 编辑项目资料；项目类型和销售/企划归属在创建后不可通过本接口变更。 */
    public Project updateProjectInformation(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        AuthController.AuthSession session = new AuthController.AuthSession(
                (String) body.getOrDefault("currentUserId", ""),
                (String) body.getOrDefault("currentRole", ""),
                (String) body.getOrDefault("currentUser", ""));
        if (!ProjectAccessPolicy.canEditProjectInformation(p, session)) {
            throw new SecurityException("仅该项目的" + ("channel_custom".equals(p.getType()) ? "销售" : "产品企划") + "可编辑项目信息");
        }

        Map<String, Object> before = snapshotProject(p);
        String productName = SecurityUtil.sanitizeText((String) body.get("productName"), 200);
        String productRequirements = SecurityUtil.sanitizeText((String) body.get("productRequirements"), 2000);
        String deadline = SecurityUtil.sanitizeText((String) body.get("deadline"), 20);
        if (productName == null || productName.isBlank()) throw new RuntimeException("产品名称不能为空");
        if (productRequirements == null || productRequirements.isBlank()) throw new RuntimeException("产品要求不能为空");
        if (deadline == null || deadline.isBlank()) throw new RuntimeException("要求完成时间不能为空");

        p.setProductName(productName.trim());
        p.setProductRequirements(productRequirements);
        p.setDeadline(deadline);
        p.setDescription(SecurityUtil.sanitizeText((String) body.getOrDefault("description", ""), 2000));

        String categoryName = SecurityUtil.sanitizeText((String) body.get("productCategory"), 100);
        if (categoryName == null || categoryName.isBlank()) {
            p.setProductCategory(null);
        } else {
            p.setProductCategory(productCategoryRepository.findByName(categoryName.trim())
                    .orElseThrow(() -> new RuntimeException("请选择有效的产品类目")));
        }
        p.setProductCategoryNote(SecurityUtil.sanitizeText((String) body.getOrDefault("productCategoryNote", ""), 500));
        p.setTargetMarket(SecurityUtil.sanitizeText((String) body.getOrDefault("targetMarket", ""), 100));
        p.setComplianceItems(SecurityUtil.sanitizeText((String) body.getOrDefault("complianceItems", ""), 500));
        p.setPriceRange(SecurityUtil.sanitizeText((String) body.getOrDefault("priceRange", ""), 100));

        String ipName = SecurityUtil.sanitizeText((String) body.getOrDefault("ipName", ""), 100);
        if (ipName == null || ipName.isBlank()) {
            p.setIpName(null);
            p.setIpSubOptions(null);
        } else {
            IpOption ipOption = ipOptionRepository.findByName(ipName.trim())
                    .filter(option -> Boolean.TRUE.equals(option.getActive()))
                    .orElseThrow(() -> new RuntimeException("请选择有效的IP"));
            p.setIpName(ipOption.getName());
            p.setIpSubOptions(validateIpSubOptions((String) body.get("ipSubOptions"), ipOption));
        }

        String referenceImagesJson = validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true);
        String attachmentsJson = validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false);
        p.setReferenceImagesJson(referenceImagesJson);
        p.setAttachmentsJson(attachmentsJson);

        Map<String, Object> after = snapshotProject(p);
        p.getLogs().add(new ActivityLog("编辑项目信息：" + p.getProductName(), session.name(), session.role(), p,
                "project", p.getId(), toJson(before), toJson(after), changedFields(before, after)));
        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(referenceImagesJson, "project", saved.getId());
        fileArchiveService.bindFilesFromJson(attachmentsJson, "project", saved.getId());
        return saved;
    }

    private Map<String, Object> snapshotProject(Project project) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productName", project.getProductName());
        data.put("deadline", project.getDeadline());
        data.put("productRequirements", project.getProductRequirements());
        data.put("description", project.getDescription());
        data.put("productCategory", project.getProductCategory() != null ? project.getProductCategory().getName() : null);
        data.put("productCategoryNote", project.getProductCategoryNote());
        data.put("targetMarket", project.getTargetMarket());
        data.put("complianceItems", project.getComplianceItems());
        data.put("priceRange", project.getPriceRange());
        data.put("ipName", project.getIpName());
        data.put("ipSubOptions", project.getIpSubOptions());
        data.put("referenceImagesJson", project.getReferenceImagesJson());
        data.put("attachmentsJson", project.getAttachmentsJson());
        return data;
    }

    // ==================== Planner Accept ====================

    @Transactional
    public Project plannerAccept(Long projectId, String currentUser, String currentRole, String userId) {
        if (!List.of("planner", "admin").contains(currentRole)) {
            throw new RuntimeException("仅企划或管理员可执行接单");
        }
        // 使用悲观锁锁定项目行，防止并发接单
        Project p = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (!List.of("draft", "pending_planner").contains(p.getStatus())) {
            throw new RuntimeException("当前项目状态不允许接单");
        }

        // 如果之前未指定企划，自动绑定接单的企划
        if ((p.getPlannerId() == null || p.getPlannerId().isBlank()) && userId != null && !userId.isBlank()) {
            p.setPlannerId(userId);
            p.setPlannerName(userService.getUserName(userId));
        } else if (p.getPlannerId() != null && !p.getPlannerId().isBlank() && !p.getPlannerId().equals(userId)) {
            // 已被其他企划接单
            throw new RuntimeException("该项目已被其他产品企划接单");
        }

        p.setStatus("planner_accepted");
        p.getLogs().add(new ActivityLog("产品企划接单，请添加子任务", currentUser, currentRole, p));
        return projectRepository.save(p);
    }

    // ==================== Sub-Task Management ====================

    public Project addSubTask(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        String role = (String) body.getOrDefault("currentRole", "");
        if (!List.of("planner", "admin").contains(role)) {
            throw new RuntimeException("仅企划或管理员可创建子任务");
        }
        if ("planner".equals(role) && !isProjectPlanner(p, body)) {
            throw new RuntimeException("仅项目负责人企划可创建子任务");
        }

        String name = SecurityUtil.sanitizeText((String) body.get("name"), 200);
        String plannedDate = (String) body.get("plannedDate");
        String designerId = SecurityUtil.sanitizeText((String) body.get("designerId"), 100);
        String details = SecurityUtil.sanitizeText((String) body.getOrDefault("details", ""), 2000);
        String workflowStage = SecurityUtil.sanitizeText((String) body.get("workflowStage"), 30);
        if (!ProjectWorkflowService.STAGES.contains(workflowStage)) {
            throw new RuntimeException("请选择有效的子任务所属阶段");
        }

        SubTask task = new SubTask();
        task.setName(name);
        task.setStatus("pending");
        task.setWorkflowStage(workflowStage);
        task.setPlannedDate(plannedDate);
        task.setDesignerId(designerId);
        task.setDesignerName(userService.getUserName(designerId));
        task.setPublisherId((String) body.get("currentUserId"));
        task.setPublisherName((String) body.getOrDefault("currentUser", ""));
        task.setPublisherRole(role);
        // 设置负责人角色类型（designer / supplychain / planner / sales），默认 designer
        String assigneeRole = (String) body.get("assigneeRole");
        task.setAssigneeRole(assigneeRole != null && !assigneeRole.isBlank() ? assigneeRole : "designer");
        validateSubTaskAssignee(designerId, task.getAssigneeRole());
        task.setDetails(details);
        task.setReferenceImagesJson(validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        task.setProject(p);

        boolean firstSubTask = p.getTasks().isEmpty();
        // 先显式持久化子任务，确保后续文件绑定和提交后通知始终使用真实任务 ID。
        // 仅依赖 Project.tasks 的级联保存时，新任务在提交后回调注册阶段仍可能没有 ID，
        // 从而让 notification_events.aggregate_id 的非空约束反向影响创建接口。
        subTaskRepository.saveAndFlush(task);
        if (task.getId() == null) {
            throw new IllegalStateException("子任务保存失败，请稍后重试");
        }
        p.getTasks().add(task);
        if (firstSubTask) {
            p.setWorkflowStage(workflowStage);
            p.setWorkflowStatus("current");
        }
        if ("planner_accepted".equals(p.getStatus())) {
            p.setStatus("in_progress");
        }
        // 已完结项目添加子任务时重新激活
        if ("completed".equals(p.getStatus())) {
            p.setStatus("in_progress");
        }

        String currentUser = (String) body.getOrDefault("currentUser", "");
        p.getLogs().add(new ActivityLog("添加子任务：" + name, currentUser, role, p));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        safeNotifyAfterCommit("TASK_ASSIGNED", task.getDesignerId(), "sub_task", task.getId(),
                (String) body.getOrDefault("currentUserId", ""), notificationContext(saved, task, currentUser, ""));
        return saved;
    }

    private void validateSubTaskAssignee(String userId, String assigneeRole) {
        String normalizedRole = normalizeAssigneeRole(assigneeRole);
        if (!List.of("designer", "supplychain", "planner", "sales", "promotion").contains(normalizedRole)) {
            throw new RuntimeException("不支持的子任务负责人类型");
        }
        if (userId == null || userId.isBlank()) return;
        User assignee = userService.getUserByUserId(userId);
        if (assignee == null || !normalizedRole.equals(normalizeAssigneeRole(assignee.getRole()))) {
            throw new RuntimeException("子任务负责人和负责人类型不匹配");
        }
    }

    private String normalizeAssigneeRole(String role) {
        if (role == null) return "";
        if ("promotion".equalsIgnoreCase(role) || "product_promotion".equalsIgnoreCase(role)
                || "product-promotion".equalsIgnoreCase(role)) return "promotion";
        return role;
    }

    public Project updateSubTask(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        // 销售不允许编辑子任务
        String currentRole = (String) body.getOrDefault("currentRole", "");
        if (!List.of("planner", "admin").contains(currentRole)) {
            throw new RuntimeException("仅企划或管理员可编辑子任务");
        }
        if ("planner".equals(currentRole) && !isProjectPlanner(p, body)) {
            throw new RuntimeException("仅项目负责人企划可编辑子任务");
        }

        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        Map<String, Object> before = snapshotSubTask(task);

        if (body.containsKey("name")) task.setName(SecurityUtil.sanitizeText((String) body.get("name"), 200));
        if (body.containsKey("workflowStage")) {
            String workflowStage = SecurityUtil.sanitizeText((String) body.get("workflowStage"), 30);
            if (!ProjectWorkflowService.STAGES.contains(workflowStage)) {
                throw new RuntimeException("请选择有效的子任务所属阶段");
            }
            task.setWorkflowStage(workflowStage);
        }
        if (body.containsKey("plannedDate")) task.setPlannedDate((String) body.get("plannedDate"));
        if (body.containsKey("designerId")) {
            String did = SecurityUtil.sanitizeText((String) body.get("designerId"), 100);
            task.setDesignerId(did);
            task.setDesignerName(userService.getUserName(did));
        }
        if (body.containsKey("assigneeRole")) {
            task.setAssigneeRole((String) body.get("assigneeRole"));
        }
        validateSubTaskAssignee(task.getDesignerId(), task.getAssigneeRole() == null ? "designer" : task.getAssigneeRole());
        if (body.containsKey("details")) task.setDetails(SecurityUtil.sanitizeText((String) body.get("details"), 2000));
        if (body.containsKey("referenceImagesJson")) task.setReferenceImagesJson(validateAndCleanFiles((String) body.get("referenceImagesJson"), true));
        if (body.containsKey("attachmentsJson")) task.setAttachmentsJson(validateAndCleanFiles((String) body.get("attachmentsJson"), false));

        String currentUser = (String) body.getOrDefault("currentUser", "");
        Map<String, Object> after = snapshotSubTask(task);
        p.getLogs().add(new ActivityLog("编辑子任务：" + task.getName(), currentUser, currentRole, p,
                "sub_task", task.getId(), toJson(before), toJson(after), changedFields(before, after)));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        return saved;
    }

    private Map<String, Object> snapshotSubTask(SubTask task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", task.getName());
        data.put("status", task.getStatus());
        data.put("workflowStage", task.getWorkflowStage());
        data.put("plannedDate", task.getPlannedDate());
        data.put("designerId", task.getDesignerId());
        data.put("designerName", task.getDesignerName());
        data.put("assigneeRole", task.getAssigneeRole());
        data.put("details", task.getDetails());
        return data;
    }

    private Map<String, String> notificationContext(Project project, SubTask task, String actor, String reason) {
        Map<String, String> context = new HashMap<>();
        context.put("projectName", project.getProductName());
        context.put("deadline", task != null ? task.getPlannedDate() : project.getDeadline());
        context.put("actorName", actor == null || actor.isBlank() ? "系统" : actor);
        context.put("projectLink", "/?projectId=" + project.getId());
        if (task != null) {
            context.put("taskName", task.getName());
            context.put("taskLink", "/?projectId=" + project.getId() + "&taskId=" + task.getId());
        }
        if (reason != null && !reason.isBlank()) context.put("reason", reason);
        return context;
    }

    private String validateIpSubOptions(String submittedJson, IpOption ipOption) {
        List<String> configured;
        List<String> selected;
        try {
            configured = objectMapper.readValue(Optional.ofNullable(ipOption.getSubOptionsJson()).orElse("[]"), new TypeReference<List<String>>() {});
            selected = objectMapper.readValue(Optional.ofNullable(submittedJson).orElse("[]"), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new RuntimeException("二级IP选项格式无效");
        }
        if (configured.isEmpty()) return null;
        if (selected.isEmpty()) throw new RuntimeException("请选择二级IP选项");
        if ("single".equals(ipOption.getSubOptionSelectionMode()) && selected.size() != 1) {
            throw new RuntimeException("该IP的二级选项仅允许单选");
        }
        if (selected.stream().anyMatch(value -> value == null || !configured.contains(value))) {
            throw new RuntimeException("请选择有效的二级IP选项");
        }
        try {
            return objectMapper.writeValueAsString(selected.stream().distinct().toList());
        } catch (Exception e) {
            throw new RuntimeException("二级IP选项保存失败");
        }
    }

    private void safeNotify(String eventType, String recipientUserId, String aggregateType, Long aggregateId,
                            String actorUserId, Map<String, String> context) {
        try {
            notificationWorkflowService.notifyUser(eventType, recipientUserId, aggregateType, aggregateId, actorUserId, context);
        } catch (Exception e) {
            log.error("通知创建失败但业务操作继续: eventType={}, aggregate={}#{}", eventType, aggregateType, aggregateId, e);
        }
    }

    private void safeNotifyAfterCommit(String eventType, String recipientUserId, String aggregateType, Long aggregateId,
                                       String actorUserId, Map<String, String> context) {
        try {
            notificationWorkflowService.notifyUserAfterCommit(
                    eventType, recipientUserId, aggregateType, aggregateId, actorUserId, context);
        } catch (Exception e) {
            log.error("提交后通知注册失败但业务操作继续: eventType={}, aggregate={}#{}",
                    eventType, aggregateType, aggregateId, e);
        }
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "{}"; }
    }

    private String changedFields(Map<String, Object> before, Map<String, Object> after) {
        return toJson(before.keySet().stream().filter(k -> !Objects.equals(before.get(k), after.get(k))).toList());
    }

    @Transactional
    public Project deleteSubTask(Long projectId, Long taskId) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        if (!List.of("pending", "pending_planner").contains(p.getStatus()) && !"pending".equals(task.getStatus())) {
            throw new RuntimeException("项目已进入工作流程，无法删除子任务");
        }
        if ("paused".equals(p.getStatus())) {
            throw new RuntimeException("项目已暂停，无法删除子任务");
        }

        // 删除关联的评分记录，并同步删除飞书记录
        List<ScoringRecord> scoringRecords = scoringRepository.findBySubTaskId(taskId);
        scoringRepository.deleteAll(scoringRecords);
        // 从项目中移除子任务
        p.getTasks().remove(task);
        subTaskRepository.delete(task);
        return projectRepository.save(p);
    }

    // ==================== Task Workflow ====================

    @Transactional
    public Project taskAccept(Long projectId, Long taskId, Map<String, Object> body) {
        // 锁定项目行
        Project p = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        // 锁定子任务行
        SubTask task = subTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new RuntimeException("子任务不存在"));

        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId)) {
            throw new RuntimeException("子任务不属于当前项目");
        }

        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String designerUserId = (String) body.get("designerUserId");

        if (designerUserId == null || designerUserId.isBlank()) {
            throw new RuntimeException("当前登录用户无效，无法接单");
        }
        if (task.getAssigneeRole() != null && !task.getAssigneeRole().isBlank()
                && !normalizeAssigneeRole(task.getAssigneeRole()).equals(normalizeAssigneeRole(currentRole))) {
            throw new RuntimeException("当前角色无法接此子任务");
        }

        // 子任务已被他人接单
        if (!"pending".equals(task.getStatus())) {
            throw new RuntimeException("该子任务已被接单或已处理");
        }

        // 如果子任务未指定设计师，自动绑定接单的设计师（防并发）
        if (task.getDesignerId() == null || task.getDesignerId().isBlank()) {
            if (designerUserId != null && !designerUserId.isBlank()) {
                task.setDesignerId(designerUserId);
                task.setDesignerName(userService.getUserName(designerUserId));
                p.getLogs().add(new ActivityLog("设计师接单：" + task.getName() + "（自动绑定" + task.getDesignerName() + "）", currentUser, currentRole, p));
            }
        } else if (!task.getDesignerId().equals(designerUserId)) {
            // 已被其他设计师接单
            String otherName = userService.getUserName(task.getDesignerId());
            throw new RuntimeException("该子任务已被 " + (otherName != null ? otherName : "其他设计师") + " 接单");
        }

        task.setStatus("accepted");
        if (body.containsKey("plannedDate")) task.setPlannedDate((String) body.get("plannedDate"));
        p.setStatus("in_progress");

        p.getLogs().add(new ActivityLog("子任务接单：" + task.getName(), currentUser, currentRole, p));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        return saved;
    }

    public Project taskDeliver(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        if (currentUserId.isBlank() || !currentUserId.equals(task.getDesignerId())) {
            throw new RuntimeException("仅当前子任务负责人可交付");
        }

        task.setStatus("delivered");
        task.setActualDate((String) body.get("actualDate"));
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setReferenceImagesJson(validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        // 设计师自评分（总分100分，整数）
        Integer selfScore = body.containsKey("selfScore") ? ((Number) body.get("selfScore")).intValue() : null;
        if (selfScore != null && (selfScore < 1 || selfScore > 100)) {
            selfScore = null;
        }
        task.setSelfScore(selfScore != null ? selfScore.doubleValue() : null);
        resetReviewWorkflow(task);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        saveDeliveryVersion(task, "initial", "首次交付", currentUserId, currentUser, currentRole);
        String selfScoreStr = selfScore != null ? selfScore.toString() : "—";
        p.getLogs().add(new ActivityLog("子任务交付（自评" + selfScoreStr + "）：" + task.getName(), currentUser, currentRole, p));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        safeNotify("TASK_DELIVERED", p.getPlannerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, currentUser, ""));
        return saved;
    }

    public Project taskRedeliver(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        if (currentUserId.isBlank() || !currentUserId.equals(task.getDesignerId())) {
            throw new RuntimeException("仅当前子任务负责人可重新交付");
        }

        task.setStatus("delivered");
        task.setActualDate((String) body.get("actualDate"));
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setReferenceImagesJson(validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        task.setReviewComments(null);
        // 设计师自评分（总分100分，整数）
        Integer reScore = body.containsKey("selfScore") ? ((Number) body.get("selfScore")).intValue() : null;
        if (reScore != null && (reScore < 1 || reScore > 100)) {
            reScore = null;
        }
        task.setSelfScore(reScore != null ? reScore.doubleValue() : null);

        // 重新交付后，两级审核都回到待审核状态；操作日志继续保留历史过程。
        resetReviewWorkflow(task);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String changeSummary = SecurityUtil.sanitizeText(
                (String) body.getOrDefault("changeSummary", "根据修改要求重新交付"), 500);
        saveDeliveryVersion(task, "redelivery", changeSummary, currentUserId, currentUser, currentRole);
        p.getLogs().add(new ActivityLog("子任务重新交付：" + task.getName(), currentUser, currentRole, p));

        Project saved = projectRepository.save(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        safeNotify("TASK_REDELIVERED", p.getPlannerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, currentUser, task.getReviewComments()));
        return saved;
    }

    public Project taskCorrectDelivery(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("当前项目状态不允许修正交付");
        }
        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        if (currentUserId.isBlank() || !currentUserId.equals(task.getDesignerId())) {
            throw new RuntimeException("仅当前子任务负责人可修正交付");
        }
        if (!List.of("delivered", "planner_approved", "sales_approved", "admin_approved").contains(task.getStatus())) {
            throw new RuntimeException("当前子任务状态不允许主动修正；已完成任务需由管理员重新开放");
        }
        String changeSummary = SecurityUtil.sanitizeText((String) body.get("changeSummary"), 500);
        if (changeSummary == null || changeSummary.isBlank()) {
            throw new RuntimeException("请填写本次修正说明");
        }
        task.setStatus("delivered");
        task.setActualDate((String) body.get("actualDate"));
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setReferenceImagesJson(validateAndCleanFiles(
                (String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(validateAndCleanFiles(
                (String) body.getOrDefault("attachmentsJson", "[]"), false));
        Integer selfScore = body.containsKey("selfScore") ? ((Number) body.get("selfScore")).intValue() : null;
        if (selfScore == null || selfScore < 1 || selfScore > 100) {
            throw new RuntimeException("请输入有效的自评分（1-100分）");
        }
        task.setSelfScore(selfScore.doubleValue());
        task.setReviewComments(null);
        resetReviewWorkflow(task);
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        saveDeliveryVersion(task, "correction", changeSummary, currentUserId, currentUser, currentRole);
        p.getLogs().add(new ActivityLog("子任务主动修正交付：" + task.getName()
                + "（" + changeSummary + "）", currentUser, currentRole, p));
        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        safeNotify("TASK_REDELIVERED", p.getPlannerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, currentUser, changeSummary));
        return saved;
    }

    private void saveDeliveryVersion(SubTask task, String submissionType, String changeSummary,
                                     String userId, String userName, String role) {
        SubTaskDeliveryVersion version = new SubTaskDeliveryVersion();
        version.setSubTask(task);
        version.setVersionNo((int) deliveryVersionRepository.countBySubTaskId(task.getId()) + 1);
        version.setSubmissionType(submissionType);
        version.setChangeSummary(changeSummary);
        version.setDeliverables(task.getDeliverables());
        version.setReferenceImagesJson(task.getReferenceImagesJson());
        version.setAttachmentsJson(task.getAttachmentsJson());
        version.setActualDate(task.getActualDate());
        version.setSelfScore(task.getSelfScore());
        version.setSubmittedById(userId);
        version.setSubmittedByName(userName);
        version.setSubmittedByRole(role);
        deliveryVersionRepository.save(version);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDeliveryVersions(Long taskId) {
        return deliveryVersionRepository.findBySubTaskIdOrderByVersionNoDesc(taskId).stream().map(version -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", version.getId());
            item.put("versionNo", version.getVersionNo());
            item.put("submissionType", version.getSubmissionType());
            item.put("changeSummary", version.getChangeSummary());
            item.put("deliverables", version.getDeliverables());
            item.put("referenceImagesJson", version.getReferenceImagesJson());
            item.put("attachmentsJson", version.getAttachmentsJson());
            item.put("actualDate", version.getActualDate());
            item.put("selfScore", version.getSelfScore());
            item.put("submittedByName", version.getSubmittedByName());
            item.put("submittedByRole", version.getSubmittedByRole());
            item.put("submittedAt", version.getSubmittedAt().toString());
            return item;
        }).toList();
    }

    public Project taskApprove(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        String comments = SecurityUtil.sanitizeText((String) body.getOrDefault("comments", ""), 500);
        boolean isChannel = "channel_custom".equals(p.getType());
        Integer score = parseOptionalScore(body.get("score"));

        if (!canReviewTask(p, task, currentRole, currentUserId)) {
            throw new RuntimeException("当前用户无权验收该子任务");
        }

        if ("delivered".equals(task.getStatus()) && "planner".equals(currentRole)) {
            // Step 1: 企划验收 → 企划评分
            validateScoreRequired(score, "企划");
            task.setStatus("planner_approved");
            task.setReviewComments(comments);
            completeReviewRecord(task, "planner", currentUserId, currentUser, comments, score);
            p.getLogs().add(new ActivityLog("企划验收通过并评分：" + task.getName(), currentUser, currentRole, p));
        } else if ("planner_approved".equals(task.getStatus()) && "sales".equals(currentRole) && isChannel) {
            // 渠道：销售验收 → 销售评分
            validateScoreRequired(score, "销售");
            task.setStatus("sales_approved");
            task.setReviewComments(comments);
            completeReviewRecord(task, "sales", currentUserId, currentUser, comments, score);
            p.getLogs().add(new ActivityLog("销售验收通过并评分：" + task.getName(), currentUser, currentRole, p));
        } else if ("planner_approved".equals(task.getStatus()) && "admin".equals(currentRole) && !isChannel) {
            // 常规品：管理验收 → 管理评分
            validateScoreRequired(score, "管理员");
            task.setStatus("admin_approved");
            task.setReviewComments(comments);
            completeReviewRecord(task, "admin", currentUserId, currentUser, comments, score);
            p.getLogs().add(new ActivityLog("管理验收通过并评分：" + task.getName(), currentUser, currentRole, p));
        } else {
            throw new RuntimeException("当前状态无法执行验收操作");
        }

        // 检查是否所有评分已完成
        checkTaskCompletion(task, p);

        return projectRepository.save(p);
    }

    /** 子任务交付或重新交付时，建立两级待审核记录。 */
    private void resetReviewWorkflow(SubTask task) {
        List<String> roles = expectedReviewRoles(task);
        for (int index = 0; index < roles.size(); index++) {
            String role = roles.get(index);
            ScoringRecord record = scoringRepository.findBySubTaskIdAndRole(task.getId(), role)
                    .orElseGet(ScoringRecord::new);
            record.setRole(role);
            record.setScoreType(role);
            record.setReviewStage(index == 0 ? "first" : "second");
            record.setReviewStatus(index == 0 ? "pending" : "waiting");
            record.setReviewerId(null);
            record.setReviewerName(null);
            record.setReviewedAt(null);
            record.setComment(null);
            record.setScore(null);
            record.setAesthetics(null);
            record.setInnovation(null);
            record.setWeight(getScoringPct(projectType(task), role) / 100.0);
            record.setSubTask(task);
            scoringRepository.save(record);
        }
    }

    /** 审核通过时更新对应阶段记录（单维度：总分100分）。 */
    private void completeReviewRecord(SubTask task, String role, String reviewerId,
                                      String reviewerName, String comment, Integer score) {
        ScoringRecord sr = scoringRepository.findBySubTaskIdAndRole(task.getId(), role)
                .orElseGet(ScoringRecord::new);
        sr.setRole(role);
        sr.setScoreType(role);
        sr.setReviewStage(reviewStage(task, role));
        sr.setReviewStatus("approved");
        sr.setReviewerId(reviewerId);
        sr.setReviewerName(reviewerName);
        sr.setReviewedAt(LocalDateTime.now());
        sr.setComment(comment);
        sr.setScore(score);
        // 兼容保留旧字段
        if (score != null) {
            // 将百分制映射到10分制作为兼容值
            double mapped = score / 10.0;
            sr.setAesthetics(mapped);
            sr.setInnovation(mapped);
        } else {
            sr.setAesthetics(null);
            sr.setInnovation(null);
        }
        // 读取对应项目类型的角色权重百分比，转为小数
        sr.setWeight(getScoringPct(projectType(task), role) / 100.0);
        sr.setSubTask(task);
        scoringRepository.save(sr);
        if ("planner".equals(role)) {
            activateSecondReview(task);
        }
    }

    private void activateSecondReview(SubTask task) {
        String secondRole = expectedReviewRoles(task).get(1);
        ScoringRecord secondReview = scoringRepository.findBySubTaskIdAndRole(task.getId(), secondRole)
                .orElseGet(ScoringRecord::new);
        boolean newlyActivated = !"pending".equals(secondReview.getReviewStatus());
        secondReview.setRole(secondRole);
        secondReview.setScoreType(secondRole);
        secondReview.setReviewStage("second");
        secondReview.setReviewStatus("pending");
        secondReview.setWeight(getScoringPct(projectType(task), secondRole) / 100.0);
        secondReview.setSubTask(task);
        scoringRepository.save(secondReview);
        if (newlyActivated && task.getProject() != null) {
            Project project = task.getProject();
            Map<String, String> context = notificationContext(project, task, "产品企划", null);
            context.put("reviewRole", "admin".equals(secondRole) ? "管理员" : "销售");
            if ("admin".equals(secondRole)) {
                safeNotifyRole("REVIEW_PENDING", "admin", "sub_task", task.getId(),
                        "system", context);
            } else if (project.getSalesId() != null && !project.getSalesId().isBlank()) {
                safeNotify("REVIEW_PENDING", project.getSalesId(), "sub_task", task.getId(),
                        "system", context);
            }
        }
    }

    private void safeNotifyRole(String eventType, String role, String aggregateType, Long aggregateId,
                                String actorUserId, Map<String, String> context) {
        try {
            notificationWorkflowService.notifyRole(eventType, role, aggregateType, aggregateId, actorUserId, context);
        } catch (Exception e) {
            log.error("角色通知创建失败但业务操作继续: eventType={}, role={}, aggregate={}#{}",
                    eventType, role, aggregateType, aggregateId, e);
        }
    }

    private List<String> expectedReviewRoles(SubTask task) {
        return "channel_custom".equals(projectType(task))
                ? List.of("planner", "sales")
                : List.of("planner", "admin");
    }

    private String reviewStage(SubTask task, String role) {
        return expectedReviewRoles(task).get(0).equals(role) ? "first" : "second";
    }

    private String projectType(SubTask task) {
        return task.getProject() != null && task.getProject().getType() != null
                ? task.getProject().getType() : "regular";
    }

    /** 从 SystemConfig 读取评分权重百分比，按项目类型+角色 */
    private double getScoringPct(String projectType, String role) {
        String key = "scoring." + projectType + "." + role;
        return systemConfigRepository.findByConfigKey(key)
            .map(c -> { try { return Double.parseDouble(c.getConfigValue()); } catch (Exception e) { return 25.0; } })
            .orElse(25.0);
    }

    /** 从 SystemConfig 读取评分权重，不存在则返回 1.0 */
    private double getScoringWeight(String role) {
        String key = "scoring.weight." + role;
        return systemConfigRepository.findByConfigKey(key)
            .map(c -> { try { return Double.parseDouble(c.getConfigValue()); } catch (Exception e) { return 1.0; } })
            .orElse(1.0);
    }

    /** 检查子任务是否所有评分已完成 */
    private void checkTaskCompletion(SubTask task, Project project) {
        boolean isChannel = "channel_custom".equals(project.getType());
        if (isChannel) {
            // 渠道：企划评分 + 销售评分 → completed
            if ("sales_approved".equals(task.getStatus())) {
                task.setStatus("completed");
            }
        } else {
            // 常规品：企划评分 + 管理评分 → completed
            if ("admin_approved".equals(task.getStatus())) {
                task.setStatus("completed");
            }
        }
        // 检查项目是否所有子任务都已完成
        if ("completed".equals(task.getStatus())) {
            boolean allDone = project.getTasks().stream().allMatch(t -> "completed".equals(t.getStatus()));
            boolean bulkStageDone = project.getTasks().stream()
                    .filter(t -> "bulk".equals(t.getWorkflowStage()))
                    .findAny()
                    .isPresent()
                    && project.getTasks().stream()
                    .filter(t -> "bulk".equals(t.getWorkflowStage()))
                    .allMatch(t -> "completed".equals(t.getStatus()));
            if (allDone && bulkStageDone) {
                project.setStatus("completed");
            }
        }
    }

    public Project taskReject(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        String comments = SecurityUtil.sanitizeText((String) body.get("comments"), 500);
        String rejectionReferenceImagesJson = validateAndCleanFiles(
                (String) body.getOrDefault("rejectionReferenceImagesJson", "[]"), true);
        String rejectionAttachmentsJson = validateAndCleanFiles(
                (String) body.getOrDefault("rejectionAttachmentsJson", "[]"), false);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        boolean canReject = ("planner".equals(currentRole) && "delivered".equals(task.getStatus()))
                || ("sales".equals(currentRole) && "channel_custom".equals(p.getType())
                    && "planner_approved".equals(task.getStatus()))
                || ("admin".equals(currentRole) && !"channel_custom".equals(p.getType())
                    && "planner_approved".equals(task.getStatus()));
        if (!canReject || !canReviewTask(p, task, currentRole, currentUserId)) {
            throw new RuntimeException("当前角色或任务状态无法驳回");
        }
        rejectReviewRecord(task, currentRole, currentUserId, currentUser, comments);
        task.setStatus("rejected");
        task.setReviewComments(comments);
        Map<String, Object> submittedSnapshot = new LinkedHashMap<>();
        submittedSnapshot.put("deliverables", task.getDeliverables());
        submittedSnapshot.put("referenceImagesJson", task.getReferenceImagesJson());
        submittedSnapshot.put("attachmentsJson", task.getAttachmentsJson());
        submittedSnapshot.put("actualDate", task.getActualDate());
        submittedSnapshot.put("submittedById", task.getDesignerId());
        submittedSnapshot.put("submittedByName", task.getDesignerName());
        Map<String, Object> rejectionSnapshot = new LinkedHashMap<>();
        rejectionSnapshot.put("reason", comments == null ? "" : comments);
        rejectionSnapshot.put("rejectionReferenceImagesJson", rejectionReferenceImagesJson);
        rejectionSnapshot.put("rejectionAttachmentsJson", rejectionAttachmentsJson);
        p.getLogs().add(new ActivityLog(
                "子任务驳回：" + task.getName() + "（意见：" + comments + "）",
                currentUser, currentRole, p, "sub_task", task.getId(),
                toJson(submittedSnapshot),
                toJson(rejectionSnapshot),
                "status,reviewComments,rejectionReferenceImagesJson,rejectionAttachmentsJson"));
        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(rejectionReferenceImagesJson, "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(rejectionAttachmentsJson, "sub_task", task.getId());
        safeNotify("TASK_REJECTED", task.getDesignerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, currentUser, comments));
        return saved;
    }

    private void rejectReviewRecord(SubTask task, String role, String reviewerId,
                                    String reviewerName, String comment) {
        ScoringRecord record = scoringRepository.findBySubTaskIdAndRole(task.getId(), role)
                .orElseGet(ScoringRecord::new);
        record.setRole(role);
        record.setScoreType(role);
        record.setReviewStage(reviewStage(task, role));
        record.setReviewStatus("rejected");
        record.setReviewerId(reviewerId);
        record.setReviewerName(reviewerName);
        record.setReviewedAt(LocalDateTime.now());
        record.setComment(comment);
        record.setScore(null);
        record.setAesthetics(null);
        record.setInnovation(null);
        record.setWeight(getScoringPct(projectType(task), role) / 100.0);
        record.setSubTask(task);
        scoringRepository.save(record);
    }

    // ==================== Scoring ====================

    public Project submitScoring(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        String role = (String) body.get("role");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        if (!List.of("planner", "sales", "admin").contains(role)
                || !role.equals(currentRole)
                || ("sales".equals(role) && !"channel_custom".equals(p.getType()))
                || ("admin".equals(role) && "channel_custom".equals(p.getType()))) {
            throw new RuntimeException("当前角色不能提交该项目评分");
        }
        if (!canReviewTask(p, task, currentRole, currentUserId)) {
            throw new RuntimeException("当前用户无权提交该项目评分");
        }
        boolean validStage = ("planner".equals(role) && "delivered".equals(task.getStatus()))
                || ("sales".equals(role) && "channel_custom".equals(p.getType())
                    && "planner_approved".equals(task.getStatus()))
                || ("admin".equals(role) && !"channel_custom".equals(p.getType())
                    && "planner_approved".equals(task.getStatus()));
        if (!validStage) {
            throw new RuntimeException("当前审核阶段不能提交该评分");
        }
        Integer score = parseOptionalScore(body.get("score"));
        validateScoreRequired(score, "评分");

        ScoringRecord sr = scoringRepository.findBySubTaskIdAndRole(taskId, role)
                .orElseGet(() -> {
                    ScoringRecord newSr = new ScoringRecord();
                    newSr.setRole(role);
                    newSr.setSubTask(task);
                    newSr.setWeight(getScoringPct(task.getProject() != null ? task.getProject().getType() : "regular", role) / 100.0);
                    return newSr;
                });
        sr.setScore(score);
        double mapped = score / 10.0;
        sr.setAesthetics(mapped);
        sr.setInnovation(mapped);
        sr.setScoreType(role);
        sr.setReviewStage(reviewStage(task, role));
        sr.setReviewStatus("approved");
        sr.setReviewerId(currentUserId);
        sr.setReviewerName((String) body.getOrDefault("currentUser", ""));
        sr.setReviewedAt(LocalDateTime.now());
        if (body.containsKey("comment")) {
            sr.setComment(SecurityUtil.sanitizeText((String) body.get("comment"), 500));
        }
        scoringRepository.save(sr);

        if ("planner".equals(role)) {
            task.setStatus("planner_approved");
            activateSecondReview(task);
        } else if ("sales".equals(role)) {
            task.setStatus("sales_approved");
        } else {
            task.setStatus("admin_approved");
        }

        String currentUser = (String) body.getOrDefault("currentUser", "");
        p.getLogs().add(new ActivityLog("子任务评分（" + role + "：" + score + "分）：" + task.getName(), currentUser, currentRole, p));

        checkTaskCompletion(task, p);
        return projectRepository.save(p);
    }

    // ==================== Terminate / Pause / Resume ====================

    public Project terminateProject(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");

        // 待企划接单：直接终止
        if ("pending_planner".equals(p.getStatus())) {
            p.setStatus("terminated");
            p.getLogs().add(new ActivityLog("项目终止", currentUser, currentRole, p));
            return projectRepository.save(p);
        }

        // 已进入工作流程：需要双方确认（仅限渠道定制单）
        boolean isChannel = "channel_custom".equals(p.getType());
        if (!isChannel) {
            // 常规品：直接终止（仅企划单方操作）
            p.setStatus("terminated");
            p.setTerminateRequester(null);
            p.getLogs().add(new ActivityLog("项目终止", currentUser, currentRole, p));
            return projectRepository.save(p);
        }

        String requester = p.getTerminateRequester();
        if (requester == null) {
            // 发起终止请求
            p.setTerminateRequester(currentRole);
            p.setStatus("pending_terminate");
            p.getLogs().add(new ActivityLog("发起终止请求", currentUser, currentRole, p));
            return projectRepository.save(p);
        }

        // 另一方确认终止
        if (requester.equals(currentRole)) {
            throw new RuntimeException("您已发起过终止请求，请等待对方确认");
        }
        p.setStatus("terminated");
        p.setTerminateRequester(null);
        p.getLogs().add(new ActivityLog("项目已终止", currentUser, currentRole, p));
        return projectRepository.save(p);
    }

    /** 取消终止（仅发起者可以取消） */
    public Project cancelTerminate(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        if (!"pending_terminate".equals(p.getStatus())) {
            throw new RuntimeException("项目不处于终止确认状态");
        }
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        p.setTerminateRequester(null);
        p.setStatus("in_progress");
        p.getLogs().add(new ActivityLog("已取消终止请求", currentUser, currentRole, p));
        return projectRepository.save(p);
    }

    public Project pauseProject(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        if (!List.of("pending_planner", "planner_accepted", "in_progress").contains(p.getStatus())) {
            throw new RuntimeException("当前状态不允许暂停");
        }
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        p.setPrePauseStatus(p.getStatus());
        p.setStatus("paused");
        p.getLogs().add(new ActivityLog("项目暂停", currentUser, currentRole, p));
        return projectRepository.save(p);
    }

    public Project resumeProject(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        if (!"paused".equals(p.getStatus())) {
            throw new RuntimeException("只有暂停中的项目可以继续");
        }
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");

        // 恢复到暂停前的状态
        String prevStatus = p.getPrePauseStatus();
        if (prevStatus != null && List.of("pending_planner", "planner_accepted", "in_progress").contains(prevStatus)) {
            p.setStatus(prevStatus);
        } else {
            // 降级：根据是否有活动任务来判断
            boolean hasActiveTasks = p.getTasks().stream().anyMatch(t -> !"pending".equals(t.getStatus()));
            p.setStatus(hasActiveTasks ? "in_progress" : "planner_accepted");
        }
        p.setPrePauseStatus(null);
        p.getLogs().add(new ActivityLog("项目继续", currentUser, currentRole, p));
        return projectRepository.save(p);
    }

    // ==================== Delete ====================

    public void deleteProject(Long projectId) {
        List<ScoringRecord> scoringRecords = scoringRepository.findByProjectIds(List.of(projectId));
        scoringRepository.deleteAll(scoringRecords);
        projectRepository.deleteById(projectId);
    }

    // ==================== Role Status Board ====================

    /** 获取指定角色的状态看板（销售/企划/供应链/设计师） */
    public Map<String, Object> getRoleStatus(String role, String viewerRole, String viewerUserId) {
        List<User> users = projectAccessService.visibleUsers(viewerRole, viewerUserId, role);
        Map<String, Object> result = new LinkedHashMap<>();

        if ("designer".equals(role) || "supplychain".equals(role)) {
            // 批量查询所有用户的子任务（一次 SQL 替代 N 次）
            List<String> userIds = users.stream().map(User::getUserId).collect(Collectors.toList());
            List<SubTask> allTasks = userIds.isEmpty() ? List.of() : subTaskRepository.findByDesignerIds(userIds);
            Map<String, List<SubTask>> tasksByUser = allTasks.stream()
                    .collect(Collectors.groupingBy(SubTask::getDesignerId));

            for (User u : users) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", u.getUserId());
                info.put("name", u.getName());
                info.put("title", u.getTitle());

                List<SubTask> userTasks = tasksByUser.getOrDefault(u.getUserId(), List.of());
                // pending 代表尚未接单，不应把设计师标记为进行中/忙碌。
                List<SubTask> activeTasks = userTasks.stream()
                        .filter(t -> List.of("accepted", "rejected", "delivered").contains(t.getStatus()))
                        .collect(Collectors.toList());
                List<SubTask> completedTasks = userTasks.stream()
                        .filter(t -> List.of("approved", "completed", "sales_approved", "admin_approved").contains(t.getStatus()))
                        .collect(Collectors.toList());

                info.put("activeTasks", activeTasks.stream().map(t -> {
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("id", t.getId());
                    tm.put("name", t.getName());
                    tm.put("status", t.getStatus());
                    tm.put("projectId", t.getProject().getId());
                    return tm;
                }).collect(Collectors.toList()));
                info.put("completedTasks", completedTasks.size());
                info.put("busy", !activeTasks.isEmpty());
                info.put("label", "designer".equals(role) ? "设计师" : "供应链");
                result.put(u.getUserId(), info);
            }
        } else if ("sales".equals(role) || "planner".equals(role)) {
            List<String> userIds = users.stream().map(User::getUserId).toList();
            List<Project> allRoleProjects = userIds.isEmpty() ? List.of()
                    : ("sales".equals(role)
                    ? projectRepository.findBySalesIdsLight(userIds)
                    : projectRepository.findByPlannerIdsLight(userIds));
            Map<String, List<Project>> projectsByUser = allRoleProjects.stream()
                    .collect(Collectors.groupingBy(p -> "sales".equals(role) ? p.getSalesId()
                                    : (p.getPlannerId() == null ? "" : p.getPlannerId()),
                            LinkedHashMap::new, Collectors.toList()));
            List<Project> unassignedPlannerProjects = "planner".equals(role)
                    ? allRoleProjects.stream().filter(p -> p.getPlannerId() == null || p.getPlannerId().isBlank()).toList()
                    : List.of();
            for (User u : users) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", u.getUserId());
                info.put("name", u.getName());
                info.put("title", u.getTitle());

                List<Project> projects = new ArrayList<>(projectsByUser.getOrDefault(u.getUserId(), List.of()));
                if ("planner".equals(role)) projects.addAll(unassignedPlannerProjects);
                List<Project> activeProjects = projects.stream()
                        .filter(p -> !List.of("draft", "terminated", "completed").contains(p.getStatus()))
                        .collect(Collectors.toList());

                info.put("activeProjects", activeProjects.stream().map(p -> {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("id", p.getId());
                    pm.put("name", p.getProductRequirements() != null ? p.getProductRequirements().substring(0, Math.min(30, p.getProductRequirements().length())) : "未命名");
                    pm.put("status", p.getStatus());
                    pm.put("type", p.getType());
                    return pm;
                }).collect(Collectors.toList()));
                info.put("completedProjects", projects.stream().filter(p -> "completed".equals(p.getStatus())).count());
                info.put("busy", !activeProjects.isEmpty());
                info.put("label", "sales".equals(role) ? "销售" : "产品企划");
                result.put(u.getUserId(), info);
            }
        }
        return result;
    }

    // 保留旧方法兼容
    public Map<String, Object> getDesignerStatus(String viewerRole, String viewerUserId) {
        return getRoleStatus("designer", viewerRole, viewerUserId);
    }

    // ==================== Project Compute Status ====================

    public String computeProjectStatus(Project p) {
        String status = p.getStatus();
        if ("completed".equals(status)) return status;
        if (List.of("draft", "pending_planner", "planner_accepted", "paused", "pending_terminate", "terminated").contains(status)) {
            return status;
        }
        if (p.getTasks().isEmpty()) return status;
        boolean bulkStageDone = p.getTasks().stream().anyMatch(t -> "bulk".equals(t.getWorkflowStage()))
                && p.getTasks().stream()
                .filter(t -> "bulk".equals(t.getWorkflowStage()))
                .allMatch(t -> "completed".equals(t.getStatus()));
        // 所有子任务是否都已通过验收（兼容旧 approved 和新 completed 状态）
        boolean allApproved = p.getTasks().stream().allMatch(t ->
            "completed".equals(t.getStatus()) || "approved".equals(t.getStatus()));
        if (!allApproved || !bulkStageDone) return "in_progress";
        // 所有子任务已通过 → 检查评分是否全部完成
        boolean allScored = p.getTasks().stream().allMatch(t -> isTaskFullyCompleted(t));
        return allScored ? "completed" : "completed_pending_score";
    }

    public static String computeProjectStatusStatic(Project p) {
        String status = p.getStatus();
        if ("completed".equals(status)) return status;
        if (List.of("draft", "pending_planner", "planner_accepted", "paused", "pending_terminate", "terminated").contains(status)) {
            return status;
        }
        if (p.getTasks().isEmpty()) return status;
        boolean bulkStageDone = p.getTasks().stream().anyMatch(t -> "bulk".equals(t.getWorkflowStage()))
                && p.getTasks().stream()
                .filter(t -> "bulk".equals(t.getWorkflowStage()))
                .allMatch(t -> "completed".equals(t.getStatus()));
        boolean allApproved = p.getTasks().stream().allMatch(t ->
            "completed".equals(t.getStatus()) || "approved".equals(t.getStatus()));
        return allApproved && bulkStageDone ? "completed" : "in_progress";
    }

    public static Map<String, String> getProjectStatusInfo(String status) {
        return switch (status) {
            case "draft" -> Map.of("label", "草稿", "cls", "badge-pending");
            case "pending_planner" -> Map.of("label", "待企划接单", "cls", "badge-pending");
            case "planner_accepted" -> Map.of("label", "企划已接单", "cls", "badge-progress");
            case "in_progress" -> Map.of("label", "进行中", "cls", "badge-progress");
            case "paused" -> Map.of("label", "已暂停", "cls", "badge-pending");
            case "pending_terminate" -> Map.of("label", "终止确认中", "cls", "badge-rejected");
            case "terminated" -> Map.of("label", "已终止", "cls", "badge-rejected");
            case "completed" -> Map.of("label", "已完成", "cls", "badge-completed");
            case "completed_pending_score" -> Map.of("label", "待评分", "cls", "badge-pending");
            default -> Map.of("label", status, "cls", "");
        };
    }

    /**
     * 判断子任务是否真正完成（已验收 + 所有评分角色已评分）
     */
    public boolean isTaskFullyCompleted(SubTask task) {
        if (!List.of("completed", "approved").contains(task.getStatus())) {
            return false;
        }
        List<String> requiredRoles = getRequiredScoringRoles(task.getProject() != null ? task.getProject().getType() : "regular");
        if (requiredRoles.isEmpty()) {
            return true;
        }
        List<ScoringRecord> records = scoringRepository.findBySubTaskId(task.getId());
        return requiredRoles.stream().allMatch(role -> records.stream()
                .filter(sr -> role.equals(sr.getRole()))
                .anyMatch(this::isScoringRecordCompleted));
    }

    /**
     * 计算项目综合得分：取所有已完成子任务的加权平均分
     * 每个子任务得分 = Σ(角色平均分 × 角色权重) / Σ(权重)
     * 角色平均分 = (审美 + 创新) / 2
     */
    public Double computeProjectScore(Project project) {
        List<SubTask> tasks = project.getTasks();
        if (tasks == null || tasks.isEmpty()) return null;
        Map<Long, List<ScoringRecord>> recordsByTask = scoringRepository.findBySubTaskIds(
                        tasks.stream().map(SubTask::getId).toList())
                .stream().collect(Collectors.groupingBy(sr -> sr.getSubTask().getId()));
        double totalScore = 0;
        int scoredCount = 0;
        for (SubTask task : tasks) {
            if (!"completed".equals(task.getStatus()) && !"approved".equals(task.getStatus())) continue;
            List<ScoringRecord> records = recordsByTask.getOrDefault(task.getId(), List.of());
            if (records == null || records.isEmpty()) continue;
            double weightedSum = 0;
            double totalWeight = 0;
            for (ScoringRecord sr : records) {
                Double normalizedScore = toHundredPointScore(sr);
                if (normalizedScore != null) {
                    weightedSum += normalizedScore * sr.getWeight();
                    totalWeight += sr.getWeight();
                }
            }
            if (totalWeight > 0) {
                totalScore += (weightedSum / totalWeight);
                scoredCount++;
            }
        }
        return scoredCount > 0 ? Math.round(totalScore / scoredCount * 10.0) / 10.0 : null;
    }

    /**
     * 批量计算项目综合得分（消除 N+1 查询）
     * 一次 SQL 拉取所有项目的评分记录，内存中聚合计算
     */
    public Map<Long, Double> computeProjectScoresBatch(List<Project> projects) {
        if (projects == null || projects.isEmpty()) return Collections.emptyMap();
        List<Long> projectIds = projects.stream().map(Project::getId).collect(Collectors.toList());
        // 一次 SQL 查全部
        List<ScoringRecord> allRecords = scoringRepository.findByProjectIds(projectIds);
        // JOIN FETCH 子任务后按项目和子任务聚合，避免分页项目逐个懒加载 tasks 产生 N+1 查询。
        Map<Long, Map<Long, List<ScoringRecord>>> recordsByProjectAndTask = new HashMap<>();
        for (ScoringRecord sr : allRecords) {
            if (sr.getSubTask() != null && sr.getSubTask().getProject() != null) {
                Long pid = sr.getSubTask().getProject().getId();
                recordsByProjectAndTask.computeIfAbsent(pid, k -> new HashMap<>())
                        .computeIfAbsent(sr.getSubTask().getId(), k -> new ArrayList<>())
                        .add(sr);
            }
        }
        // 逐项目计算：只遍历已有评分记录的子任务，未产生评分记录的项目分数为 null。
        Map<Long, Double> result = new HashMap<>();
        for (Project p : projects) {
            Map<Long, List<ScoringRecord>> recordsByTask = recordsByProjectAndTask.getOrDefault(p.getId(), Collections.emptyMap());
            double totalScore = 0;
            int scoredCount = 0;
            for (List<ScoringRecord> taskRecords : recordsByTask.values()) {
                SubTask task = taskRecords.get(0).getSubTask();
                if (!"completed".equals(task.getStatus()) && !"approved".equals(task.getStatus())) continue;
                double weightedSum = 0;
                double totalWeight = 0;
                for (ScoringRecord sr : taskRecords) {
                    Double normalizedScore = toHundredPointScore(sr);
                    if (normalizedScore != null) {
                        weightedSum += normalizedScore * sr.getWeight();
                        totalWeight += sr.getWeight();
                    }
                }
                if (totalWeight > 0) {
                    totalScore += (weightedSum / totalWeight);
                    scoredCount++;
                }
            }
            result.put(p.getId(), scoredCount > 0 ? Math.round(totalScore / scoredCount * 10.0) / 10.0 : null);
        }
        return result;
    }

    // ==================== Pending Scoring (聚合查询) ====================

    /** 获取待评分任务列表（替代前端 N+1 次循环） */
    public List<Map<String, Object>> getPendingScoringTasks(String role, String userId) {
        List<Project> projects = getProjectsByRoleAndUser(role, userId);
        List<Map<String, Object>> result = new ArrayList<>();
        List<Long> taskIds = projects.stream().flatMap(p -> p.getTasks().stream())
                .map(SubTask::getId).toList();
        Map<Long, List<ScoringRecord>> recordsByTask = taskIds.isEmpty() ? Map.of()
                : scoringRepository.findBySubTaskIds(taskIds).stream()
                .collect(Collectors.groupingBy(sr -> sr.getSubTask().getId()));

        for (Project p : projects) {
            for (SubTask t : p.getTasks()) {
                // 驳回任务等待负责人修改并重新交付，不属于评分中心的待办或历史评分结果。
                if ("rejected".equals(t.getStatus())) {
                    continue;
                }
                List<ScoringRecord> records = recordsByTask.getOrDefault(t.getId(), List.of());
                Optional<ScoringRecord> myRecord = records.stream()
                        .filter(sr -> role.equals(sr.getRole()))
                        .findFirst();
                if (myRecord.isEmpty() || "waiting".equals(myRecord.get().getReviewStatus())) {
                    continue;
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskId", t.getId());
                item.put("taskName", t.getName());
                item.put("taskStatus", t.getStatus());
                item.put("projectId", p.getId());
                item.put("projectType", p.getType());
                item.put("projectName", p.getProductName() != null && !p.getProductName().isBlank()
                        ? p.getProductName().trim() : p.getProductRequirements());
                item.put("plannerId", p.getPlannerId());
                item.put("plannerName", p.getPlannerName());
                item.put("plannedDate", t.getPlannedDate());
                item.put("designerId", t.getDesignerId());
                item.put("designerName", t.getDesignerName());
                item.put("selfScore", t.getSelfScore());
                item.put("selfAesthetics", t.getSelfAesthetics());
                item.put("selfInnovation", t.getSelfInnovation());
                item.put("isPending", isScoringRecordPending(myRecord.get()));
                item.put("scoringRecords", records.stream().map(sr -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", sr.getRole());
                    m.put("scoreType", sr.getScoreType());
                    m.put("reviewStage", sr.getReviewStage());
                    m.put("reviewStatus", sr.getReviewStatus());
                    m.put("reviewerId", sr.getReviewerId());
                    m.put("reviewerName", sr.getReviewerName());
                    m.put("reviewedAt", sr.getReviewedAt());
                    m.put("comment", sr.getComment());
                    m.put("score", sr.getScore());
                    m.put("aesthetics", sr.getAesthetics());
                    m.put("innovation", sr.getInnovation());
                    m.put("weight", sr.getWeight());
                    return m;
                }).collect(Collectors.toList()));
                result.add(item);
            }
        }
        return result;
    }

    private boolean isProjectPlanner(Project project, Map<String, Object> body) {
        String userId = (String) body.getOrDefault("currentUserId", "");
        return userId != null && !userId.isBlank() && Objects.equals(userId, project.getPlannerId());
    }

    /** 验收和评分必须同时满足角色、项目归属，不能仅由请求体中的 role 决定。 */
    private boolean canReviewTask(Project project, SubTask task, String role, String userId) {
        if (project == null || task == null || userId == null || userId.isBlank()) return false;
        if ("admin".equals(role)) return true;
        if ("planner".equals(role)) {
            return Objects.equals(userId, project.getPlannerId());
        }
        return "sales".equals(role)
                && "channel_custom".equals(project.getType())
                && Objects.equals(userId, project.getSalesId());
    }

    private Integer parseOptionalScore(Object rawScore) {
        if (rawScore == null) {
            return null;
        }
        Integer score;
        if (rawScore instanceof Number number) {
            score = number.intValue();
        } else {
            try {
                score = Integer.parseInt(String.valueOf(rawScore));
            } catch (NumberFormatException e) {
                throw new RuntimeException("评分必须为数字");
            }
        }
        if (score < 1 || score > 100) {
            throw new RuntimeException("评分必须在 1-100 之间");
        }
        return score;
    }

    private void validateScoreRequired(Integer score, String actorLabel) {
        if (score == null) {
            throw new RuntimeException(actorLabel + "评分不能为空");
        }
    }

    private List<String> getRequiredScoringRoles(String projectType) {
        return "channel_custom".equals(projectType)
                ? List.of("planner", "sales")
                : List.of("planner", "admin");
    }

    private boolean isScoringRecordCompleted(ScoringRecord record) {
        if (record.getReviewStatus() != null) {
            return "approved".equals(record.getReviewStatus());
        }
        return record.getScore() != null
                || (record.getAesthetics() != null && record.getInnovation() != null);
    }

    private boolean isScoringRecordPending(ScoringRecord record) {
        if (record.getReviewStatus() != null) {
            return "pending".equals(record.getReviewStatus());
        }
        return !isScoringRecordCompleted(record);
    }

    private Double toHundredPointScore(ScoringRecord record) {
        if (record.getScore() != null) {
            return record.getScore().doubleValue();
        }
        if (record.getAesthetics() != null && record.getInnovation() != null) {
            return ((record.getAesthetics() + record.getInnovation()) / 2.0) * 10.0;
        }
        return null;
    }

    public static Map<String, String> getTaskStatusInfo(String status) {
        return switch (status) {
            case "pending" -> Map.of("label", "待接单", "cls", "badge-pending", "icon", "⏳");
            case "accepted" -> Map.of("label", "设计中", "cls", "badge-progress", "icon", "🎨");
            case "delivered" -> Map.of("label", "待验收", "cls", "badge-pending", "icon", "📤");
            case "planner_approved" -> Map.of("label", "企划已验收", "cls", "badge-progress", "icon", "✅");
            case "scoring_planner" -> Map.of("label", "待二次验收", "cls", "badge-pending", "icon", "⏳");
            case "sales_approved" -> Map.of("label", "销售已验收", "cls", "badge-progress", "icon", "✅");
            case "admin_approved" -> Map.of("label", "管理已验收", "cls", "badge-progress", "icon", "✅");
            case "approved" -> Map.of("label", "已通过", "cls", "badge-completed", "icon", "✅");
            case "completed" -> Map.of("label", "已完成", "cls", "badge-completed", "icon", "✅");
            case "rejected" -> Map.of("label", "已驳回", "cls", "badge-rejected", "icon", "↩️");
            default -> Map.of("label", status, "cls", "", "icon", "❓");
        };
    }
}
