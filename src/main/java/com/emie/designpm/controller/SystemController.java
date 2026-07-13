package com.emie.designpm.controller;

import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.service.LogArchiveService;
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

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final ActivityLogRepository activityLogRepository;
    private final LogArchiveService logArchiveService;

    public SystemController(ActivityLogRepository activityLogRepository,
                            LogArchiveService logArchiveService) {
        this.activityLogRepository = activityLogRepository;
        this.logArchiveService = logArchiveService;
    }

    /** 获取系统操作日志，支持日期范围筛选（自动合并数据库 + 归档文件） */
    @GetMapping("/logs")
    public ResponseEntity<List<Map<String, Object>>> getSystemLogs(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
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
        return ResponseEntity.ok(result);
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
