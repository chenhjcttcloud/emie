package com.emie.designpm.project.service;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.admin.service.UserService;
import com.emie.designpm.entity.IpOption;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.entity.User;
import com.emie.designpm.materialmarket.repository.DesignerMarketEligibilityRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SubTaskInputValidator {
    private static final Logger log = LoggerFactory.getLogger(SubTaskInputValidator.class);

    private final UserService userService;
    private final SubTaskRepository subTaskRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private SubTaskAssignmentPolicy subTaskAssignmentPolicy;
    private SubTaskInputPolicy subTaskInputPolicy;
    private DesignerMarketEligibilityRepository marketEligibilityRepository;

    public SubTaskInputValidator(
            UserService userService,
            SubTaskRepository subTaskRepository,
            SystemConfigRepository systemConfigRepository) {
        this.userService = userService;
        this.subTaskRepository = subTaskRepository;
        this.systemConfigRepository = systemConfigRepository;
    }

    @Autowired(required = false)
    void setSubTaskAssignmentPolicy(SubTaskAssignmentPolicy policy) {
        this.subTaskAssignmentPolicy = policy;
    }

    @Autowired(required = false)
    void setSubTaskInputPolicy(SubTaskInputPolicy policy) {
        this.subTaskInputPolicy = policy;
    }

    @Autowired(required = false)
    void setMarketEligibilityRepository(DesignerMarketEligibilityRepository repository) {
        this.marketEligibilityRepository = repository;
    }

    String skillTags(Object raw) {
        return subTaskInputPolicy == null ? validateSkillTags(raw) : subTaskInputPolicy.skillTags(raw);
    }

    String collaboratorAllocations(Object raw, String primaryUserId) {
        return subTaskInputPolicy == null
                ? validateCollaboratorAllocations(raw, primaryUserId)
                : subTaskInputPolicy.collaboratorAllocations(raw, primaryUserId);
    }

    String milestoneMonth(Object raw) {
        return subTaskInputPolicy == null ? validateMilestoneMonth(raw) : subTaskInputPolicy.milestoneMonth(raw);
    }

    String validateAndCleanFiles(String json, boolean isImage) {
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

    void validateSubTaskAssignee(String userId, String assigneeRole) {
        if (subTaskAssignmentPolicy != null) {
            subTaskAssignmentPolicy.validate(userId, assigneeRole);
            return;
        }
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

    String normalizeAssigneeRole(String role) {
        if (role == null) return "";
        if ("promotion".equalsIgnoreCase(role)
                || "product_promotion".equalsIgnoreCase(role)
                || "product-promotion".equalsIgnoreCase(role)) return "promotion";
        return role;
    }

    String validateIpSubOptions(String submittedJson, IpOption ipOption) {
        List<String> configured;
        List<String> selected;
        try {
            configured = objectMapper.readValue(
                    Optional.ofNullable(ipOption.getSubOptionsJson()).orElse("[]"),
                    new TypeReference<List<String>>() {});
            selected = objectMapper.readValue(
                    Optional.ofNullable(submittedJson).orElse("[]"), new TypeReference<List<String>>() {});
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

    void validateMarketClaimConstraints(SubTask task, String designerUserId) {
        if (!"market_open".equals(task.getAllocationStatus())) return;
        if (marketEligibilityRepository != null)
            marketEligibilityRepository.findByUserId(designerUserId).ifPresent(eligibility -> {
                if (eligibility.isSuspended())
                    throw new RuntimeException("开放接单资格已暂停至" + eligibility.getSuspendedUntil() + "，原因："
                            + Optional.ofNullable(eligibility.getReason()).orElse("违规处理"));
            });
        String code =
                Optional.ofNullable(task.getPointRuleCode()).orElse("").trim().toUpperCase();
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

    void validateDesignCategoryEligibility(SubTask task, String designerUserId) {
        String configured = systemConfigRepository
                .findByConfigKey("points.user.skills." + designerUserId)
                .map(SystemConfig::getConfigValue)
                .orElse("[]");
        validateDesignCategoryEligibility(task, designerUserId, parseSkillTags(configured));
    }

    void validateDesignCategoryEligibility(SubTask task, String designerUserId, Set<String> actual) {
        String ruleCode =
                Optional.ofNullable(task.getPointRuleCode()).orElse("").toUpperCase(Locale.ROOT);
        if ("B1".equals(ruleCode) && !actual.contains("ID")) throw new RuntimeException("B1原创任务仅具备ID能力标签的设计师可接");
        if (ruleCode.startsWith("B")
                && !"B1".equals(ruleCode)
                && actual.stream().noneMatch(tag -> Set.of("ID", "视觉").contains(tag))) {
            throw new RuntimeException("产品设计类任务仅具备ID或视觉能力标签的设计师可接");
        }
    }

    private int positiveIntConfig(String key, int fallback) {
        return systemConfigRepository
                .findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        return fallback;
                    }
                })
                .filter(value -> value > 0)
                .orElse(fallback);
    }

    private String validateSkillTags(Object raw) {
        if (raw == null) return null;
        Collection<?> values;
        if (raw instanceof Collection<?> collection) {
            values = collection;
        } else if (raw instanceof String json && !json.isBlank()) {
            try {
                values = objectMapper.readValue(json, new TypeReference<List<Object>>() {});
            } catch (Exception e) {
                throw new RuntimeException("能力标签格式无效");
            }
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
                .distinct()
                .limit(20)
                .toList();
        try {
            return normalized.isEmpty() ? null : objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new RuntimeException("能力标签保存失败");
        }
    }

    private Set<String> parseSkillTags(String json) {
        if (json == null || json.isBlank()) return Collections.emptySet();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            log.warn("忽略格式错误的能力标签配置");
            return Collections.emptySet();
        }
    }

    private String validateMilestoneMonth(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        String month = String.valueOf(raw).trim();
        try {
            java.time.YearMonth.parse(month);
            return month;
        } catch (Exception e) {
            throw new RuntimeException("里程碑月份格式应为YYYY-MM");
        }
    }

    private String validateCollaboratorAllocations(Object raw, String primaryUserId) {
        if (raw == null
                || String.valueOf(raw).isBlank()
                || "[]".equals(String.valueOf(raw).trim())) return null;
        List<Map<String, Object>> rows;
        try {
            rows = raw instanceof Collection<?> collection
                    ? objectMapper.convertValue(collection, new TypeReference<List<Map<String, Object>>>() {})
                    : objectMapper.readValue(String.valueOf(raw), new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new RuntimeException("合作成员比例格式无效");
        }
        Set<String> users = new LinkedHashSet<>();
        int total = 0;
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String userId = SecurityUtil.sanitizeText(String.valueOf(row.getOrDefault("userId", "")), 100);
            int ratio;
            try {
                ratio = Integer.parseInt(String.valueOf(row.get("ratio")));
            } catch (Exception e) {
                throw new RuntimeException("合作比例必须是整数百分比");
            }
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
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new RuntimeException("合作比例保存失败");
        }
    }
}
