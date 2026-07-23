package com.emie.designpm.controller;

import com.emie.designpm.entity.User;
import com.emie.designpm.repository.DepartmentRepository;
import com.emie.designpm.repository.RoleRepository;
import com.emie.designpm.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;

    public UserController(UserService userService,
                          DepartmentRepository departmentRepository,
                          RoleRepository roleRepository) {
        this.userService = userService;
        this.departmentRepository = departmentRepository;
        this.roleRepository = roleRepository;
    }

    /** 当前身份切换器使用的轻量角色字典；登录用户均可读取，不暴露权限配置。 */
    @GetMapping("/roles")
    public ResponseEntity<List<Map<String, String>>> getRoleDefinitions() {
        List<Map<String, String>> roles = roleRepository.findAll().stream()
                .map(role -> Map.of(
                        "name", role.getName(),
                        "displayName", role.getDisplayName()))
                .toList();
        return ResponseEntity.ok(roles);
    }

    @GetMapping
    public ResponseEntity<Map<String, List<Map<String, String>>>> getUsers() {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();

        for (String role : List.of("sales", "planner", "designer", "supplychain", "promotion", "admin")) {
            result.put(role, new ArrayList<>());
        }

        for (User user : userService.getAllUsers()) {
            String rawRole = user.getRole();
            String role = normalizeRole(rawRole);
            if (role == null || role.isBlank() || "pending".equals(role)) continue;
            result.computeIfAbsent(role, ignored -> new ArrayList<>()).add(toUserMap(user));
            // 保留历史大小写角色键，兼容旧版管理员视角；业务前端使用标准小写键。
            if ("Promotion".equals(rawRole)) {
                result.computeIfAbsent(rawRole, ignored -> new ArrayList<>()).add(toUserMap(user));
            }
        }

        return ResponseEntity.ok(result);
    }

    private String normalizeRole(String role) {
        if (role == null) return null;
        if ("promotion".equalsIgnoreCase(role) || "product_promotion".equalsIgnoreCase(role)
                || "product-promotion".equalsIgnoreCase(role)) return "promotion";
        return role;
    }

    private Map<String, String> toUserMap(User user) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(user.getId()));
        result.put("userId", user.getUserId());
        result.put("name", user.getName());
        result.put("role", user.getRole());
        result.put("title", user.getTitle());
        result.put("roleLevel", user.getRoleLevel() != null ? String.valueOf(user.getRoleLevel()) : "");
        result.put("departmentId", user.getDepartmentId() != null ? String.valueOf(user.getDepartmentId()) : "");
        result.put("supervisorId", user.getSupervisorId() != null ? user.getSupervisorId() : "");
        result.put("titleLevel", user.getTitleLevel() != null ? String.valueOf(user.getTitleLevel()) : "0");
        return result;
    }

    @GetMapping("/info/{userId}")
    public ResponseEntity<Map<String, String>> getUserInfo(@PathVariable String userId) {
        User u = userService.getUserByUserId(userId);
        if (u == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toUserMap(u));
    }

    /** 更新用户部门/职级/主管信息 */
    @PutMapping("/org/{userId}")
    public ResponseEntity<?> updateOrgInfo(@PathVariable String userId, @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可操作"));
        User u = userService.getUserByUserId(userId);
        if (u == null) return ResponseEntity.notFound().build();
        if (body.containsKey("departmentId")) {
            Object v = body.get("departmentId");
            // 部门负责人不能被移出部门
            if (v == null && departmentRepository.findByHeadUserId(userId).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "该用户是部门负责人，无法移出。请先更换部门负责人。"));
            }
            u.setDepartmentId(v != null ? Long.valueOf(v.toString()) : null);
        }
        if (body.containsKey("supervisorId")) {
            u.setSupervisorId((String) body.get("supervisorId"));
        }
        if (body.containsKey("titleLevel")) {
            u.setTitleLevel(Integer.valueOf(body.get("titleLevel").toString()));
        }
        userService.saveUser(u);
        return ResponseEntity.ok(Map.of("message", "更新成功"));
    }
}
