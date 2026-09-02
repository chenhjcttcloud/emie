package com.emie.designpm.service;

import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.repository.SystemConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 企业级方案：统一飞书应用通过 tenant_access_token 直接调用飞书 API，
 * 自动创建属于机器人的多维表格并发送通知，无需用户授权。
 */
@Service
public class FeishuBaseService {

    private static final Logger log = LoggerFactory.getLogger(FeishuBaseService.class);
    private static final String API = "https://open.feishu.cn/open-apis";
    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper json = new ObjectMapper();

    private final SystemConfigRepository configRepository;
    private final FeishuAttachmentService attachmentService;
    private final Map<String, CachedFieldTypes> fieldTypesCache = new ConcurrentHashMap<>();

    private String cachedToken;
    private long tokenExpiresAt;

    private record CachedFieldTypes(Map<String, Integer> types, long expiresAt) {}

    private static final Map<String, String> IDENTITY_FIELDS = Map.of(
            "project", "项目ID", "task", "子任务ID", "scoring", "评分ID", "log", "日志ID");

    @Autowired
    public FeishuBaseService(SystemConfigRepository configRepository, FeishuAttachmentService attachmentService) {
        this.configRepository = configRepository;
        this.attachmentService = attachmentService;
    }

    /** 保留轻量单元测试的构造方式。 */
    public FeishuBaseService(SystemConfigRepository configRepository) {
        this(configRepository, null);
    }

    // ==================== 配置读取 ====================

    private boolean isSyncEnabled() {
        return "true".equals(getCfg("feishu.base.syncEnabled"));
    }

    public boolean isV2Active() {
        return "true".equals(getCfg("feishu.base.v2.active"));
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

    /** Read the configured sales table for automated performance aggregation. */
    public List<Map<String, String>> readSalesRecords() throws Exception {
        String appToken = getCfg("feishu.sales.appToken");
        String tableId = getCfg("feishu.sales.tableId");
        if (appToken.isBlank() || tableId.isBlank()) return List.of();
        String token = getToken();
        List<Map<String, String>> result = new ArrayList<>();
        for (RecordSnapshot record : listRecordSnapshots(token, appToken, tableId)) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("recordId", record.recordId());
            row.put("orderId", fieldText(record.fields(), getCfgOrDefault("feishu.sales.orderField", "订单号")));
            row.put("date", fieldText(record.fields(), getCfgOrDefault("feishu.sales.dateField", "订单日期")));
            row.put("amount", fieldText(record.fields(), getCfgOrDefault("feishu.sales.amountField", "销售额")));
            row.put("refund", fieldText(record.fields(), getCfgOrDefault("feishu.sales.refundField", "退款金额")));
            row.put("status", fieldText(record.fields(), getCfgOrDefault("feishu.sales.statusField", "订单状态")));
            result.add(row);
        }
        return result;
    }

    /** Update a configured sales row. Disabled unless explicitly enabled by an administrator. */
    public Map<String, Object> updateSalesRecord(String orderId, Map<String, Object> changes) throws Exception {
        if (!"true".equalsIgnoreCase(getCfg("feishu.sales.writeEnabled"))) {
            throw new IllegalStateException("飞书销售表写入未启用");
        }
        if (orderId == null || orderId.isBlank() || changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("订单号和至少一个修改字段不能为空");
        }
        String appToken = getCfg("feishu.sales.appToken"), tableId = getCfg("feishu.sales.tableId");
        String token = getToken();
        String orderField = getCfgOrDefault("feishu.sales.orderField", "订单号");
        RecordSnapshot found = listRecordSnapshots(token, appToken, tableId).stream()
                .filter(r -> orderId.equals(fieldText(r.fields(), orderField))).findFirst().orElse(null);
        if (found == null) throw new IllegalArgumentException("未找到订单: " + orderId);
        Set<String> allowed = Set.of(getCfgOrDefault("feishu.sales.dateField", "订单日期"),
                getCfgOrDefault("feishu.sales.amountField", "销售额"),
                getCfgOrDefault("feishu.sales.refundField", "退款金额"),
                getCfgOrDefault("feishu.sales.statusField", "订单状态"));
        ObjectNode fields = json.createObjectNode();
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            if (!allowed.contains(entry.getKey())) throw new IllegalArgumentException("不允许修改字段: " + entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Number n) fields.put(entry.getKey(), n.doubleValue());
            else fields.put(entry.getKey(), Objects.toString(value, ""));
        }
        updateRecord(token, appToken, tableId, found.recordId(), fields.toString());
        return Map.of("updated", true, "orderId", orderId, "fields", changes.keySet());
    }

    private String getCfgOrDefault(String key, String fallback) {
        String value = getCfg(key);
        return value.isBlank() ? fallback : value;
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

    /**
     * V2 字段契约预检：只读取飞书结构，不创建字段或写入记录。
     * 非关键冲突会报告将使用的兼容列；关键冲突必须由管理员修复后才能安全同步。
     */
    public Map<String, Object> diagnoseFieldSchema() throws Exception {
        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank()) throw new Exception("飞书 Base App Token 未配置");
        String token = getToken();
        String mappings = getCfg("feishu.base.fieldMappings");

        List<Map<String, Object>> tables = new java.util.ArrayList<>();
        int conflicts = 0;
        int fallbacks = 0;
        for (Map.Entry<String, Map<String, Integer>> catalog : FeishuFieldSchema.catalog().entrySet()) {
            String tableKey = catalog.getKey();
            String configSuffix = switch (tableKey) {
                case "project" -> "Projects";
                case "task" -> "Tasks";
                case "scoring" -> "Scoring";
                default -> "Logs";
            };
            for (boolean backup : List.of(false, true)) {
                String configKey = "feishu.base.table" + configSuffix + (backup ? "Backup" : "");
                String tableId = getCfg(configKey);
                if (tableId.isBlank()) continue;
                Map<String, Integer> actualTypes = getFieldTypes(token, appToken, tableId);
                List<Map<String, Object>> issues = new java.util.ArrayList<>();
                Map<String, Integer> expectedFields = new LinkedHashMap<>(catalog.getValue());
                if (backup) {
                    expectedFields.put("备份状态", FeishuFieldSchema.TEXT);
                    expectedFields.put("源数据删除时间", FeishuFieldSchema.DATE);
                }
                for (Map.Entry<String, Integer> field : expectedFields.entrySet()) {
                    JsonNode mapping = mappingNode(tableKey, field.getKey(), mappings);
                    if (mapping != null && !mapping.path("enabled").asBoolean(true)
                            && !IDENTITY_FIELDS.get(tableKey).equals(field.getKey())) continue;
                    String target = configuredTargetName(tableKey, field.getKey(), mappings);
                    Integer actual = actualTypes.get(target);
                    if (actual == null || compatibleFieldType(field.getKey(), field.getValue(), actual)) continue;
                    boolean critical = FeishuFieldSchema.critical(field.getKey());
                    String fallback = critical ? "" : FeishuFieldSchema.fallbackName(target, field.getValue());
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("source", field.getKey());
                    issue.put("target", target);
                    issue.put("expectedType", field.getValue());
                    issue.put("actualType", actual);
                    issue.put("severity", critical ? "blocking" : "fallback");
                    if (!fallback.isBlank()) issue.put("fallback", fallback);
                    issues.add(issue);
                    if (critical) conflicts++; else fallbacks++;
                }
                Map<String, Object> table = new LinkedHashMap<>();
                table.put("table", tableKey);
                table.put("backup", backup);
                table.put("tableId", tableId);
                table.put("issues", issues);
                tables.add(table);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", conflicts == 0);
        result.put("blockingConflicts", conflicts);
        result.put("fallbackFields", fallbacks);
        result.put("tables", tables);
        return result;
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

    // ==================== Token 管理（统一飞书应用 token）====================

    private synchronized String getToken() throws Exception {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt - 120_000) {
            return cachedToken;
        }
        String appId = getCfg("feishu.ssoAppId");
        String secret = getCfg("feishu.ssoAppSecret");
        if (appId.isBlank() || secret.isBlank()) {
            throw new Exception("飞书统一应用 App ID/Secret 未配置");
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

    /**
     * 向已绑定飞书 Open ID 的用户发送交互式卡片消息。
     * 该能力与多维表格同步共用统一应用的 tenant_access_token，避免在通知模块重复管理应用凭据。
     */
    public String sendInteractiveMessage(String openId, String cardContent) throws Exception {
        if (openId == null || openId.isBlank()) {
            throw new IllegalArgumentException("收件人的飞书 Open ID 未绑定");
        }
        ObjectNode body = json.createObjectNode();
        body.put("receive_id", openId);
        body.put("msg_type", "interactive");
        body.put("content", cardContent);

        JsonNode root = json.readTree(bearerPost(
                API + "/im/v1/messages?receive_id_type=open_id", getToken(), body.toString()));
        checkResponse(root, "发送飞书通知");
        String messageId = root.path("data").path("message_id").asText();
        if (messageId.isBlank()) {
            throw new Exception("发送飞书通知失败：响应中缺少 message_id");
        }
        return messageId;
    }

    /** 卡片解析失败时的纯文本降级通道，确保通知不会因展示格式异常丢失。 */
    public String sendTextMessage(String openId, String textContent) throws Exception {
        if (openId == null || openId.isBlank()) throw new IllegalArgumentException("收件人的飞书 Open ID 未绑定");
        ObjectNode body = json.createObjectNode();
        body.put("receive_id", openId);
        body.put("msg_type", "text");
        ObjectNode content = json.createObjectNode();
        content.put("text", textContent == null || textContent.isBlank() ? "系统通知" : textContent);
        body.put("content", content.toString());
        JsonNode root = json.readTree(bearerPost(
                API + "/im/v1/messages?receive_id_type=open_id", getToken(), body.toString()));
        checkResponse(root, "发送飞书文本通知");
        String messageId = root.path("data").path("message_id").asText();
        if (messageId.isBlank()) throw new Exception("发送飞书文本通知失败：响应中缺少 message_id");
        return messageId;
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
        log.info("飞书 Base 已创建");
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
        result.put("mirrorFields", ensureMirrorStrategyFields());

        result.put("message", "飞书 Base 初始化完成");
        return result;
    }

    /**
     * 创建或续建一套独立的 V2 Base。所有 token 先写入 staging 配置，
     * 不会改变当前生产同步目标；只有显式 activateV2Base 后才切换。
     */
    public synchronized Map<String, Object> prepareV2Base() throws Exception {
        setCfg("feishu.base.v2.staging.status", "preparing");
        String token = getToken();
        String appToken = getCfg("feishu.base.v2.staging.appToken");
        if (appToken.isBlank()) {
            appToken = createBase("EMIE 数据镜像 V2");
            setCfg("feishu.base.v2.staging.appToken", appToken);
        }

        Map<String, FeishuV2Schema.Table> schemas = FeishuV2Schema.allTables();
        Map<String, String> tableIds = new LinkedHashMap<>();
        for (FeishuV2Schema.Table table : schemas.values()) {
            String configKey = v2StagingTableConfigKey(table.key());
            String tableId = getCfg(configKey);
            if (tableId.isBlank()) {
                ArrayNode initial = json.createArrayNode();
                addTextFields(initial, table.identity());
                tableId = createTable(token, appToken, table.name(), initial);
                setCfg(configKey, tableId);
            }
            tableIds.put(table.key(), tableId);
        }

        int added = 0;
        for (FeishuV2Schema.Table table : schemas.values()) {
            String tableId = tableIds.get(table.key());
            Map<String, Integer> existing = new LinkedHashMap<>(getFieldTypes(token, appToken, tableId));
            for (FeishuV2Schema.Field field : table.fields()) {
                Integer actual = existing.get(field.name());
                if (actual == null) {
                    String linkedKey = field.linkedTable();
                    if (linkedKey != null && table.key().endsWith("Backup")) linkedKey += "Backup";
                    addV2Field(token, appToken, tableId, field,
                            linkedKey == null ? null : tableIds.get(linkedKey));
                    existing.put(field.name(), field.type());
                    added++;
                } else if (actual != field.type()) {
                    throw new Exception("V2 表字段类型不匹配: " + table.name() + "/" + field.name());
                }
            }
        }
        setCfg("feishu.base.v2.staging.status", "prepared");
        return Map.of("prepared", true, "appToken", appToken, "tables", tableIds, "addedFields", added);
    }

    private String v2StagingTableConfigKey(String tableKey) {
        return "feishu.base.v2.staging.table." + tableKey;
    }

    /** 显式切换到已预备的 V2 Base；首次切换前保存旧配置用于快速回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public synchronized Map<String, Object> activateV2Base() throws Exception {
        if (!"prepared".equals(getCfg("feishu.base.v2.staging.status"))) {
            throw new IllegalStateException("V2 Base 尚未准备完成");
        }
        validatePreparedV2Base();
        Map<String, String> activeKeys = Map.of(
                "project", "feishu.base.tableProjects",
                "task", "feishu.base.tableTasks",
                "scoring", "feishu.base.tableScoring",
                "log", "feishu.base.tableLogs",
                "projectBackup", "feishu.base.tableProjectsBackup",
                "taskBackup", "feishu.base.tableTasksBackup",
                "scoringBackup", "feishu.base.tableScoringBackup",
                "logBackup", "feishu.base.tableLogsBackup");
        if (getCfg("feishu.base.v2.legacy.appToken").isBlank()) {
            setCfg("feishu.base.v2.legacy.appToken", getCfg("feishu.base.appToken"));
            activeKeys.forEach((schemaKey, configKey) ->
                    setCfg("feishu.base.v2.legacy.table." + schemaKey, getCfg(configKey)));
        }
        setCfg("feishu.base.appToken", getCfg("feishu.base.v2.staging.appToken"));
        activeKeys.forEach((schemaKey, configKey) ->
                setCfg(configKey, getCfg(v2StagingTableConfigKey(schemaKey))));
        setCfg("feishu.base.v2.active", "true");
        fieldTypesCache.clear();
        return Map.of("active", true, "appToken", getCfg("feishu.base.appToken"));
    }

    private void validatePreparedV2Base() throws Exception {
        String appToken = getCfg("feishu.base.v2.staging.appToken");
        if (appToken.isBlank()) throw new IllegalStateException("V2 staging Base 配置缺失");
        String token = getToken();
        for (FeishuV2Schema.Table table : FeishuV2Schema.allTables().values()) {
            String tableId = getCfg(v2StagingTableConfigKey(table.key()));
            if (tableId.isBlank()) throw new IllegalStateException("V2 staging 表配置缺失: " + table.name());
            Map<String, Integer> actual = getFieldTypes(token, appToken, tableId);
            if (!Objects.equals(actual.get(table.identity()), FeishuV2Schema.TEXT)) {
                throw new IllegalStateException("V2 staging 主键字段异常: " + table.name());
            }
            for (FeishuV2Schema.Field field : table.fields()) {
                if (!Objects.equals(actual.get(field.name()), field.type())) {
                    throw new IllegalStateException("V2 staging 字段异常: " + table.name() + "/" + field.name());
                }
            }
        }
    }

    /** 回到激活前保存的旧 Base，不删除 V2 Base 或任何记录。 */
    @Transactional
    public synchronized Map<String, Object> rollbackV2Base() {
        String legacyAppToken = getCfg("feishu.base.v2.legacy.appToken");
        if (legacyAppToken.isBlank()) throw new IllegalStateException("没有可回滚的旧 Base 配置");
        Map<String, String> activeKeys = Map.of(
                "project", "feishu.base.tableProjects", "task", "feishu.base.tableTasks",
                "scoring", "feishu.base.tableScoring", "log", "feishu.base.tableLogs",
                "projectBackup", "feishu.base.tableProjectsBackup", "taskBackup", "feishu.base.tableTasksBackup",
                "scoringBackup", "feishu.base.tableScoringBackup", "logBackup", "feishu.base.tableLogsBackup");
        setCfg("feishu.base.appToken", legacyAppToken);
        activeKeys.forEach((schemaKey, configKey) ->
                setCfg(configKey, getCfg("feishu.base.v2.legacy.table." + schemaKey)));
        setCfg("feishu.base.v2.active", "false");
        fieldTypesCache.clear();
        return Map.of("active", false, "rolledBack", true);
    }

    private void addV2Field(String token, String appToken, String tableId,
                            FeishuV2Schema.Field field, String linkedTableId) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("field_name", field.name());
        body.put("type", field.type());
        if (linkedTableId != null) {
            ObjectNode property = body.putObject("property");
            property.put("table_id", linkedTableId);
            property.put("multiple", true);
        } else if (field.percentage()) {
            body.putObject("property").put("formatter", "0%");
        }
        JsonNode root = json.readTree(bearerPost(
                API + "/bitable/v1/apps/" + appToken + "/tables/" + tableId + "/fields",
                token, body.toString()));
        checkResponse(root, "新增 V2 字段");
        fieldTypesCache.remove(appToken + ":" + tableId);
    }

    /** 为现有主表和备份表补齐两级审核同步字段。 */
    public synchronized Map<String, Object> ensureReviewWorkflowFields() throws Exception {
        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank()) throw new Exception("飞书 Base App Token 未配置");
        String token = getToken();

        Map<String, Integer> projectFields = new LinkedHashMap<>();
        projectFields.put("项目流程", 1);
        projectFields.put("子任务流程", 1);
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("项目总表", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableProjects"), projectFields));
        result.put("项目总表_backup", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableProjectsBackup"), projectFields));
        result.put("子任务表", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableTasks"), taskFields));
        result.put("子任务表_backup", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableTasksBackup"), taskFields));
        result.put("评分记录表", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableScoring"), scoringFields));
        result.put("评分记录表_backup", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableScoringBackup"), scoringFields));
        return result;
    }

    /** 为主表和备份表补齐镜像来源、备份状态及源数据删除时间字段。 */
    public synchronized Map<String, Object> ensureMirrorStrategyFields() throws Exception {
        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank()) throw new Exception("飞书 Base App Token 未配置");
        String token = getToken();

        Map<String, Integer> primaryFields = Map.of("同步来源", 1);
        Map<String, Integer> backupFields = new LinkedHashMap<>();
        backupFields.put("同步来源", 1);
        backupFields.put("备份状态", 1);
        backupFields.put("源数据删除时间", 5);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("项目总表", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableProjects"), primaryFields));
        result.put("项目总表_backup", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableProjectsBackup"), backupFields));
        result.put("子任务表", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableTasks"), primaryFields));
        result.put("子任务表_backup", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableTasksBackup"), backupFields));
        result.put("评分记录表", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableScoring"), primaryFields));
        result.put("评分记录表_backup", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableScoringBackup"), backupFields));
        result.put("操作日志表", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableLogs"), primaryFields));
        result.put("操作日志表_backup", ensureFieldsForTable(token, appToken, getCfg("feishu.base.tableLogsBackup"), backupFields));
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
        return createBase("产品管理系统");
    }

    private String createBase(String name) throws Exception {
        String token = getToken();
        ObjectNode body = json.createObjectNode();
        body.put("name", name);
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
        addTextFields(fields, "项目ID", "项目编号", "产品名称", "类型", "状态", "销售", "产品企划",
                "产品类目", "产品类目备注", "参考价格", "需求说明", "项目描述", "目标市场", "合规项",
                "IP名称", "创意作者", "来源", "项目流程", "子任务流程", "当前审核阶段", "同步来源");
        addDateFields(fields, "截止日期", "创建时间");
        addNumberFields(fields, "子任务数", "完成进度", "审核进度");
        return fields;
    }

    /** 子任务表字段定义 */
    private JsonNode createTaskFields() {
        ArrayNode fields = json.createArrayNode();
        addTextFields(fields, "子任务ID", "任务名称", "所属阶段", "状态", "负责人类型", "负责人", "发布人",
                "细节要求说明", "交付成果", "审核意见", "所属项目",
                "一审角色", "一审状态", "一审审核人", "二审角色", "二审状态", "二审审核人", "同步来源");
        addDateFields(fields, "计划日期", "实际完成日期", "创建时间");
        addNumberFields(fields, "自评分", "一审得分", "二审得分", "审核得分");
        return fields;
    }

    /** 评分表字段定义 */
    private JsonNode createScoringFields() {
        ArrayNode fields = json.createArrayNode();
        addTextFields(fields, "评分ID", "评分角色", "所属子任务", "项目ID", "项目类型",
                "审核阶段", "审核状态", "审核人", "审核意见", "同步来源");
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

    // ==================== V2 固定契约同步 ====================

    public record V2ProjectData(Long id, String code, String name, String status, String sales,
                                String planner, String category, String price, String imagesJson,
                                String filesJson, int taskCount, LocalDateTime createdAt,
                                String deadline, LocalDateTime updatedAt, List<Long> taskIds,
                                String taskStage, double taskProgress, String projectStage,
                                double projectProgress, String note) {}

    public record V2TaskData(Long id, Long projectId, String name, String status,
                             String referenceImagesJson, String referenceFilesJson, String owner,
                             String details, LocalDateTime createdAt, String deadline,
                             LocalDateTime updatedAt, String deliveryImagesJson,
                             String deliveryFilesJson, Double selfScore, Integer salesScore,
                             Integer plannerScore, Integer adminScore, Double weightedScore,
                             List<Long> scoringIds, String stage, double progress, String note) {}

    public record V2ScoringData(Long id, String role, String reviewer, Long taskId,
                                LocalDateTime reviewedAt, Double weight, Double weightedScore,
                                String note) {}

    public record V2LogData(Long id, LocalDateTime time, String role, String user,
                            String action, String note) {}

    public void syncV2Project(V2ProjectData data, boolean includeBackup) throws Exception {
        if (!isSyncEnabled()) return;
        ObjectNode fields = json.createObjectNode();
        fields.put("系统项目ID", data.id().toString());
        fields.put("项目编号", text(data.code())); fields.put("项目名称", text(data.name()));
        fields.put("状态", statusLabel(data.status())); fields.put("销售", text(data.sales()));
        fields.put("产品企划", text(data.planner())); fields.put("产品类目", text(data.category()));
        fields.put("参考零售价", text(data.price())); fields.put("子任务数", data.taskCount());
        putDate(fields, "创建时间", data.createdAt()); putDate(fields, "计划完成时间", data.deadline());
        putDate(fields, "最近更新时间", data.updatedAt()); fields.put("子任务阶段", text(data.taskStage()));
        fields.put("子任务进度", normalizePercentage(data.taskProgress()));
        fields.put("项目阶段", text(data.projectStage()));
        fields.put("项目总进度", normalizePercentage(data.projectProgress())); fields.put("备注", text(data.note()));
        writeV2("project", data.id(), fields, data.imagesJson(), "参考图片", data.filesJson(), "附件", data.taskIds(),
                "关联子任务", "task", includeBackup);
    }

    public void syncV2Task(V2TaskData data, boolean includeBackup) throws Exception {
        if (!isSyncEnabled()) return;
        ObjectNode fields = json.createObjectNode();
        fields.put("系统子任务ID", data.id().toString()); fields.put("子任务编号", data.id().toString());
        fields.put("子任务名称", text(data.name())); fields.put("状态", taskStatusLabel(data.status()));
        fields.put("子任务负责人", text(data.owner())); fields.put("细节要求说明", text(data.details()));
        putDate(fields, "创建子任务时间", data.createdAt()); putDate(fields, "计划完成时间", data.deadline());
        putDate(fields, "最后更新时间", data.updatedAt()); putNumber(fields, "设计师自评分", data.selfScore());
        putNumber(fields, "销售评分", data.salesScore()); putNumber(fields, "产品企划评分", data.plannerScore());
        putNumber(fields, "管理员评分", data.adminScore()); putNumber(fields, "加权综合", data.weightedScore());
        fields.put("子任务阶段", text(data.stage())); fields.put("子任务进度", normalizePercentage(data.progress()));
        fields.put("备注", text(data.note()));
        writeV2("task", data.id(), fields, data.referenceImagesJson(), "参考图片", data.referenceFilesJson(),
                "参考附件", nullableId(data.projectId()), "关联项目", "project", includeBackup,
                data.deliveryImagesJson(), "交付图片", data.deliveryFilesJson(), "交付附件",
                data.scoringIds(), "关联评分", "scoring");
    }

    public void syncV2Scoring(V2ScoringData data, boolean includeBackup) throws Exception {
        if (!isSyncEnabled()) return;
        ObjectNode fields = json.createObjectNode();
        fields.put("系统评分ID", data.id().toString()); fields.put("评分编号", data.id().toString());
        fields.put("评分角色", roleLabel(data.role())); fields.put("评分人", text(data.reviewer()));
        putDate(fields, "评分时间", data.reviewedAt()); putNumber(fields, "权重", data.weight());
        putNumber(fields, "加权得分", data.weightedScore()); fields.put("备注", text(data.note()));
        writeV2("scoring", data.id(), fields, null, null, null, null, nullableId(data.taskId()),
                "所属子任务", "task", includeBackup);
    }

    public void syncV2Log(V2LogData data, boolean includeBackup) throws Exception {
        if (!isSyncEnabled()) return;
        ObjectNode fields = json.createObjectNode();
        fields.put("系统日志ID", data.id().toString()); fields.put("日志编号", data.id().toString());
        putDate(fields, "时间", data.time()); fields.put("角色", roleLabel(data.role()));
        fields.put("操作人", text(data.user())); fields.put("行为记录", text(data.action()));
        fields.put("备注", text(data.note()));
        writeV2("log", data.id(), fields, null, null, null, null, List.of(), null, null, includeBackup);
    }

    private void writeV2(String key, Long id, ObjectNode fields,
                         String imagesJson, String imageField, String filesJson, String fileField,
                         List<Long> links, String linkField, String linkTarget, boolean includeBackup,
                         Object... extras) throws Exception {
        String appToken = getCfg("feishu.base.appToken");
        String token = getToken();
        addAttachments(fields, imageField, imagesJson, appToken, token);
        addAttachments(fields, fileField, filesJson, appToken, token);
        addLinks(fields, linkField, links, linkTarget, false, token, appToken);
        for (int i = 0; i + 1 < extras.length; i += 2) {
            if (extras[i] instanceof String raw && extras[i + 1] instanceof String field) {
                addAttachments(fields, field, raw, appToken, token);
            } else if (extras[i] instanceof List<?> rawLinks && extras[i + 1] instanceof String field) {
                @SuppressWarnings("unchecked") List<Long> ids = (List<Long>) rawLinks;
                addLinks(fields, field, ids, "scoring", false, token, appToken);
            }
        }
        putV2Metadata(fields, false, false, null);
        upsertV2(key, id, fields, false, token, appToken);
        if (includeBackup) {
            ObjectNode backupFields = fields.deepCopy();
            putV2Metadata(backupFields, true, false, null);
            if (linkField != null) addLinks(backupFields, linkField, links, linkTarget, true, token, appToken);
            for (int i = 0; i + 1 < extras.length; i += 2) {
                if (extras[i] instanceof List<?> rawLinks && extras[i + 1] instanceof String field) {
                    @SuppressWarnings("unchecked") List<Long> ids = (List<Long>) rawLinks;
                    addLinks(backupFields, field, ids, "scoring", true, token, appToken);
                }
            }
            upsertV2(key, id, backupFields, true, token, appToken);
        }
    }

    private void upsertV2(String key, Long id, ObjectNode fields, boolean backup,
                          String token, String appToken) throws Exception {
        FeishuV2Schema.Table schema = backup ? FeishuV2Schema.backupTables().get(key + "Backup")
                : FeishuV2Schema.primaryTables().get(key);
        String tableId = getCfg("feishu.base.table" + tableSuffix(key) + (backup ? "Backup" : ""));
        if (tableId.isBlank()) throw new Exception("飞书 V2 " + schema.name() + "未配置");
        String recordId = findRecordId(token, appToken, tableId, schema.identity(), id.toString());
        if (recordId == null) createRecord(token, appToken, tableId, fields.toString());
        else updateRecord(token, appToken, tableId, recordId, fields.toString());
    }

    private void addAttachments(ObjectNode fields, String field, String raw, String appToken, String token) throws Exception {
        if (field == null) return;
        ArrayNode values = fields.putArray(field);
        if (raw == null || raw.isBlank() || attachmentService == null) return;
        for (String fileToken : attachmentService.uploadJsonFiles(raw, appToken, token)) {
            values.addObject().put("file_token", fileToken);
        }
    }

    private void addLinks(ObjectNode fields, String field, List<Long> ids, String target, boolean backup,
                          String token, String appToken) throws Exception {
        if (field == null || ids == null) return;
        ArrayNode values = fields.putArray(field);
        String tableId = getCfg("feishu.base.table" + tableSuffix(target) + (backup ? "Backup" : ""));
        FeishuV2Schema.Table schema = backup ? FeishuV2Schema.backupTables().get(target + "Backup")
                : FeishuV2Schema.primaryTables().get(target);
        for (Long id : ids) {
            if (id == null) continue;
            String recordId = findRecordId(token, appToken, tableId, schema.identity(), id.toString());
            if (recordId != null) values.add(recordId);
        }
    }

    private static String tableSuffix(String key) {
        return switch (key) { case "project" -> "Projects"; case "task" -> "Tasks";
            case "scoring" -> "Scoring"; default -> "Logs"; };
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static List<Long> nullableId(Long value) { return value == null ? List.of() : List.of(value); }
    private static double normalizePercentage(double value) { return value > 1 ? value / 100d : Math.max(0, value); }
    private static void putNumber(ObjectNode fields, String name, Number value) {
        if (value == null) fields.putNull(name); else fields.put(name, value.doubleValue());
    }
    private static void putDate(ObjectNode fields, String name, LocalDateTime value) {
        if (value != null) fields.put(name, toTimestamp(value)); else fields.putNull(name);
    }
    private static void putDate(ObjectNode fields, String name, String value) {
        if (value != null && !value.isBlank()) fields.put(name, dateToTimestamp(value)); else fields.putNull(name);
    }
    private static void putV2Metadata(ObjectNode fields, boolean backup, boolean deleted, LocalDateTime deletedAt) {
        fields.put("同步来源", "系统"); fields.put("系统最近同步时间", toTimestamp(LocalDateTime.now()));
        if (backup) { fields.put("备份状态", deleted ? "源数据已删除" : "有效");
            if (deletedAt != null) fields.put("源数据删除时间", toTimestamp(deletedAt)); }
    }

    // ==================== 同步项目 ====================

    public void syncProject(Long projectId, String type, String status,
                            String salesName, String plannerName,
                            String deadline, String productCategory,
                            String priceRange, int taskCount, int progress,
                            String projectFlow, String currentReviewStage, int reviewProgress,
                            Map<String, String> extraFields, LocalDateTime createdAt) throws Exception {
        if (!isSyncEnabled()) return;

        syncProjectToTable(projectId, type, status, salesName, plannerName, deadline,
                productCategory, priceRange, taskCount, progress,
                projectFlow, currentReviewStage, reviewProgress, extraFields, createdAt,
                getCfg("feishu.base.tableProjects"), false);
        String backup = getCfg("feishu.base.tableProjectsBackup");
        if (!backup.isBlank()) {
            syncProjectToTable(projectId, type, status, salesName, plannerName, deadline,
                    productCategory, priceRange, taskCount, progress,
                    projectFlow, currentReviewStage, reviewProgress, extraFields, createdAt, backup, true);
        }
    }

    private void syncProjectToTable(Long projectId, String type, String status,
                                    String salesName, String plannerName,
                                    String deadline, String productCategory,
                                    String priceRange, int taskCount, int progress,
                                    String projectFlow, String currentReviewStage, int reviewProgress,
                                    Map<String, String> extraFields, LocalDateTime createdAt,
                                    String tableId, boolean backupTable) throws Exception {

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
            putDateValue(fields, "截止日期", dateToTimestamp(deadline), mappedFieldType("project", "截止日期", fieldTypes));
        }
        if (productCategory != null && !productCategory.isBlank()) fields.put("产品类目", productCategory);
        if (priceRange != null && !priceRange.isBlank()) fields.put("参考价格", priceRange);
        fields.put("子任务数", taskCount);
        putProgressValue(fields, "完成进度", progress);
        fields.put("项目流程", projectFlow != null ? projectFlow : "");
        fields.put("当前审核阶段", currentReviewStage != null ? currentReviewStage : "");
        putProgressValue(fields, "审核进度", reviewProgress);
        putConfiguredExtraFields(fields, "project", fieldTypes, extraFields);
        putSyncMetadata(fields, backupTable, false, null, existed != null, fieldTypes);
        if (createdAt != null) {
            putDateValue(fields, "创建时间", toTimestamp(createdAt), mappedFieldType("project", "创建时间", fieldTypes));
        }

        fields = prepareConfiguredFields(token, appToken, tableId, "project", fields, fieldTypes);

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
            LocalDateTime createdAt,
            Map<String, String> extraFields
    ) {}

    public void syncSubTask(SubTaskSyncData data) throws Exception {
        if (!isSyncEnabled()) return;

        syncSubTaskToTable(data, getCfg("feishu.base.tableTasks"), false);
        String backup = getCfg("feishu.base.tableTasksBackup");
        if (!backup.isBlank()) {
            syncSubTaskToTable(data, backup, true);
        }
    }

    private void syncSubTaskToTable(SubTaskSyncData data, String tableId, boolean backupTable) throws Exception {

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
            putDateValue(fields, "计划日期", dateToTimestamp(data.plannedDate()), mappedFieldType("task", "计划日期", fieldTypes));
        }
        if (data.actualDate() != null && !data.actualDate().isBlank()) {
            String rawMappings = getCfg("feishu.base.fieldMappings");
            String actualField = mappingNode("task", "实际完成日期", rawMappings) != null
                    ? "实际完成日期" : actualCompletionField(fieldTypes);
            putDateValue(fields, actualField, dateToTimestamp(data.actualDate()),
                    mappedFieldType("task", actualField, fieldTypes));
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
        putConfiguredExtraFields(fields, "task", fieldTypes, data.extraFields());
        putSyncMetadata(fields, backupTable, false, null, existed != null, fieldTypes);
        if (data.createdAt() != null) {
            putDateValue(fields, "创建时间", toTimestamp(data.createdAt()), mappedFieldType("task", "创建时间", fieldTypes));
        }

        if (data.projectId() != null) {
            Integer fieldType = mappedFieldType("task", "所属项目", fieldTypes);
            String linkedRecordId = null;
            if (isLinkField(fieldType)) {
                linkedRecordId = findRecordId(token, appToken, getCfg("feishu.base.tableProjects"),
                        "项目ID", String.valueOf(data.projectId()));
            }
            putReferenceValue(fields, "所属项目", String.valueOf(data.projectId()), linkedRecordId, fieldType);
        }

        fields = prepareConfiguredFields(token, appToken, tableId, "task", fields, fieldTypes);

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

        syncScoringToTable(data, getCfg("feishu.base.tableScoring"), false);
        String backup = getCfg("feishu.base.tableScoringBackup");
        if (!backup.isBlank()) {
            syncScoringToTable(data, backup, true);
        }
    }

    private void syncScoringToTable(ScoringSyncData data, String tableId, boolean backupTable) throws Exception {

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
        putSyncMetadata(fields, backupTable, false, null, existed != null, fieldTypes);
        if (data.subTaskId() != null) {
            Integer fieldType = mappedFieldType("scoring", "所属子任务", fieldTypes);
            String linkedRecordId = null;
            if (isLinkField(fieldType)) {
                linkedRecordId = findRecordId(token, appToken, getCfg("feishu.base.tableTasks"),
                        "子任务ID", String.valueOf(data.subTaskId()));
            }
            putReferenceValue(fields, "所属子任务", data.subTaskId().toString(), linkedRecordId, fieldType);
        }

        fields = prepareConfiguredFields(token, appToken, tableId, "scoring", fields, fieldTypes);

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
            syncActivityLogToTable(logId, action, username, role, projectId, time, primary, false);
        }
        if (!backup.isBlank() && !backup.equals(primary)) {
            syncActivityLogToTable(logId, action, username, role, projectId, time, backup, true);
        }
    }

    private void syncActivityLogToTable(Long logId, String action, String username, String role,
                                        Long projectId, LocalDateTime time, String tableId,
                                        boolean backupTable) throws Exception {
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
        putSyncMetadata(fields, backupTable, false, null, existed != null, fieldTypes);
        if (time != null) {
            putDateValue(fields, "时间", toTimestamp(time), fieldTypes.get("时间"));
        }
        fields = prepareConfiguredFields(token, appToken, tableId, "log", fields, fieldTypes);
        if (existed != null) updateRecord(token, appToken, tableId, existed, fields.toString());
        else createRecord(token, appToken, tableId, fields.toString());
    }

    static void putSyncMetadata(ObjectNode fields, boolean backupTable, boolean sourceDeleted,
                                LocalDateTime deletedAt, boolean existed,
                                Map<String, Integer> fieldTypes) {
        fields.put("同步来源", "系统");
        if (!backupTable) return;
        fields.put("备份状态", sourceDeleted ? "源数据已删除" : "有效");
        if (deletedAt != null) {
            putDateValue(fields, "源数据删除时间", toTimestamp(deletedAt), fieldTypes.get("源数据删除时间"));
        } else if (existed) {
            fields.putNull("源数据删除时间");
        }
    }

    // ==================== 删除同步 ====================

    public void deleteProjectRecord(Long projectId) throws Exception {
        if (isV2Active()) {
            deleteMirroredRecord("feishu.base.tableProjects", "feishu.base.tableProjectsBackup",
                    "系统项目ID", String.valueOf(projectId));
            return;
        }
        deleteMirroredRecord("feishu.base.tableProjects", "feishu.base.tableProjectsBackup",
                "项目ID", String.valueOf(projectId));
    }

    public void deleteSubTaskRecord(Long taskId) throws Exception {
        if (isV2Active()) {
            deleteMirroredRecord("feishu.base.tableTasks", "feishu.base.tableTasksBackup",
                    "系统子任务ID", String.valueOf(taskId));
            return;
        }
        deleteMirroredRecord("feishu.base.tableTasks", "feishu.base.tableTasksBackup",
                "子任务ID", String.valueOf(taskId));
    }

    public void deleteScoringRecord(Long scoringRecordId) throws Exception {
        if (isV2Active()) {
            deleteMirroredRecord("feishu.base.tableScoring", "feishu.base.tableScoringBackup",
                    "系统评分ID", String.valueOf(scoringRecordId));
            return;
        }
        deleteMirroredRecord("feishu.base.tableScoring", "feishu.base.tableScoringBackup",
                "评分ID", String.valueOf(scoringRecordId));
    }

    private void deleteMirroredRecord(String primaryConfigKey, String backupConfigKey,
                                      String idField, String businessId) throws Exception {
        if (!isSyncEnabled()) return;
        String appToken = getCfg("feishu.base.appToken");
        String primaryTable = getCfg(primaryConfigKey);
        String backupTable = getCfg(backupConfigKey);
        if (appToken.isBlank() || primaryTable.isBlank()) return;
        String token = getToken();
        String primaryRecordId = findRecordId(token, appToken, primaryTable, idField, businessId);
        String backupRecordId = backupTable.isBlank() ? null
                : findRecordId(token, appToken, backupTable, idField, businessId);

        if (backupRecordId != null) {
            markBackupDeleted(token, appToken, backupTable, backupRecordId, LocalDateTime.now());
        }
        if (primaryRecordId != null) {
            if (backupRecordId == null) {
                throw new Exception("备份记录不存在，已阻止主表删除: " + idField + "=" + businessId);
            }
            deleteRecord(token, appToken, primaryTable, primaryRecordId);
        }
    }

    public record MirrorReconcileResult(int primaryDeleted, int backupMarked, int skippedWithoutBackup) {}

    /**
     * 主表严格镜像数据库，备份表永不删除；只处理带“同步来源=系统”的记录。
     * 子记录必须先于父记录传入，避免关联字段阻止父记录删除。
     */
    public Map<String, MirrorReconcileResult> reconcileMirrors(
            Set<Long> projectIds, Set<Long> taskIds, Set<Long> scoringIds, Set<Long> logIds) throws Exception {
        if (!isSyncEnabled()) return Map.of();
        String appToken = getCfg("feishu.base.appToken");
        if (appToken.isBlank()) throw new Exception("飞书 Base App Token 未配置");
        String token = getToken();
        boolean v2 = isV2Active();

        Map<String, MirrorReconcileResult> result = new LinkedHashMap<>();
        result.put("scoring_record", reconcileEntityMirror(token, appToken,
                getCfg("feishu.base.tableScoring"), getCfg("feishu.base.tableScoringBackup"),
                v2 ? "系统评分ID" : "评分ID", toStringIds(scoringIds)));
        result.put("sub_task", reconcileEntityMirror(token, appToken,
                getCfg("feishu.base.tableTasks"), getCfg("feishu.base.tableTasksBackup"),
                v2 ? "系统子任务ID" : "子任务ID", toStringIds(taskIds)));
        result.put("project", reconcileEntityMirror(token, appToken,
                getCfg("feishu.base.tableProjects"), getCfg("feishu.base.tableProjectsBackup"),
                v2 ? "系统项目ID" : "项目ID", toStringIds(projectIds)));
        result.put("activity_log", reconcileEntityMirror(token, appToken,
                getCfg("feishu.base.tableLogs"), getCfg("feishu.base.tableLogsBackup"),
                v2 ? "系统日志ID" : "日志ID", toStringIds(logIds)));
        return result;
    }

    private MirrorReconcileResult reconcileEntityMirror(String token, String appToken,
                                                         String primaryTable, String backupTable,
                                                         String idField, Set<String> currentIds) throws Exception {
        if (primaryTable == null || primaryTable.isBlank()) return new MirrorReconcileResult(0, 0, 0);
        List<RecordSnapshot> primaryRecords = listRecordSnapshots(token, appToken, primaryTable);
        List<RecordSnapshot> backupRecords = backupTable == null || backupTable.isBlank()
                ? List.of() : listRecordSnapshots(token, appToken, backupTable);
        Map<String, RecordSnapshot> backupByBusinessId = new HashMap<>();
        for (RecordSnapshot record : backupRecords) {
            String id = fieldText(record.fields(), idField);
            if (!id.isBlank()) backupByBusinessId.putIfAbsent(id, record);
        }

        LocalDateTime deletedAt = LocalDateTime.now();
        Set<String> markedBackupIds = new HashSet<>();
        int backupMarked = 0;
        for (RecordSnapshot backup : backupRecords) {
            String id = fieldText(backup.fields(), idField);
            if (isOrphanSystemRecord(backup.fields(), idField, currentIds)
                    && !"源数据已删除".equals(fieldText(backup.fields(), "备份状态"))) {
                markBackupDeleted(token, appToken, backupTable, backup.recordId(), deletedAt);
                markedBackupIds.add(id);
                backupMarked++;
            }
        }

        int primaryDeleted = 0;
        int skippedWithoutBackup = 0;
        for (RecordSnapshot primary : primaryRecords) {
            String id = fieldText(primary.fields(), idField);
            if (!isOrphanSystemRecord(primary.fields(), idField, currentIds)) continue;
            RecordSnapshot backup = backupByBusinessId.get(id);
            if (backup == null || !isSystemRecord(backup.fields())) {
                skippedWithoutBackup++;
                continue;
            }
            if (markedBackupIds.add(id)
                    && !"源数据已删除".equals(fieldText(backup.fields(), "备份状态"))) {
                markBackupDeleted(token, appToken, backupTable, backup.recordId(), deletedAt);
                backupMarked++;
            }
            deleteRecord(token, appToken, primaryTable, primary.recordId());
            primaryDeleted++;
        }
        return new MirrorReconcileResult(primaryDeleted, backupMarked, skippedWithoutBackup);
    }

    private void markBackupDeleted(String token, String appToken, String backupTable,
                                   String recordId, LocalDateTime deletedAt) throws Exception {
        Map<String, Integer> fieldTypes = getFieldTypes(token, appToken, backupTable);
        ObjectNode fields = json.createObjectNode();
        putSyncMetadata(fields, true, true, deletedAt, true, fieldTypes);
        updateRecord(token, appToken, backupTable, recordId, fields.toString());
    }

    private List<RecordSnapshot> listRecordSnapshots(String token, String appToken, String tableId) throws Exception {
        List<RecordSnapshot> records = new ArrayList<>();
        String pageToken = null;
        do {
            String url = String.format("%s/bitable/v1/apps/%s/tables/%s/records?page_size=500%s",
                    API, appToken, tableId,
                    pageToken != null && !pageToken.isBlank() ? "&page_token=" + pageToken : "");
            JsonNode root = json.readTree(bearerGet(url, token));
            checkResponse(root, "读取记录列表");
            for (JsonNode item : root.path("data").path("items")) {
                records.add(new RecordSnapshot(item.path("record_id").asText(), item.path("fields")));
            }
            boolean hasMore = root.path("data").path("has_more").asBoolean(false);
            pageToken = hasMore ? root.path("data").path("page_token").asText(null) : null;
        } while (pageToken != null && !pageToken.isBlank());
        return records;
    }

    private static Set<String> toStringIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        return ids.stream().filter(Objects::nonNull).map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    static boolean isSystemRecord(JsonNode fields) {
        return "系统".equals(fieldText(fields, "同步来源"));
    }

    static boolean isOrphanSystemRecord(JsonNode fields, String idField, Set<String> currentIds) {
        String id = fieldText(fields, idField);
        return isSystemRecord(fields) && !id.isBlank() && !currentIds.contains(id);
    }

    static String fieldText(JsonNode fields, String fieldName) {
        JsonNode value = fields != null ? fields.get(fieldName) : null;
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private record RecordSnapshot(String recordId, JsonNode fields) {}

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
            checkResponse(root, "查找记录");

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
        // URL 可能包含 appToken/tableToken，body 可能包含业务数据；禁止写入日志。
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
            String diagnostic = httpErrorDiagnostic(responseBody);
            log.warn("飞书 HTTP 请求失败: method={} status={} {}", method, response.statusCode(), diagnostic);
            throw new Exception("飞书 HTTP 请求失败: status=" + response.statusCode() + " " + diagnostic);
        }
        return responseBody;
    }

    private String httpErrorDiagnostic(String responseBody) {
        try {
            JsonNode root = json.readTree(responseBody);
            String code = root.path("code").asText();
            String message = root.path("msg").asText();
            if (!code.isBlank() || !message.isBlank()) return "code=" + code + " msg=" + message;
        } catch (Exception ignored) { }
        return "响应正文不可解析";
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

    static String taskStatusLabel(String s) {
        if (s == null) return "未知";
        return switch (s) {
            case "pending" -> "待认领";
            case "accepted" -> "进行中";
            case "delivered" -> "已交付";
            case "submitted_for_review" -> "已送审";
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

    /** 飞书百分比字段使用 0~1 小数；系统内部进度使用 0~100 整数。 */
    static void putProgressValue(ObjectNode fields, String fieldName, int progress) {
        int bounded = Math.max(0, Math.min(100, progress));
        fields.put(fieldName, bounded / 100.0);
    }

    /** 兼容历史表“实际完成”和当前表“实际完成日期”两种列名。 */
    static String actualCompletionField(Map<String, Integer> fieldTypes) {
        if (fieldTypes.containsKey("实际完成日期")) return "实际完成日期";
        if (fieldTypes.containsKey("实际完成")) return "实际完成";
        if (fieldTypes.containsKey("实际完成时间")) return "实际完成时间";
        return "实际完成日期";
    }

    static Integer actualCompletionFieldType(Map<String, Integer> fieldTypes) {
        return fieldTypes.get(actualCompletionField(fieldTypes));
    }

    static void putExtraFields(ObjectNode fields, Map<String, Integer> fieldTypes,
                               Map<String, String> values) {
        if (values == null) return;
        values.forEach((name, value) -> {
            if (fieldTypes.containsKey(name) && value != null && !value.isBlank()) fields.put(name, value);
        });
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

    private Integer mappedFieldType(String tableKey, String sourceName, Map<String, Integer> fieldTypes) {
        String targetName = configuredTargetName(tableKey, sourceName, getCfg("feishu.base.fieldMappings"));
        Integer actual = fieldTypes.get(targetName);
        if (actual != null && actual >= 1000 && !"创建时间".equals(sourceName)) {
            throw new IllegalArgumentException("飞书字段为只读字段，不能写入: " + targetName);
        }
        int expected = expectedFieldType(sourceName);
        // 非关键历史列冲突先按系统值类型构造载荷，prepareConfiguredFields 随后改写到兼容列。
        if (actual != null && !compatibleFieldType(sourceName, expected, actual)
                && !FeishuFieldSchema.critical(sourceName)) {
            return expected;
        }
        return actual != null ? actual : expected;
    }

    private void putConfiguredExtraFields(ObjectNode fields, String tableKey,
                                          Map<String, Integer> fieldTypes, Map<String, String> values) {
        if (values == null) return;
        String rawConfig = getCfg("feishu.base.fieldMappings");
        values.forEach((name, value) -> {
            JsonNode mapping = mappingNode(tableKey, name, rawConfig);
            boolean configured = mapping != null && mapping.path("enabled").asBoolean(true);
            if ((fieldTypes.containsKey(name) || configured) && value != null && !value.isBlank()) {
                fields.put(name, value);
            }
        });
    }

    private ObjectNode prepareConfiguredFields(String token, String appToken, String tableId,
                                                String tableKey, ObjectNode sourceFields,
                                                Map<String, Integer> fieldTypes) throws Exception {
        ObjectNode configured = applyFieldMappings(sourceFields, tableKey, getCfg("feishu.base.fieldMappings"));
        ObjectNode compatible = json.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> entries = configured.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String target = entry.getKey();
            Integer actual = fieldTypes.get(target);
            String source = configuredSourceName(tableKey, target, getCfg("feishu.base.fieldMappings"));
            int expected = expectedFieldType(source);
            if (actual == null) {
                addField(token, appToken, tableId, target, expected);
                compatible.set(target, entry.getValue());
            } else if (!compatibleFieldType(source, expected, actual)) {
                if (FeishuFieldSchema.critical(source)) {
                    throw new IllegalArgumentException("飞书关键字段类型不匹配: " + target + "，请检查字段配置");
                }
                String fallback = FeishuFieldSchema.fallbackName(target, expected);
                Integer fallbackType = fieldTypes.get(fallback);
                if (fallbackType == null) {
                    addField(token, appToken, tableId, fallback, expected);
                } else if (!compatibleFieldType(source, expected, fallbackType)) {
                    throw new IllegalArgumentException("飞书兼容字段类型不匹配: " + fallback + "，请检查字段配置");
                }
                compatible.set(fallback, entry.getValue());
                log.warn("飞书字段使用兼容列: table={} source={} target={} actualType={} fallback={}",
                        tableKey, source, target, actual, fallback);
            } else {
                compatible.set(target, entry.getValue());
            }
        }
        return compatible;
    }

    static ObjectNode applyFieldMappings(ObjectNode sourceFields, String tableKey, String rawConfig) {
        ObjectNode result = json.createObjectNode();
        Set<String> targets = new HashSet<>();
        sourceFields.fields().forEachRemaining(entry -> {
            String source = entry.getKey();
            JsonNode mapping = mappingNode(tableKey, source, rawConfig);
            boolean identity = source.equals(IDENTITY_FIELDS.get(tableKey));
            if (!identity && mapping != null && !mapping.path("enabled").asBoolean(true)) return;
            String target = identity ? source : configuredTargetName(tableKey, source, rawConfig);
            if (!targets.add(target)) throw new IllegalArgumentException("飞书字段映射重复: " + target);
            result.set(target, entry.getValue());
        });
        return result;
    }

    private static String configuredTargetName(String tableKey, String source, String rawConfig) {
        JsonNode mapping = mappingNode(tableKey, source, rawConfig);
        String target = mapping == null ? "" : mapping.path("target").asText("").trim();
        return target.isBlank() ? source : target;
    }

    private static String configuredSourceName(String tableKey, String target, String rawConfig) {
        try {
            JsonNode table = json.readTree(rawConfig == null || rawConfig.isBlank() ? "{}" : rawConfig).path(tableKey);
            Iterator<Map.Entry<String, JsonNode>> entries = table.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                if (configuredTargetName(tableKey, entry.getKey(), rawConfig).equals(target)) return entry.getKey();
            }
        } catch (Exception ignored) { }
        return target;
    }

    private static JsonNode mappingNode(String tableKey, String source, String rawConfig) {
        try {
            JsonNode node = json.readTree(rawConfig == null || rawConfig.isBlank() ? "{}" : rawConfig)
                    .path(tableKey).path(source);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            throw new IllegalArgumentException("飞书字段配置不是有效 JSON", e);
        }
    }

    private static int expectedFieldType(String fieldName) {
        return FeishuFieldSchema.expectedType(fieldName);
    }

    private static boolean compatibleFieldType(String source, int expected, int actual) {
        return FeishuFieldSchema.compatible(source, expected, actual);
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
