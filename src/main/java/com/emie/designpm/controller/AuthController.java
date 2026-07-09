package com.emie.designpm.controller;

import com.emie.designpm.entity.User;
import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.repository.UserRepository;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.service.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final AdminService adminService;
    private final ActivityLogRepository activityLogRepository;

    // 简单内存 Token 管理（生产环境应使用 Redis/DB）
    private static final Map<String, AuthSession> TOKENS = new ConcurrentHashMap<>();

    public AuthController(UserRepository userRepository, AdminService adminService, ActivityLogRepository activityLogRepository) {
        this.userRepository = userRepository;
        this.adminService = adminService;
        this.activityLogRepository = activityLogRepository;
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
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) ip = request.getRemoteAddr();
        if (isRateLimited(ip)) {
            return ResponseEntity.status(429).body(Map.of("error", "操作太频繁，请稍后再试"));
        }

        // 支持用 用户ID / 手机号 / 邮箱 登录
        User user = userRepository.findByUserId(id).orElse(null);
        if (user == null) user = userRepository.findByPhone(id).orElse(null);
        if (user == null) user = userRepository.findByEmail(id).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "账号不存在"));
        }
        if ("disabled".equalsIgnoreCase(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("error", "账号已停用，请联系管理员"));
        }

        // 验证密码
        String hashed = sha256(password);
        if (!hashed.equals(user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("error", "密码错误"));
        }

        // 生成 token
        String token = generateToken();
        TOKENS.put(token, new AuthSession(user.getUserId(), user.getRole(), user.getName()));

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
        result.put("title", user.getTitle());
        result.put("roleLevel", user.getRoleLevel());
        return ResponseEntity.ok(result);
    }

    // ==================== 防爆破 ====================
    // IP -> 请求时间戳列表（最近5分钟）
    private static final Map<String, java.util.LinkedList<Long>> RATE_LIMIT = new ConcurrentHashMap<>();

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        java.util.LinkedList<Long> times = RATE_LIMIT.computeIfAbsent(ip, k -> new java.util.LinkedList<>());
        synchronized (times) {
            // 清理5分钟前的记录
            while (!times.isEmpty() && times.peek() < now - 300_000) times.poll();
            // 注册/登录：每分钟最多30次（测试阶段放宽）
            if (times.size() >= 30) return true;
            times.add(now);
            return false;
        }
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body,
                                                         HttpServletRequest request) {
        // 获取客户端 IP
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) ip = request.getRemoteAddr();

        // 防爆破
        if (isRateLimited(ip)) {
            return ResponseEntity.status(429).body(Map.of("error", "操作太频繁，请稍后再试"));
        }

        String id = body.get("id");
        String name = body.get("name");
        String password = body.get("password");
        String phone = body.get("phone");
        String email = body.get("email");
        String captchaKey = body.get("captchaKey");
        String captchaCode = body.get("captchaCode");
        String role = body.get("role");

        // 字段校验
        if (id == null || !id.matches("^[a-zA-Z0-9_]{3,30}$"))
            return ResponseEntity.badRequest().body(Map.of("error", "用户ID限3-30位英文数字下划线"));
        if (name == null || name.isBlank() || name.length() > 20 || name.matches(".*[<>\"'\\\\].*"))
            return ResponseEntity.badRequest().body(Map.of("error", "姓名限1-20字不含特殊字符"));
        if (phone == null || !phone.matches("^1\\d{10}$"))
            return ResponseEntity.badRequest().body(Map.of("error", "请输入正确的11位手机号"));
        if (email == null || !email.matches("^[\\w.-]+@[\\w.-]+\\.\\w{2,}$"))
            return ResponseEntity.badRequest().body(Map.of("error", "邮箱格式不正确"));
        if (password == null || password.length() < 6 || password.length() > 30)
            return ResponseEntity.badRequest().body(Map.of("error", "密码长度6-30位"));
        if (role == null) role = "sales";
        if (!List.of("sales", "planner", "designer", "supplychain").contains(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的注册角色"));
        }

        if (!CaptchaController.verifyCaptcha(captchaKey, captchaCode)) {
            return ResponseEntity.badRequest().body(Map.of("error", "图形验证码错误或已过期"));
        }

        // 检查唯一性
        if (userRepository.findByUserId(id).isPresent())
            return ResponseEntity.badRequest().body(Map.of("error", "该用户ID已被注册"));
        if (userRepository.findByPhone(phone).isPresent())
            return ResponseEntity.badRequest().body(Map.of("error", "该手机号已被注册"));
        if (userRepository.findByEmail(email).isPresent())
            return ResponseEntity.badRequest().body(Map.of("error", "该邮箱已被注册"));

        // 创建用户
        User user = User.builder()
                .userId(id)
                .name(name)
                .role(role)
                .title(switch (role) {
                    case "sales" -> "销售";
                    case "planner" -> "产品企划";
                    case "designer" -> "设计师";
            case "supplychain" -> "供应链";
                    default -> "";
                })
                .password(sha256(password))
                .phone(phone)
                .email(email)
                .build();
        userRepository.save(user);

        // 自动登录
        String token = generateToken();
        TOKENS.put(token, new AuthSession(user.getUserId(), user.getRole(), user.getName()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("userId", user.getUserId());
        result.put("name", user.getName());
        result.put("role", user.getRole());
        result.put("title", user.getTitle());
        result.put("roleLevel", user.getRoleLevel());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestHeader("X-Auth-Token") String token) {
        AuthSession session = TOKENS.get(token);
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
        return ResponseEntity.ok(Map.of("message", "已退出登录"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader("X-Auth-Token") String token) {
        AuthSession session = TOKENS.get(token);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        }
        // 返回当前模拟用户信息 + 原始用户信息
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", session.userId);
        result.put("name", session.name);
        result.put("role", session.role);
        result.put("originalUserId", session.originalUserId);
        result.put("originalRole", session.originalRole);
        return ResponseEntity.ok(result);
    }

    /** 模拟用户视角（仅 admin 可使用，不修改数据库，仅更新当前会话） */
    @PostMapping("/impersonate")
    public ResponseEntity<Map<String, Object>> impersonate(
            @RequestHeader("X-Auth-Token") String token,
            @RequestBody Map<String, String> body) {
        AuthSession session = TOKENS.get(token);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        }

        // 用原始角色进行鉴权（即使已经在模拟其他用户，也允许继续切换）
        String effectiveRole = session.originalRole();
        if (!"admin".equals(effectiveRole)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权切换用户视角"));
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

        // 替换当前会话为目标用户信息，保留原始登录用户信息
        TOKENS.put(token, new AuthSession(
            target.getUserId(), target.getRole(), target.getName(),
            session.originalUserId(), session.originalRole()));

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
        AuthSession session = TOKENS.get(token);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        }
        List<String> perms = adminService.getPermissionsByRoleName(session.role());
        return ResponseEntity.ok(Map.of(
            "role", session.role(),
            "permissions", perms
        ));
    }

    // 校验 token 并返回 session（供过滤器使用）
    public static AuthSession validateToken(String token) {
        return token != null ? TOKENS.get(token) : null;
    }

    // 清除用户的所有 token（切换账号时）
    public static void clearUserTokens(String userId) {
        TOKENS.values().removeIf(s -> s.userId.equals(userId));
    }

    // ==================== 内部类 ====================

    public record AuthSession(String userId, String role, String name, String originalUserId, String originalRole) {
        public AuthSession(String userId, String role, String name) {
            this(userId, role, name, userId, role);
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
        TOKENS.put(token, new AuthSession(userId, role, name));
        return token;
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
