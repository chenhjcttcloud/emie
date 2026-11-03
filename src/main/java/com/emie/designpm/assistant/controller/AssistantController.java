package com.emie.designpm.assistant.controller;

import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.assistant.AssistantModelDiscovery;
import com.emie.designpm.assistant.AssistantModelSettings;
import com.emie.designpm.assistant.AssistantRequest;
import com.emie.designpm.assistant.AssistantService;
import com.emie.designpm.auth.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final AssistantService assistant;
    private final AssistantModelSettings settings;
    private final PermissionService permissions;
    private final AssistantModelDiscovery discovery;

    public AssistantController(
            AssistantService assistant,
            AssistantModelSettings settings,
            PermissionService permissions,
            AssistantModelDiscovery discovery) {
        this.assistant = assistant;
        this.settings = settings;
        this.permissions = permissions;
        this.discovery = discovery;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody AssistantRequest body, HttpServletRequest request) {
        AuthSession session = (AuthSession) request.getAttribute("authSession");
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        if (request.getContentLengthLong() > 65536)
            return ResponseEntity.status(413).body(Map.of("error", "对话内容过长，请清空后重试"));
        try {
            return ResponseEntity.ok(assistant.chat(body, session));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        }
    }

    @GetMapping("/config")
    public ResponseEntity<?> config(HttpServletRequest request) {
        if (!canConfigure(request, "admin.config.view")) return forbidden();
        return ResponseEntity.ok(settings.overview());
    }

    @GetMapping("/model")
    public ResponseEntity<?> model(HttpServletRequest request) {
        AuthSession session = (AuthSession) request.getAttribute("authSession");
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        return ResponseEntity.ok(settings.publicModel());
    }

    @PostMapping("/config")
    public ResponseEntity<?> saveConfig(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!canConfigure(request, "admin.config.edit")) return forbidden();
        try {
            return ResponseEntity.ok(settings.save(body));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        }
    }

    @PostMapping("/config/models")
    public ResponseEntity<?> discoverModels(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!canConfigure(request, "admin.config.edit")) return forbidden();
        if (request.getContentLengthLong() > 16384)
            return ResponseEntity.status(413).body(Map.of("error", "接口配置内容过长"));
        try {
            return ResponseEntity.ok(discovery.discover(body));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        } catch (AssistantModelDiscovery.DiscoveryFailure failure) {
            return ResponseEntity.status(failure.status()).body(Map.of("error", failure.getMessage()));
        }
    }

    private boolean canConfigure(HttpServletRequest request, String permission) {
        AuthSession session = (AuthSession) request.getAttribute("authSession");
        return session != null && "admin".equals(session.role()) && permissions.has(session.role(), permission);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "仅有配置权限的管理员可管理助手模型"));
    }
}
