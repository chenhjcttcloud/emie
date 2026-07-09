package com.emie.designpm.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/email-code")
public class EmailController {

    /**
     * 邮箱验证码能力已下线，注册改为直接校验图形验证码。
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> sendCode() {
        return ResponseEntity.status(410).body(Map.of(
                "error", "邮箱验证码功能已下线，请直接完成图形验证码后注册"
        ));
    }
}
