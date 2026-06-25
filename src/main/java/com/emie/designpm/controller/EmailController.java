package com.emie.designpm.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/email-code")
public class EmailController {

    // email -> { code, expireAt }
    private static final Map<String, EmailCode> CODE_STORE = new ConcurrentHashMap<>();
    private static final Map<String, java.util.LinkedList<Long>> RATE_LIMIT = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 发送邮箱验证码（含防爆破：每分钟最多发3次）
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> sendCode(@RequestBody Map<String, String> body,
                                                         jakarta.servlet.http.HttpServletRequest request) {
        // 防爆破：同一 IP 每分钟最多3次
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) ip = request.getRemoteAddr();
        {
            long now = System.currentTimeMillis();
            java.util.LinkedList<Long> times = RATE_LIMIT.computeIfAbsent(ip, k -> new java.util.LinkedList<>());
            synchronized (times) {
                while (!times.isEmpty() && times.peek() < now - 60_000) times.poll();
                if (times.size() >= 3) {
                    return ResponseEntity.status(429).body(Map.of("error", "发送太频繁，请1分钟后再试"));
                }
                times.add(now);
            }
        }

        String email = body.get("email");
        String captchaKey = body.get("captchaKey");
        String captchaCode = body.get("captchaCode");

        if (email == null || !email.matches("^[\\w.-]+@[\\w.-]+\\.\\w{2,}$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入正确的邮箱地址"));
        }

        // 验证图形验证码
        if (!CaptchaController.verifyCaptcha(captchaKey, captchaCode)) {
            return ResponseEntity.badRequest().body(Map.of("error", "图形验证码错误或已过期"));
        }

        // 同一邮箱60秒内不重复发送
        long now = System.currentTimeMillis();
        EmailCode existing = CODE_STORE.get(email);
        if (existing != null && existing.expireAt > now + 4 * 60 * 1000) {
            return ResponseEntity.badRequest().body(Map.of("error", "验证码已发送，请查收邮箱"));
        }

        // 生成6位验证码
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        long expireAt = System.currentTimeMillis() + 5 * 60 * 1000; // 5分钟有效
        CODE_STORE.put(email, new EmailCode(code, expireAt));

        // TODO: 实际接入邮件服务发送验证码
        // 示例：使用 Spring MailSender
        // SimpleMailMessage msg = new SimpleMailMessage();
        // msg.setTo(email);
        // msg.setSubject("EMIE设计管理系统 - 邮箱验证码");
        // msg.setText("您的验证码是: " + code + "，5分钟内有效。");
        // mailSender.send(msg);
        System.out.println("[EMAIL] 验证码发送至 " + email + ": " + code);

        return ResponseEntity.ok(Map.of("message", "验证码已发送至邮箱", "debug", code));
    }

    /**
     * 验证邮箱验证码（验证后立即删除）
     */
    public static boolean verifyCode(String email, String code) {
        if (email == null || code == null) return false;
        EmailCode stored = CODE_STORE.remove(email);
        if (stored == null) return false;
        if (System.currentTimeMillis() > stored.expireAt) return false;
        return stored.code.equals(code);
    }

    private record EmailCode(String code, long expireAt) {}
}
