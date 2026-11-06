package com.emie.designpm.project.service;

import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.PointLedger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 只组装交付与积分视图；应发快照与实际流水始终分开。 */
final class SubTaskPointView {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SubTaskPointView() {}

    static Map<String, Object> summary(List<PointLedger> ledgers, List<Map<String, Object>> versions) {
        Map<Long, Map<String, Object>> byId = versions.stream()
                .filter(version -> version.get("id") instanceof Number)
                .collect(Collectors.toMap(version -> ((Number) version.get("id")).longValue(), version -> version));
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal change = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;
        BigDecimal untracked = BigDecimal.ZERO;
        boolean hasUntracked = false;
        for (PointLedger ledger : ledgers) {
            BigDecimal amount = amount(ledger.getPoints());
            Map<String, Object> version = byId.get(ledger.getDeliveryVersionId());
            String rule = Objects.toString(ledger.getRuleCode(), "");
            if (version == null) {
                untracked = untracked.add(amount);
                hasUntracked = true;
            }
            if ((version != null && version.get("pointRequestId") != null) || rule.contains(":CHANGE:")) {
                change = change.add(amount);
            } else if (rule.endsWith(":BASE") || (version != null && rule.startsWith("LEGACY_MANUAL:APPROVAL:"))) {
                base = base.add(amount);
            } else {
                other = other.add(amount);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("awardedPoints", rounded(base.add(change).add(other)));
        result.put("basePoints", rounded(base));
        result.put("changePoints", rounded(change));
        result.put("otherPoints", rounded(other));
        result.put("untrackedPoints", rounded(untracked));
        result.put("hasUntrackedAwards", hasUntracked);
        return result;
    }

    static void enrich(
            Long taskId,
            List<Map<String, Object>> versions,
            List<PointLedger> ledgers,
            Map<String, String> userNames,
            List<ActivityLog> taskLogs) {
        Map<Long, List<PointLedger>> byVersion = ledgers.stream()
                .filter(ledger -> ledger.getDeliveryVersionId() != null)
                .collect(Collectors.groupingBy(PointLedger::getDeliveryVersionId));
        Map<String, Map<String, Object>> requests = new LinkedHashMap<>();
        Map<String, Integer> revisionNumbers = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> confirmations = new HashMap<>();
        List<ActivityLog> ordered = new ArrayList<>(taskLogs);
        ordered.sort(Comparator.comparing(ActivityLog::getTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ActivityLog::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        for (ActivityLog log : ordered) {
            if (!"sub_task".equals(log.getEntityType()) || !Objects.equals(taskId, log.getEntityId())) continue;
            Map<String, Object> snapshot = parse(log.getAfterData());
            String action = Objects.toString(log.getAction(), "");
            String requestId =
                    snapshot.get("pointRequestId") instanceof String value && !value.isBlank() ? value : null;
            if (requestId != null && action.startsWith("发起子任务修改：")) {
                revisionNumbers.computeIfAbsent(requestId, ignored -> revisionNumbers.size() + 1);
                requests.put(requestId, snapshot);
            }
            if (action.startsWith("产品企划确认成果：") && snapshot.get("deliveryVersionId") instanceof Number id) {
                confirmations.put(id.longValue(), snapshot);
            }
        }
        for (Map<String, Object> version : versions) {
            Long id = version.get("id") instanceof Number value ? value.longValue() : null;
            List<PointLedger> awards = id == null ? List.of() : byVersion.getOrDefault(id, List.of());
            BigDecimal awarded = awards.stream()
                    .map(PointLedger::getPoints)
                    .map(SubTaskPointView::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Number expected = version.get("expectedPoints") instanceof Number value ? value : null;
            boolean noPoints = expected != null && expected.doubleValue() == 0;
            String status = !awards.isEmpty()
                    ? "awarded"
                    : expected == null
                            ? "unrecorded"
                            : noPoints ? "no_points" : version.get("confirmedAt") == null ? "pending" : "missing";
            if (!awards.isEmpty()) {
                version.put("awardedPoints", rounded(awarded));
            } else {
                version.put("awardedPoints", noPoints ? Double.valueOf(0d) : null);
            }
            version.put("pointStatus", status);
            version.put(
                    "awardedAt",
                    awards.stream()
                            .map(PointLedger::getCreatedAt)
                            .filter(Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .map(Object::toString)
                            .orElse(null));
            String confirmedBy = (String) version.get("confirmedBy");
            version.put(
                    "confirmedByName", confirmedBy == null ? null : userNames.getOrDefault(confirmedBy, confirmedBy));
            String requestId = (String) version.get("pointRequestId");
            Map<String, Object> request = requests.getOrDefault(requestId, Map.of());
            version.put("revisionNo", revisionNumbers.get(requestId));
            version.put(
                    "submissionOrigin",
                    revisionNumbers.containsKey(requestId)
                            ? "revision"
                            : "initial".equals(version.get("submissionType")) ? "initial" : "unknown");
            version.put("changeReason", request.get("reason"));
            version.put("changeReferenceImagesJson", request.get("changeReferenceImagesJson"));
            version.put(
                    "reviewComments", confirmations.getOrDefault(id, Map.of()).get("reviewComments"));
        }
    }

    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = JSON.readValue(json, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static BigDecimal amount(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private static double rounded(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
