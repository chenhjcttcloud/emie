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
    private void validateCustomPriceRange(String value) {
        try {
            double price = Double.parseDouble(value.trim());
            if (!Double.isFinite(price) || price < 0 || price > 1000 || Math.round(price * 100) != price * 100) {
                throw new IllegalArgumentException("参考零售价必须在0到1,000之间，最多两位小数");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参考零售价必须在0到1,000之间，最多两位小数");
        }
    }
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
    private SubTaskAssignmentPolicy subTaskAssignmentPolicy;
    private SubTaskInputPolicy subTaskInputPolicy;
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
    @Autowired(required = false)
    void setSubTaskAssignmentPolicy(SubTaskAssignmentPolicy policy) { this.subTaskAssignmentPolicy = policy; }
    @Autowired(required = false)
    void setSubTaskInputPolicy(SubTaskInputPolicy policy) { this.subTaskInputPolicy = policy; }

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
            validateCustomPriceRange(priceRangeStr);
            p.setPriceRange(priceRangeStr.trim());
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
        String priceRange = SecurityUtil.sanitizeText((String) body.getOrDefault("priceRange", ""), 100);
        if (priceRange != null && !priceRange.isBlank()) validateCustomPriceRange(priceRange);
        p.setPriceRange(priceRange);

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

    /** 当前系统设置中的角色权重（小数形式），用于历史评分重新核算。 */
    public double currentScoringWeight(String projectType, String role) {
        return getScoringPct(projectType, role) / 100.0;
    }

    private Map<String, Double> scoringWeightMap(String projectType) {
        Map<String, Double> weights = new HashMap<>();
        for (String role : List.of("planner", "sales", "designer", "admin")) {
            weights.put(role, getScoringPct(projectType, role) / 100.0);
        }
        return weights;
    }

    /** 从 SystemConfig 读取评分权重，不存在则返回 1.0 */
    private double getScoringWeight(String role) {
        String key = "scoring.weight." + role;
        return systemConfigRepository.findByConfigKey(key)
            .map(c -> { try { return Double.parseDouble(c.getConfigValue()); } catch (Exception e) { return 1.0; } })
            .orElse(1.0);
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

        if ("designer".equals(role) || "supplychain".equals(role) || "promotion".equals(role)) {
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
                info.put("label", "designer".equals(role) ? "设计师" : ("promotion".equals(role) ? "产品推广" : "供应链"));
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
                    pm.put("projectCode", p.getProjectCode());
                    pm.put("productName", p.getProductName());
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
        List<String> requiredRoles = getRequiredScoringRoles(task);
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
        Map<String, Double> weights = scoringWeightMap(project.getType());
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
                    double weight = weights.getOrDefault(sr.getRole(), 0.25);
                    weightedSum += normalizedScore * weight;
                    totalWeight += weight;
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
        Map<String, Map<String, Double>> weightsByType = projects.stream()
                .map(Project::getType).filter(Objects::nonNull).distinct()
                .collect(Collectors.toMap(type -> type, this::scoringWeightMap));
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
                        Map<String, Double> weights = weightsByType.get(task.getProject().getType());
                        if (weights == null) weights = scoringWeightMap("regular");
                        double weight = weights.getOrDefault(sr.getRole(), 0.25);
                        weightedSum += normalizedScore * weight;
                        totalWeight += weight;
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
                    m.put("weight", getScoringPct(p.getType(), sr.getRole()) / 100.0);
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

    private List<String> getRequiredScoringRoles(SubTask task) {
        List<String> roles = new ArrayList<>(getRequiredScoringRoles(
                task.getProject() != null ? task.getProject().getType() : "regular"));
        if ("designer".equalsIgnoreCase(task.getAssigneeRole()) && task.getSelfScore() != null) {
            roles.add("designer");
        }
        return roles;
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
