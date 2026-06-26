package com.emie.designpm.service;

import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.repository.ActivityLogRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 日志归档服务。
 * 每月1日凌晨自动将上个月的日志从数据库导出为 gzip 压缩的 JSON 文件，
 * 并删除数据库中的已归档记录。
 * 查询日志时自动合并数据库 + 归档文件。
 */
@Service
public class LogArchiveService {

    private final ActivityLogRepository activityLogRepository;

    @Value("${app.log-archive.dir:logs/archive}")
    private String archiveDir;

    private static final DateTimeFormatter FILE_DTF = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter LOG_DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public LogArchiveService(ActivityLogRepository activityLogRepository) {
        this.activityLogRepository = activityLogRepository;
    }

    @PostConstruct
    public void init() {
        // 确保归档目录存在
        try {
            Files.createDirectories(Path.of(archiveDir));
        } catch (IOException e) {
            // ignore
        }
    }

    /**
     * 每月1日 00:01 执行归档
     */
    @Scheduled(cron = "0 1 0 1 * ?")
    public void archivePreviousMonth() {
        LocalDate now = LocalDate.now();
        // 归档上个月
        YearMonth prevMonth = YearMonth.from(now).minusMonths(1);
        archiveMonth(prevMonth);
    }

    /**
     * 归档指定月份的所有日志
     */
    public boolean archiveMonth(YearMonth yearMonth) {
        LocalDateTime start = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime end = yearMonth.atEndOfMonth().atTime(LocalTime.MAX);

        List<ActivityLog> logs = activityLogRepository.findByTimeBetween(start, end);
        if (logs.isEmpty()) return false;

        String fileName = "logs_" + yearMonth.format(FILE_DTF) + ".json.gz";
        Path filePath = Path.of(archiveDir, fileName);

        try {
            // 转为 JSON 并压缩写入
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (ActivityLog l : logs) {
                if (!first) json.append(",");
                first = false;
                json.append("{");
                json.append("\"id\":").append(l.getId()).append(",");
                json.append("\"time\":\"").append(l.getTime().format(LOG_DTF)).append("\",");
                json.append("\"action\":\"").append(escapeJson(l.getAction())).append("\",");
                json.append("\"username\":\"").append(escapeJson(l.getUsername())).append("\",");
                json.append("\"role\":\"").append(escapeJson(l.getRole())).append("\",");
                json.append("\"projectRefId\":").append(l.getProjectRefId() != null ? l.getProjectRefId() : "null");
                json.append("}");
            }
            json.append("]");

            try (GZIPOutputStream gz = new GZIPOutputStream(new FileOutputStream(filePath.toFile()))) {
                gz.write(json.toString().getBytes("UTF-8"));
            }

            // 删除已归档的数据库记录
            activityLogRepository.deleteAll(logs);
            activityLogRepository.flush();

            return true;
        } catch (IOException e) {
            throw new RuntimeException("归档日志失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询指定日期范围内的日志：数据库 + 归档文件
     */
    public List<Map<String, Object>> queryLogs(LocalDateTime start, LocalDateTime end) {
        List<Map<String, Object>> result = new ArrayList<>();

        // 1. 查询数据库中的日志
        List<ActivityLog> dbLogs = activityLogRepository.findByTimeBetween(start, end);
        for (ActivityLog l : dbLogs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("time", l.getTime().format(LOG_DTF));
            m.put("action", l.getAction());
            m.put("username", l.getUsername());
            m.put("role", l.getRole());
            m.put("projectId", l.getProject() != null ? l.getProject().getId() : l.getProjectRefId());
            m.put("projectType", "");
            m.put("projectRequirement", "");
            result.add(m);
        }

        // 2. 查询归档文件中的日志
        // 计算查询范围涉及的所有月份
        YearMonth current = YearMonth.from(start);
        YearMonth endMonth = YearMonth.from(end);
        while (!current.isAfter(endMonth)) {
            String fileName = "logs_" + current.format(FILE_DTF) + ".json.gz";
            Path filePath = Path.of(archiveDir, fileName);
            if (Files.exists(filePath)) {
                try {
                    List<Map<String, Object>> archivedLogs = readArchiveFile(filePath, start, end);
                    result.addAll(archivedLogs);
                } catch (IOException e) {
                    // 忽略损坏的文件
                }
            }
            current = current.plusMonths(1);
        }

        // 按时间降序排列
        result.sort((a, b) -> {
            String ta = (String) a.get("time");
            String tb = (String) b.get("time");
            return tb.compareTo(ta);
        });

        return result;
    }

    /**
     * 读取归档文件，筛选日期范围内的日志
     */
    private List<Map<String, Object>> readArchiveFile(Path filePath, LocalDateTime start, LocalDateTime end) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        String json;
        try (GZIPInputStream gz = new GZIPInputStream(new FileInputStream(filePath.toFile()))) {
            json = new String(gz.readAllBytes(), "UTF-8");
        }

        // 简单 JSON 解析（不引入依赖）
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) return result;
        json = json.substring(1, json.length() - 1);
        if (json.isBlank()) return result;

        // 按顶层逗号分割
        List<String> items = splitJsonArray(json);
        for (String item : items) {
            Map<String, Object> m = parseLogEntry(item);
            if (m == null) continue;
            String timeStr = (String) m.get("time");
            if (timeStr != null) {
                LocalDateTime logTime = LocalDateTime.parse(timeStr, LOG_DTF);
                if (!logTime.isBefore(start) && !logTime.isAfter(end)) {
                    result.add(m);
                }
            }
        }
        return result;
    }

    // ==================== 工具方法 ====================

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 分割 JSON 数组顶层元素
     */
    private List<String> splitJsonArray(String json) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == ',' && depth == 0) {
                items.add(json.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = json.substring(start).trim();
        if (!last.isEmpty()) items.add(last);
        return items;
    }

    /**
     * 解析单个日志 JSON 对象
     */
    private Map<String, Object> parseLogEntry(String json) {
        Map<String, Object> m = new LinkedHashMap<>();
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return null;
        json = json.substring(1, json.length() - 1).trim();

        // 按逗号分割字段（忽略引号内和嵌套的逗号）
        List<String> fields = splitJsonFields(json);
        for (String field : fields) {
            int colon = field.indexOf(':');
            if (colon < 0) continue;
            String key = field.substring(0, colon).trim().replaceAll("^\"|\"$", "");
            String val = field.substring(colon + 1).trim();
            if (val.equals("null")) {
                m.put(key, null);
            } else if (val.startsWith("\"") && val.endsWith("\"")) {
                m.put(key, val.substring(1, val.length() - 1));
            } else {
                // 数字
                try { m.put(key, Long.parseLong(val)); }
                catch (NumberFormatException e) { m.put(key, val); }
            }
        }
        return m;
    }

    private List<String> splitJsonFields(String json) {
        List<String> fields = new ArrayList<>();
        boolean inStr = false;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) inStr = !inStr;
            else if (!inStr) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    fields.add(json.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        if (start < json.length()) fields.add(json.substring(start).trim());
        return fields;
    }
}
