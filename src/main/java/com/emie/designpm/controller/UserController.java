package com.emie.designpm.controller;

import com.emie.designpm.entity.User;
import com.emie.designpm.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<Map<String, List<Map<String, String>>>> getUsers() {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();

        for (String role : List.of("sales", "planner", "designer", "superior", "admin")) {
            result.put(role, userService.getUsersByRole(role).stream().map(u -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", String.valueOf(u.getId()));
                m.put("userId", u.getUserId());
                m.put("name", u.getName());
                m.put("role", u.getRole());
                m.put("title", u.getTitle());
                m.put("roleLevel", u.getRoleLevel() != null ? String.valueOf(u.getRoleLevel()) : "");
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
        return ResponseEntity.ok(m);
    }
}
