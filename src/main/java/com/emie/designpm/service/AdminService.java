package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.Role;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.RoleRepository;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminService {

    private final SystemConfigRepository configRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    private Path uploadPath;

    public AdminService(SystemConfigRepository configRepository, UserRepository userRepository, RoleRepository roleRepository) {
        this.configRepository = configRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
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
        if (configRepository.count() > 0) return;

        List<SystemConfig> defaults = Arrays.asList(
            // ===== SMTP 配置 =====
            SystemConfig.builder().configKey("smtp.host").configValue("").configGroup("smtp")
                .description("SMTP 服务器地址").valueType("text").sortOrder(1).build(),
            SystemConfig.builder().configKey("smtp.port").configValue("587").configGroup("smtp")
                .description("SMTP 端口").valueType("number").sortOrder(2).build(),
            SystemConfig.builder().configKey("smtp.username").configValue("").configGroup("smtp")
                .description("SMTP 用户名").valueType("text").sortOrder(3).build(),
            SystemConfig.builder().configKey("smtp.password").configValue("").configGroup("smtp")
                .description("SMTP 密码/授权码").valueType("password").sortOrder(4).build(),
            SystemConfig.builder().configKey("smtp.from").configValue("").configGroup("smtp")
                .description("发件人邮箱地址").valueType("text").sortOrder(5).build(),
            SystemConfig.builder().configKey("smtp.fromName").configValue("EMIE 设计项目管理系统").configGroup("smtp")
                .description("发件人显示名称").valueType("text").sortOrder(6).build(),

            // ===== 外观配置 =====
            SystemConfig.builder().configKey("app.title").configValue("设计项目管理系统").configGroup("appearance")
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
                .description("文件上传最大限制（MB）").valueType("number").sortOrder(4).build()
        );

        configRepository.saveAll(defaults);
    }

    // ==================== 配置管理 ====================

    /** 获取所有配置，按分组 */
    public Map<String, List<SystemConfig>> getAllConfigs() {
        List<SystemConfig> all = configRepository.findAll();
        return all.stream().collect(Collectors.groupingBy(
            SystemConfig::getConfigGroup,
            LinkedHashMap::new,
            Collectors.toList()
        ));
    }

    /** 获取公开配置（无需登录） */
    public Map<String, String> getPublicConfig() {
        Map<String, String> result = new LinkedHashMap<>();
        // 外观相关配置对外公开
        for (String key : List.of("app.title", "app.logo", "app.logoEmoji", "app.subtitle",
                                   "login.bg", "login.bgColor", "system.version")) {
            configRepository.findByConfigKey(key).ifPresent(c ->
                result.put(c.getConfigKey(), c.getConfigValue() != null ? c.getConfigValue() : ""));
        }
        return result;
    }

    /** 批量更新配置 */
    @Transactional
    public void updateConfigs(Map<String, String> configs, String updatedBy) {
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            configRepository.findByConfigKey(entry.getKey()).ifPresent(config -> {
                config.setConfigValue(entry.getValue());
                config.setUpdatedBy(updatedBy);
                config.setUpdatedAt(LocalDateTime.now());
                configRepository.save(config);
            });
        }
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

        // 只允许图片类型
        String lower = originalName.toLowerCase();
        if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")
            && !lower.endsWith(".gif") && !lower.endsWith(".svg") && !lower.endsWith(".webp")) {
            throw new IllegalArgumentException("仅支持 PNG/JPG/GIF/SVG/WebP 格式图片");
        }

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
            m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : "");
            return m;
        }).collect(Collectors.toList());
    }

    /** 更新用户角色和权限 */
    @Transactional
    public User updateUserRole(Long userId, String newRole, String updatedBy) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // 计算 roleLevel
        Integer level = switch (newRole) {
            case "admin" -> 0;
            case "sales" -> 1;
            case "planner" -> 2;
            case "designer" -> 3;
            default -> null;
        };

        String title = switch (newRole) {
            case "sales" -> "销售";
            case "planner" -> "产品企划";
            case "designer" -> "设计师";
            case "admin" -> "系统管理员";
            case "superior" -> "上级";
            default -> user.getTitle();
        };

        user.setRole(newRole);
        user.setRoleLevel(level);
        user.setTitle(title);
        return userRepository.save(user);
    }

    /** 重置用户密码 */
    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("密码长度至少6位");
        }
        user.setPassword(AuthController.sha256(newPassword));
        userRepository.save(user);
    }

    /** 删除用户 */
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        userRepository.delete(user);
    }

    /** 编辑用户资料（userId、name、phone、email、password） */
    @Transactional
    public Map<String, Object> updateUser(Long userId, Map<String, String> fields) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (fields.containsKey("userId")) {
            String newUserId = fields.get("userId");
            if (newUserId == null || newUserId.isBlank()) {
                throw new IllegalArgumentException("用户ID不能为空");
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
            if (name == null || name.isBlank()) throw new IllegalArgumentException("姓名不能为空");
            user.setName(name);
        }

        if (fields.containsKey("phone")) {
            String phone = fields.get("phone");
            if (phone != null && !phone.isBlank()) {
                // 检查唯一性
                if (!phone.equals(user.getPhone())) {
                    userRepository.findByPhone(phone).ifPresent(u -> {
                        throw new IllegalArgumentException("手机号「" + phone + "」已被使用");
                    });
                }
            }
            user.setPhone(phone);
        }

        if (fields.containsKey("email")) {
            String email = fields.get("email");
            if (email != null && !email.isBlank()) {
                // 检查唯一性
                if (!email.equals(user.getEmail())) {
                    userRepository.findByEmail(email).ifPresent(u -> {
                        throw new IllegalArgumentException("邮箱「" + email + "」已被使用");
                    });
                }
            }
            user.setEmail(email);
        }

        if (fields.containsKey("password")) {
            String pwd = fields.get("password");
            if (pwd != null && !pwd.isBlank()) {
                if (pwd.length() < 6) throw new IllegalArgumentException("密码长度至少6位");
                user.setPassword(AuthController.sha256(pwd));
            }
        }

        // 如果更新了 role，同步更新 title 和 roleLevel
        if (fields.containsKey("role")) {
            String newRole = fields.get("role");
            Integer level = switch (newRole) {
                case "admin" -> 0;
                case "sales" -> 1;
                case "planner" -> 2;
                case "designer" -> 3;
                default -> null;
            };
            String title = switch (newRole) {
                case "admin" -> "系统管理员";
                case "sales" -> "销售";
                case "planner" -> "产品企划";
                case "designer" -> "设计师";
                case "superior" -> "上级";
                default -> user.getTitle();
            };
            user.setRole(newRole);
            user.setRoleLevel(level);
            user.setTitle(title);
        }

        userRepository.save(user);

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
        return userRepository.save(user);
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
}
