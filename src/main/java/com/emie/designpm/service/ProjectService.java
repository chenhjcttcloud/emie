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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
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
    private PointsService pointsService;
    private DesignerMarketEligibilityRepository marketEligibilityRepository;
    private TaskWithdrawalRepository taskWithdrawalRepository;
    private PointAdjustmentLedgerRepository pointAdjustmentLedgerRepository;
    private PointLedgerRepository pointLedgerRepository;
    private PointAppealRepository pointAppealRepository;
    private NotificationRepository notificationRepository;
    private FileRecordRepository fileRecordRepository;
    private DesignRequirementScoringService designRequirementScoringService;
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

    /** Optional setter keeps existing lightweight unit-test construction compatible. */
    @Autowired(required = false)
    void setPointsService(PointsService pointsService) {
        this.pointsService = pointsService;
    }
    @Autowired(required = false)
    void setMarketEligibilityRepository(DesignerMarketEligibilityRepository repository) { this.marketEligibilityRepository = repository; }
    @Autowired(required = false)
    void setTaskWithdrawalRepository(TaskWithdrawalRepository repository) { this.taskWithdrawalRepository = repository; }
    @Autowired(required = false)
    void setPointAdjustmentLedgerRepository(PointAdjustmentLedgerRepository repository) { this.pointAdjustmentLedgerRepository = repository; }
    @Autowired(required = false)
    void setPointLedgerRepository(PointLedgerRepository repository) { this.pointLedgerRepository = repository; }
    @Autowired(required = false)
    void setPointAppealRepository(PointAppealRepository repository) { this.pointAppealRepository = repository; }
    @Autowired(required = false)
    void setNotificationRepository(NotificationRepository repository) { this.notificationRepository = repository; }
    @Autowired(required = false)
    void setFileRecordRepository(FileRecordRepository repository) { this.fileRecordRepository = repository; }
    @Autowired(required = false)
    void setDesignRequirementScoringService(DesignRequirementScoringService service) { this.designRequirementScoringService = service; }

    // ==================== Query ====================

    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Project> getProjectById(Long id) {
        return projectRepository.findByIdWithTasks(id);
    }

    /** 保存项目群状态等系统级字段。 */
    public Project saveProject(Project project) {
        return projectRepository.save(project);
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
        long pendingScoreCount = countPendingScoresForUser(role, userId);

        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("totalCount", totalCount);
        stats.put("channelCount", channelCount);
        stats.put("regularCount", regularCount);
        stats.put("myTaskCount", myTaskCount);
        stats.put("pendingScoreCount", pendingScoreCount);
        return stats;
    }

    /** 工作台首页、导航徽章和评分中心共用的待评分条目数量。 */
    public long countPendingScoresForUser(String role, String userId) {
        if (!List.of("admin", "planner", "sales").contains(role)) return 0L;
        // 工作台徽章必须和评分中心使用同一批聚合条目，避免旧的 SQL 口径
        // 只统计部分任务状态、漏掉设计需求或把评分记录重复计数。
        long projectPending = getPendingScoringTasks(role, userId).stream()
                .filter(item -> Boolean.TRUE.equals(item.get("isPending"))).count();
        long requirementPending = designRequirementScoringService == null ? 0L
                : designRequirementScoringService.pendingItems(role, userId).stream()
                .filter(item -> Boolean.TRUE.equals(item.get("isPending"))).count();
        return projectPending + requirementPending;
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

    /**
     * 在明确的只读事务中完成动态状态计算，列表 DTO 不再触发懒加载。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> computeProjectStatusMap(List<Project> projects) {
        if (projects == null || projects.isEmpty()) return Collections.emptyMap();
        List<Long> ids = projects.stream().map(Project::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return Collections.emptyMap();
        Map<Long, String> result = projectRepository.findAllWithTasksByIdIn(ids).stream()
                .collect(Collectors.toMap(Project::getId, this::computeProjectStatus));
        for (Project project : projects) {
            result.putIfAbsent(project.getId(), project.getStatus());
        }
        return result;
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
        String deadline = SecurityUtil.sanitizeText((String) body.get("deadline"), 20);
        String productRequirements = SecurityUtil.sanitizeText((String) body.get("productRequirements"), 2000);
        String description = SecurityUtil.sanitizeText((String) body.getOrDefault("description", ""), 2000);
        boolean feishuChatEnabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("feishuChatEnabled", "false")));

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

        // 截止日期：非空且为 yyyy-MM-dd。
        // 不做“不早于今天”约束：受控历史批量导入（createImportedProject）允许过去日期。
        // 抛 IllegalArgumentException：GlobalExceptionHandler 归为 400（业务/参数错误），
        // 避免落入 Exception 处理器返回 500（P1-12）；ProjectController 的 catch(RuntimeException) 亦兼容。
        if (deadline == null || deadline.isBlank()) {
            throw new IllegalArgumentException("要求完成时间不能为空");
        }
        try {
            LocalDate.parse(deadline.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("要求完成时间格式应为 yyyy-MM-dd");
        }

        // 产品企划归属校验：渠道定制项目的企划由创建方指定，必须为非空且可解析到真实在职企划账号，
        // 避免项目无人接单；常规品项目的 plannerId 非空时同样要求真实在职企划（消除幽灵 plannerId），
        // 为空则保持历史语义（管理员代建/提案立项沿用空值）。
        if ("channel_custom".equals(type) && (plannerId == null || plannerId.isBlank())) {
            throw new IllegalArgumentException("请选择产品企划");
        }
        if (plannerId != null && !plannerId.isBlank()) {
            User plannerUser = userService.getUserByUserId(plannerId);
            if (plannerUser == null
                    || !"planner".equals(plannerUser.getRole())
                    || "disabled".equalsIgnoreCase(plannerUser.getStatus())
                    || "pending".equalsIgnoreCase(plannerUser.getStatus())) {
                throw new IllegalArgumentException("请选择有效的在职产品企划");
            }
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
        p.setFeishuChatEnabled(feishuChatEnabled);

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

        Project saved = saveProjectWithUniqueCodeRetry(p);
        fileArchiveService.bindFilesFromJson(refImagesJson, "project", saved.getId());
        fileArchiveService.bindFilesFromJson(attsJson, "project", saved.getId());
        // 批量历史导入不应向每位负责人逐条发送即时通知；导入本身仍保留操作日志与同步记录。
        if (!suppressNotifications) {
            safeNotify("PROJECT_ASSIGNED", saved.getPlannerId(), "project", saved.getId(), currentUserId,
                    notificationContext(saved, null, currentUser, ""));
        }
        return saved;
    }

    /** 单实例下串行分配月序号；数据库唯一索引（V39 uk_projects_project_code）负责最终兜底。 */
    private String nextProjectCode(LocalDateTime now) {
        return nextProjectCode(now, 1);
    }

    /** offset 用于并发生成冲突重试时顺延当月月序号，避免重试生成相同编号再次冲突。 */
    private String nextProjectCode(LocalDateTime now, long offset) {
        synchronized (PROJECT_CODE_LOCK) {
            LocalDateTime start = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
            LocalDateTime end = start.plusMonths(1);
            long sequence = projectRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(start, end) + offset;
            if (sequence > 9999) throw new RuntimeException("本月项目编号已超过 9999 个");
            return String.format("EMIE%04d%02d%04d", now.getYear(), now.getMonthValue(), sequence);
        }
    }

    /**
     * 保存项目并处理 project_code 并发生成冲突。
     * 采用「预检查 + 顺延序号循环」：每轮先查编号是否已被占用，未占用才尝试保存，占用则
     * 顺延当月月序号重试（上限 3 次）。不使用「catch 后在原事务内再次保存」的重试——
     * flush 抛异常后事务已被标记 rollback-only，再次保存即使成功最终 commit 也必抛
     * UnexpectedRollbackException。saveAndFlush 的 DataIntegrityViolationException 仅作为
     * 预检查与保存之间跨实例竞态的最终兜底，转中文业务异常。
     */
    private Project saveProjectWithUniqueCodeRetry(Project p) {
        LocalDateTime now = LocalDateTime.now();
        for (int attempt = 1; attempt <= 3; attempt++) {
            String code = nextProjectCode(now, attempt);
            p.setProjectCode(code);
            if (!projectRepository.existsByProjectCode(code)) {
                try {
                    return projectRepository.saveAndFlush(p);
                } catch (DataIntegrityViolationException e) {
                    // 唯一索引（V39 uk_projects_project_code）兜住预检查与保存之间的并发竞态
                    throw new RuntimeException("项目编号生成冲突，请稍后重试", e);
                }
            }
            // 编号已被占用：顺延序号进入下一轮
        }
        throw new RuntimeException("项目编号生成冲突，请稍后重试");
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
        if (body.containsKey("productArchiveJson")) {
            Object archive = body.get("productArchiveJson");
            p.setProductArchiveJson(SecurityUtil.sanitizeText(archive == null ? "{}" : String.valueOf(archive), 20000));
        }

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
        data.put("productArchiveJson", project.getProductArchiveJson());
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

    @org.springframework.transaction.annotation.Transactional
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
        boolean publishToMarket = Boolean.TRUE.equals(body.get("publishToMarket"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("publishToMarket")));
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
        if (pointsService != null && !Boolean.TRUE.equals(body.get("selfInitiated"))) {
            pointsService.bindRuleSnapshot(task, (String) body.get("pointRuleCode"),
                    (String) body.get("difficultyCode"));
        }
        task.setRequiredSkillTagsJson(validateSkillTags(body.get("requiredSkillTags")));
        task.setCollaboratorAllocationsJson(validateCollaboratorAllocations(body.get("collaboratorAllocations"), designerId));
        task.setMilestoneMonth(validateMilestoneMonth(body.get("milestoneMonth")));
        task.setAssignmentReason(SecurityUtil.sanitizeText((String) body.get("assignmentReason"), 500));
        task.setSelfInitiated(Boolean.TRUE.equals(body.get("selfInitiated")));
        task.setSelfInitiatedApproved(task.isSelfInitiated());
        // 设置负责人角色类型（designer / supplychain / planner / sales），默认 designer
        String assigneeRole = (String) body.get("assigneeRole");
        task.setAssigneeRole(assigneeRole != null && !assigneeRole.isBlank() ? assigneeRole : "designer");
        if (publishToMarket) {
            if (!"designer".equals(task.getAssigneeRole())) {
                throw new RuntimeException("只有设计师子任务可以发布到接单市场");
            }
            if (designerId != null && !designerId.isBlank()) {
                throw new RuntimeException("发布到接单市场时不能指定设计师");
            }
            task.setAllocationStatus("market_open");
            task.setMarketPublishedAt(LocalDateTime.now());
        } else {
            if (designerId == null || designerId.isBlank()) {
                throw new RuntimeException("请选择子任务负责人或发布到接单市场");
            }
            task.setAllocationStatus("direct_assigned");
        }
        validateSubTaskAssignee(designerId, task.getAssigneeRole());
        // 设计师不再按任务分类或能力标签限制；所有设计师均可承接设计师类子任务。
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
        p.getLogs().add(new ActivityLog((publishToMarket ? "发布接单市场子任务：" : "添加子任务：") + name,
                currentUser, role, p));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        // 统一按负责人 ID 通知，designerId 兼容设计、供应链、销售、产品推广等负责人类型。
        if (task.getDesignerId() != null && !task.getDesignerId().isBlank()) {
            safeNotifyAfterCommit("TASK_ASSIGNED", task.getDesignerId(), "sub_task", task.getId(),
                    (String) body.getOrDefault("currentUserId", ""), notificationContext(saved, task, currentUser, ""));
        }
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
        // 与抢单、撤回保持相同的 project -> subtask 锁序，避免编辑覆盖并发抢单结果。
        Project p = projectRepository.findByIdForUpdate(projectId)
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

        SubTask task = subTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new RuntimeException("子任务不存在"));
        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId)) {
            throw new RuntimeException("子任务不属于当前项目");
        }

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
            if ((did == null || did.isBlank())
                    && !("pending".equals(task.getStatus())
                    && List.of("market_open", "withdrawn").contains(task.getAllocationStatus()))) {
                throw new RuntimeException("已指派或已领取的子任务不能清空负责人");
            }
            if (!"pending".equals(task.getStatus()) && !Objects.equals(did, task.getDesignerId())) {
                throw new RuntimeException("子任务开始执行后不能更换负责人");
            }
            task.setDesignerId(did);
            task.setDesignerName(userService.getUserName(did));
            if (did != null && !did.isBlank()) {
                task.setAllocationStatus("direct_assigned");
            }
        }
        if (body.containsKey("assigneeRole")) {
            task.setAssigneeRole((String) body.get("assigneeRole"));
        }
        if (body.containsKey("pointRuleCode") || body.containsKey("difficultyCode")) {
            if (!"pending".equals(task.getStatus())) {
                throw new RuntimeException("子任务开始执行后不能修改积分规则或难度");
            }
            if (pointsService == null) throw new RuntimeException("积分规则服务暂不可用，请稍后重试");
            String ruleCode = body.containsKey("pointRuleCode")
                    ? (String) body.get("pointRuleCode") : task.getPointRuleCode();
            String difficultyCode = body.containsKey("difficultyCode")
                    ? (String) body.get("difficultyCode") : task.getDifficultyCode();
            pointsService.bindRuleSnapshot(task, ruleCode, difficultyCode);
        }
        if (body.containsKey("requiredSkillTags")) {
            if (!"pending".equals(task.getStatus())) {
                throw new RuntimeException("子任务开始执行后不能修改能力要求");
            }
            task.setRequiredSkillTagsJson(validateSkillTags(body.get("requiredSkillTags")));
        }
        if (body.containsKey("collaboratorAllocations") || body.containsKey("milestoneMonth")) {
            if (!"pending".equals(task.getStatus())) throw new RuntimeException("任务开始后不能修改合作比例或里程碑月份");
            if (body.containsKey("collaboratorAllocations")) {
                task.setCollaboratorAllocationsJson(validateCollaboratorAllocations(body.get("collaboratorAllocations"), task.getDesignerId()));
            }
            if (body.containsKey("milestoneMonth")) task.setMilestoneMonth(validateMilestoneMonth(body.get("milestoneMonth")));
        }
        if (body.containsKey("assignmentReason")) task.setAssignmentReason(SecurityUtil.sanitizeText((String) body.get("assignmentReason"), 500));
        if ("market_open".equals(task.getAllocationStatus())
                && (!"designer".equals(task.getAssigneeRole())
                || (task.getDesignerId() != null && !task.getDesignerId().isBlank()))) {
            throw new RuntimeException("开放市场任务必须保持设计师类型且不能指定负责人");
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
        data.put("allocationStatus", task.getAllocationStatus());
        data.put("pointRuleCode", task.getPointRuleCode());
        data.put("difficultyCode", task.getDifficultyCode());
        data.put("difficultyMultiplierSnapshot", task.getDifficultyMultiplierSnapshot());
        data.put("basePointSnapshot", task.getBasePointSnapshot());
        data.put("qualityBonusThresholdSnapshot", task.getQualityBonusThresholdSnapshot());
        data.put("qualityBonusRatioSnapshot", task.getQualityBonusRatioSnapshot());
        data.put("qualityTopThresholdSnapshot", task.getQualityTopThresholdSnapshot());
        data.put("qualityTopRatioSnapshot", task.getQualityTopRatioSnapshot());
        data.put("maxTotalMultiplierSnapshot", task.getMaxTotalMultiplierSnapshot());
        data.put("collaboratorAllocationsJson", task.getCollaboratorAllocationsJson());
        data.put("milestoneMonth", task.getMilestoneMonth());
        data.put("assignmentReason", task.getAssignmentReason());
        data.put("selfInitiated", task.isSelfInitiated());
        data.put("countInPerformanceSnapshot", task.getCountInPerformanceSnapshot());
        data.put("requiredSkillTagsJson", task.getRequiredSkillTagsJson());
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
        // 锁序与全项目一致：project → subtask。锁内重新加载并重校验状态，
        // 避免与 taskAccept 抢单并发时快照校验通过、DELETE 阻塞后误删刚被认领的任务。
        Project p = lockProject(projectId);
        if ("paused".equals(p.getStatus())) {
            throw new RuntimeException("项目已暂停，无法删除子任务");
        }
        SubTask task = lockSubTask(projectId, taskId);
        if (!List.of("pending", "pending_planner").contains(p.getStatus()) && !"pending".equals(task.getStatus())) {
            throw new RuntimeException("项目已进入工作流程，无法删除子任务");
        }

        // 锁内按 FK 依赖顺序清理子任务关联数据，避免 DELETE 子任务时触发外键违例：
        // 退单调账（TASK_WITHDRAWAL，按 withdrawalId）→ 退单记录（FK→sub_tasks）→ 交付版本（FK→sub_tasks）→ 评分（FK→sub_tasks）。
        List<TaskWithdrawal> withdrawals = taskWithdrawalRepository == null ? List.of()
                : taskWithdrawalRepository.findBySubTaskIdIn(List.of(taskId));
        List<Long> withdrawalIds = withdrawals.stream().map(TaskWithdrawal::getId).toList();
        if (!withdrawalIds.isEmpty() && pointAdjustmentLedgerRepository != null) {
            pointAdjustmentLedgerRepository.deleteProjectRelated(List.of(), withdrawalIds);
        }
        if (!withdrawalIds.isEmpty() && taskWithdrawalRepository != null) {
            taskWithdrawalRepository.deleteBySubTaskIds(List.of(taskId));
        }
        deliveryVersionRepository.deleteBySubTaskIds(List.of(taskId));

        // 删除关联的评分记录，并同步删除飞书记录
        List<ScoringRecord> scoringRecords = scoringRepository.findBySubTaskId(taskId);
        scoringRepository.deleteAll(scoringRecords);
        // 从项目中移除子任务
        p.getTasks().remove(task);
        subTaskRepository.delete(task);
        return projectRepository.save(p);
    }

    // ==================== Task Workflow ====================

    private Project lockProject(Long projectId) {
        return projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
    }

    /** All task workflow mutations must acquire locks in project -> subtask order. */
    private SubTask lockSubTask(Long projectId, Long taskId) {
        SubTask task = subTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new RuntimeException("子任务不存在"));
        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId)) {
            throw new RuntimeException("子任务不属于当前项目");
        }
        return task;
    }

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

        if (task.getDesignerId() == null || task.getDesignerId().isBlank()) {
            validateMarketClaimConstraints(task, designerUserId);
        }

        // 如果子任务未指定设计师，自动绑定接单的设计师（防并发）
        if (task.getDesignerId() == null || task.getDesignerId().isBlank()) {
            if (!"market_open".equals(task.getAllocationStatus())) {
                throw new RuntimeException("该子任务未开放接单");
            }
            if (designerUserId != null && !designerUserId.isBlank()) {
                task.setDesignerId(designerUserId);
                task.setDesignerName(userService.getUserName(designerUserId));
                task.setAllocationStatus("claimed");
                task.setClaimedAt(LocalDateTime.now());
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
        safeNotifyAfterCommit("TASK_ASSIGNED", task.getDesignerId(), "sub_task", task.getId(),
                (String) body.getOrDefault("currentUserId", ""), notificationContext(saved, task, currentUser, ""));
        return saved;
    }

    private void validateMarketClaimConstraints(SubTask task, String designerUserId) {
        if (!"market_open".equals(task.getAllocationStatus())) return;
        if (marketEligibilityRepository != null) marketEligibilityRepository.findByUserId(designerUserId).ifPresent(eligibility -> {
            if (eligibility.isSuspended()) throw new RuntimeException("开放接单资格已暂停至" + eligibility.getSuspendedUntil() + "，原因：" + Optional.ofNullable(eligibility.getReason()).orElse("违规处理"));
        });
        String code = Optional.ofNullable(task.getPointRuleCode()).orElse("").trim().toUpperCase();
        if (code.startsWith("A") || code.startsWith("B")) {
            long activeMainTasks = subTaskRepository.countActiveMainTasksByCategory(designerUserId, "A")
                    + subTaskRepository.countActiveMainTasksByCategory(designerUserId, "B");
            int maxMainTasks = positiveIntConfig("points.claim.max_main_tasks", 5);
            if (activeMainTasks >= maxMainTasks) {
                throw new RuntimeException("当前A/B类主任务已达上限（" + maxMainTasks + "个），请完成现有任务后再接单");
            }
        }

        // 接单不再按能力标签或任务分类限制；历史标签字段保留，仅用于兼容旧数据展示。
    }

    private void validateDesignCategoryEligibility(SubTask task, String designerUserId) {
        String configured = systemConfigRepository.findByConfigKey("points.user.skills." + designerUserId)
                .map(SystemConfig::getConfigValue).orElse("[]");
        validateDesignCategoryEligibility(task, designerUserId, parseSkillTags(configured));
    }

    private void validateDesignCategoryEligibility(SubTask task, String designerUserId, Set<String> actual) {
        String ruleCode = Optional.ofNullable(task.getPointRuleCode()).orElse("").toUpperCase(Locale.ROOT);
        if ("B1".equals(ruleCode) && !actual.contains("ID")) throw new RuntimeException("B1原创任务仅具备ID能力标签的设计师可接");
        if (ruleCode.startsWith("B") && !"B1".equals(ruleCode) && actual.stream().noneMatch(tag -> Set.of("ID", "视觉").contains(tag))) {
            throw new RuntimeException("产品设计类任务仅具备ID或视觉能力标签的设计师可接");
        }
    }

    private int positiveIntConfig(String key, int fallback) {
        return systemConfigRepository.findByConfigKey(key).map(SystemConfig::getConfigValue)
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> {
                    try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
                }).filter(value -> value > 0).orElse(fallback);
    }

    private String validateSkillTags(Object raw) {
        if (raw == null) return null;
        Collection<?> values;
        if (raw instanceof Collection<?> collection) {
            values = collection;
        } else if (raw instanceof String json && !json.isBlank()) {
            try { values = objectMapper.readValue(json, new TypeReference<List<Object>>() {}); }
            catch (Exception e) { throw new RuntimeException("能力标签格式无效"); }
        } else if (raw instanceof String) {
            return null;
        } else {
            throw new RuntimeException("能力标签格式无效");
        }
        List<String> normalized = values.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> SecurityUtil.sanitizeText(value, 40))
                .filter(Objects::nonNull)
                .distinct().limit(20).toList();
        try { return normalized.isEmpty() ? null : objectMapper.writeValueAsString(normalized); }
        catch (Exception e) { throw new RuntimeException("能力标签保存失败"); }
    }

    private Set<String> parseSkillTags(String json) {
        if (json == null || json.isBlank()) return Collections.emptySet();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                    .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            log.warn("忽略格式错误的能力标签配置");
            return Collections.emptySet();
        }
    }

    private String validateMilestoneMonth(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        String month = String.valueOf(raw).trim();
        try { java.time.YearMonth.parse(month); return month; }
        catch (Exception e) { throw new RuntimeException("里程碑月份格式应为YYYY-MM"); }
    }

    private String validateCollaboratorAllocations(Object raw, String primaryUserId) {
        if (raw == null || String.valueOf(raw).isBlank() || "[]".equals(String.valueOf(raw).trim())) return null;
        List<Map<String, Object>> rows;
        try {
            rows = raw instanceof Collection<?> collection
                    ? objectMapper.convertValue(collection, new TypeReference<List<Map<String, Object>>>() {})
                    : objectMapper.readValue(String.valueOf(raw), new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) { throw new RuntimeException("合作成员比例格式无效"); }
        Set<String> users = new LinkedHashSet<>();
        int total = 0;
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String userId = SecurityUtil.sanitizeText(String.valueOf(row.getOrDefault("userId", "")), 100);
            int ratio;
            try { ratio = Integer.parseInt(String.valueOf(row.get("ratio"))); }
            catch (Exception e) { throw new RuntimeException("合作比例必须是整数百分比"); }
            if (userId == null || userId.isBlank() || userId.equals(primaryUserId) || !users.add(userId)) {
                throw new RuntimeException("合作成员不能重复或与主负责人相同");
            }
            if (ratio <= 0 || ratio >= 100) throw new RuntimeException("单个合作比例必须在1%到99%之间");
            User collaborator = userService.getUserByUserId(userId);
            if (collaborator == null || !"designer".equals(normalizeAssigneeRole(collaborator.getRole()))) {
                throw new RuntimeException("合作成员必须是有效设计师");
            }
            total += ratio;
            normalized.add(Map.of("userId", userId, "name", collaborator.getName(), "ratio", ratio));
        }
        if (total >= 100) throw new RuntimeException("合作成员比例合计必须小于100%，剩余比例归主负责人");
        try { return objectMapper.writeValueAsString(normalized); }
        catch (Exception e) { throw new RuntimeException("合作比例保存失败"); }
    }

    @Transactional
    public Project withdrawMarketTask(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        String role = (String) body.getOrDefault("currentRole", "");
        if (!List.of("planner", "admin").contains(role)) throw new RuntimeException("仅企划或管理员可撤回市场任务");
        if ("planner".equals(role) && !isProjectPlanner(p, body)) throw new RuntimeException("仅项目负责人企划可撤回市场任务");
        SubTask task = subTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new RuntimeException("子任务不存在"));
        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId)) throw new RuntimeException("子任务不属于当前项目");
        if (!"market_open".equals(task.getAllocationStatus()) || !"pending".equals(task.getStatus())) {
            throw new RuntimeException("该任务已被领取或不在接单市场");
        }
        task.setAllocationStatus("withdrawn");
        p.getLogs().add(new ActivityLog("撤回接单市场子任务：" + task.getName(),
                (String) body.getOrDefault("currentUser", ""), role, p));
        return projectRepository.saveAndFlush(p);
    }

    /** 设计师退单：接单后一小时内免费，超时按任务基础分及累计退单次数比例扣分。 */
    @Transactional
    public Project withdrawAcceptedTask(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);
        SubTask task = lockSubTask(projectId, taskId);
        String userId = String.valueOf(body.getOrDefault("currentUserId", ""));
        if (userId.isBlank() || !userId.equals(task.getDesignerId())) throw new RuntimeException("仅当前负责人可退单");
        if (!"accepted".equals(task.getStatus())) throw new RuntimeException("只有已接单且未交付的任务可以退单");
        if (taskWithdrawalRepository == null) throw new RuntimeException("退单服务未就绪");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime claimed = task.getClaimedAt() == null ? now : task.getClaimedAt();
        long elapsed = Math.max(0, java.time.Duration.between(claimed, now).toMinutes());
        // 行锁读取市场资格，防同一设计师并发退单时 violation_count 读改写丢失更新。
        DesignerMarketEligibility eligibility = marketEligibilityRepository == null ? null
                : marketEligibilityRepository.findByUserIdForUpdate(userId)
                        .orElseGet(() -> { DesignerMarketEligibility x = new DesignerMarketEligibility(); x.setUserId(userId); return x; });
        int previous = eligibility == null ? 0 : Optional.ofNullable(eligibility.getViolationCount()).orElse(0);
        long freeMinutes = positiveLongConfig("points.withdrawal.free_minutes", 60);
        int suspendCount = positiveIntConfig("points.withdrawal.suspend_count", 3);
        double perWithdrawalRate = boundedDoubleConfig("points.withdrawal.penalty_rate", 10d) / 100d;
        int suspendDays = positiveIntConfig("points.withdrawal.suspend_days", 7);
        double ratio = elapsed <= freeMinutes ? 0d : Math.min(1d, perWithdrawalRate * (previous + 1));
        int base = (int)Math.round(Optional.ofNullable(task.getBasePointSnapshot()).orElse(0) * Optional.ofNullable(task.getDifficultyMultiplierSnapshot()).orElse(1d));
        int penalty = (int)Math.ceil(base * ratio);
        TaskWithdrawal event = new TaskWithdrawal(); event.setSubTaskId(taskId); event.setUserId(userId); event.setElapsedMinutes(elapsed); event.setPenaltyRatio(ratio); event.setPenaltyPoints(penalty); event.setReason(elapsed <= 60 ? "接单1小时内退单（免罚）" : "接单超1小时退单，按累计次数比例扣分");
        taskWithdrawalRepository.save(event);
        if (penalty > 0 && pointAdjustmentLedgerRepository != null) {
            PointAdjustmentLedger adjustment = new PointAdjustmentLedger(); adjustment.setUserId(userId); adjustment.setSourceType("TASK_WITHDRAWAL"); adjustment.setSourceId(event.getId()); adjustment.setPoints(-penalty); adjustment.setReason(event.getReason()); adjustment.setCreatedBy(userId); pointAdjustmentLedgerRepository.save(adjustment);
        }
        if (eligibility != null) {
            eligibility.setViolationCount(previous + 1); eligibility.setReason("退单累计" + (previous + 1) + "次");
            if (previous + 1 >= suspendCount) eligibility.setSuspendedUntil(now.plusDays(suspendDays));
            eligibility.setUpdatedBy(userId); marketEligibilityRepository.save(eligibility);
        }
        task.setDesignerId(null); task.setDesignerName(null); task.setClaimedAt(null); task.setStatus("pending"); task.setAllocationStatus("market_open");
        p.getLogs().add(new ActivityLog("设计师退单：" + task.getName() + "，扣分" + penalty, String.valueOf(body.getOrDefault("currentUser", userId)), "designer", p));
        return projectRepository.saveAndFlush(p);
    }

    private long positiveLongConfig(String key, long fallback) {
        try { return Math.max(0, Long.parseLong(systemConfigRepository.findByConfigKey(key).map(SystemConfig::getConfigValue).orElse(String.valueOf(fallback)).trim())); }
        catch (Exception e) { return fallback; }
    }
    private double boundedDoubleConfig(String key, double fallback) {
        try { return Math.min(100d, Math.max(0d, Double.parseDouble(systemConfigRepository.findByConfigKey(key).map(SystemConfig::getConfigValue).orElse(String.valueOf(fallback)).trim()))); }
        catch (Exception e) { return fallback; }
    }

    public Project taskDeliver(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = lockSubTask(projectId, taskId);

        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        if (currentUserId.isBlank() || !currentUserId.equals(task.getDesignerId())) {
            throw new RuntimeException("仅当前子任务负责人可交付");
        }

        String submittedActualDate = (String) body.get("actualDate");
        task.setStatus("delivered");
        task.setActualDate(null);
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
        saveDeliveryVersion(task, "initial", "首次交付", submittedActualDate, currentUserId, currentUser, currentRole);
        String selfScoreStr = selfScore != null ? selfScore.toString() : "—";
        p.getLogs().add(new ActivityLog("子任务交付（自评" + selfScoreStr + "）：" + task.getName(), currentUser, currentRole, p));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        safeNotifyAfterCommit("TASK_DELIVERED", p.getPlannerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, currentUser, ""));
        if ("channel_custom".equals(p.getType()) && p.getSalesId() != null && !p.getSalesId().isBlank()
                && !p.getSalesId().equals(currentUserId)) {
            safeNotifyAfterCommit("TASK_DELIVERED", p.getSalesId(), "sub_task", task.getId(), currentUserId,
                    notificationContext(p, task, currentUser, "销售关联项目已收到设计交付成果"));
        }
        return saved;
    }

    /** 负责人将已交付成果正式送审；仅设计师和供应链任务需要此一步。 */
    public Project taskSubmitReview(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);
        SubTask task = lockSubTask(projectId, taskId);
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        String role = (String) body.getOrDefault("currentRole", "");
        if (!"planner".equals(role)) throw new RuntimeException("仅产品企划可送审");
        if (!Objects.equals(currentUserId, p.getPlannerId())) throw new RuntimeException("当前用户不是该项目负责人企划，无法送审");
        if (!List.of("designer", "supplychain").contains(task.getAssigneeRole())) throw new RuntimeException("仅设计师和供应链子任务需要送审");
        if (!"delivered".equals(task.getStatus())) throw new RuntimeException("当前子任务不在待送审状态");
        task.setStatus("submitted_for_review");
        if (pointsService != null && !task.isSelfInitiated()) pointsService.awardBaseSubmission(task);
        String user = (String) body.getOrDefault("currentUser", "");
        p.getLogs().add(new ActivityLog("子任务送审：" + task.getName(), user, role, p));
        Project saved = projectRepository.saveAndFlush(p);
        safeNotifyAfterCommit("TASK_SUBMITTED_FOR_REVIEW", p.getPlannerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, user, ""));
        return saved;
    }

    public Project taskRedeliver(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = lockSubTask(projectId, taskId);

        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        if (currentUserId.isBlank() || !currentUserId.equals(task.getDesignerId())) {
            throw new RuntimeException("仅当前子任务负责人可重新交付");
        }

        String submittedActualDate = (String) body.get("actualDate");
        task.setStatus("delivered");
        task.setActualDate(null);
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setReferenceImagesJson(validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        String previousReviewComments = task.getReviewComments();
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
        saveDeliveryVersion(task, "redelivery", changeSummary, submittedActualDate, currentUserId, currentUser, currentRole);
        p.getLogs().add(new ActivityLog("子任务重新交付：" + task.getName(), currentUser, currentRole, p));

        Project saved = projectRepository.save(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        safeNotifyAfterCommit("TASK_REDELIVERED", p.getPlannerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, currentUser, previousReviewComments));
        if ("channel_custom".equals(p.getType()) && p.getSalesId() != null && !p.getSalesId().isBlank()) {
            safeNotifyAfterCommit("TASK_REDELIVERED", p.getSalesId(), "sub_task", task.getId(), currentUserId,
                    notificationContext(p, task, currentUser, previousReviewComments));
        }
        return saved;
    }

    /** 被驳回负责人确认开始修改，任务回到执行中。 */
    public Project taskConfirmRevision(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);
        SubTask task = lockSubTask(projectId, taskId);
        String userId = (String) body.getOrDefault("currentUserId", "");
        if (!userId.equals(task.getDesignerId())) throw new RuntimeException("仅当前子任务负责人可确认修改");
        if (!"rejected".equals(task.getStatus())) throw new RuntimeException("当前子任务不是已驳回状态");
        task.setStatus("accepted");
        String user = (String) body.getOrDefault("currentUser", "");
        String role = (String) body.getOrDefault("currentRole", "");
        p.getLogs().add(new ActivityLog("确认修改子任务：" + task.getName(), user, role, p));
        return projectRepository.saveAndFlush(p);
    }

    public Project taskCorrectDelivery(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);
        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("当前项目状态不允许修正交付");
        }
        SubTask task = lockSubTask(projectId, taskId);
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
        String submittedActualDate = (String) body.get("actualDate");
        task.setStatus("delivered");
        task.setActualDate(null);
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
        saveDeliveryVersion(task, "correction", changeSummary, submittedActualDate, currentUserId, currentUser, currentRole);
        p.getLogs().add(new ActivityLog("子任务主动修正交付：" + task.getName()
                + "（" + changeSummary + "）", currentUser, currentRole, p));
        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        safeNotifyAfterCommit("TASK_REDELIVERED", p.getPlannerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, currentUser, changeSummary));
        if ("channel_custom".equals(p.getType()) && p.getSalesId() != null && !p.getSalesId().isBlank()
                && !p.getSalesId().equals(currentUserId)) {
            safeNotifyAfterCommit("TASK_REDELIVERED", p.getSalesId(), "sub_task", task.getId(), currentUserId,
                    notificationContext(p, task, currentUser, "销售关联项目已收到重新交付成果"));
        }
        return saved;
    }

    private void saveDeliveryVersion(SubTask task, String submissionType, String changeSummary,
                                     String submittedActualDate, String userId, String userName, String role) {
        SubTaskDeliveryVersion version = new SubTaskDeliveryVersion();
        version.setSubTask(task);
        version.setVersionNo(deliveryVersionRepository.findMaxVersionNoBySubTaskId(task.getId()) + 1);
        version.setSubmissionType(submissionType);
        version.setChangeSummary(changeSummary);
        version.setDeliverables(task.getDeliverables());
        version.setReferenceImagesJson(task.getReferenceImagesJson());
        version.setAttachmentsJson(task.getAttachmentsJson());
        version.setActualDate(submittedActualDate);
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
        Project p = lockProject(projectId);

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = lockSubTask(projectId, taskId);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        String comments = SecurityUtil.sanitizeText((String) body.getOrDefault("comments", ""), 500);
        boolean isChannel = "channel_custom".equals(p.getType());
        Integer score = parseOptionalScore(body.get("score"));

        if (!canReviewTask(p, task, currentRole, currentUserId)) {
            throw new RuntimeException("当前用户无权验收该子任务");
        }

        // 防止按钮重复点击：首个请求已推进状态时，后续重复请求直接返回当前项目。
        boolean alreadyProcessed = ("planner".equals(currentRole) && "planner_approved".equals(task.getStatus()))
                || ("sales".equals(currentRole) && "sales_approved".equals(task.getStatus()))
                || ("admin".equals(currentRole) && "admin_approved".equals(task.getStatus()));
        if (alreadyProcessed) {
            return p;
        }

        if ("submitted_for_review".equals(task.getStatus()) && "planner".equals(currentRole)) {
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

        // 检查是否所有评分已完成；最终验收通过时发放质量加分（与评分中心 submitScoring 共用同一发分路径）
        finalizeTaskApproval(task, p);
        Project saved = projectRepository.save(p);
        Map<String, String> notifyContext = notificationContext(saved, task, currentUser, comments);
        notifyContext.put("reviewRole", "planner".equals(currentRole) ? "产品企划" : ("admin".equals(currentRole) ? "管理员" : "销售"));
        safeNotifyAfterCommit("REVIEW_APPROVED", task.getDesignerId(), "sub_task", task.getId(),
                currentUserId, notifyContext);
        return saved;
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
                task.setActualDate(java.time.LocalDate.now().toString());
            }
        } else {
            // 常规品：企划评分 + 管理评分 → completed
            if ("admin_approved".equals(task.getStatus())) {
                task.setStatus("completed");
                task.setActualDate(java.time.LocalDate.now().toString());
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

    /**
     * 最终验收通过（任务进入 completed）时发放质量加分。
     * 任务详情入口（taskApprove）与评分中心入口（submitScoring）共用本方法，
     * 保证同一业务动作「最终验收通过」在两个入口的积分结果一致；
     * awardQualityCompletion 内部以用户+子任务+规则码幂等，同一任务只会发放一次。
     */
    private void finalizeTaskApproval(SubTask task, Project project) {
        checkTaskCompletion(task, project);
        if ("completed".equals(task.getStatus()) && pointsService != null && !task.isSelfInitiated()) {
            pointsService.awardQualityCompletion(task);
        }
    }

    public Project taskReject(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = lockSubTask(projectId, taskId);

        String comments = SecurityUtil.sanitizeText((String) body.get("comments"), 500);
        String requiredCompletionDate = SecurityUtil.sanitizeText((String) body.get("requiredCompletionDate"), 20);
        if (requiredCompletionDate == null || !requiredCompletionDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new RuntimeException("请选择有效的要求完成时间");
        }
        String rejectionReferenceImagesJson = validateAndCleanFiles(
                (String) body.getOrDefault("rejectionReferenceImagesJson", "[]"), true);
        String rejectionAttachmentsJson = validateAndCleanFiles(
                (String) body.getOrDefault("rejectionAttachmentsJson", "[]"), false);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        boolean canReject = ("planner".equals(currentRole) && List.of("delivered", "submitted_for_review").contains(task.getStatus()))
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
        task.setPlannedDate(requiredCompletionDate);
        Map<String, Object> submittedSnapshot = new LinkedHashMap<>();
        submittedSnapshot.put("deliverables", task.getDeliverables());
        submittedSnapshot.put("referenceImagesJson", task.getReferenceImagesJson());
        submittedSnapshot.put("attachmentsJson", task.getAttachmentsJson());
        submittedSnapshot.put("actualDate", task.getActualDate());
        submittedSnapshot.put("submittedById", task.getDesignerId());
        submittedSnapshot.put("submittedByName", task.getDesignerName());
        Map<String, Object> rejectionSnapshot = new LinkedHashMap<>();
        rejectionSnapshot.put("reason", comments == null ? "" : comments);
        rejectionSnapshot.put("requiredCompletionDate", requiredCompletionDate);
        rejectionSnapshot.put("rejectionReferenceImagesJson", rejectionReferenceImagesJson);
        rejectionSnapshot.put("rejectionAttachmentsJson", rejectionAttachmentsJson);
        p.getLogs().add(new ActivityLog(
                "子任务驳回：" + task.getName() + "（意见：" + comments + "）",
                currentUser, currentRole, p, "sub_task", task.getId(),
                toJson(submittedSnapshot),
                toJson(rejectionSnapshot),
                "status,reviewComments,plannedDate,rejectionReferenceImagesJson,rejectionAttachmentsJson"));
        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(rejectionReferenceImagesJson, "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(rejectionAttachmentsJson, "sub_task", task.getId());
        safeNotifyAfterCommit("TASK_REJECTED", task.getDesignerId(), "sub_task", task.getId(), currentUserId,
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
        Project p = lockProject(projectId);

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = lockSubTask(projectId, taskId);

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
        boolean validStage = ("planner".equals(role) && "submitted_for_review".equals(task.getStatus()))
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

        // 最终验收通过时发放质量加分（与任务详情 taskApprove 共用同一发分路径）
        finalizeTaskApproval(task, p);
        return projectRepository.save(p);
    }

    // ==================== Terminate / Pause / Resume ====================

    public Project terminateProject(Long projectId, Map<String, Object> body) {
        Project p = lockProject(projectId);
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
        Project p = lockProject(projectId);
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
        Project p = lockProject(projectId);
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
        Project p = lockProject(projectId);
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

    /**
     * 删除项目并清理全部关联数据，避免遗留孤儿记录。
     * 现有表大多没有 FK 级联（baseline 为空迁移），必须显式清理，删除顺序满足 FK 依赖：
     * 调账(APPEAL/TASK_WITHDRAWAL) → 退单(FK→sub_tasks) → 异议(FK→point_ledgers) →
     * 积分流水 → 交付版本(FK→sub_tasks) → 评分(FK→sub_tasks) → 通知 → 文件记录 →
     * 项目本身（级联删除子任务与操作日志）。
     */
    public void deleteProject(Long projectId) {
        List<SubTask> tasks = subTaskRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        List<Long> taskIds = tasks.stream().map(SubTask::getId).toList();

        if (taskIds.isEmpty()) {
            scoringRepository.deleteByProjectId(projectId);
            projectRepository.deleteById(projectId);
            return;
        }

        // 1) 关联调账记录（异议调账按 appealId、退单调账按 withdrawalId）
        List<Long> withdrawalIds = taskWithdrawalRepository == null ? List.of()
                : taskWithdrawalRepository.findBySubTaskIdIn(taskIds).stream().map(TaskWithdrawal::getId).toList();
        List<Long> ledgerIds = pointLedgerRepository == null ? List.of()
                : pointLedgerRepository.findBySubTaskIdIn(taskIds).stream().map(PointLedger::getId).toList();
        List<Long> appealIds = (pointAppealRepository == null || ledgerIds.isEmpty()) ? List.of()
                : pointAppealRepository.findByPointLedgerIdIn(ledgerIds).stream().map(PointAppeal::getId).toList();
        if (pointAdjustmentLedgerRepository != null
                && (!appealIds.isEmpty() || !withdrawalIds.isEmpty())) {
            pointAdjustmentLedgerRepository.deleteProjectRelated(appealIds, withdrawalIds);
        }
        // 2) 退单记录（FK → sub_tasks，必须先删）
        if (taskWithdrawalRepository != null) taskWithdrawalRepository.deleteBySubTaskIds(taskIds);
        // 3) 积分异议（FK → point_ledgers，必须先删）
        if (pointAppealRepository != null && !ledgerIds.isEmpty()) pointAppealRepository.deleteByPointLedgerIds(ledgerIds);
        // 4) 积分流水
        if (pointLedgerRepository != null) pointLedgerRepository.deleteBySubTaskIds(taskIds);
        // 5) 交付版本（FK → sub_tasks）
        deliveryVersionRepository.deleteBySubTaskIds(taskIds);
        // 6) 评分记录（FK → sub_tasks）
        scoringRepository.deleteByProjectId(projectId);
        // 7) 通知（project / sub_task 聚合）
        if (notificationRepository != null) {
            notificationRepository.deleteByAggregateTypeAndAggregateIdIn("project", List.of(projectId));
            notificationRepository.deleteByAggregateTypeAndAggregateIdIn("sub_task", taskIds);
        }
        // 8) 文件记录（project / sub_task 目标）
        if (fileRecordRepository != null) {
            fileRecordRepository.deleteByTargetTypeAndTargetIdIn("project", List.of(projectId));
            fileRecordRepository.deleteByTargetTypeAndTargetIdIn("sub_task", taskIds);
        }
        // 9) 项目本身（级联删除子任务与操作日志；@PostRemove 在提交后入队飞书删除）
        projectRepository.deleteById(projectId);
    }

    // ==================== Role Status Board ====================

    /** 获取指定角色的状态看板（销售/企划/供应链/设计师） */
    public Map<String, Object> getRoleStatus(String role, String viewerRole, String viewerUserId) {
        return getRoleStatus(role, viewerRole, viewerUserId, "all");
    }

    public Map<String, Object> getRoleStatus(String role, String viewerRole, String viewerUserId, String scope) {
        List<User> users = projectAccessService.visibleUsers(viewerRole, viewerUserId, role);
        if ("planner".equals(viewerRole) && "planner".equals(role) && "mine".equalsIgnoreCase(scope)) {
            users = users.stream().filter(u -> Objects.equals(u.getUserId(), viewerUserId)).toList();
        }
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
                // 状态看板只展示负责人仍需执行的任务；已交付/送审中交由企划审核，不再算执行中。
                List<SubTask> activeTasks = userTasks.stream()
                        .filter(t -> List.of("accepted", "rejected").contains(t.getStatus()))
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
                if ("planner".equals(role)) {
                    List<Map<String, Object>> activeTasks = activeProjects.stream()
                            .flatMap(p -> p.getTasks().stream()
                                    .filter(t -> List.of("pending", "accepted", "rejected").contains(t.getStatus()))
                                    .map(t -> {
                                        Map<String, Object> tm = new LinkedHashMap<>();
                                        tm.put("id", t.getId()); tm.put("name", t.getName()); tm.put("status", t.getStatus());
                                        tm.put("projectId", p.getId()); return tm;
                                    }))
                            .toList();
                    info.put("activeTasks", activeTasks);
                }
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
                item.put("lastActivityAt", records.stream().map(ScoringRecord::getReviewedAt).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
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
