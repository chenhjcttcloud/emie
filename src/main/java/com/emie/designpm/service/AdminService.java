package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.Role;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.RoleRepository;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.repository.UserRepository;
import com.emie.designpm.util.SecurityUtil;
import com.emie.designpm.dto.PageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataAccessException;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Service
public class AdminService {

    private static final Set<String> BUSINESS_ROLES = Set.of("sales", "planner", "designer", "supplychain", "admin");
    private static final long PUBLIC_CONFIG_CACHE_MILLIS = 5_000L;
    private volatile Map<String, String> publicConfigCache;
    private volatile long publicConfigCacheAt;

    /** 将用户管理端可能传入的历史别名统一为系统标准角色标识。 */
    private static String normalizeBusinessRole(String role) {
        if (role == null) return null;
        String value = role.trim();
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "供应链", "supply", "supply_chain", "supply-chain" -> "supplychain";
            case "管理员", "administrator" -> "admin";
            case "销售" -> "sales";
            case "产品企划", "企划" -> "planner";
            case "设计师" -> "designer";
            default -> value;
        };
    }

    private final SystemConfigRepository configRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserService userService;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    private Path uploadPath;

    public AdminService(SystemConfigRepository configRepository, UserRepository userRepository,
                        RoleRepository roleRepository, UserService userService) {
        this.configRepository = configRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userService = userService;
    }

    @PostConstruct
    public void init() {
        uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadPath.resolve("admin"));
        } catch (IOException e) {
            throw new RuntimeException("无法创建管理上传目录", e);
        }
        // 初始化默认配置
        initDefaultConfigs();
        // 初始化系统角色
        initDefaultRoles();
    }

    private void initDefaultConfigs() {
        List<SystemConfig> defaults = new ArrayList<>(Arrays.asList(
            // ===== 外观配置 =====
            SystemConfig.builder().configKey("app.title").configValue("产品管理系统").configGroup("appearance")
                .description("系统标题").valueType("text").sortOrder(1).build(),
            SystemConfig.builder().configKey("app.logo").configValue("").configGroup("appearance")
                .description("系统 Logo（上传图片后自动填充路径）").valueType("image").sortOrder(2).build(),
            SystemConfig.builder().configKey("app.logoEmoji").configValue("🎨").configGroup("appearance")
                .description("Logo 备用 Emoji（无图片时显示）").valueType("text").sortOrder(3).build(),
            SystemConfig.builder().configKey("app.subtitle").configValue("EMIE Design Project Management").configGroup("appearance")
                .description("系统副标题").valueType("text").sortOrder(4).build(),
            SystemConfig.builder().configKey("login.bg").configValue("").configGroup("appearance")
                .description("登录页背景图片路径").valueType("image").sortOrder(5).build(),
            SystemConfig.builder().configKey("login.bgColor").configValue("#F3F4F6").configGroup("appearance")
                .description("登录页背景色（无图片时使用）").valueType("text").sortOrder(6).build(),

            // ===== 安全配置 =====
            SystemConfig.builder().configKey("security.passwordMinLen").configValue("6").configGroup("security")
                .description("密码最小长度").valueType("number").sortOrder(1).build(),
            SystemConfig.builder().configKey("security.sessionTimeout").configValue("3600").configGroup("security")
                .description("会话超时时间（秒）").valueType("number").sortOrder(2).build(),
            SystemConfig.builder().configKey("security.rateLimit").configValue("30").configGroup("security")
                .description("登录频率限制（每分钟请求数）").valueType("number").sortOrder(3).build(),

            // ===== 系统信息 =====
            SystemConfig.builder().configKey("system.dbType").configValue("").configGroup("system")
                .description("数据库类型（自动检测）").valueType("text").sortOrder(1).build(),
            SystemConfig.builder().configKey("system.dbUrl").configValue("").configGroup("system")
                .description("数据库连接地址（自动检测）").valueType("text").sortOrder(2).build(),
            SystemConfig.builder().configKey("system.version").configValue("1.0.0").configGroup("system")
                .description("系统版本号").valueType("text").sortOrder(3).build(),
            SystemConfig.builder().configKey("system.fileUploadMaxSize").configValue("1024").configGroup("system")
                .description("文件上传最大限制（MB）").valueType("number").sortOrder(4).build(),

            // ===== 飞书 SSO 登录 =====
            SystemConfig.builder().configKey("feishu.appId").configValue("").configGroup("feishu")
                .description("飞书应用 App ID").valueType("text").sortOrder(1).build(),
            SystemConfig.builder().configKey("feishu.appSecret").configValue("").configGroup("feishu")
                .description("飞书应用 App Secret").valueType("password").sortOrder(2).build(),
            SystemConfig.builder().configKey("feishu.ssoAppId").configValue("").configGroup("feishu")
                .description("飞书 SSO 应用 App ID").valueType("text").sortOrder(4).build(),
            SystemConfig.builder().configKey("feishu.ssoAppSecret").configValue("").configGroup("feishu")
                .description("飞书 SSO 应用 App Secret").valueType("password").sortOrder(5).build(),
            SystemConfig.builder().configKey("feishu.enabled").configValue("false").configGroup("feishu")
                .description("启用飞书 SSO 登录").valueType("boolean").sortOrder(3).build(),

            // ===== 评分权重 =====
            SystemConfig.builder().configKey("scoring.channel_custom.planner").configValue("40").configGroup("scoring")
                .description("渠道定制单 - 企划评分权重(%)").valueType("number").sortOrder(1).build(),
            SystemConfig.builder().configKey("scoring.channel_custom.sales").configValue("30").configGroup("scoring")
                .description("渠道定制单 - 销售评分权重(%)").valueType("number").sortOrder(2).build(),
            SystemConfig.builder().configKey("scoring.channel_custom.designer").configValue("20").configGroup("scoring")
                .description("渠道定制单 - 设计师自评权重(%)").valueType("number").sortOrder(3).build(),
            SystemConfig.builder().configKey("scoring.channel_custom.admin").configValue("10").configGroup("scoring")
                .description("渠道定制单 - 管理评分权重(%)").valueType("number").sortOrder(4).build(),
            SystemConfig.builder().configKey("scoring.regular.planner").configValue("40").configGroup("scoring")
                .description("公司常规品 - 企划评分权重(%)").valueType("number").sortOrder(5).build(),
            SystemConfig.builder().configKey("scoring.regular.sales").configValue("10").configGroup("scoring")
                .description("公司常规品 - 销售评分权重(%)").valueType("number").sortOrder(6).build(),
            SystemConfig.builder().configKey("scoring.regular.designer").configValue("20").configGroup("scoring")
                .description("公司常规品 - 设计师自评权重(%)").valueType("number").sortOrder(7).build(),
            SystemConfig.builder().configKey("scoring.regular.admin").configValue("30").configGroup("scoring")
                .description("公司常规品 - 管理评分权重(%)").valueType("number").sortOrder(8).build(),

            // ===== NAS 归档 =====
            SystemConfig.builder().configKey("nas.enabled").configValue("false").configGroup("nas")
                .description("启用 NAS 归档").valueType("boolean").sortOrder(1).build(),
            SystemConfig.builder().configKey("nas.host").configValue("").configGroup("nas")
                .description("NAS IP 地址").valueType("text").sortOrder(2).build(),
            SystemConfig.builder().configKey("nas.user").configValue("root").configGroup("nas")
                .description("NAS SSH 用户名").valueType("text").sortOrder(3).build(),
            SystemConfig.builder().configKey("nas.password").configValue("").configGroup("nas")
                .description("NAS SSH 密码").valueType("password").sortOrder(4).build(),
            SystemConfig.builder().configKey("nas.path").configValue("/volume1/emie-archive").configGroup("nas")
                .description("NAS 存储路径").valueType("text").sortOrder(5).build(),

            // ===== 飞书多维表格同步 =====
            SystemConfig.builder().configKey("feishu.base.syncEnabled").configValue("false").configGroup("feishu_base")
                .description("启用飞书多维表格同步").valueType("boolean").sortOrder(1).build(),
            SystemConfig.builder().configKey("feishu.base.appToken").configValue("").configGroup("feishu_base")
                .description("飞书 Base App Token").valueType("text").sortOrder(2).build(),
            SystemConfig.builder().configKey("feishu.base.tableProjects").configValue("").configGroup("feishu_base")
                .description("项目总表 Table ID").valueType("text").sortOrder(3).build(),
            SystemConfig.builder().configKey("feishu.base.tableTasks").configValue("").configGroup("feishu_base")
                .description("子任务表 Table ID").valueType("text").sortOrder(4).build(),
            SystemConfig.builder().configKey("feishu.base.tableScoring").configValue("").configGroup("feishu_base")
                .description("评分记录表 Table ID").valueType("text").sortOrder(5).build(),
            SystemConfig.builder().configKey("feishu.base.tableProjectsBackup").configValue("").configGroup("feishu_base")
                .description("项目备份表 Table ID").valueType("text").sortOrder(6).build(),
            SystemConfig.builder().configKey("feishu.base.tableTasksBackup").configValue("").configGroup("feishu_base")
                .description("子任务备份表 Table ID").valueType("text").sortOrder(7).build(),
            SystemConfig.builder().configKey("feishu.base.tableScoringBackup").configValue("").configGroup("feishu_base")
                .description("评分备份表 Table ID").valueType("text").sortOrder(8).build(),
            SystemConfig.builder().configKey("feishu.base.tableLogsBackup").configValue("").configGroup("feishu_base")
                .description("操作日志备份表 Table ID").valueType("text").sortOrder(9).build(),
            SystemConfig.builder().configKey("feishu.base.tableLogs").configValue("").configGroup("feishu_base")
                .description("操作日志表 Table ID").valueType("text").sortOrder(10).build(),

            // ===== 通知中心 =====
            SystemConfig.builder().configKey("notification.enabled").configValue("true").configGroup("notification")
                .description("启用通知中心（关闭后仅保留已有审计，不创建新通知）").valueType("boolean").sortOrder(1).build(),
            SystemConfig.builder().configKey("notification.inAppEnabled").configValue("true").configGroup("notification")
                .description("启用站内通知；关键任务、审核、驳回和催办始终按必达规则处理").valueType("boolean").sortOrder(2).build(),
            SystemConfig.builder().configKey("notification.feishuEnabled").configValue("false").configGroup("notification")
                .description("启用飞书机器人外送（需先完成机器人权限、用户 OpenID 和卡片回调配置）").valueType("boolean").sortOrder(3).build(),
            SystemConfig.builder().configKey("notification.publicBaseUrl").configValue("").configGroup("notification")
                .description("系统公网访问地址，例如 https://pm.example.com；飞书卡片“查看并处理”将跳转到此地址").valueType("text").sortOrder(4).build(),
            SystemConfig.builder().configKey("notification.deliveryRetryLimit").configValue("8").configGroup("notification")
                .description("外部渠道最大重试次数，超出后进入失败队列并告警管理员").valueType("number").sortOrder(5).build(),
            SystemConfig.builder().configKey("notification.reminderMinIntervalMinutes").configValue("10").configGroup("notification")
                .description("同一催办人对同一项目或任务的最短催办间隔（分钟）").valueType("number").sortOrder(6).build(),
            SystemConfig.builder().configKey("notification.reminderCrossUserMergeMinutes").configValue("30").configGroup("notification")
                .description("不同催办人的展示合并窗口（分钟）；不会删除任何原始审计或必达通知").valueType("number").sortOrder(7).build(),
            SystemConfig.builder().configKey("notification.overdueEscalationHours").configValue("24").configGroup("notification")
                .description("逾期后升级提醒负责人或管理员的等待时长（小时）").valueType("number").sortOrder(8).build(),
            SystemConfig.builder().configKey("notification.dailyDigestEnabled").configValue("true").configGroup("notification")
                .description("启用普通动态摘要；摘要仅补充展示，不替代必达通知").valueType("boolean").sortOrder(9).build()
        ));
        addNotificationTemplateDefaults(defaults);

        // 只插入缺失的配置项（不覆盖已有值）
        for (SystemConfig cfg : defaults) {
            if (configRepository.findByConfigKey(cfg.getConfigKey()).isEmpty()) {
                configRepository.save(cfg);
            }
        }
    }

    private void addNotificationTemplateDefaults(List<SystemConfig> defaults) {
        String variables = "可用变量：{{projectName}}、{{taskName}}、{{actorName}}、{{deadline}}、{{reason}}、{{deliveryCount}}、{{reviewRole}}、{{targetName}}、{{message}}";
        String[][] templates = {
            {"PROJECT_ASSIGNED", "项目指派", "有新的项目待接单", "“{{projectName}}”已由{{actorName}}指定给你，请及时接单并安排任务。"},
            {"TASK_ASSIGNED", "子任务派发", "有新的子任务待处理", "子任务“{{taskName}}”已指派给你，所属项目：{{projectName}}；计划完成：{{deadline}}。"},
            {"DESIGN_REQUIREMENT_ASSIGNED", "设计送审需求派发", "有新的设计/送审需求", "“{{projectName}}”已由{{actorName}}创建并指定给你，请及时查看并跟进。"},
            {"DESIGN_REQUIREMENT_DESIGNER_ASSIGNED", "设计需求指派设计师", "有新的设计需求待交付", "“{{projectName}}”已由{{actorName}}指派给你，请在{{deadline}}前完成设计交付。"},
            {"TASK_REASSIGNED", "子任务改派", "有新的子任务待处理", "子任务“{{taskName}}”已改派给你，所属项目：{{projectName}}；计划完成：{{deadline}}。"},
            {"TASK_ACCEPTED", "子任务接单", "子任务已接单", "{{actorName}}已接单子任务“{{taskName}}”。"},
            {"TASK_DELIVERED", "子任务首次交付", "子任务待审核", "{{actorName}}已交付子任务“{{taskName}}”，请查看成果并完成审核。"},
            {"TASK_SUBMITTED_FOR_REVIEW", "子任务送审", "子任务已送审", "子任务“{{taskName}}”已送审，请进行通过并评分或驳回。"},
            {"DESIGN_REQUIREMENT_DELIVERED", "设计需求交付", "设计需求已交付", "设计师已提交“{{projectName}}”的交付成果，请及时查看。"},
            {"DESIGN_REQUIREMENT_REVIEW_PENDING", "设计需求待复评", "设计需求待复评", "“{{projectName}}”已完成设计师自评，请及时完成复评。"},
            {"DESIGN_REQUIREMENT_REJECTED", "设计需求驳回", "设计需求已驳回", "“{{projectName}}”已被驳回，原因：{{reason}}。请在{{deadline}}前修改并重新交付。"},
            {"DESIGN_REQUIREMENT_COMPLETED", "设计需求完成", "设计需求已完成", "“{{projectName}}”已完成全部评分流程。"},
            {"DESIGN_REQUIREMENT_TERMINATED", "设计需求终止", "设计需求已终止", "“{{projectName}}”已由{{actorName}}终止。"},
            {"TASK_REJECTED", "子任务驳回", "子任务已驳回", "子任务“{{taskName}}”被驳回，原因：{{reason}}。请修改后重新交付。"},
            {"TASK_REDELIVERED", "子任务再次交付", "子任务再次交付待审核", "{{actorName}}已第{{deliveryCount}}次交付“{{taskName}}”。上次驳回原因：{{reason}}。"},
            {"REVIEW_PENDING", "审核待办", "有审核待办", "项目“{{projectName}}”的子任务“{{taskName}}”等待{{reviewRole}}审核。"},
            {"REVIEW_APPROVED", "审核通过", "审核已通过", "“{{taskName}}”已由{{actorName}}审核通过。"},
            {"REVIEW_REJECTED", "审核驳回", "审核已驳回", "“{{taskName}}”审核未通过，原因：{{reason}}。"},
            {"PROJECT_REMINDER", "项目催办", "待办催办提醒", "{{actorName}}提醒你处理“{{projectName}}”。当前负责人：{{targetName}}。"},
            {"TASK_REMINDER", "子任务催办", "待办催办提醒", "{{actorName}}提醒你处理“{{taskName}}”。当前负责人：{{targetName}}。"},
            {"TASK_DUE_SOON", "任务临期", "子任务即将到期", "子任务“{{taskName}}”将于{{deadline}}到期，请及时处理。"},
            {"TASK_OVERDUE", "任务逾期", "子任务已逾期", "子任务“{{taskName}}”已超过计划完成时间{{deadline}}，请尽快处理。"},
            {"SYSTEM_ALERT", "系统告警", "系统通知告警", "{{message}}"}
        };
        int order = 1;
        for (String[] template : templates) {
            String prefix = "notification.template." + template[0];
            defaults.add(SystemConfig.builder().configKey(prefix + ".title").configValue(template[2]).configGroup("notification_templates")
                    .description(template[1] + " — 通知标题").valueType("text").sortOrder(order++).build());
            defaults.add(SystemConfig.builder().configKey(prefix + ".content").configValue(template[3]).configGroup("notification_templates")
                    .description(template[1] + " — 通知正文；" + variables).valueType("textarea").sortOrder(order++).build());
        }
    }

    // ==================== 配置管理 ====================

    /** 获取所有配置，按分组 */
    public Map<String, List<SystemConfig>> getAllConfigs() {
        List<SystemConfig> all = configRepository.findAll();
        return all.stream()
                .filter(config -> !"smtp".equals(config.getConfigGroup()))
                .peek(config -> {
                    if ("password".equalsIgnoreCase(config.getValueType())) {
                        config.setConfigValue("******");
                    }
                })
                .collect(Collectors.groupingBy(
            SystemConfig::getConfigGroup,
            LinkedHashMap::new,
            Collectors.toList()
        ));
    }

    /** 获取公开配置（无需登录） */
    public synchronized Map<String, String> getPublicConfig() {
        long now = System.currentTimeMillis();
        if (publicConfigCache != null && now - publicConfigCacheAt < PUBLIC_CONFIG_CACHE_MILLIS) {
            return new LinkedHashMap<>(publicConfigCache);
        }
        Map<String, String> result = new LinkedHashMap<>();
        try {
            // 外观相关配置对外公开
            for (String key : List.of("app.title", "app.logo", "app.logoEmoji", "app.subtitle",
                                       "login.bg", "login.bgColor", "system.version",
                                       "feishu.enabled", "feishu.appId")) {
                configRepository.findByConfigKey(key).ifPresent(c ->
                    result.put(c.getConfigKey(), c.getConfigValue() != null ? c.getConfigValue() : ""));
            }
        } catch (DataAccessException e) {
            if (publicConfigCache != null) return new LinkedHashMap<>(publicConfigCache);
            throw e;
        }
        String runtimeVersion = System.getenv("APP_VERSION");
        if (runtimeVersion != null && runtimeVersion.matches("\\d+\\.\\d+\\.\\d+")) {
            result.put("system.version", runtimeVersion);
        }
        publicConfigCache = Collections.unmodifiableMap(new LinkedHashMap<>(result));
        publicConfigCacheAt = now;
        return new LinkedHashMap<>(result);
    }

    /** 批量更新配置 */
    @Transactional
    public void updateConfigs(Map<String, String> configs, String updatedBy) {
        if (configs == null) return;
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if ("notification.publicBaseUrl".equals(key)
                    && !value.isBlank()
                    && !value.matches("https?://[^\\s/]+(?::\\d+)?(?:/.*)?")) {
                throw new IllegalArgumentException("飞书通知跳转地址必须是完整的 http:// 或 https:// 地址");
            }
            configRepository.findByConfigKey(entry.getKey()).ifPresent(config -> {
                if ("password".equalsIgnoreCase(config.getValueType())
                        && "******".equals(entry.getValue())) {
                    return;
                }
                config.setConfigValue(value);
                config.setUpdatedBy(updatedBy);
                config.setUpdatedAt(LocalDateTime.now());
                configRepository.save(config);
            });
        }
        invalidatePublicConfigCache();
    }

    private void invalidatePublicConfigCache() {
        publicConfigCache = null;
        publicConfigCacheAt = 0L;
    }

    /** 上传管理员图片（logo / 登录背景） */
    public Map<String, Object> uploadAdminImage(MultipartFile file, String type) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("文件名为空");
        }

        if (!SecurityUtil.isValidImageFile(originalName)) {
            throw new IllegalArgumentException("仅支持 PNG/JPG/GIF/BMP/WebP 格式图片");
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("图片大小不能超过10MB");
        }

        String lower = originalName.toLowerCase();
        String ext = lower.substring(lower.lastIndexOf('.'));
        String storedName = "admin_" + type + "_" + UUID.randomUUID().toString().substring(0, 8) + ext;

        try {
            Path targetPath = uploadPath.resolve("admin").resolve(storedName).normalize();
            if (!targetPath.startsWith(uploadPath.resolve("admin"))) {
                throw new IllegalArgumentException("文件名非法");
            }
            Files.createDirectories(targetPath.getParent());
            file.transferTo(targetPath.toFile());

            String urlPath = "/api/files/download/admin/" + storedName;

            // 自动更新配置
            String configKey = type.equals("logo") ? "app.logo" : "login.bg";
            configRepository.findByConfigKey(configKey).ifPresent(config -> {
                config.setConfigValue(urlPath);
                config.setUpdatedAt(LocalDateTime.now());
                configRepository.save(config);
            });

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", urlPath);
            result.put("storedName", storedName);
            return result;
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage());
        }
    }

    // ==================== 用户管理 ====================

    /** 获取所有用户（含详情） */
    public List<Map<String, Object>> getAllUsers() {
        return userRepository.findAll().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("userId", u.getUserId());
            m.put("name", u.getName());
            m.put("role", u.getRole());
            m.put("roleLevel", u.getRoleLevel());
            m.put("title", u.getTitle());
            m.put("phone", u.getPhone());
            m.put("email", u.getEmail());
            m.put("status", u.getStatus() != null ? u.getStatus() : "active");
            m.put("feishuBound", u.getFeishuOpenId() != null && !u.getFeishuOpenId().isBlank());
            m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : "");
            return m;
        }).collect(Collectors.toList());
    }

    public PageResponse<Map<String, Object>> getUsersPage(String keyword, String role, String status, Pageable pageable) {
        Page<User> page = userRepository.searchPage(blankToNull(keyword), blankToNull(role), blankToNull(status), pageable);
        return PageResponse.from(page.map(this::toUserMap));
    }

    private Map<String, Object> toUserMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId()); m.put("userId", u.getUserId()); m.put("name", u.getName());
        m.put("role", u.getRole()); m.put("roleLevel", u.getRoleLevel()); m.put("title", u.getTitle());
        m.put("phone", u.getPhone()); m.put("email", u.getEmail());
        m.put("status", u.getStatus() != null ? u.getStatus() : "active");
        m.put("feishuBound", u.getFeishuOpenId() != null && !u.getFeishuOpenId().isBlank());
        m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : "");
        return m;
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    /** 更新用户角色和权限 */
    @Transactional
    public User updateUserRole(Long userId, String newRole, String updatedBy) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        newRole = normalizeBusinessRole(newRole);
        Role assignedRole = roleRepository.findByName(newRole).orElse(null);
        if (assignedRole == null && !BUSINESS_ROLES.contains(newRole)) {
            throw new IllegalArgumentException("角色不存在或不可分配");
        }
        if ("pending".equals(newRole)) {
            throw new IllegalArgumentException("无效的角色");
        }
        if ("admin".equals(user.getRole()) && !"admin".equals(newRole)
                && userRepository.findByRole("admin").stream()
                        .filter(u -> u.getStatus() == null || !"disabled".equalsIgnoreCase(u.getStatus()))
                        .count() <= 1) {
            throw new IllegalArgumentException("系统至少需要保留一名启用中的管理员");
        }
        boolean wasPending = "pending".equals(user.getRole()) || "pending".equalsIgnoreCase(user.getStatus());

        // 计算 roleLevel
        Integer level = switch (newRole) {
            case "admin" -> 0;
            case "sales" -> 1;
            case "planner" -> 2;
            case "designer" -> 3;
            case "supplychain" -> 3;
            default -> null;
        };

        String title = switch (newRole) {
            case "sales" -> "销售";
            case "planner" -> "产品企划";
            case "designer" -> "设计师";
            case "supplychain" -> "供应链";
            case "admin" -> "系统管理员";
            default -> assignedRole != null ? assignedRole.getDisplayName() : user.getTitle();
        };

        user.setRole(newRole);
        user.setRoleLevel(level);
        user.setTitle(title);
        if (wasPending) user.setStatus("active");
        User saved = userRepository.save(user);
        AuthController.clearUserTokens(user.getUserId());
        userService.refreshCache();
        return saved;
    }

    /** 重置用户密码 */
    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!SecurityUtil.isValidPassword(newPassword)) {
            throw new IllegalArgumentException("密码长度须为6-72位");
        }
        user.setPassword(AuthController.hashPassword(newPassword));
        userRepository.save(user);
        AuthController.clearUserTokens(user.getUserId());
    }

    /** 删除用户 */
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        userRepository.delete(user);
        AuthController.clearUserTokens(user.getUserId());
        userService.refreshCache();
    }

    /** 编辑用户资料（userId、name、phone、email、password） */
    @Transactional
    public Map<String, Object> updateUser(Long userId, Map<String, String> fields) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        boolean roleChanged = false;
        boolean wasPending = "pending".equals(user.getRole()) || "pending".equalsIgnoreCase(user.getStatus());

        if (fields.containsKey("userId")) {
            String newUserId = fields.get("userId");
            if (!SecurityUtil.isValidUserId(newUserId)) {
                throw new IllegalArgumentException("用户ID限3-30位英文数字下划线");
            }
            // 检查唯一性
            if (!newUserId.equals(user.getUserId())) {
                userRepository.findByUserId(newUserId).ifPresent(u -> {
                    throw new IllegalArgumentException("用户ID「" + newUserId + "」已被使用");
                });
            }
            user.setUserId(newUserId);
        }

        if (fields.containsKey("name")) {
            String name = fields.get("name");
            if (!SecurityUtil.isValidDisplayName(name)) throw new IllegalArgumentException("姓名限1-20字不含特殊字符");
            user.setName(name.trim());
        }

        if (fields.containsKey("phone")) {
            String phone = fields.get("phone");
            if (phone != null && !phone.isBlank()) {
                if (!SecurityUtil.isValidPhone(phone)) throw new IllegalArgumentException("请输入正确的11位手机号");
                // 检查唯一性
                if (!phone.equals(user.getPhone())) {
                    userRepository.findByPhone(phone).ifPresent(u -> {
                        throw new IllegalArgumentException("手机号「" + phone + "」已被使用");
                    });
                }
                user.setPhone(phone);
            } else {
                user.setPhone(null);
            }
        }

        if (fields.containsKey("email")) {
            String rawEmail = fields.get("email");
            if (rawEmail != null && !rawEmail.isBlank()) {
                String email = rawEmail.trim();
                if (!SecurityUtil.isValidEmail(email)) throw new IllegalArgumentException("邮箱格式不正确");
                // 检查唯一性
                if (!email.equals(user.getEmail())) {
                    userRepository.findByEmail(email).ifPresent(u -> {
                        throw new IllegalArgumentException("邮箱「" + email + "」已被使用");
                    });
                }
                user.setEmail(email);
            } else {
                user.setEmail(null);
            }
        }

        if (fields.containsKey("password")) {
            String pwd = fields.get("password");
            if (pwd != null && !pwd.isBlank()) {
                if (!SecurityUtil.isValidPassword(pwd)) throw new IllegalArgumentException("密码长度须为6-72位");
                user.setPassword(AuthController.hashPassword(pwd));
            }
        }

        // 如果更新了 role，同步更新 title 和 roleLevel
        if (fields.containsKey("role")) {
            String newRole = fields.get("role");
            if (!List.of("admin", "sales", "planner", "designer", "supplychain").contains(newRole)) {
                throw new IllegalArgumentException("无效的角色");
            }
            Integer level = switch (newRole) {
                case "admin" -> 0;
                case "sales" -> 1;
                case "planner" -> 2;
                case "designer" -> 3;
            case "supplychain" -> 3;
                default -> null;
            };
            String title = switch (newRole) {
                case "admin" -> "系统管理员";
                case "sales" -> "销售";
                case "planner" -> "产品企划";
                case "designer" -> "设计师";
            case "supplychain" -> "供应链";
                default -> user.getTitle();
            };
            user.setRole(newRole);
            user.setRoleLevel(level);
            user.setTitle(title);
            if (wasPending) user.setStatus("active");
            roleChanged = true;
        }

        userRepository.save(user);
        if (roleChanged) AuthController.clearUserTokens(user.getUserId());
        userService.refreshCache();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("userId", user.getUserId());
        result.put("name", user.getName());
        result.put("role", user.getRole());
        result.put("title", user.getTitle());
        result.put("phone", user.getPhone());
        result.put("email", user.getEmail());
        result.put("status", user.getStatus());
        return result;
    }

    /** 切换账号启用/停用状态 */
    @Transactional
    public User toggleUserStatus(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        String current = user.getStatus() != null ? user.getStatus() : "active";
        if ("disabled".equals(current)) {
            user.setStatus("active");
        } else {
            user.setStatus("disabled");
        }
        User saved = userRepository.save(user);
        AuthController.clearUserTokens(user.getUserId());
        userService.refreshCache();
        return saved;
    }

    // ==================== 角色管理 ====================

    /** 定义所有权限项（用于前端展示） */
    public static List<Map<String, Object>> getPermissionDefs() {
        List<Map<String, Object>> defs = new ArrayList<>();
        defs.add(Map.of("key", "dashboard:view", "label", "查看工作台", "group", "概览"));
        defs.add(Map.of("key", "project:view", "label", "查看项目", "group", "项目"));
        defs.add(Map.of("key", "project:create", "label", "创建项目", "group", "项目"));
        defs.add(Map.of("key", "project:edit", "label", "编辑项目", "group", "项目"));
        defs.add(Map.of("key", "task:view", "label", "查看子任务", "group", "任务"));
        defs.add(Map.of("key", "task:assign", "label", "分配子任务", "group", "任务"));
        defs.add(Map.of("key", "task:execute", "label", "执行/交付子任务", "group", "任务"));
        defs.add(Map.of("key", "task:approve", "label", "验收通过子任务", "group", "任务"));
        defs.add(Map.of("key", "task:reject", "label", "驳回子任务", "group", "任务"));
        defs.add(Map.of("key", "scoring:view", "label", "查看评分", "group", "评分"));
        defs.add(Map.of("key", "scoring:submit", "label", "提交评分", "group", "评分"));
        defs.add(Map.of("key", "admin:dashboard", "label", "系统管理概览", "group", "系统管理"));
        defs.add(Map.of("key", "admin:config", "label", "系统配置管理", "group", "系统管理"));
        defs.add(Map.of("key", "admin:users", "label", "用户管理", "group", "系统管理"));
        defs.add(Map.of("key", "admin:roles", "label", "角色管理", "group", "系统管理"));
        defs.add(Map.of("key", "file:upload", "label", "上传文件", "group", "文件"));
        return defs;
    }

    /** 初始化系统内置角色 */
    private void initDefaultRoles() {
        if (roleRepository.count() > 0) return;

        record RoleDef(String name, String displayName, String description, String[] perms) {}

        RoleDef[] defs = {
            new RoleDef("admin", "系统管理员", "拥有系统全部权限，可管理用户、角色和系统配置",
                new String[]{"dashboard:view","project:view","project:create","project:edit",
                    "task:view","task:assign","task:execute","task:approve","task:reject",
                    "scoring:view","scoring:submit",
                    "admin:dashboard","admin:config","admin:users","admin:roles","file:upload"}),
            new RoleDef("sales", "销售", "查看项目、发起渠道定制需求、执行子任务和评分",
                new String[]{"dashboard:view","project:view","project:create",
                    "task:view","task:execute","scoring:view","scoring:submit","file:upload"}),
            new RoleDef("planner", "产品企划", "管理项目和子任务全流程，含分配、验收和评分",
                new String[]{"dashboard:view","project:view","project:create","project:edit",
                    "task:view","task:assign","task:approve","task:reject",
                    "scoring:view","scoring:submit","file:upload"}),
            new RoleDef("designer", "设计师", "接单执行设计任务并交付成果",
                new String[]{"dashboard:view","project:view",
                    "task:view","task:execute","file:upload"}),
            new RoleDef("supplychain", "供应链", "接单执行供应链任务并交付成果",
                new String[]{"dashboard:view","project:view",
                    "task:view","task:execute","file:upload"}),
        };

        List<Role> roles = new ArrayList<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (RoleDef d : defs) {
            roles.add(Role.builder()
                .name(d.name())
                .displayName(d.displayName())
                .description(d.description())
                .permissions(String.join(",", d.perms()))
                .isSystem(true)
                .createdAt(now)
                .updatedAt(now)
                .build());
        }
        roleRepository.saveAll(roles);
    }

    /** 获取所有角色 */
    public List<Map<String, Object>> getAllRoles() {
        return roleRepository.findAll().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("displayName", r.getDisplayName());
            m.put("description", r.getDescription());
            m.put("permissions", r.getPermissions() != null
                ? Arrays.asList(r.getPermissions().split(","))
                : List.of());
            m.put("isSystem", r.getIsSystem());
            m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : "");
            return m;
        }).collect(Collectors.toList());
    }

    /** 创建角色 */
    @Transactional
    public Map<String, Object> createRole(String name, String displayName, String description, List<String> permissions) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("角色标识不能为空");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("角色名称不能为空");
        if (roleRepository.existsByName(name)) throw new IllegalArgumentException("角色标识「" + name + "」已存在");

        Role role = Role.builder()
            .name(name)
            .displayName(displayName)
            .description(description != null ? description : "")
            .permissions(permissions != null ? String.join(",", permissions) : "")
            .isSystem(false)
            .build();
        role = roleRepository.save(role);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", role.getId());
        result.put("name", role.getName());
        result.put("displayName", role.getDisplayName());
        result.put("description", role.getDescription());
        result.put("permissions", role.getPermissions() != null
            ? Arrays.asList(role.getPermissions().split(","))
            : List.of());
        result.put("isSystem", false);
        return result;
    }

    /** 更新角色 */
    @Transactional
    public Map<String, Object> updateRole(Long roleId, String displayName, String description, List<String> permissions) {
        Role role = roleRepository.findById(roleId)
            .orElseThrow(() -> new IllegalArgumentException("角色不存在"));

        if (displayName != null && !displayName.isBlank()) role.setDisplayName(displayName);
        if (description != null) role.setDescription(description);
        if (permissions != null) role.setPermissions(String.join(",", permissions));

        role = roleRepository.save(role);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", role.getId());
        result.put("name", role.getName());
        result.put("displayName", role.getDisplayName());
        result.put("description", role.getDescription());
        result.put("permissions", role.getPermissions() != null
            ? Arrays.asList(role.getPermissions().split(","))
            : List.of());
        result.put("isSystem", role.getIsSystem());
        return result;
    }

    /** 删除角色 */
    @Transactional
    public void deleteRole(Long roleId) {
        Role role = roleRepository.findById(roleId)
            .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        // 检查是否有用户正在使用该角色
        if (userRepository.findByRole(role.getName()).size() > 0) {
            throw new IllegalArgumentException("该角色下还有用户，无法删除。请先变更用户的角色");
        }
        roleRepository.delete(role);
    }

    /** 根据角色名获取权限列表 */
    public List<String> getPermissionsByRoleName(String roleName) {
        Optional<Role> opt = roleRepository.findByName(roleName);
        if (opt.isPresent()) {
            String perms = opt.get().getPermissions();
            if (perms != null && !perms.isEmpty()) {
                return Arrays.asList(perms.split(","));
            }
        }
        return new ArrayList<>();
    }

    // ==================== 数据清除 ====================

    /**
     * 清空所有项目业务数据（新一轮测试前使用）。
     * 按 FK 依赖从子表到父表顺序删除，保留基础数据（用户/角色/部门/系统配置）。
     */
    @Transactional
    public Map<String, Object> clearAllProjectData() {
        Map<String, Object> result = new LinkedHashMap<>();
        int deleted = 0;

        // 按 FK 依赖顺序删除：子表 → 父表
        try {
            deleted += entityManager.createNativeQuery("DELETE FROM scoring_records").executeUpdate();
            deleted += entityManager.createNativeQuery("DELETE FROM activity_logs").executeUpdate();
            deleted += entityManager.createNativeQuery("DELETE FROM sub_tasks").executeUpdate();
            deleted += entityManager.createNativeQuery("DELETE FROM projects").executeUpdate();
            deleted += entityManager.createNativeQuery("DELETE FROM product_categories").executeUpdate();
            deleted += entityManager.createNativeQuery("DELETE FROM compliance_items").executeUpdate();
            deleted += entityManager.createNativeQuery("DELETE FROM price_ranges").executeUpdate();

            result.put("success", true);
            result.put("deletedRows", deleted);
            result.put("message", "所有项目数据已清除（共删除 " + deleted + " 条记录）");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "清除失败: " + e.getMessage());
        }
        return result;
    }

    // ==================== 评分权重管理 ====================

    private static final String[] SCORING_ROLES = {"planner", "sales", "designer", "admin"};
    private static final String[] PROJECT_TYPES = {"channel_custom", "regular"};
    private static final Map<String, String> SCORING_ROLE_LABELS = Map.of(
        "planner", "企划", "sales", "销售", "designer", "设计师", "admin", "管理"
    );
    private static final Map<String, String> PROJECT_TYPE_LABELS = Map.of(
        "channel_custom", "渠道定制单", "regular", "公司常规品"
    );

    /** 获取所有评分权重（按项目类型分组，百分比） */
    public Map<String, Object> getScoringWeights() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> types = new ArrayList<>();
        for (String pt : PROJECT_TYPES) {
            Map<String, Object> typeEntry = new LinkedHashMap<>();
            typeEntry.put("type", pt);
            typeEntry.put("label", PROJECT_TYPE_LABELS.getOrDefault(pt, pt));
            List<Map<String, Object>> items = new ArrayList<>();
            for (String role : SCORING_ROLES) {
                String key = "scoring." + pt + "." + role;
                Optional<SystemConfig> opt = configRepository.findByConfigKey(key);
                double pct = opt.isPresent() ? Double.parseDouble(opt.get().getConfigValue()) : getDefaultWeight(pt, role);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", role);
                item.put("label", SCORING_ROLE_LABELS.getOrDefault(role, role));
                item.put("weight", pct);
                items.add(item);
            }
            typeEntry.put("weights", items);
            types.add(typeEntry);
        }
        result.put("types", types);
        return result;
    }

    private static double getDefaultWeight(String projectType, String role) {
        if ("channel_custom".equals(projectType)) {
            return switch (role) {
                case "planner" -> 40;
                case "sales" -> 30;
                case "designer" -> 20;
                case "admin" -> 10;
                default -> 25;
            };
        } else {
            return switch (role) {
                case "planner" -> 40;
                case "admin" -> 30;
                case "designer" -> 20;
                case "sales" -> 10;
                default -> 25;
            };
        }
    }

    /** 更新评分权重（按项目类型分组，百分比） */
    @Transactional
    public void updateScoringWeights(Map<String, Object> body) {
        for (String pt : PROJECT_TYPES) {
            if (!body.containsKey(pt)) continue;
            Object raw = body.get(pt);
            if (!(raw instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> roleWeights = (Map<String, Object>) raw;
            double total = 0;
            for (Map.Entry<String, Object> entry : roleWeights.entrySet()) {
                String role = entry.getKey();
                if (!SCORING_ROLE_LABELS.containsKey(role)) continue;
                double pct;
                try {
                    pct = ((Number) entry.getValue()).doubleValue();
                } catch (Exception e) {
                    continue;
                }
                if (pct < 0 || pct > 100) throw new IllegalArgumentException(role + " 百分比超出范围(0-100)");
                total += pct;
                String key = "scoring." + pt + "." + role;
                configRepository.findByConfigKey(key).ifPresent(config -> {
                    config.setConfigValue(String.valueOf(pct));
                    config.setUpdatedAt(LocalDateTime.now());
                    configRepository.save(config);
                });
            }
            if (Math.abs(total - 100.0) > 0.001) {
                throw new IllegalArgumentException(pt + " 的评分权重合计必须为100%");
            }
        }
    }

    /** 获取指定项目类型的角色评分权重百分比 */
    public double getScoringWeight(String projectType, String role) {
        String key = "scoring." + projectType + "." + role;
        return configRepository.findByConfigKey(key)
            .map(c -> { try { return Double.parseDouble(c.getConfigValue()); } catch (Exception e) { return getDefaultWeight(projectType, role); } })
            .orElseGet(() -> getDefaultWeight(projectType, role));
    }

    // ==================== 工作量统计 ====================

    /** 工作量只需要四类业务角色，避免把管理员、待授权账号等全量加载进统计内存。 */
    private List<User> workloadUsers() {
        return Stream.of("sales", "planner", "designer", "supplychain")
                .flatMap(role -> userRepository.findByRole(role).stream())
                .filter(u -> u.getStatus() == null || "active".equalsIgnoreCase(u.getStatus()))
                .toList();
    }

    /** 获取各角色各员工的工作量统计 */
    public Map<String, Object> getWorkloadStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<User> allUsers = workloadUsers();

        // 按角色分组
        Map<String, List<User>> byRole = allUsers.stream()
                .filter(u -> Set.of("sales", "planner", "designer", "supplychain").contains(u.getRole()))
                .filter(u -> u.getStatus() == null || "active".equalsIgnoreCase(u.getStatus()))
                .collect(Collectors.groupingBy(User::getRole));

        // 定义角色显示信息
        Map<String, String> roleLabels = Map.of(
                "sales", "销售", "planner", "产品企划",
                "designer", "设计师", "supplychain", "供应链"
        );
        Map<String, String> roleIcons = Map.of(
                "sales", "📊", "planner", "📋",
                "designer", "🎨", "supplychain", "📦"
        );

        Map<String, Map<String, Long>> projectCountsBySales = workloadCounts(
                "SELECT sales_id, status, COUNT(*) FROM projects GROUP BY sales_id, status");
        Map<String, Map<String, Long>> projectCountsByPlanner = workloadCounts(
                "SELECT planner_id, status, COUNT(*) FROM projects GROUP BY planner_id, status");
        Map<String, Map<String, Long>> taskCountsByDesigner = workloadCounts(
                "SELECT designer_id, status, COUNT(*) FROM sub_tasks WHERE assignee_role = 'designer' OR assignee_role IS NULL GROUP BY designer_id, status");
        Map<String, Map<String, Long>> taskCountsBySupplychain = workloadCounts(
                "SELECT designer_id, status, COUNT(*) FROM sub_tasks WHERE assignee_role = 'supplychain' GROUP BY designer_id, status");

        for (Map.Entry<String, List<User>> entry : byRole.entrySet()) {
            String role = entry.getKey();
            List<User> users = entry.getValue();

            List<Map<String, Object>> userStats = new ArrayList<>();
            for (User u : users) {
                Map<String, Object> us = new LinkedHashMap<>();
                us.put("userId", u.getUserId());
                us.put("name", u.getName());
                us.put("title", u.getTitle() != null ? u.getTitle() : "");

                // 按角色类型聚合
                switch (role) {
                    case "sales" -> {
                        Map<String, Long> counts = projectCountsBySales.getOrDefault(u.getUserId(), Map.of());
                        long total = counts.values().stream().mapToLong(Long::longValue).sum();
                        us.put("totalProjects", total);
                        us.put("projectCounts", counts);
                    }
                    case "planner" -> {
                        Map<String, Long> counts = projectCountsByPlanner.getOrDefault(u.getUserId(), Map.of());
                        long total = counts.values().stream().mapToLong(Long::longValue).sum();
                        us.put("totalProjects", total);
                        us.put("projectCounts", counts);
                    }
                    case "designer", "supplychain" -> {
                        Map<String, Long> counts = ("supplychain".equals(role) ? taskCountsBySupplychain : taskCountsByDesigner)
                                .getOrDefault(u.getUserId(), Map.of());
                        long total = counts.values().stream().mapToLong(Long::longValue).sum();
                        us.put("totalTasks", total);
                        us.put("taskCounts", counts);
                    }
                }

                userStats.add(us);
            }

            // 用户按总量排序
            Map<String, Object> roleEntry = new LinkedHashMap<>();
            roleEntry.put("label", roleLabels.getOrDefault(role, role));
            roleEntry.put("icon", roleIcons.getOrDefault(role, "👤"));
            roleEntry.put("totalUsers", users.size());
            roleEntry.put("users", userStats);
            result.put(role, roleEntry);
        }

        // 汇总统计
        long totalProjects = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM projects").getSingleResult()).longValue();
        long totalTasks = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM sub_tasks").getSingleResult()).longValue();
        long activeProjects = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM projects WHERE status NOT IN ('completed','terminated','draft')")
                .getSingleResult()).longValue();
        long pendingTasks = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM sub_tasks WHERE status IN ('pending','accepted','delivered')")
                .getSingleResult()).longValue();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalUsers", allUsers.size());
        summary.put("totalProjects", totalProjects);
        summary.put("totalTasks", totalTasks);
        summary.put("activeProjects", activeProjects);
        summary.put("pendingTasks", pendingTasks);
        result.put("_summary", summary);

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Long>> workloadCounts(String sql) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();
        for (Object[] row : rows) {
            if (row[0] == null || row[1] == null) continue;
            result.computeIfAbsent(String.valueOf(row[0]), ignored -> new LinkedHashMap<>())
                    .put(String.valueOf(row[1]), ((Number) row[2]).longValue());
        }
        return result;
    }

    /** 获取指定时间范围内各角色的工作量统计 */
    public Map<String, Object> getWorkloadTimeline(String range) {
        // 计算截止时间
        LocalDateTime cutoff = switch (range) {
            case "day" -> LocalDateTime.now().minusDays(1);
            case "week" -> LocalDateTime.now().minusDays(7);
            case "month" -> LocalDateTime.now().minusDays(30);
            case "quarter" -> LocalDateTime.now().minusDays(90);
            case "half-year" -> LocalDateTime.now().minusDays(180);
            case "year" -> LocalDateTime.now().minusDays(365);
            default -> LocalDateTime.now().minusDays(30);
        };

        Map<String, Object> result = new LinkedHashMap<>();
        List<User> allUsers = workloadUsers();

        Map<String, List<User>> byRole = allUsers.stream()
                .filter(u -> Set.of("sales", "planner", "designer", "supplychain").contains(u.getRole()))
                .filter(u -> u.getStatus() == null || "active".equalsIgnoreCase(u.getStatus()))
                .collect(Collectors.groupingBy(User::getRole));

        Map<String, String> roleLabels = Map.of(
                "sales", "销售", "planner", "产品企划",
                "designer", "设计师", "supplychain", "供应链"
        );
        Map<String, String> roleIcons = Map.of(
                "sales", "📊", "planner", "📋",
                "designer", "🎨", "supplychain", "📦"
        );

        Map<String, long[]> projectTimelineBySales = workloadTimelineCounts(
                "SELECT sales_id, SUM(CASE WHEN created_at >= ?1 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 'completed' AND updated_at >= ?1 THEN 1 ELSE 0 END) "
                        + "FROM projects WHERE created_at >= ?1 OR updated_at >= ?1 GROUP BY sales_id", cutoff);
        Map<String, long[]> projectTimelineByPlanner = workloadTimelineCounts(
                "SELECT planner_id, SUM(CASE WHEN created_at >= ?1 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 'completed' AND updated_at >= ?1 THEN 1 ELSE 0 END) "
                        + "FROM projects WHERE created_at >= ?1 OR updated_at >= ?1 GROUP BY planner_id", cutoff);
        Map<String, long[]> taskTimelineByDesigner = workloadTimelineCounts(
                "SELECT designer_id, COUNT(*), SUM(CASE WHEN status = 'approved' THEN 1 ELSE 0 END) "
                        + "FROM sub_tasks WHERE created_at >= ?1 AND (assignee_role = 'designer' OR assignee_role IS NULL) GROUP BY designer_id", cutoff);
        Map<String, long[]> taskTimelineBySupplychain = workloadTimelineCounts(
                "SELECT designer_id, COUNT(*), SUM(CASE WHEN status = 'approved' THEN 1 ELSE 0 END) "
                        + "FROM sub_tasks WHERE created_at >= ?1 AND assignee_role = 'supplychain' GROUP BY designer_id", cutoff);

        for (Map.Entry<String, List<User>> entry : byRole.entrySet()) {
            String role = entry.getKey();
            List<User> users = entry.getValue();

            List<Map<String, Object>> userStats = new ArrayList<>();
            for (User u : users) {
                Map<String, Object> us = new LinkedHashMap<>();
                us.put("userId", u.getUserId());
                us.put("name", u.getName());
                us.put("title", u.getTitle() != null ? u.getTitle() : "");

                switch (role) {
                    case "sales" -> {
                        long[] counts = projectTimelineBySales.getOrDefault(u.getUserId(), new long[2]);
                        long created = counts[0];
                        long completed = counts[1];
                        us.put("created", created);
                        us.put("completed", completed);
                    }
                    case "planner" -> {
                        long[] counts = projectTimelineByPlanner.getOrDefault(u.getUserId(), new long[2]);
                        long created = counts[0];
                        long completed = counts[1];
                        us.put("created", created);
                        us.put("completed", completed);
                    }
                    case "designer", "supplychain" -> {
                        long[] counts = ("supplychain".equals(role) ? taskTimelineBySupplychain : taskTimelineByDesigner)
                                .getOrDefault(u.getUserId(), new long[2]);
                        long assigned = counts[0];
                        long completed = counts[1];
                        us.put("assigned", assigned);
                        us.put("completed", completed);
                    }
                }
                userStats.add(us);
            }

            Map<String, Object> roleEntry = new LinkedHashMap<>();
            roleEntry.put("label", roleLabels.getOrDefault(role, role));
            roleEntry.put("icon", roleIcons.getOrDefault(role, "👤"));
            roleEntry.put("totalUsers", users.size());
            roleEntry.put("users", userStats);
            result.put(role, roleEntry);
        }

        // 汇总
        Object[] projectSummary = (Object[]) entityManager
                .createNativeQuery("SELECT "
                        + "SUM(CASE WHEN created_at >= ?1 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 'completed' AND updated_at >= ?1 THEN 1 ELSE 0 END) "
                        + "FROM projects")
                .setParameter(1, cutoff).getSingleResult();
        Object[] taskSummary = (Object[]) entityManager
                .createNativeQuery("SELECT "
                        + "SUM(CASE WHEN created_at >= ?1 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 'approved' AND created_at >= ?1 THEN 1 ELSE 0 END) "
                        + "FROM sub_tasks")
                .setParameter(1, cutoff).getSingleResult();
        long totalCreated = numberOrZero(projectSummary[0]);
        long totalCompleted = numberOrZero(projectSummary[1]);
        long totalTasksAssigned = numberOrZero(taskSummary[0]);
        long totalTasksCompleted = numberOrZero(taskSummary[1]);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("range", range);
        summary.put("rangeLabel", switch (range) {
            case "day" -> "今日";
            case "week" -> "本周";
            case "month" -> "本月";
            case "quarter" -> "本季度";
            case "half-year" -> "本半年";
            case "year" -> "本年度";
            default -> range;
        });
        summary.put("cutoff", cutoff.toString());
        summary.put("totalProjectsCreated", totalCreated);
        summary.put("totalProjectsCompleted", totalCompleted);
        summary.put("totalTasksAssigned", totalTasksAssigned);
        summary.put("totalTasksCompleted", totalTasksCompleted);
        result.put("_summary", summary);

        return result;
    }

    private long numberOrZero(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private Map<String, long[]> workloadTimelineCounts(String sql, LocalDateTime cutoff) {
        Map<String, long[]> result = new HashMap<>();
        for (Object[] row : (List<Object[]>) entityManager.createNativeQuery(sql)
                .setParameter(1, cutoff).getResultList()) {
            if (row[0] == null) continue;
            result.put(String.valueOf(row[0]), new long[]{
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue()
            });
        }
        return result;
    }
}
