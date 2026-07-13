package com.emie.designpm.controller;

import com.emie.designpm.service.FileArchiveService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 文件归档管理接口（管理员）
 */
@RestController
@RequestMapping("/api/admin/files")
public class FileArchiveController {

    private final FileArchiveService fileArchiveService;

    public FileArchiveController(FileArchiveService fileArchiveService) {
        this.fileArchiveService = fileArchiveService;
    }

    /** 获取存储统计 */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        try {
            return ResponseEntity.ok(fileArchiveService.getStorageStats());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "获取存储统计失败"));
        }
    }

    /** 获取已归档文件列表 */
    @GetMapping("/archived")
    public ResponseEntity<?> getArchived() {
        return ResponseEntity.ok(fileArchiveService.getArchivedFiles());
    }

    /** 手动触发归档 */
    @PostMapping("/archive")
    public ResponseEntity<?> manualArchive() {
        Map<String, Object> result = fileArchiveService.manualArchive();
        return ResponseEntity.ok(result);
    }

    /** 恢复指定文件 */
    @PostMapping("/restore/{fileId}")
    public ResponseEntity<?> restoreFile(@PathVariable Long fileId) {
        try {
            fileArchiveService.restoreFile(fileId);
            return ResponseEntity.ok(Map.of("message", "文件已恢复"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "恢复失败，请稍后重试"));
        }
    }
}
