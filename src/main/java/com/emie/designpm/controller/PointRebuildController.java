package com.emie.designpm.controller;

import com.emie.designpm.service.PointRebuildService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/points")
public class PointRebuildController {
    private final PointRebuildService rebuild;
    public PointRebuildController(PointRebuildService rebuild) { this.rebuild = rebuild; }

    @PostMapping("/rebuild-local")
    public ResponseEntity<?> rebuildLocal(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!("127.0.0.1".equals(remote) || "::1".equals(remote))) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(rebuild.rebuild());
    }
}
