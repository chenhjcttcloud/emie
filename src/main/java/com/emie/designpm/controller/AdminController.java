package com.emie.designpm.controller;

import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.entity.User;
import com.emie.designpm.service.AdminService;
import com.emie.designpm.service.NotificationTestService;
import com.emie.designpm.service.NotificationRetryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.emie.designpm.dto.PageResponse;

import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final NotificationTestService notificationTestService;
    private final NotificationRetryService notificationRetryService;

    public AdminController(AdminService adminService, NotificationTestService notificationTestService, NotificationRetryService notificationRetryService) {
        this.adminService = adminService;
        this.notificationTestService = notificationTestService;
        this.notificationRetryService = notificationRetryService;
    }

    // ==================== 公开配置 ====================

    /** 公开配置（供登录页等无需登录的场景使用） */
    @GetMapping("/public-config")
    public ResponseEntity<Map<String, String>> getPublicConfig() {
        return ResponseEntity.ok(adminService.getPublicConfig());
    }

    // ==================== 系统配置管理 ====================

    /** 获取所有系统配置（按分组） */
    @GetMapping("/configs")
    public ResponseEntity<Map<String, List<SystemConfig>>> getAllConfigs() {
        return ResponseEntity.ok(adminService.getAllConfigs());
    }

    /** 批量更新配置 */
    @PutMapping("/configs")
    public ResponseEntity<Map<String, String>> updateConfigs(
            @RequestBody Map<String, Object> body,
            @RequestHeader("X-Auth-Token") String token) {
        @SuppressWarnings("unchecked")
        Map<String, String> configs = (Map<String, String>) body.get("configs");
        String updatedBy = getUserFromToken(token);
        adminService.updateConfigs(configs, updatedBy);
        return ResponseEntity.ok(Map.of("message", "配置已更新"));
    }

    /** 保存配置后，向当前管理员验证站内及飞书通知渠道。 */
    @PostMapping("/notifications/test")
    public ResponseEntity<Map<String, Object>> sendNotificationTest(
            @RequestHeader("X-Auth-Token") String token) {
        AuthController.AuthSession session = AuthController.validateToken(token);
        if (session == null || !"admin".equals(session.role())) {
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可发送通知测试"));
        }
        try {
            return ResponseEntity.ok(notificationTestService.sendTest(session.userId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/notifications/temporary-broadcast")
    public ResponseEntity<?> sendTemporaryBroadcast(@RequestBody Map<String, String> body,
                                                     @RequestHeader("X-Auth-Token") String token) {
        AuthController.AuthSession session = AuthController.validateToken(token);
        if (session == null || !"admin".equals(session.role())) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可发送临时通知"));
        try { return ResponseEntity.ok(notificationTestService.sendTemporaryBroadcast(body.get("title"), body.get("content"), session.userId())); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @GetMapping("/notifications/failures")
    public ResponseEntity<?> getNotificationFailures(@RequestHeader("X-Auth-Token") String token) {
        AuthController.AuthSession session = AuthController.validateToken(token);
        if (session == null || !"admin".equals(session.role())) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可查看通知失败记录"));
        return ResponseEntity.ok(notificationRetryService.recentFailures());
    }

    @PostMapping("/notifications/deliveries/{id}/retry")
    public ResponseEntity<?> retryNotification(@PathVariable Long id, @RequestHeader("X-Auth-Token") String token) {
        AuthController.AuthSession session = AuthController.validateToken(token);
        if (session == null || !"admin".equals(session.role())) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可重试通知"));
        try {
            notificationRetryService.retryNow(id, session.userId());
            return ResponseEntity.ok(Map.of("message", "通知已重新排队"));
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    /** 上传管理图片（logo / login-bg） */
    @PostMapping("/upload-image")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type) {
        if (!type.equals("logo") && !type.equals("login-bg")) {
            return ResponseEntity.badRequest().body(Map.of("error", "type 参数必须为 logo 或 login-bg"));
        }
        try {
            Map<String, Object> result = adminService.uploadAdminImage(file, type);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "图片上传失败，请稍后重试"));
        }
    }

    // ==================== 用户管理 ====================

    /** 获取所有用户 */
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getAllUsers() {
        return ResponseEntity.ok(adminService.getAllUsers());
    }

    @GetMapping("/users/page")
    public ResponseEntity<PageResponse<Map<String, Object>>> getUsersPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return ResponseEntity.ok(adminService.getUsersPage(keyword, role, status, pageable));
    }

    /** 更新用户角色 */
    @PutMapping("/users/{id}/role")
    public ResponseEntity<Map<String, Object>> updateUserRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader("X-Auth-Token") String token) {
        String newRole = body.get("role");
        User user;
        try {
            user = adminService.updateUserRole(id, newRole, getUserFromToken(token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("userId", user.getUserId());
        result.put("name", user.getName());
        result.put("role", user.getRole());
        result.put("title", user.getTitle());
        result.put("status", user.getStatus() != null ? user.getStatus() : "active");
        return ResponseEntity.ok(result);
    }

    /** 重置用户密码 */
    @PutMapping("/users/{id}/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String newPassword = body.get("password");
        try {
            adminService.resetPassword(id, newPassword);
            return ResponseEntity.ok(Map.of("message", "密码已重置"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除用户 */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, String>> deleteUser(@PathVariable Long id) {
        try {
            adminService.deleteUser(id);
            return ResponseEntity.ok(Map.of("message", "用户已删除"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 编辑用户资料 */
    @PutMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        try {
            Map<String, Object> result = adminService.updateUser(id, body);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 停用/启用账号 */
    @PutMapping("/users/{id}/toggle-status")
    public ResponseEntity<Map<String, Object>> toggleUserStatus(@PathVariable Long id) {
        try {
            User user = adminService.toggleUserStatus(id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", user.getId());
            result.put("userId", user.getUserId());
            result.put("name", user.getName());
            result.put("status", user.getStatus());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 角色管理 ====================

    /** 获取权限定义列表 */
    @GetMapping("/permission-defs")
    public ResponseEntity<List<Map<String, Object>>> getPermissionDefs() {
        return ResponseEntity.ok(AdminService.getPermissionDefs());
    }

    /** 获取所有角色 */
    @GetMapping("/roles")
    public ResponseEntity<List<Map<String, Object>>> getAllRoles() {
        return ResponseEntity.ok(adminService.getAllRoles());
    }

    /** 创建角色 */
    @PostMapping("/roles")
    public ResponseEntity<Map<String, Object>> createRole(@RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            String displayName = (String) body.get("displayName");
            String description = (String) body.get("description");
            @SuppressWarnings("unchecked")
            List<String> permissions = (List<String>) body.get("permissions");
            return ResponseEntity.ok(adminService.createRole(name, displayName, description, permissions));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 更新角色 */
    @PutMapping("/roles/{id}")
    public ResponseEntity<Map<String, Object>> updateRole(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            String displayName = (String) body.get("displayName");
            String description = (String) body.get("description");
            @SuppressWarnings("unchecked")
            List<String> permissions = (List<String>) body.get("permissions");
            return ResponseEntity.ok(adminService.updateRole(id, displayName, description, permissions));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除角色 */
    @DeleteMapping("/roles/{id}")
    public ResponseEntity<Map<String, String>> deleteRole(@PathVariable Long id) {
        try {
            adminService.deleteRole(id);
            return ResponseEntity.ok(Map.of("message", "角色已删除"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 评分权重管理 ====================

    /** 获取评分权重配置 */
    @GetMapping("/scoring-weights")
    public ResponseEntity<Map<String, Object>> getScoringWeights() {
        return ResponseEntity.ok(adminService.getScoringWeights());
    }

    /** 更新评分权重 */
    @PutMapping("/scoring-weights")
    public ResponseEntity<Map<String, Object>> updateScoringWeights(
            @RequestBody Map<String, Object> body) {
        try {
            adminService.updateScoringWeights(body);
            return ResponseEntity.ok(Map.of("success", true, "message", "评分权重已更新"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 数据清除 ====================

    /** 清空所有项目业务数据（测试用，保留用户/角色/部门/系统配置） */
    @DeleteMapping("/clear-data")
    public ResponseEntity<Map<String, Object>> clearAllProjectData(
            @RequestHeader("X-Auth-Token") String token) {
        // 仅 admin 可用
        AuthController.AuthSession session = AuthController.validateToken(token);
        if (session == null || !"admin".equals(session.role())) {
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可执行此操作"));
        }
        Map<String, Object> result = adminService.clearAllProjectData();
        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // ==================== 工作量统计 ====================

    @GetMapping("/workload")
    public ResponseEntity<Map<String, Object>> getWorkload(
            @RequestHeader("X-Auth-Token") String token) {
        AuthController.AuthSession session = AuthController.validateToken(token);
        if (session == null || !"admin".equals(session.role())) {
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可查看工作量"));
        }
        return ResponseEntity.ok(adminService.getWorkloadStats());
    }

    @GetMapping("/workload/timeline")
    public ResponseEntity<Map<String, Object>> getWorkloadTimeline(
            @RequestParam(value = "range", defaultValue = "month") String range,
            @RequestHeader("X-Auth-Token") String token) {
        AuthController.AuthSession session = AuthController.validateToken(token);
        if (session == null || !"admin".equals(session.role())) {
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可查看工作量"));
        }
        return ResponseEntity.ok(adminService.getWorkloadTimeline(range));
    }

    // ==================== 辅助方法 ====================

    private String getUserFromToken(String token) {
        AuthController.AuthSession session = AuthController.validateToken(token);
        return session != null ? session.name() : "未知";
    }
}
