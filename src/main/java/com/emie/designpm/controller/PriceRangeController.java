package com.emie.designpm.controller;

import com.emie.designpm.entity.PriceRange;
import com.emie.designpm.repository.PriceRangeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/price-ranges")
public class PriceRangeController {

    private final PriceRangeRepository repo;

    public PriceRangeController(PriceRangeRepository repo) { this.repo = repo; }

    @GetMapping
    public ResponseEntity<List<PriceRange>> listActive() {
        return ResponseEntity.ok(repo.findByActiveTrueOrderBySortOrderAsc());
    }

    @GetMapping("/all")
    public ResponseEntity<List<PriceRange>> listAll() {
        return ResponseEntity.ok(repo.findAllByOrderBySortOrderAsc());
    }

    @PostMapping
    public ResponseEntity<PriceRange> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        String name = body.get("name");
        if (name == null || name.isBlank()) return ResponseEntity.badRequest().build();
        int sortOrder = 0;
        if (body.containsKey("sortOrder")) {
            try { sortOrder = Integer.parseInt(body.get("sortOrder")); } catch (NumberFormatException ignored) {}
        }
        return ResponseEntity.ok(repo.save(new PriceRange(name.trim(), sortOrder)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PriceRange> update(@PathVariable Long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        PriceRange item = repo.findById(id).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        if (body.containsKey("name")) item.setName(body.get("name").trim());
        if (body.containsKey("sortOrder")) {
            try { item.setSortOrder(Integer.parseInt(body.get("sortOrder"))); } catch (NumberFormatException ignored) {}
        }
        if (body.containsKey("active")) item.setActive("true".equals(body.get("active")));
        return ResponseEntity.ok(repo.save(item));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
