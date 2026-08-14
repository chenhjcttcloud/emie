package com.emie.designpm.controller;

import com.emie.designpm.entity.PointLedger;
import com.emie.designpm.entity.PointRule;
import com.emie.designpm.entity.PointDifficultyConfig;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.repository.DesignerMarketEligibilityRepository;
import com.emie.designpm.entity.DesignerMarketEligibility;
import com.emie.designpm.service.PointsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/points")
public class PointsController {
    private final PointsService points;
    private final SystemConfigRepository systemConfigs;
    private final DesignerMarketEligibilityRepository marketEligibility;
    public PointsController(PointsService points, SystemConfigRepository systemConfigs, DesignerMarketEligibilityRepository marketEligibility) {
        this.points = points;
        this.systemConfigs = systemConfigs;
        this.marketEligibility = marketEligibility;
    }

    @GetMapping("/me")
    public ResponseEntity<?> mine(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        String userId = session(request).userId();
        int safePage = Math.max(0, page), safeSize = Math.min(50, Math.max(1, size));
        var ledger = points.ledgerPage(userId, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));
        return ResponseEntity.ok(Map.of("userId", userId, "balance", points.balance(userId), "ledger", ledger.getContent(),
                "ledgerPage", ledger.getNumber(), "ledgerSize", ledger.getSize(), "ledgerTotal", ledger.getTotalElements(), "ledgerPages", ledger.getTotalPages(),
                "adjustmentLedger", points.adjustmentLedger(userId)));
    }

    @GetMapping("/rules")
    public List<PointRule> rules() { return points.rules(); }

    @GetMapping("/difficulties")
    public List<PointDifficultyConfig> difficulties() { return points.difficulties(); }

    @GetMapping("/market-eligibility/{userId}")
    public ResponseEntity<?> marketEligibility(@PathVariable String userId, HttpServletRequest request) {
        AuthController.AuthSession current=session(request); if(!"admin".equals(current.role())&&!userId.equals(current.userId()))return ResponseEntity.status(403).body(Map.of("error","只能查看自己的接单资格"));
        return ResponseEntity.ok(marketEligibility.findByUserId(userId).orElseGet(()->{DesignerMarketEligibility e=new DesignerMarketEligibility();e.setUserId(userId);return e;}));
    }
    @PutMapping("/market-eligibility/{userId}")
    public ResponseEntity<?> updateMarketEligibility(@PathVariable String userId,@RequestBody Map<String,Object> body,HttpServletRequest request){
        AuthController.AuthSession current=session(request);if(!"admin".equals(current.role()))return ResponseEntity.status(403).body(Map.of("error","仅管理员可管理接单资格"));
        DesignerMarketEligibility e=marketEligibility.findByUserId(userId).orElseGet(DesignerMarketEligibility::new);e.setUserId(userId);
        Object until=body.get("suspendedUntil");e.setSuspendedUntil(until==null||String.valueOf(until).isBlank()?null:java.time.LocalDateTime.parse(String.valueOf(until)));
        e.setReason(body.get("reason")==null?null:String.valueOf(body.get("reason")).trim());
        if(body.get("violationCount") instanceof Number n)e.setViolationCount(Math.max(0,n.intValue()));e.setUpdatedBy(current.userId());return ResponseEntity.ok(marketEligibility.save(e));
    }

    @GetMapping("/skills/{userId}")
    public ResponseEntity<?> skills(@PathVariable String userId, HttpServletRequest request) {
        AuthController.AuthSession current = session(request);
        if (!"admin".equals(current.role()) && !userId.equals(current.userId())) {
            return ResponseEntity.status(403).body(Map.of("error", "只能查看自己的能力标签"));
        }
        String value = systemConfigs.findByConfigKey("points.user.skills." + userId)
                .map(SystemConfig::getConfigValue).orElse("[]");
        return ResponseEntity.ok(Map.of("userId", userId, "skillsJson", value));
    }

    @PutMapping("/skills/{userId}")
    public ResponseEntity<?> updateSkills(@PathVariable String userId, @RequestBody Map<String, Object> body,
                                          HttpServletRequest request) {
        AuthController.AuthSession current = session(request);
        if (!"admin".equals(current.role())) {
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可配置能力标签"));
        }
        Object raw = body.get("skills");
        if (!(raw instanceof List<?> values)) {
            return ResponseEntity.badRequest().body(Map.of("error", "能力标签必须是数组"));
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (Object value : values) {
            String tag = value == null ? "" : String.valueOf(value).trim();
            if (!tag.isEmpty()) normalized.add(tag.substring(0, Math.min(tag.length(), 40)));
            if (normalized.size() >= 20) break;
        }
        String json;
        try { json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(normalized); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "能力标签格式无效")); }
        String key = "points.user.skills." + userId;
        SystemConfig config = systemConfigs.findByConfigKey(key).orElseGet(SystemConfig::new);
        config.setConfigKey(key);
        config.setConfigValue(json);
        config.setConfigGroup("points");
        config.setDescription("用户 " + userId + " 的接单能力标签");
        config.setValueType("text");
        config.setUpdatedBy(current.userId());
        systemConfigs.save(config);
        return ResponseEntity.ok(Map.of("userId", userId, "skills", normalized));
    }

    @PutMapping("/difficulties/{difficultyCode}")
    public ResponseEntity<?> updateDifficulty(@PathVariable String difficultyCode,
                                              @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        if (!"admin".equals(session(request).role())) {
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可修改难度配置"));
        }
        try {
            Double multiplier = body.get("multiplier") instanceof Number n ? n.doubleValue() : null;
            Boolean enabled = body.get("enabled") instanceof Boolean b ? b : null;
            String description = body.get("description") instanceof String s ? s : null;
            return ResponseEntity.ok(points.updateDifficulty(difficultyCode, multiplier, enabled, description));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/rules/{ruleCode}")
    public ResponseEntity<?> updateRule(@PathVariable String ruleCode, @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        if (!"admin".equals(session(request).role())) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可修改积分规则"));
        try {
            Integer value = body.get("points") instanceof Number n ? n.intValue() : null;
            Boolean enabled = body.get("enabled") instanceof Boolean b ? b : null;
            String description = body.get("description") instanceof String s ? s : null;
            String category = body.get("category") instanceof String s ? s : null;
            Double difficulty = body.get("difficultyMultiplier") instanceof Number n ? n.doubleValue() : null;
            Integer threshold = body.get("qualityBonusThreshold") instanceof Number n ? n.intValue() : null;
            Double ratio = body.get("qualityBonusRatio") instanceof Number n ? n.doubleValue() : null;
            Integer topThreshold = body.get("qualityTopThreshold") instanceof Number n ? n.intValue() : null;
            Double topRatio = body.get("qualityTopRatio") instanceof Number n ? n.doubleValue() : null;
            Double maxTotalMultiplier = body.get("maxTotalMultiplier") instanceof Number n ? n.doubleValue() : null;
            Boolean performance = body.get("countInPerformance") instanceof Boolean b ? b : null;
            return ResponseEntity.ok(points.updateRule(ruleCode, value, enabled, description, category, difficulty,
                    threshold, ratio, topThreshold, topRatio, maxTotalMultiplier, performance));
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/rules")
    public ResponseEntity<?> createRule(@RequestBody PointRule rule, HttpServletRequest request) {
        if (!"admin".equals(session(request).role())) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可新增积分规则"));
        try { return ResponseEntity.ok(points.createRule(rule)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @DeleteMapping("/rules/{ruleCode}")
    public ResponseEntity<?> deleteRule(@PathVariable String ruleCode, HttpServletRequest request) {
        if (!"admin".equals(session(request).role())) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可删除积分规则"));
        try { points.deleteRule(ruleCode); return ResponseEntity.ok(Map.of("deleted", true)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    private AuthController.AuthSession session(HttpServletRequest request) {
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        if (session == null) throw new IllegalStateException("未登录或会话已过期");
        return session;
    }
}
