package com.emie.designpm.service;

import com.emie.designpm.entity.RuntimeAlert;
import com.emie.designpm.repository.RuntimeAlertRepository;
import com.emie.designpm.repository.SyncQueueRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 运行时压力告警。当前记录结构化日志，并按告警类型做冷却去重。 */
@Service
public class RuntimeAlertService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeAlertService.class);
    private final SyncQueueRepository syncQueue;
    private final RuntimeAlertRepository alerts;
    private final DataSource dataSource;
    private final DataSource backgroundDataSource;
    private final int jvmPercent;
    private final int queueSize;
    private final Duration cooldown;
    private final Map<String, Instant> lastAlerts = new ConcurrentHashMap<>();

    public RuntimeAlertService(SyncQueueRepository syncQueue, RuntimeAlertRepository alerts, DataSource dataSource,
                               @Autowired(required = false) @Qualifier("backgroundDataSource") DataSource backgroundDataSource,
                               @Value("${monitoring.jvm-used-percent:85}") int jvmPercent,
                               @Value("${monitoring.sync-queue-pending:100}") int queueSize,
                               @Value("${monitoring.alert-cooldown-minutes:10}") long cooldownMinutes) {
        this.syncQueue = syncQueue;
        this.alerts = alerts;
        this.dataSource = dataSource;
        this.backgroundDataSource = backgroundDataSource;
        this.jvmPercent = jvmPercent;
        this.queueSize = queueSize;
        this.cooldown = Duration.ofMinutes(Math.max(1, cooldownMinutes));
    }

    @Scheduled(fixedDelayString = "${monitoring.alert-interval-ms:60000}")
    public void check() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        int usedPercent = (int) (used * 100 / Math.max(1L, rt.maxMemory()));
        if (usedPercent >= jvmPercent) warnOnce("jvm_memory", "运行时告警 type=jvm_memory usedPercent={} threshold={} usedMb={} maxMb={}",
                usedPercent, jvmPercent, used / 1024 / 1024, rt.maxMemory() / 1024 / 1024);
        else recover("jvm_memory");

        long pending = syncQueue.countByStatus("pending");
        long processing = syncQueue.countByStatus("processing");
        long failed = syncQueue.countByStatus("fail");
        if (pending >= queueSize || failed > 0) warnOnce("feishu_sync_queue", "运行时告警 type=feishu_sync_queue pending={} processing={} failed={} threshold={}",
                pending, processing, failed, queueSize);
        else recover("feishu_sync_queue");

        try {
            HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
            int active = hikari.getHikariPoolMXBean().getActiveConnections();
            int total = hikari.getHikariPoolMXBean().getTotalConnections();
            if (total > 0 && active * 100 / total >= 85) warnOnce("db_pool", "运行时告警 type=db_pool active={} total={} idle={} waiting={}",
                    active, total, hikari.getHikariPoolMXBean().getIdleConnections(), hikari.getHikariPoolMXBean().getThreadsAwaitingConnection());
            else recover("db_pool");
            checkPool("db_pool_background", backgroundDataSource);
        } catch (SQLException | RuntimeException ignored) {
            log.debug("无法读取数据库连接池指标", ignored);
        }
    }

    private void checkPool(String type, DataSource source) {
        if (source == null) return;
        try {
            HikariDataSource hikari = source.unwrap(HikariDataSource.class);
            int active = hikari.getHikariPoolMXBean().getActiveConnections();
            int total = hikari.getHikariPoolMXBean().getTotalConnections();
            if (total > 0 && active * 100 / total >= 85) {
                warnOnce(type, "运行时告警 type={} active={} total={} idle={} waiting={}",
                        type, active, total, hikari.getHikariPoolMXBean().getIdleConnections(),
                        hikari.getHikariPoolMXBean().getThreadsAwaitingConnection());
            } else {
                recover(type);
            }
        } catch (SQLException | RuntimeException ignored) {
            log.debug("无法读取{}连接池指标", type, ignored);
        }
    }

    private void warnOnce(String type, String message, Object... args) {
        Instant now = Instant.now();
        Instant previous = lastAlerts.putIfAbsent(type, now);
        if (previous == null || Duration.between(previous, now).compareTo(cooldown) >= 0) {
            lastAlerts.put(type, now);
            log.warn(message, args);
        }
        RuntimeAlert alert = alerts.findByAlertType(type).orElseGet(RuntimeAlert::new);
        LocalDateTime timestamp = LocalDateTime.now();
        if (alert.getFirstSeenAt() == null) alert.setFirstSeenAt(timestamp);
        alert.setAlertType(type);
        alert.setStatus("active");
        alert.setLastSeenAt(timestamp);
        alert.setRecoveredAt(null);
        alert.setDetail(java.util.Arrays.toString(args));
        alerts.save(alert);
    }

    private void recover(String type) {
        alerts.findByAlertType(type).filter(alert -> "active".equals(alert.getStatus())).ifPresent(alert -> {
            alert.setStatus("recovered");
            alert.setRecoveredAt(LocalDateTime.now());
            alerts.save(alert);
            log.info("运行时告警恢复 type={}", type);
        });
    }
}
