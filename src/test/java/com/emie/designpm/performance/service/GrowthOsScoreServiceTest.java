package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GrowthOsScoreServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final UserRepository users = mock(UserRepository.class);
    private final GrowthOsScoreClient client = mock(GrowthOsScoreClient.class);
    private final GrowthOsScoreService service = new GrowthOsScoreService(users, client);

    private User user(String localId, String feishuId, String name, String status) {
        return User.builder()
                .userId(localId)
                .feishuUserId(feishuId)
                .name(name)
                .role("designer")
                .status(status)
                .build();
    }

    private Map<String, Object> score(Object score, String status, boolean finalized, Object previous) {
        var map = new LinkedHashMap<String, Object>();
        map.put("score", score);
        map.put("status", status);
        map.put("isFinal", finalized);
        map.put("previousScore", previous);
        map.put("source", "automatic_report");
        map.put("updatedAt", "2026-10-31T12:00:00.000Z");
        map.put("revision", "a".repeat(64));
        return map;
    }

    private Map<String, Object> row(String id, String name, Object quality, Object collaboration) {
        return Map.of(
                "input",
                Map.of("feishuUserId", id, "name", name),
                "matchStatus",
                "matched",
                "employee",
                Map.of("feishuUserId", id, "name", name),
                "weeklyQuality",
                quality,
                "collaboration360",
                collaboration);
    }

    private JsonNode response(List<?> data) {
        return JSON.valueToTree(Map.of("ok", true, "apiVersion", "1", "month", "2026-10", "data", data));
    }

    @Test
    void matchesOnlyFeishuIdsPreservesSameNamesAndZeroScoresWithoutWriting() {
        when(client.isConfigured()).thenReturn(true);
        when(users.findByRole("designer"))
                .thenReturn(List.of(
                        user("local-a", "feishu-a", "测试同名", "active"), user("local-b", "feishu-b", "测试同名", "active")));
        when(client.fetch(eq("2026-10"), anyList()))
                .thenReturn(response(List.of(
                        row(
                                "feishu-b",
                                "测试同名",
                                score(79, "published", true, null),
                                score(null, "not_published", false, null)),
                        row(
                                "feishu-a",
                                "测试同名",
                                score(0, "published", true, null),
                                score(88, "published", true, null)))));
        var result = service.month("2026-10");
        assertEquals("ok", result.status());
        assertEquals("local-a", result.users().getFirst().userId());
        assertEquals(BigDecimal.ZERO, result.users().getFirst().weeklyQuality().score());
        assertEquals(
                new BigDecimal("88"),
                result.users().getFirst().collaboration360().score());
        assertEquals(new BigDecimal("79"), result.users().get(1).weeklyQuality().score());
        verify(users, never()).save(any());
        verify(users, never()).saveAll(any());
    }

    @Test
    void absentBindingsAndDuplicateFeishuBindingsNeverFallBackToLocalIdsOrNames() {
        when(client.isConfigured()).thenReturn(true);
        var missing = user("local-user", null, "测试甲", "active");
        missing.setFeishuOpenId("ou_application_specific");
        when(users.findByRole("designer"))
                .thenReturn(List.of(
                        missing,
                        user("local-open", "ou_application_specific", "测试甲", "active"),
                        user("local-x", "same-feishu", "测试甲", "active"),
                        user("local-y", "same-feishu", "测试乙", "active")));
        var result = service.month("2026-10");
        assertEquals("missing_feishu_user_id", result.users().getFirst().matchStatus());
        assertEquals("missing_feishu_user_id", result.users().get(1).matchStatus());
        assertEquals("duplicate_feishu_user_id", result.users().get(2).matchStatus());
        assertTrue(result.users().stream().allMatch(row -> row.weeklyQuality().score() == null));
        verify(client, never()).fetch(anyString(), anyList());
    }

    @Test
    void disabledConfigurationIsExplicitAndInactiveDesignerHistoryCanBeRead() {
        var user = user("history-user", "feishu-historic", "测试离职", "disabled");
        when(users.findByUserId("history-user")).thenReturn(Optional.of(user));
        assertEquals(
                "not_configured",
                service.forUser("2026-10", "history-user").weeklyQuality().status());
        when(client.isConfigured()).thenReturn(true);
        when(client.fetch(eq("2026-10"), anyList()))
                .thenReturn(response(List.of(row(
                        "feishu-historic",
                        "测试离职",
                        score(81, "rescoring", false, 81),
                        score(82, "published", true, null)))));
        // A non-final current score must be null. Malformed upstream fails closed.
        assertEquals("unavailable", service.forUser("2026-10", "history-user").matchStatus());
        when(client.fetch(eq("2026-10"), anyList()))
                .thenReturn(response(List.of(row(
                        "feishu-historic",
                        "测试离职",
                        score(null, "rescore_failed", false, 81),
                        score(82, "published", true, null)))));
        var result = service.forUser("2026-10", "history-user");
        assertEquals("matched", result.matchStatus());
        assertNull(result.weeklyQuality().score());
        assertEquals(new BigDecimal("81"), result.weeklyQuality().previousScore());
        assertFalse(result.weeklyQuality().isFinal());
        assertEquals(new BigDecimal("82"), result.collaboration360().score());
    }

    @Test
    void mismatchesAreExplicitAndAllUnexpectedMissingOrDuplicateRowsFailClosed() {
        when(client.isConfigured()).thenReturn(true);
        when(users.findByRole("designer")).thenReturn(List.of(user("local-a", "feishu-a", "测试甲", "active")));
        var valid =
                row("feishu-a", "测试甲", score(80, "published", true, null), score(null, "not_published", false, null));
        for (var rows : List.of(
                List.of(),
                List.of(valid, valid),
                List.of(row(
                        "someone-else", "测试甲", score(99, "published", true, null), score(99, "published", true, null))),
                List.of(row(
                        "feishu-a", "其他姓名", score(80, "published", true, null), score(80, "published", true, null))))) {
            when(client.fetch(eq("2026-10"), anyList())).thenReturn(response(rows));
            assertEquals("unavailable", service.month("2026-10").status());
            assertNull(
                    service.month("2026-10").users().getFirst().weeklyQuality().score());
        }
        when(client.fetch(eq("2026-10"), anyList()))
                .thenReturn(response(List.of(Map.of(
                        "input",
                        Map.of("feishuUserId", "feishu-a", "name", "测试甲"),
                        "matchStatus",
                        "name_mismatch",
                        "employee",
                        Map.of("feishuUserId", "feishu-a", "name", "上游当前名")))));
        var mismatch = service.month("2026-10").users().getFirst();
        assertEquals("name_mismatch", mismatch.matchStatus());
        assertEquals("identity_mismatch", mismatch.weeklyQuality().status());
        assertNull(mismatch.weeklyQuality().previousScore());
    }

    @Test
    void validatesScoreTypeRangeEnvelopeAndPublicFailureMessage() {
        when(client.isConfigured()).thenReturn(true);
        when(users.findByRole("designer")).thenReturn(List.of(user("local-a", "feishu-a", "测试甲", "active")));
        for (Object bad : List.of(-1, 101, "80", "NaN")) {
            when(client.fetch(eq("2026-10"), anyList()))
                    .thenReturn(response(List.of(row(
                            "feishu-a",
                            "测试甲",
                            score(bad, "published", true, null),
                            score(null, "not_published", false, null)))));
            assertEquals("unavailable", service.month("2026-10").status());
        }
        var wrongMonth = response(List.of());
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongMonth).put("month", "2026-09");
        when(client.fetch(eq("2026-10"), anyList())).thenReturn(wrongMonth);
        assertEquals("unavailable", service.month("2026-10").status());
        when(client.fetch(eq("2026-10"), anyList())).thenThrow(new GrowthOsScoreClient.Unavailable("读取成长周报评分超时，请稍后重试"));
        assertEquals("读取成长周报评分超时，请稍后重试", service.month("2026-10").message());
        assertThrows(IllegalArgumentException.class, () -> service.month("2026-13"));
        assertThrows(IllegalArgumentException.class, () -> service.month("2026"));
    }

    @Test
    void batchesOverOneHundredAndExcludesInactiveFromMonthOnly() {
        when(client.isConfigured()).thenReturn(true);
        var selected = new ArrayList<User>();
        for (int i = 0; i < 101; i++) selected.add(user("local-" + i, "feishu-" + i, "测试" + i, "active"));
        selected.add(user("inactive", "feishu-inactive", "测试离职", "disabled"));
        when(users.findByRole("designer")).thenReturn(selected);
        when(client.fetch(eq("2026-10"), anyList())).thenAnswer(invocation -> {
            List<GrowthOsScoreClient.Identity> identities = invocation.getArgument(1);
            return response(identities.stream()
                    .map(identity -> row(
                            identity.feishuUserId(),
                            identity.name(),
                            score(80, "published", true, null),
                            score(null, "not_published", false, null)))
                    .toList());
        });
        assertEquals(101, service.month("2026-10").users().size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GrowthOsScoreClient.Identity>> batches = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).fetch(eq("2026-10"), batches.capture());
        assertEquals(
                List.of(100, 1), batches.getAllValues().stream().map(List::size).toList());
    }
}
