package com.emie.designpm.recommendation.controller;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.recommendation.service.VisualAiSettings;
import com.emie.designpm.recommendation.service.VisualAnalysisService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/visual-ai-config")
public class VisualAiConfigController {
    private final VisualAiSettings settings;
    private final VisualAnalysisService analysis;

    public VisualAiConfigController(VisualAiSettings settings, VisualAnalysisService analysis) {
        this.settings = settings;
        this.analysis = analysis;
    }

    @GetMapping
    public ResponseEntity<?> read(HttpServletRequest request) {
        if (!admin(request)) return forbidden();
        return ResponseEntity.ok(overview());
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!admin(request)) return forbidden();
        try {
            settings.save(body);
            return ResponseEntity.ok(overview());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/index-library")
    public ResponseEntity<?> index(HttpServletRequest request) {
        if (!admin(request)) return forbidden();
        try {
            return ResponseEntity.ok(analysis.indexLibrary());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> overview() {
        Map<String, Object> view = new LinkedHashMap<>(settings.overview());
        view.put("indexStats", analysis.indexStats());
        return view;
    }

    private boolean admin(HttpServletRequest request) {
        AuthSession session = (AuthSession) request.getAttribute("authSession");
        return session != null && "admin".equals(session.role());
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "仅管理员可配置AI服务"));
    }
}
