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

    private String cachedToken;
    private long tokenExpiresAt;

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
                "feishu.base.tableScoringBackup",
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

        result.put("message", "飞书 Base 初始化完成");
        return result;
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
        addTextFields(fields, "项目ID", "类型", "状态", "销售", "产品企划", "产品类目", "参考价格");
        addDateFields(fields, "截止日期", "创建时间");
        addNumberFields(fields, "子任务数", "完成进度");
        return fields;
    }

    /** 子任务表字段定义 */
    private JsonNode createTaskFields() {
        ArrayNode fields = json.createArrayNode();
        addTextFields(fields, "子任务ID", "任务名称", "状态", "负责人", "所属项目");
        addDateFields(fields, "计划日期", "实际完成", "创建时间");
        addNumberFields(fields, "自评分");
        return fields;
    }

    /** 评分表字段定义 */
    private JsonNode createScoringFields() {
        ArrayNode fields = json.createArrayNode();
        addTextFields(fields, "评分ID", "评分角色", "所属子任务");
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
        bearerPost(API + "/bitable/v1/apps/" + appToken + "/tables/" + tableId + "/fields", token, body.toString());
    }

    // ==================== 同步项目 ====================

    public void syncProject(Long projectId, String type, String status,
                            String salesName, String plannerName,
                            String deadline, String productCategory,
                            String priceRange, int taskCount, int progress,
                            LocalDateTime createdAt) throws Exception {
        if (!isSyncEnabled()) return;

        syncProjectToTable(projectId, type, status, salesName, plannerName, deadline,
                productCategory, priceRange, taskCount, progress, createdAt,
                getCfg("feishu.base.tableProjects"));
        String backup = getCfg("feishu.base.tableProjectsBackup");
        if (!backup.isBlank()) {
            syncProjectToTable(projectId, type, status, salesName, plannerName, deadline,
                    productCategory, priceRange, taskCount, progress, createdAt, backup);
        }
    }

    private void syncProjectToTable(Long projectId, String type, String status,
                                    String salesName, String plannerName,
                                    String deadline, String productCategory,
                                    String priceRange, int taskCount, int progress,
                                    LocalDateTime createdAt, String tableId) throws Exception {

        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank() || tableId.isBlank())
            throw new Exception("飞书项目表未配置，无法同步");

        String token = getToken();
        String existed = findRecordId(token, appToken, tableId, "项目ID", String.valueOf(projectId));

        ObjectNode fields = json.createObjectNode();
        fields.put("项目ID", String.valueOf(projectId));
        fields.put("类型", "channel_custom".equals(type) ? "渠道定制单" : "公司常规品");
        fields.put("状态", statusLabel(status));
        fields.put("销售", salesName != null ? salesName : "");
        fields.put("产品企划", plannerName != null ? plannerName : "");
        if (deadline != null && !deadline.isBlank()) {
            fields.put("截止日期", dateToTimestamp(deadline));
        }
        if (productCategory != null && !productCategory.isBlank()) fields.put("产品类目", productCategory);
        if (priceRange != null && !priceRange.isBlank()) fields.put("参考价格", priceRange);
        fields.put("子任务数", taskCount);
        fields.put("完成进度", progress);
        if (createdAt != null) fields.put("创建时间", createdAt.toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond());

        if (existed != null) {
            updateRecord(token, appToken, tableId, existed, fields.toString());
            log.debug("更新飞书项目记录: {}", projectId);
        } else {
            createRecord(token, appToken, tableId, fields.toString());
            log.info("创建飞书项目记录: {}", projectId);
        }
    }

    // ==================== 同步子任务 ====================

    public void syncSubTask(Long taskId, String name, String status,
                            String designerName, String plannedDate,
                            String actualDate, Double selfScore,
                            Long projectId, LocalDateTime createdAt) throws Exception {
        if (!isSyncEnabled()) return;

        syncSubTaskToTable(taskId, name, status, designerName, plannedDate, actualDate,
                selfScore, projectId, createdAt, getCfg("feishu.base.tableTasks"));
        String backup = getCfg("feishu.base.tableTasksBackup");
        if (!backup.isBlank()) {
            syncSubTaskToTable(taskId, name, status, designerName, plannedDate, actualDate,
                    selfScore, projectId, createdAt, backup);
        }
    }

    private void syncSubTaskToTable(Long taskId, String name, String status,
                                    String designerName, String plannedDate,
                                    String actualDate, Double selfScore,
                                    Long projectId, LocalDateTime createdAt,
                                    String tableId) throws Exception {

        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank() || tableId.isBlank())
            throw new Exception("飞书子任务表未配置，无法同步");

        String token = getToken();
        String existed = findRecordId(token, appToken, tableId, "子任务ID", String.valueOf(taskId));

        ObjectNode fields = json.createObjectNode();
        fields.put("子任务ID", String.valueOf(taskId));
        fields.put("任务名称", name != null ? name : "");
        fields.put("状态", taskStatusLabel(status));
        fields.put("负责人", designerName != null ? designerName : "");
        if (plannedDate != null && !plannedDate.isBlank()) {
            fields.put("计划日期", dateToTimestamp(plannedDate));
        }
        if (actualDate != null && !actualDate.isBlank()) {
            fields.put("实际完成", dateToTimestamp(actualDate));
        }
        if (selfScore != null) fields.put("自评分", selfScore.intValue());
        if (createdAt != null) fields.put("创建时间", createdAt.toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond());

        // 生产环境“所属项目”字段为文本类型，写入项目编号即可。
        // 不能发送关联字段所需的 record_id 数组，否则飞书会返回 TextFieldConvFail。
        if (projectId != null) fields.put("所属项目", String.valueOf(projectId));

        if (existed != null) {
            updateRecord(token, appToken, tableId, existed, fields.toString());
        } else {
            createRecord(token, appToken, tableId, fields.toString());
        }
    }

    // ==================== 同步评分 ====================

    public void syncScoring(Long recordId, String role, Integer score,
                            Double weight, Long subTaskId) throws Exception {
        if (!isSyncEnabled()) return;

        syncScoringToTable(recordId, role, score, weight, subTaskId,
                getCfg("feishu.base.tableScoring"));
        String backup = getCfg("feishu.base.tableScoringBackup");
        if (!backup.isBlank()) {
            syncScoringToTable(recordId, role, score, weight, subTaskId, backup);
        }
    }

    private void syncScoringToTable(Long recordId, String role, Integer score,
                                    Double weight, Long subTaskId,
                                    String tableId) throws Exception {

        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank() || tableId.isBlank())
            throw new Exception("飞书评分表未配置，无法同步");

        String token = getToken();
        String existed = findRecordId(token, appToken, tableId, "评分ID", String.valueOf(recordId));

        ObjectNode fields = json.createObjectNode();
        fields.put("评分ID", String.valueOf(recordId));
        fields.put("评分角色", roleLabel(role));
        fields.put("评分", score != null ? score : 0);
        fields.put("权重", weight != null ? (int)(weight * 100) : 0);

        if (subTaskId != null) {
            // 生产 Base 中“所属子任务”为文本字段，直接写业务 ID，避免依赖同步顺序。
            fields.put("所属子任务", subTaskId.toString());
        }

        if (existed != null) {
            updateRecord(token, appToken, tableId, existed, fields.toString());
        } else {
            createRecord(token, appToken, tableId, fields.toString());
        }
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
        return switch (r) {
            case "sales" -> "销售";
            case "planner" -> "企划";
            case "designer" -> "设计师";
            case "supplychain" -> "供应链";
            case "admin" -> "管理";
            default -> r;
        };
    }

    /** 将日期字符串 yyyy-MM-dd 转换为 Unix 时间戳（秒） */
    private static long dateToTimestamp(String dateStr) {
        try {
            String d = dateStr.contains(" ") ? dateStr.split(" ")[0] : dateStr;
            return java.time.LocalDate.parse(d)
                .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
                .toEpochSecond();
        } catch (Exception e) {
            return java.time.Instant.now().getEpochSecond();
        }
    }
}
