package com.emie.designpm.controller;

import com.emie.designpm.entity.ProductCategory;
import com.emie.designpm.repository.ProductCategoryRepository;
import com.emie.designpm.util.SecurityUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final ProductCategoryRepository repo;

    public CategoryController(ProductCategoryRepository repo) {
        this.repo = repo;
    }

    /** 获取启用的类目列表 */
    @GetMapping
    public ResponseEntity<List<ProductCategory>> listActive() {
        return ResponseEntity.ok(repo.findByActiveTrueOrderBySortOrderAsc());
    }

    /** 获取全部类目（管理后台用） */
    @GetMapping("/all")
    public ResponseEntity<List<ProductCategory>> listAll(HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(repo.findAllByOrderBySortOrderAsc());
    }

    /** 新增类目 */
    @PostMapping
    public ResponseEntity<ProductCategory> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        int sortOrder = 0;
        if (body.containsKey("sortOrder")) {
            try { sortOrder = Integer.parseInt(body.get("sortOrder")); } catch (NumberFormatException ignored) {}
        }
        ProductCategory cat = new ProductCategory(SecurityUtil.sanitizeText(name.trim(), 50), sortOrder);
        return ResponseEntity.ok(repo.save(cat));
    }

    /** 更新类目 */
    @PutMapping("/{id}")
    public ResponseEntity<ProductCategory> update(@PathVariable Long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        ProductCategory cat = repo.findById(id).orElse(null);
        if (cat == null) return ResponseEntity.notFound().build();

        if (body.containsKey("name") && body.get("name") != null) cat.setName(SecurityUtil.sanitizeText(body.get("name").trim(), 50));
        if (body.containsKey("sortOrder")) {
            try { cat.setSortOrder(Integer.parseInt(body.get("sortOrder"))); } catch (NumberFormatException ignored) {}
        }
        if (body.containsKey("active")) cat.setActive("true".equals(body.get("active")));
        return ResponseEntity.ok(repo.save(cat));
    }

    /** 删除类目 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
