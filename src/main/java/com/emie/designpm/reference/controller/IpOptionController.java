package com.emie.designpm.reference.controller;

import com.emie.designpm.auth.AuthSessions;
import com.emie.designpm.entity.IpOption;
import com.emie.designpm.reference.dto.IpOptionUpsertRequest;
import com.emie.designpm.reference.repository.IpOptionRepository;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(repository.findAllByOrderBySortOrderAsc());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody IpOptionUpsertRequest body, HttpServletRequest request) {
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        String name = normalizeName(body.name());
        if (name == null) return ResponseEntity.badRequest().body(Map.of("error", "请输入IP名称"));
        if (repository.findByName(name).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "IP名称已存在"));
        }
        try {
            IpOption item = new IpOption(name, parseSortOrder(body.sortOrder()));
            applySubOptions(item, body);
            return ResponseEntity.ok(repository.save(item));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "IP名称已存在"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id, @RequestBody IpOptionUpsertRequest body, HttpServletRequest request) {
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        IpOption item = repository.findById(id).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        if (body.name() != null) {
            String name = normalizeName(body.name());
            if (name == null) return ResponseEntity.badRequest().body(Map.of("error", "请输入IP名称"));
            if (repository
                    .findByName(name)
                    .filter(existing -> !existing.getId().equals(id))
                    .isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "IP名称已存在"));
            }
            item.setName(name);
        }
        if (body.sortOrder() != null) item.setSortOrder(parseSortOrder(body.sortOrder()));
        if (body.active() != null) item.setActive(Boolean.parseBoolean(body.active()));
        if (body.subOptions() != null || body.subOptionSelectionMode() != null) applySubOptions(item, body);
        try {
            return ResponseEntity.ok(repository.save(item));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "IP名称已存在"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        if (!AuthSessions.isAdmin(request)) return ResponseEntity.status(403).build();
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "IP配置已删除"));
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) return null;
        // 原始用户输入入库前清洗（列长 100，与 IpOption.name 的 @Column(length=100) 一致）
        String name = SecurityUtil.sanitizeText(value, 100);
        return name.isBlank() ? null : name;
    }

    private static int parseSortOrder(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void applySubOptions(IpOption item, IpOptionUpsertRequest body) {
        List<String> subOptions = normalizeSubOptions(body.subOptions());
        String mode = "single".equals(body.subOptionSelectionMode()) ? "single" : "multiple";
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
