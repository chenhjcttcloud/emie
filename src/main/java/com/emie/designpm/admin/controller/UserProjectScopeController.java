package com.emie.designpm.admin.controller;

import com.emie.designpm.admin.service.PermissionManagementService;
import com.emie.designpm.admin.service.UserProjectScopeService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.auth.AuthSessions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class UserProjectScopeController {
    private final UserProjectScopeService scopes;

    public UserProjectScopeController(UserProjectScopeService scopes) {
        this.scopes = scopes;
    }

    @PutMapping("/{id}/project-scope")
    public ResponseEntity<?> update(
            @PathVariable Long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可操作"));
        AuthSession session = (AuthSession) request.getAttribute("authSession");
        try {
            return ResponseEntity.ok(scopes.updateScope(
                    id,
                    body.get("scope"),
                    new PermissionManagementService.Actor(
                            session.userId(), session.name(), body.get("reason"), request.getRemoteAddr())));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        }
    }
}
