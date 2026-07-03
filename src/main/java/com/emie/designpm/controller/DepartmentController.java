package com.emie.designpm.controller;

import com.emie.designpm.entity.Department;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.DepartmentRepository;
import com.emie.designpm.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<Department> create(@RequestBody Department dept) {
        if (dept.getName() == null || dept.getName().isBlank()) {
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
    public ResponseEntity<Department> update(@PathVariable Long id, @RequestBody Department dept) {
        return departmentRepository.findById(id)
                .map(existing -> {
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

    /** 删除部门 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
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
