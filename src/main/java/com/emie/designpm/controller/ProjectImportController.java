package com.emie.designpm.controller;

import com.emie.designpm.service.ProjectExcelImportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 管理员项目导入接口：预览不写库，确认导入前会再次完整校验。 */
@RestController
@RequestMapping("/api/project-import")
public class ProjectImportController {
    private final ProjectExcelImportService importService;

    public ProjectImportController(ProjectExcelImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws Exception {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).body(java.util.Map.of("error", "仅管理员可导入项目"));
        try (var input = file.getInputStream()) {
            return ResponseEntity.ok(importService.preview(input));
        }
    }

    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws Exception {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).body(java.util.Map.of("error", "仅管理员可导入项目"));
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        ProjectExcelImportService.ImportResult result;
        try (var input = file.getInputStream()) {
            result = importService.importWorkbook(input, session.userId(), session.name());
        }
        if (!result.errors().isEmpty()) return ResponseEntity.badRequest().body(result);
        return ResponseEntity.ok(result);
    }
}
