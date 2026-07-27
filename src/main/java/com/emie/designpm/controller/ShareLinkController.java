package com.emie.designpm.controller;

import com.emie.designpm.service.ShareLinkService;
import com.emie.designpm.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 分享链接管理接口（需登录认证）
 */
@RestController
@RequestMapping("/api/share")
public class ShareLinkController {

    private final ShareLinkService shareLinkService;
    private final PermissionService permissionService;

    @Autowired
    public ShareLinkController(ShareLinkService shareLinkService, PermissionService permissionService) {
        this.shareLinkService = shareLinkService;
        this.permissionService = permissionService;
    }

    public ShareLinkController(ShareLinkService shareLinkService) {
        this(shareLinkService, null);
    }

    /** 创建分享链接 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body,
                                    @RequestHeader("X-Auth-Token") String token) {
        try {
            AuthController.AuthSession session = AuthController.validateToken(token);
            if (session == null) {
                return ResponseEntity.status(401).body(Map.of("error", "未登录"));
            }
            if (permissionService != null && !permissionService.has(session.role(), "project.share.create")) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "当前账号没有创建项目分享链接的权限",
                        "permission", "project.share.create"));
            }

            String targetType = (String) body.get("targetType");
            Object targetIdRaw = body.get("targetId");
            Long expiresIn = body.get("expiresIn") != null
                    ? ((Number) body.get("expiresIn")).longValue() : null;
            String password = (String) body.get("password");

            if (targetType == null || targetType.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "缺少 targetType"));
            }
            if (targetIdRaw == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "缺少 targetId"));
            }
            Long targetId = ((Number) targetIdRaw).longValue();

            Map<String, Object> result = shareLinkService.createShareLink(
                    targetType, targetId, session.userId(), expiresIn, password);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 获取我的分享列表 */
    @GetMapping("/list")
    public ResponseEntity<?> list(@RequestHeader("X-Auth-Token") String token) {
        AuthController.AuthSession session = AuthController.validateToken(token);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        List<Map<String, Object>> shares = shareLinkService.getUserShares(session.userId());
        return ResponseEntity.ok(shares);
    }

    /** 管理员获取全部分享列表 */
    @GetMapping("/admin/all")
    public ResponseEntity<?> adminList(@RequestHeader("X-Auth-Token") String token,
                                       HttpServletRequest request) {
        AuthController.AuthSession session = AuthController.validateToken(token);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        if (!AuthController.isAdmin(request)) {
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可操作"));
        }
        List<Map<String, Object>> shares = shareLinkService.getAllShares();
        return ResponseEntity.ok(shares);
    }

    /** 管理员强制收回任意分享链接 */
    @PostMapping("/admin/{id}/revoke")
    public ResponseEntity<?> adminRevoke(@PathVariable Long id,
                                         @RequestHeader("X-Auth-Token") String token,
                                         HttpServletRequest request) {
        try {
            AuthController.AuthSession session = AuthController.validateToken(token);
            if (session == null) {
                return ResponseEntity.status(401).body(Map.of("error", "未登录"));
            }
            if (!AuthController.isAdmin(request)) {
                return ResponseEntity.status(403).body(Map.of("error", "仅管理员可操作"));
            }
            shareLinkService.adminRevokeShare(id);
            return ResponseEntity.ok(Map.of("message", "分享链接已收回"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 管理员更新分享链接（过期时间、密码） */
    @PutMapping("/admin/{id}")
    public ResponseEntity<?> adminUpdate(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body,
                                         @RequestHeader("X-Auth-Token") String token,
                                         HttpServletRequest request) {
        try {
            AuthController.AuthSession session = AuthController.validateToken(token);
            if (session == null) {
                return ResponseEntity.status(401).body(Map.of("error", "未登录"));
            }
            if (!AuthController.isAdmin(request)) {
                return ResponseEntity.status(403).body(Map.of("error", "仅管理员可操作"));
            }
            Long expiresIn = body.get("expiresIn") != null
                    ? ((Number) body.get("expiresIn")).longValue() : null;
            String password = (String) body.get("password");
            shareLinkService.adminUpdateShare(id, expiresIn, password);
            return ResponseEntity.ok(Map.of("message", "分享链接已更新"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 收回分享链接 */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<?> revoke(@PathVariable Long id,
                                    @RequestHeader("X-Auth-Token") String token) {
        try {
            AuthController.AuthSession session = AuthController.validateToken(token);
            if (session == null) {
                return ResponseEntity.status(401).body(Map.of("error", "未登录"));
            }
            shareLinkService.revokeShare(id, session.userId());
            return ResponseEntity.ok(Map.of("message", "分享链接已收回"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
