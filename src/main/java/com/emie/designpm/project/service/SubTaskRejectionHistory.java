package com.emie.designpm.project.service;

import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SubTaskDeliveryVersion;
import com.emie.designpm.entity.SubTaskRejectionCycle;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the timeline from durable rejection cycles and pre-cycle audit events. */
final class SubTaskRejectionHistory {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REJECTION = "子任务驳回：";
    private static final String REASON = "（意见：";
    private static final Comparator<LocalDateTime> BY_TIME = Comparator.nullsLast(Comparator.naturalOrder());
    private static final Comparator<Long> BY_ID = Comparator.nullsLast(Comparator.naturalOrder());
    private static final List<String> SUBMISSION_FIELDS = List.of(
            "deliverables", "referenceImagesJson", "attachmentsJson", "actualDate", "submittedByName", "submittedAt");

    private SubTaskRejectionHistory() {}

    record View(List<Map<String, Object>> rejections, List<Map<String, Object>> deliveries) {}

    static Map<String, Object> submissionSnapshot(SubTask task, SubTaskDeliveryVersion version) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("deliveryVersionId", version == null ? null : version.getId());
        snapshot.put("deliverables", version == null ? task.getDeliverables() : version.getDeliverables());
        snapshot.put(
                "referenceImagesJson",
                version == null ? task.getReferenceImagesJson() : version.getReferenceImagesJson());
        snapshot.put("attachmentsJson", version == null ? task.getAttachmentsJson() : version.getAttachmentsJson());
        snapshot.put("actualDate", version == null ? task.getActualDate() : version.getActualDate());
        snapshot.put("submittedById", version == null ? task.getDesignerId() : version.getSubmittedById());
        snapshot.put("submittedByName", version == null ? task.getDesignerName() : version.getSubmittedByName());
        snapshot.put("submittedAt", iso(version == null ? task.getSubmittedForReviewAt() : version.getSubmittedAt()));
        return snapshot;
    }

    static View build(
            SubTask task,
            List<ActivityLog> logs,
            List<SubTaskRejectionCycle> cycles,
            List<Map<String, Object>> versions) {
        Map<Long, ActivityLog> cycleLogs = new LinkedHashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (ActivityLog log : logs) {
            if (!matches(task, log)) continue;
            Map<String, Object> after = parse(log.getAfterData());
            Long cycleId = id(after.get("rejectionCycleId"));
            if (cycleId != null) {
                cycleLogs.putIfAbsent(cycleId, log);
            } else {
                records.add(record(null, log));
            }
        }
        cycles.forEach(cycle -> records.add(record(cycle, cycleLogs.remove(cycle.getId()))));
        cycleLogs.values().forEach(log -> records.add(record(null, log)));
        records.sort(
                Comparator.<Map<String, Object>, LocalDateTime>comparing(row -> time(row.get("reviewedAt")), BY_TIME)
                        .thenComparing(row -> id(row.get("cycleId")), BY_ID)
                        .thenComparing(row -> id(row.get("id")), BY_ID));
        List<Map<String, Object>> deliveries = versions.stream()
                .map(version -> (Map<String, Object>) new LinkedHashMap<>(version))
                .toList();
        List<Map<String, Object>> ordered = deliveries.stream()
                .sorted(Comparator.<Map<String, Object>, Long>comparing(version -> id(version.get("versionNo")), BY_ID)
                        .thenComparing(version -> time(version.get("submittedAt")), BY_TIME)
                        .thenComparing(version -> id(version.get("id")), BY_ID))
                .toList();
        for (int index = 0; index < records.size(); index++) {
            Map<String, Object> record = records.get(index);
            record.put("attemptNo", index + 1);
            link(
                    record,
                    ordered,
                    index == records.size() - 1 && "rejected".equals(task.getStatus())
                            ? submissionSnapshot(task, null)
                            : Map.of());
        }
        return new View(records, deliveries);
    }

    private static boolean matches(SubTask task, ActivityLog log) {
        String action = log.getAction();
        if (action == null || !action.startsWith(REJECTION)) return false;
        if ("sub_task".equals(log.getEntityType())) return Objects.equals(log.getEntityId(), task.getId());
        Project project = task.getProject();
        Long projectId = log.getProjectRefId() == null && log.getProject() != null
                ? log.getProject().getId()
                : log.getProjectRefId();
        return project != null
                && Objects.equals(projectId, project.getId())
                && action.startsWith(REJECTION + task.getName() + REASON);
    }

    private static Map<String, Object> record(SubTaskRejectionCycle cycle, ActivityLog log) {
        Map<String, Object> saved = cycle == null ? Map.of() : parse(cycle.getPriorReviewSnapshotJson());
        Map<String, Object> submission = new LinkedHashMap<>(log == null ? Map.of() : parse(log.getBeforeData()));
        submission.putAll(nested(saved.get("submission")));
        Map<String, Object> rejection = new LinkedHashMap<>(log == null ? Map.of() : parse(log.getAfterData()));
        rejection.putAll(nested(saved.get("rejection")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", log == null ? cycle.getId() : log.getId());
        result.put("cycleId", cycle == null ? id(rejection.get("rejectionCycleId")) : cycle.getId());
        result.put("reviewerName", cycle == null ? log.getUsername() : cycle.getRejectedByName());
        result.put("reviewerRole", cycle == null ? log.getRole() : cycle.getRejectionRole());
        result.put("reviewedAt", iso(cycle == null ? log.getTime() : cycle.getRejectedAt()));
        result.put(
                "reason",
                cycle == null ? rejection.getOrDefault("reason", reason(log.getAction())) : cycle.getReason());
        result.put(
                "requiredCompletionDate",
                cycle == null
                        ? rejection.getOrDefault("requiredCompletionDate", submission.get("plannedDate"))
                        : cycle.getRequiredCompletionDate());
        result.put(
                "rejectionReferenceImagesJson",
                cycle == null
                        ? rejection.getOrDefault("rejectionReferenceImagesJson", "[]")
                        : cycle.getReferenceImagesJson());
        result.put(
                "rejectionAttachmentsJson",
                cycle == null ? rejection.getOrDefault("rejectionAttachmentsJson", "[]") : cycle.getAttachmentsJson());
        result.put(
                "pointRequestId",
                cycle != null && cycle.getCreatedBonusRequestId() != null
                        ? cycle.getCreatedBonusRequestId()
                        : rejection.get("pointRequestId"));
        result.put(
                "withPoints",
                rejection.getOrDefault("withPoints", cycle == null ? null : cycle.getCreatedBonusRequestId() != null));
        result.put("pointRuleCode", rejection.get("pointRuleCode"));
        result.put("pointRuleDescription", rejection.get("pointRuleDescription"));
        result.put("points", rejection.get("points"));
        result.put("cancelled", cycle != null && "CANCELLED".equals(cycle.getStatus()));
        result.put("cancelledAt", cycle == null ? null : iso(cycle.getCancelledAt()));
        result.put("cancelledByName", cycle == null ? null : cycle.getCancelledByName());
        result.put("rejectedDeliveryVersionId", id(submission.get("deliveryVersionId")));
        SUBMISSION_FIELDS.forEach(field -> {
            if (submission.containsKey(field)) result.put(field, submission.get(field));
        });
        return result;
    }

    private static void link(
            Map<String, Object> record, List<Map<String, Object>> versions, Map<String, Object> current) {
        LocalDateTime rejectedAt = time(record.get("reviewedAt"));
        Long rejectedId = id(record.get("rejectedDeliveryVersionId"));
        int rejectedIndex = -1;
        for (int index = 0; index < versions.size(); index++) {
            Map<String, Object> version = versions.get(index);
            if (rejectedId != null) {
                if (rejectedId.equals(id(version.get("id")))) {
                    rejectedIndex = index;
                    break;
                }
            } else {
                LocalDateTime submitted = time(version.get("submittedAt"));
                if (submitted != null && rejectedAt != null && !submitted.isAfter(rejectedAt)) rejectedIndex = index;
            }
        }
        Map<String, Object> rejected = rejectedIndex < 0 ? current : versions.get(rejectedIndex);
        if (rejectedIndex >= 0) record.put("rejectedDeliveryVersionId", rejected.get("id"));
        record.put("legacy", !record.containsKey("deliverables") && rejectedIndex < 0);
        SUBMISSION_FIELDS.forEach(field -> {
            if (!record.containsKey(field)) record.put(field, rejected.get(field));
        });
        if (Boolean.TRUE.equals(record.get("cancelled"))
                || rejectedAt == null
                || (rejectedId != null && rejectedIndex < 0)) return;

        boolean linked = false;
        for (int index = rejectedIndex + 1; index < versions.size(); index++) {
            Map<String, Object> version = versions.get(index);
            boolean correction = "correction".equals(version.get("submissionType"));
            if (linked && !correction) break;
            if (!linked && correction) continue;
            LocalDateTime submitted = time(version.get("submittedAt"));
            if (submitted == null || submitted.isBefore(rejectedAt)) continue;
            if (!linked && rejectedIndex < 0 && "initial".equals(version.get("submissionType"))) break;
            if (!linked
                    && record.get("pointRequestId") != null
                    && Objects.equals(record.get("pointRequestId"), version.get("pointRequestId"))) {
                record.putIfAbsent("pointRuleCode", version.get("pointRuleCode"));
                record.putIfAbsent("pointRuleDescription", version.get("pointRuleDescription"));
                record.putIfAbsent("points", version.get("expectedPoints"));
            }
            version.put("rejectionCycleId", record.get("cycleId"));
            version.put("rejectionRecordId", record.get("cycleId") == null ? record.get("id") : null);
            version.put("rejectionReason", record.get("reason"));
            version.put("submissionOrigin", "rejection");
            version.put("revisionNo", null);
            version.put("changeReason", null);
            version.put("changeReferenceImagesJson", null);
            linked = true;
        }
    }

    private static String reason(String action) {
        int start = action.indexOf(REASON);
        if (start < 0) return "";
        return action.substring(start + REASON.length(), action.endsWith("）") ? action.length() - 1 : action.length());
    }

    private static Long id(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof String date) {
            try {
                return LocalDateTime.parse(date);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Map<String, Object> parse(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return Objects.requireNonNullElse(JSON.readValue(value, new TypeReference<>() {}), Map.of());
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
