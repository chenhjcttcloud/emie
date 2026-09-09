package com.emie.designpm.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话 / Token 注册表。原为 {@code AuthController} 的静态成员，
 * 因被过滤器和大量 Controller 直接引用，抽到 auth 包以解除对 Web 层的依赖。
 * 语义与原实现保持一致：内存 {@code TOKENS} 为主，Redis 为可选副本。
 */
public final class AuthSessions {

    private AuthSessions() {
    }

    public static final String AUTH_COOKIE = "designpm_auth";

    // 简单内存 Token 管理（生产环境应使用 Redis/DB）
    private static final Map<String, AuthSession> TOKENS = new ConcurrentHashMap<>();
    private static RedisSessionStore redisSessionStore;

    /** 由 {@code AuthController} 构造时注入，保留原有 setter 注入语义。 */
    public static void bindRedisSessionStore(RedisSessionStore store) {
        redisSessionStore = store;
    }

    public static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 供 Feishu SSO 使用：生成 token 并存入会话。 */
    public static String generateToken(String userId, String role, String name) {
        String token = generateToken();
        put(token, new AuthSession(userId, role, name));
        return token;
    }

    public static AuthSession get(String token) {
        AuthSession local = TOKENS.get(token);
        if (redisSessionStore == null) return local;
        AuthSession remote = redisSessionStore.get(token);
        return remote != null ? remote : local;
    }

    public static void put(String token, AuthSession session) {
        TOKENS.put(token, session);
        if (redisSessionStore != null) redisSessionStore.put(token, session);
    }

    /** 删除本地及 Redis 中的会话（登出）。 */
    public static void remove(String token) {
        TOKENS.remove(token);
        if (redisSessionStore != null) redisSessionStore.remove(token);
    }

    /** 校验 token 并返回 session（供过滤器使用）。 */
    public static AuthSession validateToken(String token) {
        if (token == null) return null;
        AuthSession session = get(token);
        if (session == null) return null;
        if (session.expiresAt() > 0 && System.currentTimeMillis() >= session.expiresAt()) {
            TOKENS.remove(token, session);
            return null;
        }
        return session;
    }

    /** 统一的管理员判断，避免仅依赖前端隐藏按钮。 */
    public static boolean isAdmin(HttpServletRequest request) {
        AuthSession session = request != null
                ? (AuthSession) request.getAttribute("authSession") : null;
        return session != null && ("admin".equals(session.role())
                || Boolean.TRUE.equals(request.getAttribute("permissionGranted")));
    }

    /** 清除用户的所有 token（切换账号 / 改角色时）。 */
    public static void clearUserTokens(String userId) {
        TOKENS.values().removeIf(s -> s.userId().equals(userId));
        if (redisSessionStore != null) redisSessionStore.removeUserTokens(userId);
    }
}
