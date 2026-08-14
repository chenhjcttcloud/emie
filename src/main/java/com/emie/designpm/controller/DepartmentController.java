package com.emie.designpm.controller;

import com.emie.designpm.entity.Department;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.DepartmentRepository;
import com.emie.designpm.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/departments")
public class DepartmentController {

    private final DepartmentRepository departmentRepository;
    private final UserService userService;

    public DepartmentController(DepartmentRepository departmentRepository,
                                UserService userService) {
        this.departmentRepository = departmentRepository;
        this.userService = userService;
    }

    /** 获取所有部门 */
    @GetMapping
    public ResponseEntity<List<Department>> getAll() {
        return ResponseEntity.ok(departmentRepository.findAllByOrderBySortOrderAsc());
    }

    /** 创建部门 */
    @PostMapping
    @Transactional
    public ResponseEntity<Department> create(@RequestBody Department dept, HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        if (dept.getName() == null || dept.getName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (!isValidHeadRole(dept.getRole(), dept.getHeadUserId())) {
            return ResponseEntity.badRequest().build();
        }
        Department saved = departmentRepository.save(dept);
        // 如果有负责人，自动分配到该部门并设为部门负责人级别
        if (dept.getHeadUserId() != null && !dept.getHeadUserId().isBlank()) {
            assignHeadToDept(saved.getId(), dept.getHeadUserId());
        }
        return ResponseEntity.ok(saved);
    }

    /** 更新部门 */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Department> update(@PathVariable Long id, @RequestBody Department dept, HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        return departmentRepository.findById(id)
                .map(existing -> {
                    if (!isValidHeadRole(dept.getRole(), dept.getHeadUserId())) {
                        return ResponseEntity.badRequest().<Department>build();
                    }
                    existing.setName(dept.getName());
                    existing.setRole(dept.getRole());

                    // 如果负责人变更，旧负责人降为普通成员
                    String oldHeadId = existing.getHeadUserId();
                    String newHeadId = dept.getHeadUserId();
                    if (newHeadId != null && !newHeadId.equals(oldHeadId)) {
                        // 旧负责人降级
                        if (oldHeadId != null && !oldHeadId.isBlank()) {
                            User oldHead = userService.getUserByUserId(oldHeadId);
                            if (oldHead != null) {
                                oldHead.setTitleLevel(0);
                                userService.saveUser(oldHead);
                            }
                        }
                    }

                    existing.setHeadUserId(newHeadId);
                    existing.setSortOrder(dept.getSortOrder());
                    existing.setActive(dept.getActive());

                    Department saved = departmentRepository.save(existing);

                    // 新负责人自动分配到部门
                    if (newHeadId != null && !newHeadId.isBlank()) {
                        assignHeadToDept(id, newHeadId);
                    }

                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private void assignHeadToDept(Long deptId, String headUserId) {
        User user = userService.getUserByUserId(headUserId);
        if (user != null) {
            user.setDepartmentId(deptId);
            user.setTitleLevel(2); // 2=部门负责人
            userService.saveUser(user);
        }
    }

    /** 部门负责人必须与部门关联角色一致；管理员可跨部门担任负责人。 */
    private boolean isValidHeadRole(String departmentRole, String headUserId) {
        if (headUserId == null || headUserId.isBlank()) return true;
        if (departmentRole == null || departmentRole.isBlank()) return false;
        User user = userService.getUserByUserId(headUserId);
        return user != null && (departmentRole.equals(user.getRole()) || "admin".equals(user.getRole()));
    }

    /** 删除部门 */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可操作"));
        // 清空部门成员的 departmentId
        List<User> members = userService.getUsersByDepartmentId(id);
        for (User u : members) {
            u.setDepartmentId(null);
            u.setTitleLevel(0);
            userService.saveUser(u);
        }
        departmentRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }
}
