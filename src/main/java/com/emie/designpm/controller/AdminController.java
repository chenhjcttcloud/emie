package com.emie.designpm.controller;

import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.entity.User;
import com.emie.designpm.service.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
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
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 用户管理 ====================

    /** 获取所有用户 */
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getAllUsers() {
        return ResponseEntity.ok(adminService.getAllUsers());
    }

    /** 更新用户角色 */
    @PutMapping("/users/{id}/role")
    public ResponseEntity<Map<String, Object>> updateUserRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader("X-Auth-Token") String token) {
        String newRole = body.get("role");
        if (newRole == null || !List.of("sales", "planner", "designer", "superior", "admin").contains(newRole)) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的角色"));
        }
        String updatedBy = getUserFromToken(token);
        User user = adminService.updateUserRole(id, newRole, updatedBy);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("userId", user.getUserId());
        result.put("name", user.getName());
        result.put("role", user.getRole());
        result.put("title", user.getTitle());
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

    // ==================== 辅助方法 ====================

    private String getUserFromToken(String token) {
        AuthController.AuthSession session = AuthController.validateToken(token);
        return session != null ? session.name() : "未知";
    }
}
