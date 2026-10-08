package com.emie.designpm.performance.controller;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.DesignerPerformanceExportService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 设计师月度绩效表：列出、生成、下载（仅管理员）。 */
@RestController
@RequestMapping("/api/performance/exports")
public class PerformanceExportController {
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final DesignerPerformanceExportService exports;

    public PerformanceExportController(DesignerPerformanceExportService exports) {
        this.exports = exports;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        if (!admin(request)) return forbidden();
        return ResponseEntity.ok(exports.list());
    }

    @PostMapping("/{month}")
    public ResponseEntity<?> generate(@PathVariable String month, HttpServletRequest request) {
        AuthSession session = (AuthSession) request.getAttribute("authSession");
        if (session == null || !"admin".equals(session.role())) return forbidden();
        try {
            return ResponseEntity.accepted().body(exports.startGeneration(parse(month), session.name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{month}")
    public ResponseEntity<?> generationStatus(@PathVariable String month, HttpServletRequest request) {
        if (!admin(request)) return forbidden();
        try {
            return ResponseEntity.ok(exports.generationStatus(parse(month)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{month}/files/{userId}")
    public ResponseEntity<?> download(
            @PathVariable String month, @PathVariable String userId, HttpServletRequest request) {
        if (!admin(request)) return forbidden();
        try {
            Path file = exports.designerFile(parse(month), userId).orElse(null);
            if (file == null) return ResponseEntity.status(404).body(Map.of("error", "该设计师的绩效表不存在，请先生成"));
            return ResponseEntity.ok()
                    .contentType(XLSX)
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            disposition(file.getFileName().toString()))
                    .body(new FileSystemResource(file));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{month}/zip")
    public ResponseEntity<StreamingResponseBody> zip(@PathVariable String month, HttpServletRequest request) {
        if (!admin(request)) return ResponseEntity.status(403).build();
        try {
            YearMonth ym = parse(month);
            if (exports.manifest(ym).isEmpty()) return ResponseEntity.notFound().build();
            StreamingResponseBody body = out -> exports.writeZip(ym, out);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition(ym + "_设计师绩效考评表.zip"))
                    .body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    private static YearMonth parse(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("月份格式应为YYYY-MM");
        }
    }

    private static String disposition(String fileName) {
        String ascii = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private static boolean admin(HttpServletRequest request) {
        AuthSession session = (AuthSession) request.getAttribute("authSession");
        return session != null && "admin".equals(session.role());
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "仅管理员可查看设计师绩效表"));
    }
}
