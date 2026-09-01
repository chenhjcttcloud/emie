package com.emie.designpm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FeishuBaseServiceFieldCompatibilityTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sourceDeletionTimeIsRecognizedAsDateField() throws Exception {
        Method method = FeishuBaseService.class.getDeclaredMethod("expectedFieldType", String.class);
        method.setAccessible(true);

        assertEquals(5, method.invoke(null, "源数据删除时间"));
    }

    @Test
    void relationFieldUsesLinkedRecordPayload() {
        ObjectNode fields = json.createObjectNode();

        FeishuBaseService.putReferenceValue(fields, "所属项目", "42", "recProject42", 21);

        assertTrue(fields.path("所属项目").isArray());
        assertEquals("recProject42", fields.path("所属项目").path(0).asText());
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

    @Test
    void progressValuesUseFeishuPercentageScaleAndNeverExceedOneHundredPercent() {
        ObjectNode fields = json.createObjectNode();

        FeishuBaseService.putProgressValue(fields, "完成进度", 33);
        FeishuBaseService.putProgressValue(fields, "审核进度", 150);

        assertEquals(0.33, fields.path("完成进度").asDouble(), 0.0001);
        assertEquals(1.0, fields.path("审核进度").asDouble(), 0.0001);
    }

    @Test
    void configurableMappingsCanDisableAndRenameFields() {
        ObjectNode fields = json.createObjectNode();
        fields.put("项目ID", "42");
        fields.put("销售", "张三");
        fields.put("完成进度", 0.5);
        String config = "{\"project\":{\"销售\":{\"enabled\":false},\"完成进度\":{\"enabled\":true,\"target\":\"项目总进度\"}}}";

        ObjectNode result = FeishuBaseService.applyFieldMappings(fields, "project", config);

        assertEquals("42", result.path("项目ID").asText());
        assertFalse(result.has("销售"));
        assertEquals(0.5, result.path("项目总进度").asDouble());
    }

    @Test
    void identityFieldCannotBeDisabledOrRenamed() {
        ObjectNode fields = json.createObjectNode();
        fields.put("子任务ID", "7");
        String config = "{\"task\":{\"子任务ID\":{\"enabled\":false,\"target\":\"别名\"}}}";

        ObjectNode result = FeishuBaseService.applyFieldMappings(fields, "task", config);

        assertEquals("7", result.path("子任务ID").asText());
        assertFalse(result.has("别名"));
    }

    @Test
    void duplicateTargetColumnsAreRejected() {
        ObjectNode fields = json.createObjectNode();
        fields.put("状态", "进行中");
        fields.put("销售", "张三");
        String config = "{\"project\":{\"状态\":{\"target\":\"同一列\"},\"销售\":{\"target\":\"同一列\"}}}";

        assertThrows(IllegalArgumentException.class,
                () -> FeishuBaseService.applyFieldMappings(fields, "project", config));
    }

    @Test
    void taskStatusAndActualCompletionFieldMatchBusinessLabelsAndExistingTable() {
        assertEquals("已送审", FeishuBaseService.taskStatusLabel("submitted_for_review"));
        assertEquals("实际完成日期", FeishuBaseService.actualCompletionField(Map.of("实际完成日期", 5)));
        assertEquals("实际完成", FeishuBaseService.actualCompletionField(Map.of("实际完成", 5)));
        assertEquals("实际完成时间", FeishuBaseService.actualCompletionField(Map.of("实际完成时间", 5)));
    }

    @Test
    void extraBusinessFieldsAreOnlyWrittenWhenTargetColumnsExist() {
        ObjectNode fields = json.createObjectNode();

        FeishuBaseService.putExtraFields(fields, Map.of("产品名称", 1),
                Map.of("产品名称", "耳机", "预算金额", "10000"));

        assertEquals("耳机", fields.path("产品名称").asText());
        assertFalse(fields.has("预算金额"));
    }

    @Test
    void reviewLabelsExposeTwoStageWorkflow() {
        assertEquals("渠道定制", FeishuBaseService.projectTypeLabel("channel_custom"));
        assertEquals("公司常规品", FeishuBaseService.projectTypeLabel("regular"));
        assertEquals("一审", FeishuBaseService.reviewStageLabel("first"));
        assertEquals("二审", FeishuBaseService.reviewStageLabel("second"));
        assertEquals("等待一审", FeishuBaseService.reviewStatusLabel("waiting"));
        assertEquals("待审核", FeishuBaseService.reviewStatusLabel("pending"));
        assertEquals("已通过", FeishuBaseService.reviewStatusLabel("approved"));
        assertEquals("已驳回", FeishuBaseService.reviewStatusLabel("rejected"));
    }

    @Test
    void primaryAndBackupRecordsReceiveDifferentSyncMetadata() {
        ObjectNode primary = json.createObjectNode();
        ObjectNode backup = json.createObjectNode();
        backup.put("源数据删除时间", 1_700_000_000_000L);

        FeishuBaseService.putSyncMetadata(primary, false, false, null, false, Map.of());
        FeishuBaseService.putSyncMetadata(backup, true, false, null, true,
                Map.of("源数据删除时间", 5));

        assertEquals("系统", primary.path("同步来源").asText());
        assertFalse(primary.has("备份状态"));
        assertEquals("系统", backup.path("同步来源").asText());
        assertEquals("有效", backup.path("备份状态").asText());
        assertTrue(backup.path("源数据删除时间").isNull());
    }

    @Test
    void deletedBackupIsMarkedWithoutRemovingItsBusinessIdentity() {
        ObjectNode fields = json.createObjectNode();
        fields.put("项目ID", "42");
        LocalDateTime deletedAt = LocalDateTime.of(2026, 7, 15, 18, 0);

        FeishuBaseService.putSyncMetadata(fields, true, true, deletedAt, true,
                Map.of("源数据删除时间", 5));

        assertEquals("42", fields.path("项目ID").asText());
        assertEquals("源数据已删除", fields.path("备份状态").asText());
        assertEquals(FeishuBaseService.toTimestamp(deletedAt), fields.path("源数据删除时间").asLong());
    }

    @Test
    void mirrorCleanupOnlyTargetsSystemRecordsMissingFromDatabase() {
        ObjectNode currentSystemRecord = json.createObjectNode();
        currentSystemRecord.put("同步来源", "系统");
        currentSystemRecord.put("项目ID", "1");
        ObjectNode orphanSystemRecord = currentSystemRecord.deepCopy();
        orphanSystemRecord.put("项目ID", "2");
        ObjectNode manualRecord = orphanSystemRecord.deepCopy();
        manualRecord.put("同步来源", "人工");

        assertFalse(FeishuBaseService.isOrphanSystemRecord(currentSystemRecord, "项目ID", Set.of("1")));
        assertTrue(FeishuBaseService.isOrphanSystemRecord(orphanSystemRecord, "项目ID", Set.of("1")));
        assertFalse(FeishuBaseService.isOrphanSystemRecord(manualRecord, "项目ID", Set.of("1")));
    }
}
