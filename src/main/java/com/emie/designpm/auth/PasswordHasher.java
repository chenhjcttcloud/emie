package com.emie.designpm.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码散列工具。原为 {@code AuthController} 的静态方法，与会话管理无关，
 * 抽到 auth 包供登录、用户管理、分享链接校验等复用。
 */
public final class PasswordHasher {

    private PasswordHasher() {}

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /** 兼容旧账号的 SHA-256 散列。 */
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
        return ENCODER.encode(input);
    }

    public static boolean matches(String raw, String encoded) {
        return ENCODER.matches(raw, encoded);
    }
}
