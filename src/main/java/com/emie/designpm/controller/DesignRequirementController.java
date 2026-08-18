package com.emie.designpm.controller;

import com.emie.designpm.dto.PageResponse;
import com.emie.designpm.entity.DesignRequirement;
import com.emie.designpm.repository.DesignRequirementRepository;
import com.emie.designpm.repository.DesignRequirementScoreRepository;
import com.emie.designpm.service.DesignRequirementScoringService;
import com.emie.designpm.service.UserService;
import com.emie.designpm.service.PermissionService;
import com.emie.designpm.service.NotificationWorkflowService;
import com.emie.designpm.service.FeishuChatService;
import com.emie.designpm.entity.User;
import com.emie.designpm.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/design-requirements")
public class DesignRequirementController {
    private final DesignRequirementRepository repository;
    private final DesignRequirementScoreRepository scoreRepository;
    private final DesignRequirementScoringService scoringService;
    private final UserService userService;
    private final PermissionService permissionService;
    private final NotificationWorkflowService notificationWorkflowService;
    private final FeishuChatService feishuChatService;
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    public DesignRequirementController(DesignRequirementRepository repository,
                                       DesignRequirementScoreRepository scoreRepository,
                                       DesignRequirementScoringService scoringService,
                                       UserService userService,
                                       PermissionService permissionService,
                                       NotificationWorkflowService notificationWorkflowService,
                                       FeishuChatService feishuChatService) {
        this.repository = repository;
        this.scoreRepository = scoreRepository;
        this.scoringService = scoringService;
        this.userService = userService;
        this.permissionService = permissionService;
        this.notificationWorkflowService = notificationWorkflowService;
        this.feishuChatService = feishuChatService;
    }

    /** 保留给轻量单元测试；生产运行始终使用完整依赖构造器。 */
    DesignRequirementController(DesignRequirementRepository repository) {
        this(repository, null, null, null, null, null, null);
    }

    DesignRequirementController(DesignRequirementRepository repository,
                                DesignRequirementScoreRepository scoreRepository,
                                DesignRequirementScoringService scoringService,
                                UserService userService) {
        this(repository, scoreRepository, scoringService, userService, null, null, null);
    }

    DesignRequirementController(DesignRequirementRepository repository,
                                DesignRequirementScoreRepository scoreRepository,
                                DesignRequirementScoringService scoringService,
                                UserService userService,
                                PermissionService permissionService) {
        this(repository, scoreRepository, scoringService, userService, permissionService, null, null);
    }

    DesignRequirementController(DesignRequirementRepository repository,
                                DesignRequirementScoreRepository scoreRepository,
                                DesignRequirementScoringService scoringService,
                                UserService userService,
                                PermissionService permissionService,
                                NotificationWorkflowService notificationWorkflowService) {
        this(repository, scoreRepository, scoringService, userService, permissionService, notificationWorkflowService, null);
    }

    @GetMapping("/page")
    public ResponseEntity<?> page(@RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "15") int size,
                                  HttpServletRequest request) {
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        if (session == null) return ResponseEntity.status(401).build();
        String userId = "admin".equals(session.role()) ? null : session.userId();
        var result = repository.findPage(blankToNull(keyword), blankToNull(status), userId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
        var items = result.getContent().stream().map(this::toRow).toList();
        return ResponseEntity.ok(new PageResponse<>(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id, HttpServletRequest request) {
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        if (session == null) return ResponseEntity.status(401).build();
        DesignRequirement requirement = repository.findById(id).orElse(null);
        if (requirement == null) return ResponseEntity.notFound().build();
        boolean visible = "admin".equals(session.role())
                || session.userId().equals(requirement.getOwnerId())
                || session.userId().equals(requirement.getResponsibleId())
                || session.userId().equals(requirement.getPlannerId())
                || session.userId().equals(requirement.getDesignerId());
        if (!visible) return ResponseEntity.status(403).body(java.util.Map.of("error", "无权查看该设计/送审需求"));
        return ResponseEntity.ok(toDetail(requirement));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody java.util.Map<String, Object> body, HttpServletRequest request) {
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        if (session == null) return ResponseEntity.status(401).build();
        String creatorRole = normalizeRole(session.role());
        if (permissionService != null && !permissionService.has(creatorRole, "design_requirement.create")) {
            return ResponseEntity.status(403).body(java.util.Map.of(
                    "error", "当前账号没有新建设计/送审需求的权限",
                    "permission", "design_requirement.create"));
        }
        if (!java.util.Set.of("planner", "sales", "promotion").contains(creatorRole)) {
            return ResponseEntity.status(403).body(java.util.Map.of("error", "当前角色无权创建设计/送审需求"));
        }
        String name = text(body.get("productName"));
        String deadline = text(body.get("deadline"));
        String requirements = text(body.get("productRequirements"));
        String designerId = text(body.get("designerId"));
        String plannerId = "planner".equals(creatorRole) ? session.userId() : text(body.get("plannerId"));
        if (name == null || requirements == null || deadline == null || designerId == null || plannerId == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "产品名称、设计师、产品企划、要求完成时间和产品要求不能为空"));
        }
        User designer = userService == null ? null : userService.getUserByUserId(designerId);
        User planner = userService == null ? null : userService.getUserByUserId(plannerId);
        if (userService != null && !isActiveRole(designer, "designer")) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "请选择有效的在职设计师"));
        }
        if (userService != null && !isActiveRole(planner, "planner")) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "请选择有效的在职产品企划"));
        }
        String designerName = designer != null ? designer.getName() : text(body.get("designerName"));
        String plannerName = planner != null ? planner.getName()
                : ("planner".equals(creatorRole) ? session.name() : text(body.get("plannerName")));
        DesignRequirement d = new DesignRequirement();
        // 用户文本字段统一清洗（去 HTML 标签/危险脚本，按列长截断）；JSON 结构字段不在此列。
        d.setName(SecurityUtil.sanitizeText(name, 200));
        d.setDeadline(deadline);
        d.setRequirements(SecurityUtil.sanitizeText(requirements, 2000));
        d.setDescription(SecurityUtil.sanitizeText(text(body.get("description")), 2000));
        d.setCustomerName(SecurityUtil.sanitizeText(text(body.get("customerName")), 100));
        // 需求负责人始终以当前登录账号为准，不信任前端传入的负责人身份。
        d.setResponsibleId(session.userId());
        d.setResponsibleName(session.name());
        d.setResponsibleRole(creatorRole);
        d.setPlannerId(plannerId); d.setPlannerName(plannerName);
        d.setDesignerId(designerId); d.setDesignerName(designerName);
        d.setAttachmentsJson(text(body.get("attachmentsJson")));
        d.setReferenceImagesJson(text(body.get("referenceImagesJson")));
        d.setOwnerId(session.userId()); d.setOwnerName(session.name());
        d.setRequirementCode("DR" + java.time.LocalDate.now().toString().replace("-", "") + System.currentTimeMillis() % 10000);
        DesignRequirement saved = repository.save(d);
        if (scoringService != null) scoringService.initialize(saved);
        notifyAssignees(saved, session);
        return ResponseEntity.ok(java.util.Map.of("id", saved.getId(), "requirementCode", saved.getRequirementCode()));
    }

    private void notifyAssignees(DesignRequirement requirement, AuthController.AuthSession actor) {
        if (notificationWorkflowService == null) return;
        java.util.Map<String, String> context = notificationContext(requirement, actor.name());
        notificationWorkflowService.notifyUserAfterCommit(
                "DESIGN_REQUIREMENT_DESIGNER_ASSIGNED", requirement.getDesignerId(),
                "design_requirement", requirement.getId(), actor.userId(), context);
        notificationWorkflowService.notifyUserAfterCommit(
                "DESIGN_REQUIREMENT_ASSIGNED", requirement.getPlannerId(),
                "design_requirement", requirement.getId(), actor.userId(), context);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard(HttpServletRequest request) {
        AuthController.AuthSession session = session(request);
        if (session == null) return ResponseEntity.status(401).build();
        String userId = "admin".equals(normalizeRole(session.role())) ? null : session.userId();
        return ResponseEntity.ok(repository.findDashboardItems(userId).stream().map(this::toDetail).toList());
    }

    @PostMapping("/{id}/deliver")
    @Transactional
    public ResponseEntity<?> deliver(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body,
                                     HttpServletRequest request) {
        AuthController.AuthSession session = session(request);
        if (session == null) return ResponseEntity.status(401).build();
        if (permissionService != null && !permissionService.has(session.role(), "design_requirement.deliver")) {
            return forbidden("design_requirement.deliver");
        }
        DesignRequirement d = repository.findById(id).orElse(null);
        if (d == null) return ResponseEntity.notFound().build();
        if (!"designer".equals(normalizeRole(session.role())) || !session.userId().equals(d.getDesignerId())) {
            return ResponseEntity.status(403).body(java.util.Map.of("error", "仅该需求的设计师可以提交交付成果"));
        }
        if (!java.util.Set.of("draft", "in_progress", "rejected").contains(d.getStatus())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "当前需求已提交，不能重复交付"));
        }
        String content = text(body.get("deliveryContent"));
        if (content == null) return ResponseEntity.badRequest().body(java.util.Map.of("error", "请填写交付成果"));
        d.setDeliveryContent(SecurityUtil.sanitizeText(content, 2000));
        d.setDeliveryAttachmentsJson(text(body.get("deliveryAttachmentsJson")));
        d.setDeliveryReferenceImagesJson(text(body.get("deliveryReferenceImagesJson")));
        d.setDeliveredAt(java.time.LocalDateTime.now());
        d.setStatus("pending_self_score");
        repository.save(d);
        scoringService.activateSelfScore(d);
        // 设计/送审需求统一通知创建人：无论由销售、产品推广还是产品企划创建，
        // 首次交付和驳回后的重新交付都回到同一个需求发起人。
        String ownerId = d.getOwnerId();
        if (ownerId != null && !ownerId.isBlank() && notificationWorkflowService != null) {
            try {
                java.util.Map<String, String> context = notificationContext(d, session.name());
                notificationWorkflowService.notifyUserAfterCommit("DESIGN_REQUIREMENT_DELIVERED", ownerId,
                        "design_requirement", d.getId(), session.userId(), context);
            } catch (Exception ignored) { }
        }
        return ResponseEntity.ok(toDetail(d));
    }

    /** 设计/送审需求被驳回后，设计师确认开始修改。 */
    @PostMapping("/{id}/confirm-revision")
    @Transactional
    public ResponseEntity<?> confirmRevision(@PathVariable Long id, HttpServletRequest request) {
        AuthController.AuthSession session = session(request);
        if (session == null) return ResponseEntity.status(401).build();
        if (permissionService != null && !permissionService.has(session.role(), "design_requirement.deliver")) {
            return forbidden("design_requirement.deliver");
        }
        DesignRequirement d = repository.findById(id).orElse(null);
        if (d == null) return ResponseEntity.notFound().build();
        if (!"designer".equals(normalizeRole(session.role())) || !session.userId().equals(d.getDesignerId())) {
            return ResponseEntity.status(403).body(java.util.Map.of("error", "仅该需求的设计师可以确认修改"));
        }
        if (!"rejected".equals(d.getStatus())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "当前需求不是已驳回状态"));
        }
        d.setStatus("in_progress");
        repository.save(d);
        return ResponseEntity.ok(toDetail(d));
    }

    @PostMapping("/{id}/reject")
    @Transactional
    public ResponseEntity<?> reject(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body,
                                    HttpServletRequest request) {
        AuthController.AuthSession session = session(request);
        if (session == null) return ResponseEntity.status(401).build();
        if (permissionService != null && !permissionService.has(session.role(), "design_requirement.score.review")) {
            return forbidden("design_requirement.score.review");
        }
        DesignRequirement d = repository.findById(id).orElse(null);
        if (d == null) return ResponseEntity.notFound().build();
        if (!"planner".equals(normalizeRole(session.role())) || !session.userId().equals(d.getPlannerId())) {
            return ResponseEntity.status(403).body(java.util.Map.of("error", "仅该需求的产品企划可以驳回"));
        }
        if (!java.util.Set.of("pending_self_score", "pending_review").contains(d.getStatus())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "当前状态不能驳回"));
        }
        String comments = text(body.get("comments"));
        String deadline = text(body.get("requiredCompletionDate"));
        if (comments == null || deadline == null) return ResponseEntity.badRequest().body(java.util.Map.of("error", "驳回意见和要求完成时间不能为空"));
        d.setRejectionComments(SecurityUtil.sanitizeText(comments, 2000)); d.setRejectionDeadline(deadline); d.setStatus("rejected");
        repository.save(d);
        if (notificationWorkflowService != null) {
            java.util.Map<String, String> context = notificationContext(d, session.name());
            context.put("deadline", deadline);
            context.put("reason", comments);
            notificationWorkflowService.notifyUserAfterCommit("DESIGN_REQUIREMENT_REJECTED", d.getDesignerId(), "design_requirement", id, session.userId(), context);
        }
        return ResponseEntity.ok(toDetail(d));
    }

    @PostMapping("/{id}/terminate")
    @Transactional
    public ResponseEntity<?> terminate(@PathVariable Long id, HttpServletRequest request) {
        AuthController.AuthSession session = session(request);
        if (session == null) return ResponseEntity.status(401).build();
        DesignRequirement d = repository.findById(id).orElse(null);
        if (d == null) return ResponseEntity.notFound().build();
        boolean canTerminate = "admin".equals(normalizeRole(session.role()))
                || session.userId().equals(d.getOwnerId())
                || session.userId().equals(d.getResponsibleId())
                || session.userId().equals(d.getPlannerId());
        if (!canTerminate) {
            return ResponseEntity.status(403).body(java.util.Map.of("error", "仅需求负责人或管理员可以终止"));
        }
        if ("completed".equals(d.getStatus()) || "terminated".equals(d.getStatus())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "已完成或已终止的需求不能再次终止"));
        }
        d.setStatus("terminated");
        repository.save(d);
        if (scoreRepository != null) {
            var records = scoreRepository.findByRequirementIdOrderByIdAsc(d.getId());
            records.stream().filter(s -> !"completed".equals(s.getStatus())).forEach(s -> s.setStatus("waiting"));
            scoreRepository.saveAll(records);
        }
        if (notificationWorkflowService != null) {
            var context = notificationContext(d, session.name());
            notifyDistinct("DESIGN_REQUIREMENT_TERMINATED", d, session, context,
                    d.getOwnerId(), d.getDesignerId(), d.getPlannerId());
        }
        return ResponseEntity.ok(toDetail(d));
    }

    @PostMapping("/{id}/self-score")
    public ResponseEntity<?> selfScore(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body,
                                       HttpServletRequest request) {
        return score(id, body, request, true);
    }

    @PostMapping("/{id}/score")
    public ResponseEntity<?> reviewScore(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body,
                                         HttpServletRequest request) {
        return score(id, body, request, false);
    }

    private ResponseEntity<?> score(Long id, java.util.Map<String, Object> body, HttpServletRequest request,
                                    boolean self) {
        AuthController.AuthSession session = session(request);
        if (session == null) return ResponseEntity.status(401).build();
        String permission = self ? "design_requirement.score.self" : "design_requirement.score.review";
        if (permissionService != null && !permissionService.has(session.role(), permission)) {
            return forbidden(permission);
        }
        DesignRequirement d = repository.findById(id).orElse(null);
        if (d == null) return ResponseEntity.notFound().build();
        if ("terminated".equals(d.getStatus())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "已终止的需求不能评分"));
        }
        try {
            int value = Integer.parseInt(String.valueOf(body.get("score")));
            if (self) scoringService.submitSelfScore(d, session, value);
            else scoringService.submitReview(d, session, value);
            repository.save(d);
            if (notificationWorkflowService != null) {
                java.util.Map<String, String> context = notificationContext(d, session.name());
                if (self) {
                    scoreRepository.findByRequirementIdOrderByIdAsc(d.getId()).stream()
                            .filter(s -> "review".equals(s.getStage()) && "pending".equals(s.getStatus()))
                            .forEach(s -> {
                                if (s.getReviewerId() != null && !s.getReviewerId().isBlank()) {
                                    notificationWorkflowService.notifyUserAfterCommit(
                                            "DESIGN_REQUIREMENT_REVIEW_PENDING", s.getReviewerId(),
                                            "design_requirement", d.getId(), session.userId(), context);
                                } else if ("admin".equals(s.getRole())) {
                                    notificationWorkflowService.notifyRole(
                                            "DESIGN_REQUIREMENT_REVIEW_PENDING", "admin",
                                            "design_requirement", d.getId(), session.userId(), context);
                                }
                            });
                } else if ("completed".equals(d.getStatus())) {
                    notifyDistinct("DESIGN_REQUIREMENT_COMPLETED", d, session, context,
                            d.getOwnerId(), d.getDesignerId(), d.getPlannerId());
                }
            }
            return ResponseEntity.ok(toDetail(d));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "评分必须为1-100分"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    private java.util.Map<String, String> notificationContext(DesignRequirement d, String actorName) {
        java.util.Map<String, String> context = new java.util.LinkedHashMap<>();
        context.put("projectName", d.getName());
        context.put("taskName", d.getName());
        context.put("deadline", d.getDeadline());
        context.put("actorName", actorName);
        context.put("projectLink", "/?view=design-needs&requirementId=" + d.getId());
        return context;
    }

    private void notifyDistinct(String eventType, DesignRequirement d, AuthController.AuthSession actor,
                                java.util.Map<String, String> context, String... recipients) {
        java.util.Arrays.stream(recipients).filter(java.util.Objects::nonNull)
                .filter(id -> !id.isBlank()).distinct()
                .forEach(id -> notificationWorkflowService.notifyUserAfterCommit(eventType, id,
                        "design_requirement", d.getId(), actor.userId(), context));
    }

    private ResponseEntity<?> forbidden(String permission) {
        return ResponseEntity.status(403).body(java.util.Map.of(
                "error", "当前账号没有执行此操作的权限",
                "permission", permission));
    }

    private String text(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return value.toString().trim();
    }

    private java.util.Map<String, Object> toRow(DesignRequirement d) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("id", d.getId()); row.put("projectCode", d.getRequirementCode());
        row.put("type", "design_requirement"); row.put("status", d.getStatus());
        row.put("statusLabel", switch (d.getStatus()) {
            case "draft" -> "待设计交付";
            case "in_progress" -> "设计中";
            case "pending_self_score" -> "待设计师自评";
            case "pending_review" -> "待复评";
            case "completed" -> "已完成";
            case "terminated" -> "已终止";
            default -> d.getStatus();
        });
        row.put("statusCls", switch (d.getStatus()) {
            case "draft" -> "badge-pending";
            case "pending_self_score" -> "badge-self-score";
            case "pending_review" -> "badge-review";
            case "completed" -> "badge-completed";
            case "terminated" -> "badge-rejected";
            default -> "badge-progress";
        });
        row.put("productName", d.getName()); row.put("salesName", d.getOwnerName());
        row.put("responsibleName", d.getResponsibleName());
        row.put("plannerName", d.getPlannerName());
        row.put("customerName", d.getCustomerName()); row.put("deadline", d.getDeadline());
        row.put("productRequirements", d.getRequirements()); row.put("taskCount", 0);
        row.put("approvedTaskCount", 0); row.put("progressPercent", 0);
        row.put("createdAt", d.getCreatedAt() == null ? null : d.getCreatedAt().format(DTF));
        row.put("updatedAt", d.getUpdatedAt() == null ? null : d.getUpdatedAt().format(DTF));
        return row;
    }

    @PostMapping("/{id}/feishu-chat/create")
    @Transactional
    public ResponseEntity<?> createChat(@PathVariable Long id, HttpServletRequest request) {
        try {
            AuthController.AuthSession s = session(request);
            DesignRequirement d = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("设计需求不存在"));
            if (!canManageChat(d, s)) return ResponseEntity.status(403).body(java.util.Map.of("error", "仅管理员或相关产品企划可创建设计需求群"));
            if (feishuChatService == null || !feishuChatService.enabled()) return ResponseEntity.badRequest().body(java.util.Map.of("error", "飞书应用配置未完成"));
            if (d.getFeishuChatId() != null && !d.getFeishuChatId().isBlank()) return ResponseEntity.ok(toDetail(d));
            String name = "设计需求-#" + d.getId() + "-" + (d.getPlannerName() == null ? "" : d.getPlannerName());
            String chatId = feishuChatService.createChat(name, java.util.List.of());
            d.setFeishuChatId(chatId); d.setFeishuChatStatus("created"); d.setFeishuChatError(null); d.setFeishuChatCreatedAt(java.time.LocalDateTime.now());
            return ResponseEntity.ok(toDetail(repository.save(d)));
        } catch (Exception e) { return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage())); }
    }

    @PostMapping("/{id}/feishu-chat/dissolve")
    @Transactional
    public ResponseEntity<?> dissolveChat(@PathVariable Long id, HttpServletRequest request) {
        try {
            AuthController.AuthSession s = session(request);
            DesignRequirement d = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("设计需求不存在"));
            if (!canManageChat(d, s)) return ResponseEntity.status(403).body(java.util.Map.of("error", "无权解散该设计需求群"));
            if (!java.util.Set.of("completed", "terminated").contains(d.getStatus())) return ResponseEntity.badRequest().body(java.util.Map.of("error", "设计需求未完成或终止，不能解散群聊"));
            feishuChatService.dissolve(d.getFeishuChatId()); d.setFeishuChatStatus("dissolved"); d.setFeishuChatDissolvedAt(java.time.LocalDateTime.now());
            return ResponseEntity.ok(toDetail(repository.save(d)));
        } catch (Exception e) { return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage())); }
    }

    private boolean canManageChat(DesignRequirement d, AuthController.AuthSession s) {
        return s != null && ("admin".equals(s.role()) || ("planner".equals(s.role()) && java.util.Objects.equals(s.userId(), d.getPlannerId())));
    }

    private java.util.Map<String, Object> toDetail(DesignRequirement d) {
        var detail = new java.util.LinkedHashMap<>(toRow(d));
        detail.put("description", d.getDescription());
        detail.put("attachmentsJson", d.getAttachmentsJson());
        detail.put("referenceImagesJson", d.getReferenceImagesJson());
        detail.put("responsibleId", d.getResponsibleId());
        detail.put("responsibleName", d.getResponsibleName());
        detail.put("responsibleRole", d.getResponsibleRole());
        detail.put("plannerId", d.getPlannerId());
        detail.put("plannerName", d.getPlannerName());
        detail.put("rejectionComments", d.getRejectionComments());
        detail.put("rejectionDeadline", d.getRejectionDeadline());
        detail.put("designerId", d.getDesignerId());
        detail.put("designerName", d.getDesignerName());
        detail.put("ownerId", d.getOwnerId());
        detail.put("ownerName", d.getOwnerName());
        detail.put("feishuChatId", d.getFeishuChatId());
        detail.put("feishuChatStatus", d.getFeishuChatStatus());
        detail.put("deliveryContent", d.getDeliveryContent());
        detail.put("deliveryAttachmentsJson", d.getDeliveryAttachmentsJson());
        detail.put("deliveryReferenceImagesJson", d.getDeliveryReferenceImagesJson());
        detail.put("deliveredAt", d.getDeliveredAt());
        detail.put("scoringRecords", scoringService == null ? java.util.List.of()
                : scoringService.scoreMaps(scoreRepository.findByRequirementIdOrderByIdAsc(d.getId())));
        return detail;
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private AuthController.AuthSession session(HttpServletRequest request) {
        return (AuthController.AuthSession) request.getAttribute("authSession");
    }
    private String normalizeRole(String role) {
        if (role == null) return "";
        if ("Promotion".equalsIgnoreCase(role) || "product_promotion".equalsIgnoreCase(role)
                || "product-promotion".equalsIgnoreCase(role)) return "promotion";
        return role.toLowerCase(java.util.Locale.ROOT);
    }
    private boolean isActiveRole(User user, String expectedRole) {
        return user != null && expectedRole.equals(normalizeRole(user.getRole()))
                && !"disabled".equalsIgnoreCase(user.getStatus())
                && !"pending".equalsIgnoreCase(user.getStatus());
    }
}
