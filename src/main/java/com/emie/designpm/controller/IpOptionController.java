package com.emie.designpm.controller;

import com.emie.designpm.entity.IpOption;
import com.emie.designpm.repository.IpOptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/ip-options")
public class IpOptionController {

    private static final ObjectMapper JSON = new ObjectMapper();
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
            IpOption item = new IpOption(name, parseSortOrder(body.get("sortOrder")));
            applySubOptions(item, body);
            return ResponseEntity.ok(repository.save(item));
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
        if (body.containsKey("subOptions") || body.containsKey("subOptionSelectionMode")) applySubOptions(item, body);
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

    private static void applySubOptions(IpOption item, Map<String, String> body) {
        List<String> subOptions = normalizeSubOptions(body.get("subOptions"));
        String mode = "single".equals(body.get("subOptionSelectionMode")) ? "single" : "multiple";
        try {
            item.setSubOptionsJson(JSON.writeValueAsString(subOptions));
            item.setSubOptionSelectionMode(mode);
        } catch (Exception e) {
            throw new IllegalArgumentException("二级IP选项格式无效", e);
        }
    }

    private static List<String> normalizeSubOptions(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        Stream.of(raw.split("[,，\\n\\r]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(value -> {
                    if (value.length() > 100) throw new IllegalArgumentException("二级IP名称不能超过100个字符");
                    if (!result.contains(value)) result.add(value);
                });
        if (result.size() > 50) throw new IllegalArgumentException("单个IP最多配置50个二级选项");
        return result;
    }
}
