package com.emie.designpm.controller;

import com.emie.designpm.dto.PageResponse;
import com.emie.designpm.entity.DesignRequirement;
import com.emie.designpm.repository.DesignRequirementRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/design-requirements")
public class DesignRequirementController {
    private final DesignRequirementRepository repository;
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public DesignRequirementController(DesignRequirementRepository repository) { this.repository = repository; }

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
                || session.userId().equals(requirement.getPlannerId());
        if (!visible) return ResponseEntity.status(403).body(java.util.Map.of("error", "无权查看该设计/送审需求"));
        return ResponseEntity.ok(toDetail(requirement));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody java.util.Map<String, Object> body, HttpServletRequest request) {
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        if (session == null) return ResponseEntity.status(401).build();
        if (!java.util.Set.of("planner", "sales", "promotion", "product_promotion", "product-promotion").contains(session.role())) {
            return ResponseEntity.status(403).body(java.util.Map.of("error", "当前角色无权创建设计/送审需求"));
        }
        String name = text(body.get("productName"));
        String deadline = text(body.get("deadline"));
        String requirements = text(body.get("productRequirements"));
        if (name == null || requirements == null || deadline == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "产品名称、要求完成时间、产品要求不能为空"));
        }
        DesignRequirement d = new DesignRequirement();
        d.setName(name); d.setDeadline(deadline); d.setRequirements(requirements);
        d.setDescription(text(body.get("description")));
        d.setCustomerName(text(body.get("customerName")));
        // 需求负责人始终以当前登录账号为准，不信任前端传入的负责人身份。
        d.setResponsibleId(session.userId());
        d.setResponsibleName(session.name());
        d.setResponsibleRole(session.role());
        d.setPlannerId(text(body.get("plannerId"))); d.setPlannerName(text(body.get("plannerName")));
        d.setOwnerId(session.userId()); d.setOwnerName(session.name());
        d.setRequirementCode("DR" + java.time.LocalDate.now().toString().replace("-", "") + System.currentTimeMillis() % 10000);
        DesignRequirement saved = repository.save(d);
        return ResponseEntity.ok(java.util.Map.of("id", saved.getId(), "requirementCode", saved.getRequirementCode()));
    }

    private String text(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return value.toString().trim();
    }

    private java.util.Map<String, Object> toRow(DesignRequirement d) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("id", d.getId()); row.put("projectCode", d.getRequirementCode());
        row.put("type", "design_requirement"); row.put("status", d.getStatus());
        row.put("statusLabel", d.getStatus()); row.put("statusCls", "badge-progress");
        row.put("productName", d.getName()); row.put("salesName", d.getOwnerName());
        row.put("plannerName", d.getResponsibleName() != null ? d.getResponsibleName() : d.getPlannerName());
        row.put("customerName", d.getCustomerName()); row.put("deadline", d.getDeadline());
        row.put("productRequirements", d.getRequirements()); row.put("taskCount", 0);
        row.put("approvedTaskCount", 0); row.put("progressPercent", 0);
        row.put("createdAt", d.getCreatedAt() == null ? null : d.getCreatedAt().format(DTF));
        row.put("updatedAt", d.getUpdatedAt() == null ? null : d.getUpdatedAt().format(DTF));
        return row;
    }

    private java.util.Map<String, Object> toDetail(DesignRequirement d) {
        var detail = new java.util.LinkedHashMap<>(toRow(d));
        detail.put("description", d.getDescription());
        detail.put("attachmentsJson", d.getAttachmentsJson());
        detail.put("responsibleId", d.getResponsibleId());
        detail.put("responsibleName", d.getResponsibleName());
        detail.put("responsibleRole", d.getResponsibleRole());
        detail.put("plannerId", d.getPlannerId());
        detail.put("ownerId", d.getOwnerId());
        detail.put("ownerName", d.getOwnerName());
        return detail;
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
