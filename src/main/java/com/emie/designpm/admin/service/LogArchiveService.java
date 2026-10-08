package com.emie.designpm.admin.service;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.entity.ActivityLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 日志归档服务。
 * 每月1日凌晨自动将上个月的日志从数据库导出为 gzip 压缩的 JSON 文件，
 * 并删除数据库中的已归档记录。
 * 查询日志时自动合并数据库 + 归档文件。
 */
@Service
public class LogArchiveService {

    private final ActivityLogRepository activityLogRepository;
    private final ObjectMapper objectMapper;
    /** 归档互斥锁：串行化「读日志 → 写归档文件 → 删库」整段流程，防止并发归档互相覆盖损坏文件。 */
    private final ReentrantLock archiveLock = new ReentrantLock();

    @Value("${app.log-archive.dir:logs/archive}")
    private String archiveDir;

    private static final DateTimeFormatter FILE_DTF = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter LOG_DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public LogArchiveService(ActivityLogRepository activityLogRepository) {
        this.activityLogRepository = activityLogRepository;
        this.objectMapper = new ObjectMapper();
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
        archiveLock.lock();
        try {
            return archiveMonthLocked(yearMonth);
        } finally {
            archiveLock.unlock();
        }
    }

    private boolean archiveMonthLocked(YearMonth yearMonth) {
        LocalDateTime start = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime end = yearMonth.atEndOfMonth().atTime(LocalTime.MAX);

        String fileName = "logs_" + yearMonth.format(FILE_DTF) + ".json.gz";
        Path filePath = Path.of(archiveDir, fileName);

        List<ActivityLog> logs = activityLogRepository.findByTimeBetween(start, end);
        // 幂等检查仅对「rename 完成的最终文件」生效：最终文件已存在说明该月已归档成功
        // （或上次写盘成功但删库失败），此时不再重复写盘，只兜底清理残留的数据库记录。
        // 写盘中途崩溃/磁盘满只会留下 .tmp 临时文件，不会触发幂等，下次可重新完整写入。
        // 用 isRegularFile 而非 exists：与最终文件名同名的目录（异常/误操作创建）不能当作
        // “已归档”标志，否则会误判已归档并删库丢数据；isRegularFile 会走正常写盘，
        // rename 遇到同名目录时失败抛异常（归档日志失败），库记录保留可重试。
        if (Files.isRegularFile(filePath)) {
            if (!logs.isEmpty()) {
                activityLogRepository.deleteAll(logs);
                activityLogRepository.flush();
            }
            return false;
        }
        if (logs.isEmpty()) return false;

        // 先写临时文件，全部写完后再原子 rename 到最终路径；临时文件命名带线程 id 与
        // 纳秒时间戳，避免并发/崩溃残留的临时文件互相冲突（残留临时文件可被安全覆盖重写）。
        Path tmpPath = filePath.resolveSibling(
                fileName + ".tmp." + Thread.currentThread().getId() + "." + System.nanoTime());

        try {
            // 转为 JSON 并压缩写入临时文件
            List<Map<String, Object>> archiveEntries = new ArrayList<>();
            for (ActivityLog l : logs) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", l.getId());
                entry.put("time", l.getTime().format(LOG_DTF));
                entry.put("action", l.getAction());
                entry.put("username", l.getUsername());
                entry.put("role", l.getRole());
                entry.put("projectId", l.getProject() != null ? l.getProject().getId() : l.getProjectRefId());
                entry.put("entityType", l.getEntityType());
                entry.put("entityId", l.getEntityId());
                entry.put("beforeData", l.getBeforeData());
                entry.put("afterData", l.getAfterData());
                entry.put("changedFields", l.getChangedFields());
                archiveEntries.add(entry);
            }
            byte[] json = objectMapper.writeValueAsBytes(archiveEntries);

            try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(tmpPath))) {
                gz.write(json);
            }

            // 原子 rename：同一文件系统内的 rename 原子完成，最终文件要么完整存在要么不存在，
            // 不存在“残缺最终文件”被误判为已归档的状态；个别文件系统不支持 ATOMIC_MOVE 时回退普通 move。
            try {
                Files.move(tmpPath, filePath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING);
            }

            // 删除已归档的数据库记录
            activityLogRepository.deleteAll(logs);
            activityLogRepository.flush();

            return true;
        } catch (IOException e) {
            // 清理本次临时文件（尽力而为）；残留临时文件不影响下次重新归档
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) {
                /* ignore */
            }
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
            m.put("entityType", l.getEntityType());
            m.put("entityId", l.getEntityId());
            m.put("beforeData", l.getBeforeData());
            m.put("afterData", l.getAfterData());
            m.put("changedFields", l.getChangedFields());
            result.add(m);
        }

        // 2. 查询归档文件中的日志
        // 计算查询范围涉及的所有月份
        YearMonth current = YearMonth.from(start);
        YearMonth endMonth = YearMonth.from(end);
        while (!current.isAfter(endMonth)) {
            String fileName = "logs_" + current.format(FILE_DTF) + ".json.gz";
            Path filePath = Path.of(archiveDir, fileName);
            // 仅读取常规归档文件；同名目录（异常/误操作）不作为归档数据读取
            if (Files.isRegularFile(filePath)) {
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
    private List<Map<String, Object>> readArchiveFile(Path filePath, LocalDateTime start, LocalDateTime end)
            throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        String json;
        try (GZIPInputStream gz = new GZIPInputStream(new FileInputStream(filePath.toFile()))) {
            json = new String(gz.readAllBytes(), "UTF-8");
        }

        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) return result;
        List<Map<String, Object>> items = objectMapper.readValue(json, new TypeReference<>() {});
        for (Map<String, Object> m : items) {
            if (m.get("projectId") == null && m.get("projectRefId") != null) {
                m.put("projectId", m.get("projectRefId"));
            }
            m.putIfAbsent("projectType", "");
            m.putIfAbsent("projectRequirement", "");
            String timeStr = Objects.toString(m.get("time"), null);
            if (timeStr != null) {
                LocalDateTime logTime = LocalDateTime.parse(timeStr, LOG_DTF);
                if (!logTime.isBefore(start) && !logTime.isAfter(end)) {
                    result.add(m);
                }
            }
        }
        return result;
    }
}
