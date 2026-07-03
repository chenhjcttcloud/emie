package com.emie.designpm.controller;

import com.emie.designpm.entity.User;
import com.emie.designpm.repository.DepartmentRepository;
import com.emie.designpm.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final DepartmentRepository departmentRepository;

    public UserController(UserService userService,
                          DepartmentRepository departmentRepository) {
        this.userService = userService;
        this.departmentRepository = departmentRepository;
    }

    @GetMapping
    public ResponseEntity<Map<String, List<Map<String, String>>>> getUsers() {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();

        for (String role : List.of("sales", "planner", "designer", "supplychain", "admin")) {
            result.put(role, userService.getUsersByRole(role).stream().map(u -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", String.valueOf(u.getId()));
                m.put("userId", u.getUserId());
                m.put("name", u.getName());
                m.put("role", u.getRole());
                m.put("title", u.getTitle());
                m.put("roleLevel", u.getRoleLevel() != null ? String.valueOf(u.getRoleLevel()) : "");
                m.put("departmentId", u.getDepartmentId() != null ? String.valueOf(u.getDepartmentId()) : "");
                m.put("supervisorId", u.getSupervisorId() != null ? u.getSupervisorId() : "");
                m.put("titleLevel", u.getTitleLevel() != null ? String.valueOf(u.getTitleLevel()) : "0");
                return m;
            }).collect(Collectors.toList()));
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/info/{userId}")
    public ResponseEntity<Map<String, String>> getUserInfo(@PathVariable String userId) {
        User u = userService.getUserByUserId(userId);
        if (u == null) return ResponseEntity.notFound().build();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(u.getId()));
        m.put("userId", u.getUserId());
        m.put("name", u.getName());
        m.put("role", u.getRole());
        m.put("title", u.getTitle());
        m.put("roleLevel", u.getRoleLevel() != null ? String.valueOf(u.getRoleLevel()) : "");
        m.put("departmentId", u.getDepartmentId() != null ? String.valueOf(u.getDepartmentId()) : "");
        m.put("supervisorId", u.getSupervisorId() != null ? u.getSupervisorId() : "");
        m.put("titleLevel", u.getTitleLevel() != null ? String.valueOf(u.getTitleLevel()) : "0");
        return ResponseEntity.ok(m);
    }

    /** 更新用户部门/职级/主管信息 */
    @PutMapping("/org/{userId}")
    public ResponseEntity<?> updateOrgInfo(@PathVariable String userId, @RequestBody Map<String, Object> body) {
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
