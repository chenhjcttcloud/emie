package com.emie.designpm.service;

import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.repository.SystemConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书多维表格（Base）同步服务
 *
 * 企业级方案：机器人通过 tenant_access_token 直接调用飞书 API，
 * 自动创建属于机器人自己的多维表格，无需用户授权。
 */
@Service
public class FeishuBaseService {

    private static final Logger log = LoggerFactory.getLogger(FeishuBaseService.class);
    private static final String API = "https://open.feishu.cn/open-apis";
    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper json = new ObjectMapper();

    private final SystemConfigRepository configRepository;
    private final Map<String, CachedFieldTypes> fieldTypesCache = new ConcurrentHashMap<>();

    private String cachedToken;
    private long tokenExpiresAt;

    private record CachedFieldTypes(Map<String, Integer> types, long expiresAt) {}

    public FeishuBaseService(SystemConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    // ==================== 配置读取 ====================

    private boolean isSyncEnabled() {
        return "true".equals(getCfg("feishu.base.syncEnabled"));
    }

    private String getCfg(String key) {
        return configRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue).orElse("");
    }

    private void setCfg(String key, String value) {
        SystemConfig cfg = configRepository.findByConfigKey(key)
                .orElse(SystemConfig.builder().configKey(key).configGroup("feishu").build());
        cfg.setConfigValue(value);
        configRepository.save(cfg);
    }

    public Map<String, String> getConfig() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : List.of("feishu.base.appToken", "feishu.base.tableProjects",
                "feishu.base.tableTasks", "feishu.base.tableScoring", "feishu.base.tableLogs",
                "feishu.base.tableProjectsBackup", "feishu.base.tableTasksBackup",
                "feishu.base.tableScoringBackup", "feishu.base.tableLogsBackup",
                "feishu.base.syncEnabled")) {
            m.put(k, getCfg(k));
        }
        return m;
    }

    /**
     * 只读确认已配置的备份表可由当前 appToken 与企业自建应用访问。
     * 备份表为可选项；未填写时不会阻断主表同步。
     */
    public Map<String, Object> validateBackupTables() throws Exception {
        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank()) {
            return Map.of("valid", false, "message", "未配置飞书 Base App Token", "tables", List.of());
        }

        String token = getToken();
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("projectsBackup", getCfg("feishu.base.tableProjectsBackup"));
        configured.put("tasksBackup", getCfg("feishu.base.tableTasksBackup"));
        configured.put("scoringBackup", getCfg("feishu.base.tableScoringBackup"));
        configured.put("logsBackup", getCfg("feishu.base.tableLogsBackup"));

        if (configured.values().stream().allMatch(String::isBlank)) {
            return Map.of("valid", true, "message", "未配置备份表，主表同步不受影响", "tables", List.of());
        }

        Map<String, String> availableTables = listTables(token, appToken);
        List<Map<String, Object>> results = new ArrayList<>();
        boolean valid = true;
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            String tableId = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", entry.getKey());
            item.put("configured", !tableId.isBlank());
            if (tableId.isBlank()) {
                item.put("valid", true);
                item.put("message", "未配置，备份双写将跳过该表");
            } else if (availableTables.containsKey(tableId)) {
                item.put("valid", true);
                item.put("tableName", availableTables.get(tableId));
                item.put("message", "备份表可访问");
            } else {
                valid = false;
                item.put("valid", false);
                item.put("message", "当前 Base 中未找到该 Table ID，或应用没有访问权限");
            }
            results.add(item);
        }
        return Map.of(
                "valid", valid,
                "message", valid ? "备份表预检通过" : "备份表预检失败，请核对 Base、Table ID 与应用权限",
                "tables", results);
    }

    private Map<String, String> listTables(String token, String appToken) throws Exception {
        Map<String, String> tables = new LinkedHashMap<>();
        String pageToken = null;
        do {
            String url = String.format("%s/bitable/v1/apps/%s/tables?page_size=100%s",
                    API, appToken,
                    pageToken != null && !pageToken.isBlank() ? "&page_token=" + pageToken : "");
            JsonNode root = json.readTree(bearerGet(url, token));
            checkResponse(root, "读取数据表列表");
            for (JsonNode table : root.path("data").path("items")) {
                String tableId = table.path("table_id").asText();
                if (!tableId.isBlank()) {
                    tables.put(tableId, table.path("name").asText("未命名表"));
                }
            }
            boolean hasMore = root.path("data").path("has_more").asBoolean(false);
            pageToken = hasMore ? root.path("data").path("page_token").asText(null) : null;
        } while (pageToken != null && !pageToken.isBlank());
        return tables;
    }

    // ==================== Token 管理（纯机器人 token）====================

    private synchronized String getToken() throws Exception {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt - 120_000) {
            return cachedToken;
        }
        String appId = getCfg("feishu.appId");
        String secret = getCfg("feishu.appSecret");
        if (appId.isBlank() || secret.isBlank()) {
            throw new Exception("飞书 App ID/Secret 未配置");
        }
        ObjectNode body = json.createObjectNode();
        body.put("app_id", appId);
        body.put("app_secret", secret);

        String resp = directPost("/auth/v3/tenant_access_token/internal", body.toString());
        JsonNode root = json.readTree(resp);
        if (root.has("code") && root.get("code").asInt() != 0) {
            throw new Exception("获取 tenant_access_token 失败: " + root.path("msg").asText());
        }
        cachedToken = root.get("tenant_access_token").asText();
        tokenExpiresAt = System.currentTimeMillis() + root.get("expire").asLong() * 1000;
        log.info("飞书 tenant_access_token 已刷新");
        return cachedToken;
    }

    // ==================== 初始化（机器人自动创建 Base + 表）====================

    /**
     * 初始化飞书多维表格。如果尚未创建，则自动创建 Base 和所有数据表。
     * 由前端或首次同步时触发。
     */
    public synchronized Map<String, Object> initBase() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();

        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank()) {
            // 1. 创建 Base
            appToken = createBase();
            setCfg("feishu.base.appToken", appToken);
            result.put("appToken", appToken);
            log.info("飞书 Base 已创建: {}", appToken);
        } else {
            result.put("appToken", appToken);
        }

        // 2. 保证所有表存在
        String token = getToken();

        String tableProjects = getCfg("feishu.base.tableProjects");
        if (tableProjects.isBlank()) {
            tableProjects = createTable(token, appToken, "项目管理", createProjectFields());
            setCfg("feishu.base.tableProjects", tableProjects);
            log.info("项目管理表已创建: {}", tableProjects);
        }
        result.put("tableProjects", tableProjects);

        String tableTasks = getCfg("feishu.base.tableTasks");
        if (tableTasks.isBlank()) {
            tableTasks = createTable(token, appToken, "子任务管理", createTaskFields());
            setCfg("feishu.base.tableTasks", tableTasks);
            log.info("子任务表已创建: {}", tableTasks);
        }
        result.put("tableTasks", tableTasks);

        String tableScoring = getCfg("feishu.base.tableScoring");
        if (tableScoring.isBlank()) {
            tableScoring = createTable(token, appToken, "评分管理", createScoringFields());
            setCfg("feishu.base.tableScoring", tableScoring);
            log.info("评分表已创建: {}", tableScoring);
        }
        result.put("tableScoring", tableScoring);

        result.put("reviewFields", ensureReviewWorkflowFields());

        result.put("message", "飞书 Base 初始化完成");
        return result;
    }

    /** 为现有主表和备份表补齐两级审核同步字段。 */
    public synchronized Map<String, Object> ensureReviewWorkflowFields() throws Exception {
        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank()) throw new Exception("飞书 Base App Token 未配置");
        String token = getToken();

        Map<String, Integer> projectFields = new LinkedHashMap<>();
        projectFields.put("审核流程", 1);
        projectFields.put("当前审核阶段", 1);
        projectFields.put("审核进度", 2);

        Map<String, Integer> taskFields = new LinkedHashMap<>();
        for (String name : List.of("一审角色", "一审状态", "一审审核人", "二审角色", "二审状态", "二审审核人")) {
            taskFields.put(name, 1);
        }
        for (String name : List.of("一审得分", "二审得分", "审核得分")) {
            taskFields.put(name, 2);
        }

        Map<String, Integer> scoringFields = new LinkedHashMap<>();
        for (String name : List.of("项目ID", "项目类型", "审核阶段", "审核状态", "审核人", "审核意见")) {
            scoringFields.put(name, 1);
        }
        scoringFields.put("审核时间", 5);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("项目总表", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableProjects"), projectFields));
        result.put("项目总表_backup", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableProjectsBackup"), projectFields));
        result.put("子任务表", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableTasks"), taskFields));
        result.put("子任务表_backup", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableTasksBackup"), taskFields));
        result.put("评分记录表", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableScoring"), scoringFields));
        result.put("评分记录表_backup", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableScoringBackup"), scoringFields));
        return result;
    }

    private Map<String, Object> ensureFieldsForTable(String token, String appToken, String tableId,
                                                     Map<String, Integer> requiredFields) throws Exception {
        if (tableId == null || tableId.isBlank()) {
            return Map.of("configured", false, "added", 0);
        }
        Map<String, Integer> existing = new LinkedHashMap<>(getFieldTypes(token, appToken, tableId));
        int added = 0;
        for (Map.Entry<String, Integer> required : requiredFields.entrySet()) {
            Integer currentType = existing.get(required.getKey());
            if (currentType == null) {
                addField(token, appToken, tableId, required.getKey(), required.getValue());
                existing.put(required.getKey(), required.getValue());
                added++;
            } else if (!currentType.equals(required.getValue())) {
                throw new Exception("飞书字段类型不匹配: " + required.getKey());
            }
        }
        return Map.of("configured", true, "added", added, "total", requiredFields.size());
    }

    private String createBase() throws Exception {
        String token = getToken();
        ObjectNode body = json.createObjectNode();
        body.put("name", "产品管理系统");
        body.put("folder_token", "");
        String resp = bearerPost(API + "/bitable/v1/apps", token, body.toString());
        JsonNode root = json.readTree(resp);
        int code = root.path("code").asInt();
        if (code != 0) {
            throw new Exception("创建 Base 失败: " + root.path("msg").asText());
        }
        return root.path("data").path("app_token").asText();
    }

    private String createTable(String token, String appToken, String name, JsonNode fields) throws Exception {
        ObjectNode body = json.createObjectNode();
        ObjectNode table = json.createObjectNode();
        table.put("name", name);
        ArrayNode fieldsArr = fields.isArray() ? (ArrayNode) fields : json.createArrayNode().add(fields);
        table.set("fields", fieldsArr);
        body.set("table", table);

        String resp = bearerPost(API + "/bitable/v1/apps/" + appToken + "/tables", token, body.toString());
        JsonNode root = json.readTree(resp);
        int code = root.path("code").asInt();
        if (code != 0) {
            throw new Exception("创建表失败 (" + name + "): " + root.path("msg").asText());
        }
        return root.path("data").path("table_id").asText();
    }

    /** 项目表字段定义 */
    private JsonNode createProjectFields() {
        ArrayNode fields = json.createArrayNode();
        addTextFields(fields, "项目ID", "类型", "状态", "销售", "产品企划", "产品类目", "参考价格",
                "审核流程", "当前审核阶段");
        addDateFields(fields, "截止日期", "创建时间");
        addNumberFields(fields, "子任务数", "完成进度", "审核进度");
        return fields;
    }

    /** 子任务表字段定义 */
    private JsonNode createTaskFields() {
        ArrayNode fields = json.createArrayNode();
        addTextFields(fields, "子任务ID", "任务名称", "状态", "负责人", "所属项目",
                "一审角色", "一审状态", "一审审核人", "二审角色", "二审状态", "二审审核人");
        addDateFields(fields, "计划日期", "实际完成", "创建时间");
        addNumberFields(fields, "自评分", "一审得分", "二审得分", "审核得分");
        return fields;
    }

    /** 评分表字段定义 */
    private JsonNode createScoringFields() {
        ArrayNode fields = json.createArrayNode();
        addTextFields(fields, "评分ID", "评分角色", "所属子任务", "项目ID", "项目类型",
                "审核阶段", "审核状态", "审核人", "审核意见");
        addDateFields(fields, "审核时间");
        addNumberFields(fields, "评分", "权重");
        return fields;
    }

    private void addTextFields(ArrayNode fields, String... names) {
        for (String name : names) {
            ObjectNode field = json.createObjectNode();
            field.put("field_name", name);
            field.put("type", 1);
            fields.add(field);
        }
    }

    private void addNumberFields(ArrayNode fields, String... names) {
        for (String name : names) {
            ObjectNode field = json.createObjectNode();
            field.put("field_name", name);
            field.put("type", 2);
            fields.add(field);
        }
    }

    private void addDateFields(ArrayNode fields, String... names) {
        for (String name : names) {
            ObjectNode field = json.createObjectNode();
            field.put("field_name", name);
            field.put("type", 5);
            fields.add(field);
        }
    }

    /** 添加字段到已有表 */
    private void addField(String token, String appToken, String tableId, String fieldName, int fieldType) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("field_name", fieldName);
        body.put("type", fieldType);
        JsonNode root = json.readTree(bearerPost(
                API + "/bitable/v1/apps/" + appToken + "/tables/" + tableId + "/fields",
                token, body.toString()));
        checkResponse(root, "新增字段");
        fieldTypesCache.remove(appToken + ":" + tableId);
    }

    private Map<String, Integer> getFieldTypes(String token, String appToken, String tableId) throws Exception {
        String cacheKey = appToken + ":" + tableId;
        CachedFieldTypes cached = fieldTypesCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt()) {
            return cached.types();
        }

        Map<String, Integer> types = new LinkedHashMap<>();
        String pageToken = null;
        do {
            String url = String.format("%s/bitable/v1/apps/%s/tables/%s/fields?page_size=100%s",
                    API, appToken, tableId,
                    pageToken != null && !pageToken.isBlank() ? "&page_token=" + pageToken : "");
            JsonNode root = json.readTree(bearerGet(url, token));
            checkResponse(root, "读取字段列表");
            for (JsonNode field : root.path("data").path("items")) {
                types.put(field.path("field_name").asText(), field.path("type").asInt());
            }
            boolean hasMore = root.path("data").path("has_more").asBoolean(false);
            pageToken = hasMore ? root.path("data").path("page_token").asText(null) : null;
        } while (pageToken != null && !pageToken.isBlank());

        Map<String, Integer> immutable = Collections.unmodifiableMap(types);
        fieldTypesCache.put(cacheKey, new CachedFieldTypes(immutable, System.currentTimeMillis() + 600_000));
        return immutable;
    }

    // ==================== 同步项目 ====================

    public void syncProject(Long projectId, String type, String status,
                            String salesName, String plannerName,
                            String deadline, String productCategory,
                            String priceRange, int taskCount, int progress,
                            String reviewFlow, String currentReviewStage, int reviewProgress,
                            LocalDateTime createdAt) throws Exception {
        if (!isSyncEnabled()) return;

        syncProjectToTable(projectId, type, status, salesName, plannerName, deadline,
                productCategory, priceRange, taskCount, progress,
                reviewFlow, currentReviewStage, reviewProgress, createdAt,
                getCfg("feishu.base.tableProjects"));
        String backup = getCfg("feishu.base.tableProjectsBackup");
        if (!backup.isBlank()) {
            syncProjectToTable(projectId, type, status, salesName, plannerName, deadline,
                    productCategory, priceRange, taskCount, progress,
                    reviewFlow, currentReviewStage, reviewProgress, createdAt, backup);
        }
    }

    private void syncProjectToTable(Long projectId, String type, String status,
                                    String salesName, String plannerName,
                                    String deadline, String productCategory,
                                    String priceRange, int taskCount, int progress,
                                    String reviewFlow, String currentReviewStage, int reviewProgress,
                                    LocalDateTime createdAt, String tableId) throws Exception {

        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank() || tableId.isBlank())
            throw new Exception("飞书项目表未配置，无法同步");

        String token = getToken();
        String existed = findRecordId(token, appToken, tableId, "项目ID", String.valueOf(projectId));
        Map<String, Integer> fieldTypes = getFieldTypes(token, appToken, tableId);

        ObjectNode fields = json.createObjectNode();
        fields.put("项目ID", String.valueOf(projectId));
        fields.put("类型", "channel_custom".equals(type) ? "渠道定制单" : "公司常规品");
        fields.put("状态", statusLabel(status));
        fields.put("销售", salesName != null ? salesName : "");
        fields.put("产品企划", plannerName != null ? plannerName : "");
        if (deadline != null && !deadline.isBlank()) {
            putDateValue(fields, "截止日期", dateToTimestamp(deadline), fieldTypes.get("截止日期"));
        }
        if (productCategory != null && !productCategory.isBlank()) fields.put("产品类目", productCategory);
        if (priceRange != null && !priceRange.isBlank()) fields.put("参考价格", priceRange);
        fields.put("子任务数", taskCount);
        fields.put("完成进度", progress);
        fields.put("审核流程", reviewFlow != null ? reviewFlow : "");
        fields.put("当前审核阶段", currentReviewStage != null ? currentReviewStage : "");
        fields.put("审核进度", reviewProgress);
        if (createdAt != null) {
            putDateValue(fields, "创建时间", toTimestamp(createdAt), fieldTypes.get("创建时间"));
        }

        if (existed != null) {
            updateRecord(token, appToken, tableId, existed, fields.toString());
            log.debug("更新飞书项目记录: {}", projectId);
        } else {
            createRecord(token, appToken, tableId, fields.toString());
            log.info("创建飞书项目记录: {}", projectId);
        }
    }

    // ==================== 同步子任务 ====================

    public record SubTaskSyncData(
            Long taskId,
            String name,
            String status,
            String designerName,
            String plannedDate,
            String actualDate,
            Double selfScore,
            Long projectId,
            String firstReviewRole,
            String firstReviewStatus,
            Integer firstReviewScore,
            String firstReviewerName,
            String secondReviewRole,
            String secondReviewStatus,
            Integer secondReviewScore,
            String secondReviewerName,
            Double finalReviewScore,
            LocalDateTime createdAt
    ) {}

    public void syncSubTask(SubTaskSyncData data) throws Exception {
        if (!isSyncEnabled()) return;

        syncSubTaskToTable(data, getCfg("feishu.base.tableTasks"));
        String backup = getCfg("feishu.base.tableTasksBackup");
        if (!backup.isBlank()) {
            syncSubTaskToTable(data, backup);
        }
    }

    private void syncSubTaskToTable(SubTaskSyncData data, String tableId) throws Exception {

        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank() || tableId.isBlank())
            throw new Exception("飞书子任务表未配置，无法同步");

        String token = getToken();
        String existed = findRecordId(token, appToken, tableId, "子任务ID", String.valueOf(data.taskId()));
        Map<String, Integer> fieldTypes = getFieldTypes(token, appToken, tableId);

        ObjectNode fields = json.createObjectNode();
        fields.put("子任务ID", String.valueOf(data.taskId()));
        fields.put("任务名称", data.name() != null ? data.name() : "");
        fields.put("状态", taskStatusLabel(data.status()));
        fields.put("负责人", data.designerName() != null ? data.designerName() : "");
        if (data.plannedDate() != null && !data.plannedDate().isBlank()) {
            putDateValue(fields, "计划日期", dateToTimestamp(data.plannedDate()), fieldTypes.get("计划日期"));
        }
        if (data.actualDate() != null && !data.actualDate().isBlank()) {
            putDateValue(fields, "实际完成", dateToTimestamp(data.actualDate()), fieldTypes.get("实际完成"));
        }
        if (data.selfScore() != null) fields.put("自评分", data.selfScore().intValue());
        putReviewSummary(fields, "一审", data.firstReviewRole(), data.firstReviewStatus(),
                data.firstReviewScore(), data.firstReviewerName(), existed != null);
        putReviewSummary(fields, "二审", data.secondReviewRole(), data.secondReviewStatus(),
                data.secondReviewScore(), data.secondReviewerName(), existed != null);
        if (data.finalReviewScore() != null) {
            fields.put("审核得分", data.finalReviewScore());
        } else if (existed != null) {
            fields.putNull("审核得分");
        }
        if (data.createdAt() != null) {
            putDateValue(fields, "创建时间", toTimestamp(data.createdAt()), fieldTypes.get("创建时间"));
        }

        if (data.projectId() != null) {
            Integer fieldType = fieldTypes.get("所属项目");
            String linkedRecordId = null;
            if (isLinkField(fieldType)) {
                linkedRecordId = findRecordId(token, appToken, getCfg("feishu.base.tableProjects"),
                        "项目ID", String.valueOf(data.projectId()));
            }
            putReferenceValue(fields, "所属项目", String.valueOf(data.projectId()), linkedRecordId, fieldType);
        }

        if (existed != null) {
            updateRecord(token, appToken, tableId, existed, fields.toString());
        } else {
            createRecord(token, appToken, tableId, fields.toString());
        }
    }

    private void putReviewSummary(ObjectNode fields, String prefix, String role, String status,
                                  Integer score, String reviewerName, boolean existed) {
        fields.put(prefix + "角色", roleLabel(role));
        fields.put(prefix + "状态", reviewStatusLabel(status));
        fields.put(prefix + "审核人", reviewerName != null ? reviewerName : "");
        if (score != null) {
            fields.put(prefix + "得分", score);
        } else if (existed) {
            fields.putNull(prefix + "得分");
        }
    }

    // ==================== 同步评分 ====================

    public record ScoringSyncData(
            Long recordId,
            String role,
            Integer score,
            Double weight,
            Long subTaskId,
            Long projectId,
            String projectType,
            String reviewStage,
            String reviewStatus,
            String reviewerName,
            String comment,
            LocalDateTime reviewedAt
    ) {}

    public void syncScoring(ScoringSyncData data) throws Exception {
        if (!isSyncEnabled()) return;

        syncScoringToTable(data, getCfg("feishu.base.tableScoring"));
        String backup = getCfg("feishu.base.tableScoringBackup");
        if (!backup.isBlank()) {
            syncScoringToTable(data, backup);
        }
    }

    private void syncScoringToTable(ScoringSyncData data, String tableId) throws Exception {

        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank() || tableId.isBlank())
            throw new Exception("飞书评分表未配置，无法同步");

        String token = getToken();
        String existed = findRecordId(token, appToken, tableId, "评分ID", String.valueOf(data.recordId()));
        Map<String, Integer> fieldTypes = getFieldTypes(token, appToken, tableId);

        ObjectNode fields = json.createObjectNode();
        fields.put("评分ID", String.valueOf(data.recordId()));
        fields.put("评分角色", roleLabel(data.role()));
        if (data.score() != null) {
            fields.put("评分", data.score());
        } else if (existed != null) {
            fields.putNull("评分");
        }
        fields.put("权重", data.weight() != null ? (int)(data.weight() * 100) : 0);
        fields.put("项目ID", data.projectId() != null ? String.valueOf(data.projectId()) : "");
        fields.put("项目类型", projectTypeLabel(data.projectType()));
        fields.put("审核阶段", reviewStageLabel(data.reviewStage()));
        fields.put("审核状态", reviewStatusLabel(data.reviewStatus()));
        fields.put("审核人", data.reviewerName() != null ? data.reviewerName() : "");
        fields.put("审核意见", data.comment() != null ? data.comment() : "");
        if (data.reviewedAt() != null) {
            putDateValue(fields, "审核时间", toTimestamp(data.reviewedAt()), fieldTypes.get("审核时间"));
        } else if (existed != null) {
            fields.putNull("审核时间");
        }

        if (data.subTaskId() != null) {
            Integer fieldType = fieldTypes.get("所属子任务");
            String linkedRecordId = null;
            if (isLinkField(fieldType)) {
                linkedRecordId = findRecordId(token, appToken, getCfg("feishu.base.tableTasks"),
                        "子任务ID", String.valueOf(data.subTaskId()));
            }
            putReferenceValue(fields, "所属子任务", data.subTaskId().toString(), linkedRecordId, fieldType);
        }

        if (existed != null) {
            updateRecord(token, appToken, tableId, existed, fields.toString());
        } else {
            createRecord(token, appToken, tableId, fields.toString());
        }
    }

    public void syncActivityLog(Long logId, String action, String username, String role,
                                Long projectId, LocalDateTime time) throws Exception {
        if (!isSyncEnabled()) return;
        String primary = getCfg("feishu.base.tableLogs");
        String backup = getCfg("feishu.base.tableLogsBackup");
        if (primary.isBlank() && backup.isBlank()) return;

        if (!primary.isBlank()) {
            syncActivityLogToTable(logId, action, username, role, projectId, time, primary);
        }
        if (!backup.isBlank() && !backup.equals(primary)) {
            syncActivityLogToTable(logId, action, username, role, projectId, time, backup);
        }
    }

    private void syncActivityLogToTable(Long logId, String action, String username, String role,
                                        Long projectId, LocalDateTime time, String tableId) throws Exception {
        String appToken = getCfg("feishu.base.appToken");
        String token = getToken();
        String existed = findRecordId(token, appToken, tableId, "日志ID", String.valueOf(logId));
        Map<String, Integer> fieldTypes = getFieldTypes(token, appToken, tableId);
        ObjectNode fields = json.createObjectNode();
        fields.put("日志ID", String.valueOf(logId));
        fields.put("操作内容", action != null ? action : "");
        fields.put("操作人", username != null ? username : "");
        fields.put("角色", roleLabel(role));
        if (projectId != null) fields.put("所属项目", String.valueOf(projectId));
        if (time != null) {
            putDateValue(fields, "时间", toTimestamp(time), fieldTypes.get("时间"));
        }
        if (existed != null) updateRecord(token, appToken, tableId, existed, fields.toString());
        else createRecord(token, appToken, tableId, fields.toString());
    }

    // ==================== 删除同步 ====================

    public void deleteProjectRecord(Long projectId) throws Exception {
        if (!isSyncEnabled()) return;
        String token = getToken();
        String existed = findRecordId(token,
                getCfg("feishu.base.appToken"), getCfg("feishu.base.tableProjects"),
                "项目ID", String.valueOf(projectId));
        if (existed != null) {
            deleteRecord(token, getCfg("feishu.base.appToken"), getCfg("feishu.base.tableProjects"), existed);
        }
    }

    public void deleteSubTaskRecord(Long taskId) throws Exception {
        if (!isSyncEnabled()) return;
        String token = getToken();
        String existed = findRecordId(token,
                getCfg("feishu.base.appToken"), getCfg("feishu.base.tableTasks"),
                "子任务ID", String.valueOf(taskId));
        if (existed != null) {
            deleteRecord(token, getCfg("feishu.base.appToken"), getCfg("feishu.base.tableTasks"), existed);
        }
    }

    public void deleteScoringRecord(Long scoringRecordId) throws Exception {
        if (!isSyncEnabled()) return;
        String token = getToken();
        String existed = findRecordId(token,
                getCfg("feishu.base.appToken"), getCfg("feishu.base.tableScoring"),
                "评分ID", String.valueOf(scoringRecordId));
        if (existed != null) {
            deleteRecord(token, getCfg("feishu.base.appToken"), getCfg("feishu.base.tableScoring"), existed);
        }
    }

    // ==================== 通用 API 调用 ====================

    private String findRecordId(String token, String appToken, String tableId,
                                String fieldKey, String fieldValue) throws Exception {
        String pageToken = null;
        do {
            String url = String.format("%s/bitable/v1/apps/%s/tables/%s/records?page_size=500%s",
                    API, appToken, tableId,
                    pageToken != null && !pageToken.isBlank() ? "&page_token=" + pageToken : "");
            String resp = bearerGet(url, token);
            JsonNode root = json.readTree(resp);
            if (root.has("code") && root.get("code").asInt() != 0) {
                return null;
            }

            JsonNode items = root.path("data").path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    JsonNode fields = item.path("fields");
                    if (fields.has(fieldKey) && fieldValue.equals(fields.get(fieldKey).asText())) {
                        return item.get("record_id").asText();
                    }
                }
            }

            boolean hasMore = root.path("data").path("has_more").asBoolean(false);
            pageToken = hasMore ? root.path("data").path("page_token").asText(null) : null;
        } while (pageToken != null && !pageToken.isBlank());
        return null;
    }

    private void createRecord(String token, String appToken, String tableId, String fieldsJson) throws Exception {
        String url = String.format("%s/bitable/v1/apps/%s/tables/%s/records", API, appToken, tableId);
        String body = "{\"fields\": " + fieldsJson + "}";
        String resp = bearerPost(url, token, body);
        checkResponse(resp, "创建记录");
    }

    private void updateRecord(String token, String appToken, String tableId,
                              String recordId, String fieldsJson) throws Exception {
        String url = String.format("%s/bitable/v1/apps/%s/tables/%s/records/%s",
                API, appToken, tableId, recordId);
        String body = "{\"fields\": " + fieldsJson + "}";
        String resp = bearerPut(url, token, body);
        checkResponse(resp, "更新记录");
    }

    private void deleteRecord(String token, String appToken, String tableId,
                              String recordId) throws Exception {
        String url = String.format("%s/bitable/v1/apps/%s/tables/%s/records/%s",
                API, appToken, tableId, recordId);
        String resp = bearerDelete(url, token);
        checkResponse(resp, "删除记录");
    }

    private void checkResponse(String resp, String action) throws Exception {
        checkResponse(json.readTree(resp), action);
    }

    private void checkResponse(JsonNode root, String action) throws Exception {
        int code = root.path("code").asInt();
        if (code != 0) {
            log.warn("飞书 API {} 失败: code={} msg={}", action, code, root.path("msg").asText());
            throw new Exception("飞书 API " + action + " 失败: " + root.path("msg").asText());
        }
    }

    // ==================== HTTP 方法（Bearer Token）====================

    /** 不带 Authorization 的 POST（仅用于获取 token 本身） */
    private String directPost(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String bearerRequest(String method, String url, String token, String body) throws Exception {
        log.debug("bearerRequest: {} {} body={}", method, url, body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8");

        switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
            case "DELETE" -> builder.DELETE();
            default -> builder.GET();
        }

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();
        if (response.statusCode() / 100 != 2) {
            throw new Exception("飞书 HTTP 请求失败: status=" + response.statusCode() + " body=" + responseBody);
        }
        return responseBody;
    }

    private String bearerGet(String url, String token) throws Exception {
        return bearerRequest("GET", url, token, null);
    }

    private String bearerPost(String url, String token, String body) throws Exception {
        return bearerRequest("POST", url, token, body);
    }

    private String bearerPut(String url, String token, String body) throws Exception {
        return bearerRequest("PUT", url, token, body);
    }

    private String bearerDelete(String url, String token) throws Exception {
        return bearerRequest("DELETE", url, token, null);
    }

    // ==================== 辅助方法 ====================

    private static String statusLabel(String s) {
        if (s == null) return "未知";
        return switch (s) {
            case "draft" -> "草稿";
            case "pending_planner" -> "待企划";
            case "planner_accepted" -> "企划已接单";
            case "in_progress" -> "进行中";
            case "completed" -> "已完成";
            case "paused" -> "已暂停";
            case "pending_terminate" -> "终止确认中";
            case "terminated" -> "已终止";
            default -> s;
        };
    }

    private static String taskStatusLabel(String s) {
        if (s == null) return "未知";
        return switch (s) {
            case "pending" -> "待认领";
            case "accepted" -> "进行中";
            case "delivered" -> "已交付";
            case "planner_approved" -> "企划已验收";
            case "scoring_planner" -> "待二次验收";
            case "sales_approved" -> "销售已验收";
            case "admin_approved" -> "管理已验收";
            case "approved" -> "已通过";
            case "completed" -> "已完成";
            case "rejected" -> "已驳回";
            default -> s;
        };
    }

    private static String roleLabel(String r) {
        if (r == null) return "未知";
        return switch (r) {
            case "sales" -> "销售";
            case "planner" -> "企划";
            case "designer" -> "设计师";
            case "supplychain" -> "供应链";
            case "admin" -> "管理";
            default -> r;
        };
    }

    static String projectTypeLabel(String type) {
        return "channel_custom".equals(type) ? "渠道定制" : "公司常规品";
    }

    static String reviewStageLabel(String stage) {
        return "second".equals(stage) ? "二审" : "一审";
    }

    static String reviewStatusLabel(String status) {
        return switch (status != null ? status : "pending") {
            case "waiting" -> "等待一审";
            case "approved" -> "已通过";
            case "rejected" -> "已驳回";
            default -> "待审核";
        };
    }

    static boolean isLinkField(Integer fieldType) {
        return fieldType != null && (fieldType == 18 || fieldType == 21);
    }

    static void putReferenceValue(ObjectNode fields, String fieldName, String businessId,
                                  String linkedRecordId, Integer fieldType) {
        if (isLinkField(fieldType)) {
            if (linkedRecordId == null || linkedRecordId.isBlank()) {
                throw new IllegalArgumentException("关联记录尚未同步: " + fieldName);
            }
            fields.putArray(fieldName).add(linkedRecordId);
            return;
        }
        if (fieldType != null && (fieldType == 1 || fieldType == 3)) {
            fields.put(fieldName, businessId);
            return;
        }
        throw new IllegalArgumentException("字段类型不兼容: " + fieldName);
    }

    static void putDateValue(ObjectNode fields, String fieldName, long timestamp, Integer fieldType) {
        if (fieldType != null && fieldType == 5) {
            fields.put(fieldName, timestamp);
            return;
        }
        // 飞书“创建时间”等自动字段为只读字段，保留飞书自动生成值。
        if (fieldType != null && fieldType >= 1000) return;
        throw new IllegalArgumentException("日期字段类型不兼容: " + fieldName);
    }

    static long toTimestamp(LocalDateTime time) {
        return time.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }

    /** 将日期字符串 yyyy-MM-dd 转换为飞书日期字段使用的毫秒时间戳。 */
    static long dateToTimestamp(String dateStr) {
        try {
            String d = dateStr.contains(" ") ? dateStr.split(" ")[0] : dateStr;
            return java.time.LocalDate.parse(d)
                .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli();
        } catch (Exception e) {
            return java.time.Instant.now().toEpochMilli();
        }
    }
}
