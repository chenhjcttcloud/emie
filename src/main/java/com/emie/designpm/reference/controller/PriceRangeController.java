package com.emie.designpm.reference.controller;

import com.emie.designpm.auth.AuthSessions;
import com.emie.designpm.entity.PriceRange;
import com.emie.designpm.reference.dto.PriceRangeUpsertRequest;
import com.emie.designpm.reference.repository.PriceRangeRepository;
import com.emie.designpm.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/price-ranges")
public class PriceRangeController {

    private final PriceRangeRepository repo;

    public PriceRangeController(PriceRangeRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<List<PriceRange>> listActive() {
        return ResponseEntity.ok(repo.findByActiveTrueOrderBySortOrderAsc());
    }

    @GetMapping("/all")
    public ResponseEntity<List<PriceRange>> listAll(HttpServletRequest request) {
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(repo.findAllByOrderBySortOrderAsc());
    }

    @PostMapping
    public ResponseEntity<PriceRange> create(@RequestBody PriceRangeUpsertRequest body, HttpServletRequest request) {
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        String name = body.name();
        if (name == null || name.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(
                repo.save(new PriceRange(SecurityUtil.sanitizeText(name.trim(), 50), parseSortOrder(body, 0))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PriceRange> update(
            @PathVariable Long id, @RequestBody PriceRangeUpsertRequest body, HttpServletRequest request) {
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        PriceRange item = repo.findById(id).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        if (body.name() != null)
            item.setName(SecurityUtil.sanitizeText(body.name().trim(), 50));
        if (body.sortOrder() != null) item.setSortOrder(parseSortOrder(body, item.getSortOrder()));
        if (body.active() != null) item.setActive("true".equals(body.active()));
        return ResponseEntity.ok(repo.save(item));
    }

    private static int parseSortOrder(PriceRangeUpsertRequest body, int fallback) {
        if (body.sortOrder() == null) return fallback;
        try {
            return Integer.parseInt(body.sortOrder());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
