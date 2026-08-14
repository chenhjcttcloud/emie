package com.emie.designpm.controller;

import com.emie.designpm.entity.User;
import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.repository.UserRepository;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.service.PermissionService;
import com.emie.designpm.service.RedisSessionStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public static final String AUTH_COOKIE = "designpm_auth";
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** 按产品要求，会话不因时间自动失效；0 明确表示永久有效。 */
    private static final long PERMANENT_SESSION_EXPIRES_AT = 0L;
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final ActivityLogRepository activityLogRepository;
    private static RedisSessionStore redisSessionStore;

    // 简单内存 Token 管理（生产环境应使用 Redis/DB）
    private static final Map<String, AuthSession> TOKENS = new ConcurrentHashMap<>();

    public AuthController(UserRepository userRepository, PermissionService permissionService, ActivityLogRepository activityLogRepository) {
        this(userRepository, permissionService, activityLogRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthController(UserRepository userRepository, PermissionService permissionService,
                          ActivityLogRepository activityLogRepository, RedisSessionStore redisSessionStore) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.activityLogRepository = activityLogRepository;
        AuthController.redisSessionStore = redisSessionStore;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String id = body.get("id");
        String password = body.get("password");

        if (id == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "账号和密码不能为空"));
        }

        // 防爆破
        // 未配置可信反向代理时不能信任客户端自带的 X-Forwarded-For。
        String ip = request.getRemoteAddr();
        if (isRateLimited(ip)) {
            return ResponseEntity.status(429).body(Map.of("error", "操作太频繁，请稍后再试"));
        }

        // 支持用 用户ID / 手机号 / 邮箱 登录
        User user = userRepository.findByUserId(id).orElse(null);
        if (user == null) user = userRepository.findByPhone(id).orElse(null);
        if (user == null) user = userRepository.findByEmail(id).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "账号或密码错误"));
        }
        if ("disabled".equalsIgnoreCase(user.getStatus())) {
            return ResponseEntity.status(401).body(Map.of("error", "账号或密码错误"));
        }
        if ("pending".equalsIgnoreCase(user.getStatus()) || "pending".equals(user.getRole())) {
            return ResponseEntity.status(401).body(Map.of("error", "账号或密码错误"));
        }

        // 验证密码
        String storedPassword = user.getPassword();
        boolean bcrypt = storedPassword != null && storedPassword.startsWith("$2");
        boolean passwordMatches = bcrypt
                ? PASSWORD_ENCODER.matches(password, storedPassword)
                : sha256(password).equals(storedPassword);
        if (!passwordMatches) {
            return ResponseEntity.status(401).body(Map.of("error", "账号或密码错误"));
        }

        // 兼容旧 SHA-256 账号，并在成功登录时升级为 BCrypt。
        if (!bcrypt) {
            user.setPassword(hashPassword(password));
            userRepository.save(user);
        }

        // 生成 token
        String token = generateToken();
        putSession(token, new AuthSession(user.getUserId(), user.getRole(), user.getName()));

        // 记录登录日志
        String roleLabel = switch (user.getRole()) {
            case "sales" -> "销售";
            case "planner" -> "企划";
            case "designer" -> "设计师";
            case "supplychain" -> "供应链";
            case "admin" -> "管理员";
            default -> user.getRole();
        };
        activityLogRepository.save(new ActivityLog("登录系统：" + roleLabel + "·" + user.getName() + "（" + user.getUserId() + "）", user.getName(), user.getRole()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("userId", user.getUserId());
        result.put("name", user.getName());
        result.put("role", user.getRole());
        result.put("status", user.getStatus() != null ? user.getStatus() : "active");
        result.put("title", user.getTitle());
        result.put("roleLevel", user.getRoleLevel());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookie(token, request, false).toString())
                .body(result);
    }

    // ==================== 防爆破 ====================
    // IP -> 请求时间戳列表（最近5分钟）
    private static final Map<String, java.util.LinkedList<Long>> RATE_LIMIT = new ConcurrentHashMap<>();

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        if (RATE_LIMIT.size() > 10_000) {
            RATE_LIMIT.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    return entry.getValue().isEmpty()
                            || entry.getValue().getLast() < now - 300_000;
                }
            });
        }
        java.util.LinkedList<Long> times = RATE_LIMIT.computeIfAbsent(ip, k -> new java.util.LinkedList<>());
        synchronized (times) {
            // 清理5分钟前的记录
            while (!times.isEmpty() && times.peek() < now - 300_000) times.poll();
            // 登录：每分钟最多30次（测试阶段放宽）
            if (times.size() >= 30) return true;
            times.add(now);
            return false;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                       HttpServletRequest request) {
        if (token == null || token.isBlank()) token = readCookie(request);
        AuthSession session = getSession(token);
        if (session != null) {
            // 在删除 token 之前记录日志（需要用户信息）
            String roleLabel = switch (session.role()) {
                case "sales" -> "销售";
                case "planner" -> "企划";
                case "designer" -> "设计师";
            case "supplychain" -> "供应链";
                case "admin" -> "管理员";
                default -> session.role();
            };
            try {
                activityLogRepository.save(new ActivityLog(
                    "退出系统：" + roleLabel + "·" + session.name() + "（" + session.userId() + "）",
                    session.name(), session.role()));
            } catch (Exception ignored) {}
        }
        TOKENS.remove(token);
        removeSession(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookie("", request, true).toString())
                .body(Map.of("message", "已退出登录"));
    }

    private static ResponseCookie authCookie(String token, HttpServletRequest request, boolean clear) {
        return ResponseCookie.from(AUTH_COOKIE, token == null ? "" : token)
                .httpOnly(true).secure(request != null && request.isSecure()).sameSite("Lax")
                .path("/").maxAge(clear ? java.time.Duration.ZERO : java.time.Duration.ofDays(36500)).build();
    }

    private static String readCookie(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(c -> AUTH_COOKIE.equals(c.getName()))
                .map(jakarta.servlet.http.Cookie::getValue).findFirst().orElse(null);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader("X-Auth-Token") String token) {
        AuthSession session = validateToken(token);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        }
        // 返回当前模拟用户信息 + 原始用户信息
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", session.userId);
        result.put("name", session.name);
        result.put("role", session.role);
        result.put("status", "pending".equals(session.role) ? "pending" : "active");
        result.put("originalUserId", session.originalUserId);
        result.put("originalRole", session.originalRole);
        return ResponseEntity.ok(result);
    }

    /** 模拟用户视角（仅 admin 可使用，不修改数据库，仅更新当前会话） */
    @PostMapping("/impersonate")
    public ResponseEntity<Map<String, Object>> impersonate(
            @RequestHeader("X-Auth-Token") String token,
            @RequestBody Map<String, String> body) {
        AuthSession session = validateToken(token);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        }

        // 用原始角色进行鉴权（即使已经在模拟其他用户，也允许继续切换）
        String effectiveRole = session.originalRole();
        if (!permissionService.has(effectiveRole, "admin.identity.switch")) {
            log.warn("身份切换被拒绝 userId={} role={} originalUserId={} originalRole={}",
                    session.userId(), session.role(), session.originalUserId(), effectiveRole);
            return ResponseEntity.status(403).body(Map.of(
                    "error", "无权切换用户视角",
                    "permission", "admin.identity.switch"));
        }

        String targetUserId = body.get("userId");
        if (targetUserId == null || targetUserId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请指定目标用户ID"));
        }

        // 查找目标用户
        User target = userRepository.findByUserId(targetUserId).orElse(null);
        if (target == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "目标用户不存在"));
        }

        // 防止初始化/重复点击时把当前用户“切换到自己”记录成身份切换日志。
        // 直接返回当前会话，不改写会话，也不写操作日志。
        if (target.getUserId().equals(session.userId())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", token);
            result.put("userId", target.getUserId());
            result.put("name", target.getName());
            result.put("role", target.getRole());
            result.put("title", target.getTitle());
            result.put("roleLevel", target.getRoleLevel());
            result.put("noop", true);
            return ResponseEntity.ok(result);
        }

        // 替换当前会话为目标用户信息，保留原始登录用户信息
        putSession(token, new AuthSession(
            target.getUserId(), target.getRole(), target.getName(),
            session.originalUserId(), session.originalRole(), session.expiresAt()));

        // 记录操作日志
        try {
            activityLogRepository.save(new ActivityLog(
                "模拟用户：" + session.originalUserId() + " 切换到 " + target.getName() + "（" + target.getUserId() + "）",
                session.originalUserId(), effectiveRole));
        } catch (Exception ignored) {}

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("userId", target.getUserId());
        result.put("name", target.getName());
        result.put("role", target.getRole());
        result.put("title", target.getTitle());
        result.put("roleLevel", target.getRoleLevel());
        return ResponseEntity.ok(result);
    }

    /** 获取当前用户权限列表 */
    @GetMapping("/permissions")
    public ResponseEntity<Map<String, Object>> getPermissions(@RequestHeader("X-Auth-Token") String token) {
        AuthSession session = validateToken(token);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        }
        return ResponseEntity.ok(permissionService.capabilities(session.role()));
    }

    // 校验 token 并返回 session（供过滤器使用）
    public static AuthSession validateToken(String token) {
        if (token == null) return null;
        AuthSession session = getSession(token);
        if (session == null) return null;
        if (session.expiresAt() > 0 && System.currentTimeMillis() >= session.expiresAt()) {
            TOKENS.remove(token, session);
            return null;
        }
        return session;
    }

    /** Controller 层统一使用的管理员判断，避免仅依赖前端隐藏按钮。 */
    public static boolean isAdmin(HttpServletRequest request) {
        AuthSession session = request != null
                ? (AuthSession) request.getAttribute("authSession") : null;
        return session != null && ("admin".equals(session.role())
                || Boolean.TRUE.equals(request.getAttribute("permissionGranted")));
    }

    // 清除用户的所有 token（切换账号时）
    public static void clearUserTokens(String userId) {
        TOKENS.values().removeIf(s -> s.userId.equals(userId));
        if (redisSessionStore != null) redisSessionStore.removeUserTokens(userId);
    }

    // ==================== 内部类 ====================

    public record AuthSession(String userId, String role, String name, String originalUserId,
                              String originalRole, long expiresAt) {
        public AuthSession(String userId, String role, String name) {
            this(userId, role, name, userId, role, PERMANENT_SESSION_EXPIRES_AT);
        }

        public AuthSession(String userId, String role, String name,
                           String originalUserId, String originalRole) {
            this(userId, role, name, originalUserId, originalRole,
                    PERMANENT_SESSION_EXPIRES_AT);
        }
    }

    // ==================== 工具方法 ====================

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 供 Feishu SSO 使用：生成 token 并存入会话 */
    public static String generateToken(String userId, String role, String name) {
        String token = generateToken();
        putSession(token, new AuthSession(userId, role, name));
        return token;
    }

    private static AuthSession getSession(String token) {
        AuthSession local = TOKENS.get(token);
        if (redisSessionStore == null) return local;
        AuthSession remote = redisSessionStore.get(token);
        return remote != null ? remote : local;
    }

    private static void putSession(String token, AuthSession session) {
        TOKENS.put(token, session);
        if (redisSessionStore != null) redisSessionStore.put(token, session);
    }

    private static void removeSession(String token) {
        if (redisSessionStore != null) redisSessionStore.remove(token);
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String hashPassword(String input) {
        return PASSWORD_ENCODER.encode(input);
    }
}
