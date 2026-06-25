package com.emie.designpm.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/captcha")
public class CaptchaController {

    private static final Map<String, String> CAPTCHA_STORE = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成图形验证码，返回 base64 图片
     */
    @GetMapping("/image")
    public void getCaptcha(@RequestParam String key, HttpServletResponse response) throws IOException {
        // 生成4位验证码
        String code = String.format("%04d", RANDOM.nextInt(10000));
        CAPTCHA_STORE.put(key, code);

        int w = 140, h = 48;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        // 背景
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);

        // 干扰线
        g.setColor(new Color(220, 220, 220));
        for (int i = 0; i < 6; i++) {
            int x1 = RANDOM.nextInt(w), y1 = RANDOM.nextInt(h);
            int x2 = RANDOM.nextInt(w), y2 = RANDOM.nextInt(h);
            g.drawLine(x1, y1, x2, y2);
        }

        // 验证码文字
        g.setFont(new Font("Arial", Font.BOLD, 26));
        for (int i = 0; i < code.length(); i++) {
            int r = 30 + RANDOM.nextInt(180);
            int gr = 30 + RANDOM.nextInt(180);
            int b = 30 + RANDOM.nextInt(180);
            g.setColor(new Color(r, gr, b));
            double angle = (RANDOM.nextDouble() - 0.5) * 0.4;
            g.rotate(angle, 25 + i * 28, 30);
            g.drawString(String.valueOf(code.charAt(i)), 20 + i * 28, 34);
            g.rotate(-angle, 25 + i * 28, 30);
        }

        // 干扰点
        g.setColor(new Color(200, 200, 200));
        for (int i = 0; i < 30; i++) {
            int x = RANDOM.nextInt(w), y = RANDOM.nextInt(h);
            g.fillRect(x, y, 2, 2);
        }

        g.dispose();

        response.setContentType("image/png");
        ImageIO.write(img, "PNG", response.getOutputStream());
    }

    /**
     * 验证图形验证码（验证后立即删除，一次性使用）
     */
    public static boolean verifyCaptcha(String key, String code) {
        if (key == null || code == null) return false;
        String stored = CAPTCHA_STORE.remove(key);
        return stored != null && stored.equals(code);
    }
}
