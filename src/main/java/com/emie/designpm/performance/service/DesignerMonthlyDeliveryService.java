package com.emie.designpm.performance.service;

import com.emie.designpm.designrequirement.repository.DesignRequirementRepository;
import com.emie.designpm.entity.DesignRequirement;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SubTaskDeliveryVersion;
import com.emie.designpm.project.repository.SubTaskDeliveryVersionRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DesignerMonthlyDeliveryService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SubTaskRepository tasks;
    private final SubTaskDeliveryVersionRepository versions;
    private final DesignRequirementRepository requirements;

    public DesignerMonthlyDeliveryService(
            SubTaskRepository tasks,
            SubTaskDeliveryVersionRepository versions,
            DesignRequirementRepository requirements) {
        this.tasks = tasks;
        this.versions = versions;
        this.requirements = requirements;
    }

    public record PreviewFile(String name, String storedName, String kind) {}

    public record DeliveryPreview(
            Integer versionNo,
            String submissionType,
            String changeSummary,
            LocalDateTime submittedAt,
            String submittedBy,
            String deliverables,
            List<PreviewFile> files) {}

    @Transactional(readOnly = true)
    public List<DeliveryPreview> forCompletedTask(YearMonth month, Long taskId) {
        SubTask task = tasks.findByIdWithProject(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        LocalDateTime completedAt = task.getCompletedAt();
        if (task.getDesignerId() == null
                || (task.getAssigneeRole() != null && !"designer".equals(task.getAssigneeRole()))
                || completedAt == null
                || !YearMonth.from(completedAt).equals(month)) {
            throw new IllegalArgumentException("任务不属于该月设计师绩效");
        }
        List<SubTaskDeliveryVersion> history = versions.findBySubTaskIdOrderByVersionNoDesc(taskId);
        if (history.isEmpty()) {
            return List.of(new DeliveryPreview(
                    null,
                    "当前交付",
                    "",
                    task.getSubmittedForReviewAt(),
                    task.getDesignerName(),
                    task.getDeliverables(),
                    files(task.getReferenceImagesJson(), task.getAttachmentsJson())));
        }
        return history.stream()
                .sorted(Comparator.comparing(SubTaskDeliveryVersion::getVersionNo))
                .map(version -> new DeliveryPreview(
                        version.getVersionNo(),
                        version.getSubmissionType(),
                        version.getChangeSummary(),
                        version.getSubmittedAt(),
                        version.getSubmittedByName(),
                        version.getDeliverables(),
                        files(version.getReferenceImagesJson(), version.getAttachmentsJson())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DeliveryPreview> forCompletedRequirement(YearMonth month, Long requirementId) {
        DesignRequirement requirement =
                requirements.findById(requirementId).orElseThrow(() -> new IllegalArgumentException("设计需求不存在"));
        if (!"completed".equals(requirement.getStatus())
                || requirement.getDesignerId() == null
                || (requirement.getDeliveredAt() == null && requirement.getUpdatedAt() == null)
                || !YearMonth.from(
                                requirement.getDeliveredAt() != null
                                        ? requirement.getDeliveredAt()
                                        : requirement.getUpdatedAt())
                        .equals(month)) {
            throw new IllegalArgumentException("设计需求不属于该月设计师绩效");
        }
        return List.of(new DeliveryPreview(
                null,
                "设计/送审需求交付",
                "",
                requirement.getDeliveredAt(),
                requirement.getDesignerName(),
                requirement.getDeliveryContent(),
                files(requirement.getDeliveryReferenceImagesJson(), requirement.getDeliveryAttachmentsJson())));
    }

    private static List<PreviewFile> files(String imagesJson, String attachmentsJson) {
        List<Map<String, Object>> sources = new ArrayList<>(parseFiles(imagesJson));
        sources.addAll(parseFiles(attachmentsJson));
        return sources.stream()
                .map(DesignerMonthlyDeliveryService::previewFile)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private static PreviewFile previewFile(Map<String, Object> file) {
        String url = file.get("url") instanceof String value ? value : null;
        String storedName = storedName(url);
        if (!SecurityUtil.isValidFileName(storedName)) return null;
        String name = file.get("name") instanceof String value && !value.isBlank() ? value : storedName;
        String kind = storedName.toLowerCase(Locale.ROOT).endsWith(".ai")
                ? "ai"
                : SecurityUtil.isValidImageFile(storedName) ? "image" : "other";
        return new PreviewFile(name, storedName, kind);
    }

    private static List<Map<String, Object>> parseFiles(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String storedName(String url) {
        if (url == null) return null;
        String clean = url.split("\\?", 2)[0];
        String prefix = "/api/files/download/";
        int start = clean.indexOf(prefix);
        if (start < 0) return null;
        String name = URLDecoder.decode(clean.substring(start + prefix.length()), StandardCharsets.UTF_8);
        return name.isBlank() || name.contains("..") || name.startsWith("/") ? null : name;
    }
}
