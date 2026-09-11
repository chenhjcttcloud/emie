package com.emie.designpm.reference.controller;

import com.emie.designpm.auth.AuthSessions;
import com.emie.designpm.entity.ProductCategory;
import com.emie.designpm.reference.dto.CategoryUpsertRequest;
import com.emie.designpm.reference.repository.ProductCategoryRepository;
import com.emie.designpm.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(repo.findAllByOrderBySortOrderAsc());
    }

    /** 新增类目 */
    @PostMapping
    public ResponseEntity<ProductCategory> create(@RequestBody CategoryUpsertRequest body, HttpServletRequest request) {
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        String name = body.name();
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        ProductCategory cat = new ProductCategory(SecurityUtil.sanitizeText(name.trim(), 50), parseSortOrder(body, 0));
        return ResponseEntity.ok(repo.save(cat));
    }

    /** 更新类目 */
    @PutMapping("/{id}")
    public ResponseEntity<ProductCategory> update(
            @PathVariable Long id, @RequestBody CategoryUpsertRequest body, HttpServletRequest request) {
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        ProductCategory cat = repo.findById(id).orElse(null);
        if (cat == null) return ResponseEntity.notFound().build();

        if (body.name() != null)
            cat.setName(SecurityUtil.sanitizeText(body.name().trim(), 50));
        if (body.sortOrder() != null) cat.setSortOrder(parseSortOrder(body, cat.getSortOrder()));
        if (body.active() != null) cat.setActive("true".equals(body.active()));
        return ResponseEntity.ok(repo.save(cat));
    }

    private static int parseSortOrder(CategoryUpsertRequest body, int fallback) {
        if (body.sortOrder() == null) return fallback;
        try {
            return Integer.parseInt(body.sortOrder());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 删除类目 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
