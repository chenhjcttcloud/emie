package com.emie.designpm.controller;

import com.emie.designpm.entity.ComplianceItem;
import com.emie.designpm.repository.ComplianceItemRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final ComplianceItemRepository repo;

    public ComplianceController(ComplianceItemRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<List<ComplianceItem>> listActive() {
        return ResponseEntity.ok(repo.findByActiveTrueOrderBySortOrderAsc());
    }

    @GetMapping("/all")
    public ResponseEntity<List<ComplianceItem>> listAll() {
        return ResponseEntity.ok(repo.findAllByOrderBySortOrderAsc());
    }

    @PostMapping
    public ResponseEntity<ComplianceItem> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        int sortOrder = 0;
        if (body.containsKey("sortOrder")) {
            try { sortOrder = Integer.parseInt(body.get("sortOrder")); } catch (NumberFormatException ignored) {}
        }
        ComplianceItem item = new ComplianceItem(name.trim(), sortOrder);
        return ResponseEntity.ok(repo.save(item));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ComplianceItem> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        ComplianceItem item = repo.findById(id).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();

        if (body.containsKey("name")) item.setName(body.get("name").trim());
        if (body.containsKey("sortOrder")) {
            try { item.setSortOrder(Integer.parseInt(body.get("sortOrder"))); } catch (NumberFormatException ignored) {}
        }
        if (body.containsKey("active")) item.setActive("true".equals(body.get("active")));
        return ResponseEntity.ok(repo.save(item));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
