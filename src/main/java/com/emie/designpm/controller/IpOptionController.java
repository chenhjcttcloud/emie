package com.emie.designpm.controller;

import com.emie.designpm.entity.IpOption;
import com.emie.designpm.repository.IpOptionRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ip-options")
public class IpOptionController {

    private final IpOptionRepository repository;

    public IpOptionController(IpOptionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<List<IpOption>> listActive() {
        return ResponseEntity.ok(repository.findByActiveTrueOrderBySortOrderAsc());
    }

    @GetMapping("/all")
    public ResponseEntity<List<IpOption>> listAll(HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(repository.findAllByOrderBySortOrderAsc());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        String name = normalizeName(body.get("name"));
        if (name == null) return ResponseEntity.badRequest().body(Map.of("error", "请输入IP名称"));
        if (repository.findByName(name).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "IP名称已存在"));
        }
        try {
            return ResponseEntity.ok(repository.save(new IpOption(name, parseSortOrder(body.get("sortOrder")))));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "IP名称已存在"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody Map<String, String> body,
                                    HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        IpOption item = repository.findById(id).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        if (body.containsKey("name")) {
            String name = normalizeName(body.get("name"));
            if (name == null) return ResponseEntity.badRequest().body(Map.of("error", "请输入IP名称"));
            if (repository.findByName(name).filter(existing -> !existing.getId().equals(id)).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "IP名称已存在"));
            }
            item.setName(name);
        }
        if (body.containsKey("sortOrder")) item.setSortOrder(parseSortOrder(body.get("sortOrder")));
        if (body.containsKey("active")) item.setActive(Boolean.parseBoolean(body.get("active")));
        try {
            return ResponseEntity.ok(repository.save(item));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "IP名称已存在"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "IP配置已删除"));
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) return null;
        String name = value.trim();
        return name.length() <= 100 ? name : null;
    }

    private static int parseSortOrder(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
