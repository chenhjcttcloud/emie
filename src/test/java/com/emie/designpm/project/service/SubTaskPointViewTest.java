package com.emie.designpm.project.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.PointLedger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubTaskPointViewTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 9, 0);

    @Test
    void summaryUsesActualLedgersAndClassifiesLegacyManualChangesByLinkedRequest() {
        List<Map<String, Object>> versions = List.of(version(1L, 99d, true, null), version(2L, 99d, true, "change-1"));
        Map<String, Object> result = SubTaskPointView.summary(
                List.of(
                        ledger(1L, "A1:BASE", 0.1),
                        ledger(2L, "A3:CHANGE:change-1", 0.2),
                        ledger(2L, "LEGACY_MANUAL:APPROVAL:2", 0.3),
                        ledger(null, "A1:QUALITY", 0.4),
                        ledger(null, "A1:BASE", 0.5)),
                versions);

        assertEquals(1.5d, result.get("awardedPoints"));
        assertEquals(0.6d, result.get("basePoints"));
        assertEquals(0.5d, result.get("changePoints"));
        assertEquals(0.4d, result.get("otherPoints"));
        assertEquals(0.9d, result.get("untrackedPoints"));
        assertEquals(true, result.get("hasUntrackedAwards"));
    }

    @Test
    void unlinkedHistoryNeverAppearsAsAParticularVersionsAward() {
        Map<String, Object> historical = version(1L, null, true, null);
        Map<String, Object> missing = version(2L, 10d, true, "change-1");
        List<Map<String, Object>> versions = List.of(historical, missing);

        SubTaskPointView.enrich(11L, versions, List.of(ledger(null, "A1:BASE", 10d)), Map.of(), List.of());

        assertNull(historical.get("awardedPoints"));
        assertEquals("unrecorded", historical.get("pointStatus"));
        assertNull(missing.get("awardedPoints"));
        assertEquals("missing", missing.get("pointStatus"));
    }

    @Test
    void actualAwardOverridesExpectedAmountAndIncludesConfirmationNameAndAwardTime() {
        Map<String, Object> version = version(1L, 10d, true, null);
        version.put("confirmedBy", "planner-1");
        PointLedger ledger = ledger(1L, "A1:BASE", 8.25d);
        ledger.setCreatedAt(NOW.plusMinutes(1));

        SubTaskPointView.enrich(11L, List.of(version), List.of(ledger), Map.of("planner-1", "企划甲"), List.of());

        assertEquals(8.25d, version.get("awardedPoints"));
        assertEquals("awarded", version.get("pointStatus"));
        assertEquals("企划甲", version.get("confirmedByName"));
        assertEquals(NOW.plusMinutes(1).toString(), version.get("awardedAt"));
    }

    @Test
    void pendingAndZeroPointSubmissionsRemainDistinctFromIssuedPoints() {
        Map<String, Object> pending = version(1L, 3d, false, "change-1");
        Map<String, Object> noPoints = version(2L, 0d, true, "change-2");

        SubTaskPointView.enrich(11L, List.of(pending, noPoints), List.of(), Map.of(), List.of());

        assertEquals("pending", pending.get("pointStatus"));
        assertNull(pending.get("awardedPoints"));
        assertEquals("no_points", noPoints.get("pointStatus"));
        assertEquals(0d, noPoints.get("awardedPoints"));
    }

    @Test
    void fullModificationHistoryNumbersRequestsWithoutCountingRejectionOrCorrection() {
        Map<String, Object> secondChange = version(4L, 3d, false, "change-2");
        Map<String, Object> correction = version(5L, 3d, false, "change-2");
        correction.put("submissionType", "correction");
        Map<String, Object> rejection = version(6L, 3d, false, "reject-1");
        Map<String, Object> unknown = version(7L, 3d, false, "missing-history");
        List<ActivityLog> logs = new ArrayList<>(List.of(
                log(1L, 11L, "发起子任务修改：设计", Map.of("pointRequestId", "change-1", "reason", "第一次要求")),
                log(202L, 11L, "发起子任务修改：设计", Map.of("pointRequestId", "change-2", "reason", "第二次要求")),
                log(
                        203L,
                        11L,
                        "子任务驳回：设计",
                        Map.of("pointRequestId", "reject-1", "reason", "驳回要求", "rejectionCycleId", 9)),
                log(204L, 99L, "发起子任务修改：另一任务", Map.of("pointRequestId", "change-2", "reason", "其他任务"))));

        SubTaskPointView.enrich(11L, List.of(secondChange, correction, rejection, unknown), List.of(), Map.of(), logs);

        assertEquals(2, secondChange.get("revisionNo"));
        assertEquals(2, correction.get("revisionNo"));
        assertEquals("第二次要求", secondChange.get("changeReason"));
        assertEquals("revision", correction.get("submissionOrigin"));
        assertNull(rejection.get("revisionNo"));
        assertEquals("unknown", rejection.get("submissionOrigin"));
        assertNull(rejection.get("rejectionReason"));
        assertNull(rejection.get("rejectionCycleId"));
        assertNull(unknown.get("revisionNo"));
        assertEquals("unknown", unknown.get("submissionOrigin"));
    }

    @Test
    void reviewCommentsAreMatchedByVersionAndNeverCopiedFromLatestTaskState() {
        Map<String, Object> first = version(1L, 10d, true, null);
        Map<String, Object> second = version(2L, 3d, true, "change-1");
        List<ActivityLog> logs = List.of(
                log(1L, 11L, "产品企划确认成果：设计", Map.of("deliveryVersionId", 1, "reviewComments", "初稿通过")),
                log(2L, 11L, "产品企划确认成果：设计", Map.of("deliveryVersionId", 2, "reviewComments", "修改通过")));

        SubTaskPointView.enrich(11L, List.of(first, second), List.of(), Map.of(), logs);

        assertEquals("初稿通过", first.get("reviewComments"));
        assertEquals("修改通过", second.get("reviewComments"));
    }

    private Map<String, Object> version(Long id, Double expected, boolean confirmed, String requestId) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", id);
        version.put("expectedPoints", expected);
        version.put("confirmedAt", confirmed ? NOW.toString() : null);
        version.put("pointRequestId", requestId);
        version.put("submissionType", requestId == null ? "initial" : "revision");
        return version;
    }

    private PointLedger ledger(Long versionId, String code, double points) {
        PointLedger ledger = new PointLedger();
        ledger.setDeliveryVersionId(versionId);
        ledger.setRuleCode(code);
        ledger.setPoints(points);
        ledger.setSubTaskId(11L);
        return ledger;
    }

    private ActivityLog log(Long id, Long taskId, String action, Map<String, Object> after) {
        ActivityLog log = new ActivityLog();
        log.setId(id);
        log.setEntityType("sub_task");
        log.setEntityId(taskId);
        log.setAction(action);
        log.setTime(NOW.plusSeconds(id));
        log.setAfterData(AuditJson.toJson(after));
        return log;
    }
}
