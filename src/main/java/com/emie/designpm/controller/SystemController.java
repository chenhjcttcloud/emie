package com.emie.designpm.controller;

import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.service.LogArchiveService;
import com.emie.designpm.repository.SyncQueueRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.emie.designpm.dto.PageResponse;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final ActivityLogRepository activityLogRepository;
    private final LogArchiveService logArchiveService;
    private final SyncQueueRepository syncQueueRepository;
    private final com.emie.designpm.service.DataIntegrityService dataIntegrityService;

    public SystemController(ActivityLogRepository activityLogRepository,
                            LogArchiveService logArchiveService,
                            SyncQueueRepository syncQueueRepository,
                            com.emie.designpm.service.DataIntegrityService dataIntegrityService) {
        this.activityLogRepository = activityLogRepository;
        this.logArchiveService = logArchiveService;
        this.syncQueueRepository = syncQueueRepository;
        this.dataIntegrityService = dataIntegrityService;
    }

    /** 只读数据完整性报告，管理员手动执行。 */
    @GetMapping("/data-integrity")
    public ResponseEntity<Map<String, Object>> dataIntegrity(HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(dataIntegrityService.scan());
    }

    /** 管理员运行指标：用于快速判断 JVM、队列和同步是否有压力。 */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics(HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> result = new HashMap<>();
        result.put("jvmUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        result.put("jvmMaxMb", runtime.maxMemory() / 1024 / 1024);
        result.put("processors", runtime.availableProcessors());
        if (syncQueueRepository != null) {
            result.put("syncPending", syncQueueRepository.countByStatus("pending"));
            result.put("syncProcessing", syncQueueRepository.countByStatus("processing"));
            result.put("syncFailed", syncQueueRepository.countByStatus("fail"));
        }
        return ResponseEntity.ok(result);
    }

    /** 获取系统操作日志，支持日期范围筛选（自动合并数据库 + 归档文件） */
    @GetMapping("/logs")
    public ResponseEntity<PageResponse<Map<String, Object>>> getSystemLogs(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            HttpServletRequest request) {

        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();

        LocalDateTime start, end;

        if (startDate != null && !startDate.isBlank() && endDate != null && !endDate.isBlank()) {
            start = LocalDate.parse(startDate).atStartOfDay();
            end = LocalDate.parse(endDate).atTime(LocalTime.MAX);
        } else if (startDate != null && !startDate.isBlank()) {
            start = LocalDate.parse(startDate).atStartOfDay();
            end = LocalDateTime.now();
        } else {
            // 默认最近 7 天
            start = LocalDateTime.now().minusDays(7);
            end = LocalDateTime.now();
        }

        List<Map<String, Object>> result = logArchiveService.queryLogs(start, end);
        int safeSize = Math.min(Math.max(size, 10), 100);
        int safePage = Math.max(page, 0);
        int total = result.size();
        int from = Math.min(safePage * safeSize, total);
        int to = Math.min(from + safeSize, total);
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        return ResponseEntity.ok(new PageResponse<>(result.subList(from, to), safePage, safeSize, total, totalPages));
    }

    /** 手动触发归档指定月份（管理员用） */
    @PostMapping("/archive")
    public ResponseEntity<Map<String, Object>> triggerArchive(@RequestBody Map<String, String> body,
                                                               HttpServletRequest request) {
        if (!AuthController.isAdmin(request)) return ResponseEntity.status(403).build();
        String yearMonth = body.get("yearMonth");
        if (yearMonth == null || yearMonth.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请指定年月（yyyy-MM）"));
        }
        try {
            YearMonth ym = YearMonth.parse(yearMonth);
            boolean archived = logArchiveService.archiveMonth(ym);
            Map<String, Object> result = new HashMap<>();
            result.put("archived", archived);
            result.put("yearMonth", yearMonth);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "归档失败，请稍后重试"));
        }
    }
}
