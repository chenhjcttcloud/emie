package com.emie.designpm.auth;

/**
 * 登录会话快照。原先是 {@code AuthController.AuthSession} 内部记录，
 * 因被过滤器、Service、工具类全链路引用，抽到 auth 包作为独立类型。
 */
public record AuthSession(
        String userId, String role, String name, String originalUserId, String originalRole, long expiresAt) {

    /** 按产品要求，会话不因时间自动失效；0 明确表示永久有效。 */
    public static final long PERMANENT_SESSION_EXPIRES_AT = 0L;

    public AuthSession(String userId, String role, String name) {
        this(userId, role, name, userId, role, PERMANENT_SESSION_EXPIRES_AT);
    }

    public AuthSession(String userId, String role, String name, String originalUserId, String originalRole) {
        this(userId, role, name, originalUserId, originalRole, PERMANENT_SESSION_EXPIRES_AT);
    }
}
