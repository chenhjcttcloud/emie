package com.emie.designpm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class FeishuBaseServiceFieldCompatibilityTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void relationFieldUsesLinkedRecordPayload() {
        ObjectNode fields = json.createObjectNode();

        FeishuBaseService.putReferenceValue(fields, "所属项目", "42", "recProject42", 21);

        assertEquals("recProject42",
                fields.path("所属项目").path("link_record_ids").path(0).asText());
    }

    @Test
    void textAndSelectReferenceFieldsKeepBusinessId() {
        ObjectNode textFields = json.createObjectNode();
        ObjectNode selectFields = json.createObjectNode();

        FeishuBaseService.putReferenceValue(textFields, "所属项目", "42", null, 1);
        FeishuBaseService.putReferenceValue(selectFields, "所属项目", "42", null, 3);

        assertEquals("42", textFields.path("所属项目").asText());
        assertEquals("42", selectFields.path("所属项目").asText());
    }

    @Test
    void missingLinkedRecordFailsBeforeCallingFeishu() {
        ObjectNode fields = json.createObjectNode();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FeishuBaseService.putReferenceValue(fields, "所属项目", "42", null, 21));

        assertTrue(error.getMessage().contains("关联记录尚未同步"));
    }

    @Test
    void automaticCreatedTimeIsNotOverwritten() {
        ObjectNode fields = json.createObjectNode();

        FeishuBaseService.putDateValue(fields, "创建时间", 1_700_000_000_000L, 1001);

        assertFalse(fields.has("创建时间"));
    }

    @Test
    void dateValuesUseMilliseconds() {
        ObjectNode fields = json.createObjectNode();
        long timestamp = FeishuBaseService.dateToTimestamp("2026-07-15");

        FeishuBaseService.putDateValue(fields, "截止日期", timestamp, 5);

        assertTrue(timestamp >= 1_000_000_000_000L);
        assertEquals(timestamp, fields.path("截止日期").asLong());
        assertEquals(timestamp, FeishuBaseService.toTimestamp(LocalDateTime.of(2026, 7, 15, 0, 0)));
    }
}
