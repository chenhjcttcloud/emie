package com.emie.designpm.service;

import com.emie.designpm.repository.SyncQueueRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.SQLException;

/** 运行时压力告警。当前先记录结构化日志，后续可接飞书/邮件告警通道。 */
@Service
public class RuntimeAlertService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeAlertService.class);
    private final SyncQueueRepository syncQueue;
    private final DataSource dataSource;
    private final int jvmPercent;
    private final int queueSize;

    public RuntimeAlertService(SyncQueueRepository syncQueue, DataSource dataSource,
                               @Value("${monitoring.jvm-used-percent:85}") int jvmPercent,
                               @Value("${monitoring.sync-queue-pending:100}") int queueSize) {
        this.syncQueue = syncQueue;
        this.dataSource = dataSource;
        this.jvmPercent = jvmPercent;
        this.queueSize = queueSize;
    }

    @Scheduled(fixedDelayString = "${monitoring.alert-interval-ms:60000}")
    public void check() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        int usedPercent = (int) (used * 100 / Math.max(1L, rt.maxMemory()));
        if (usedPercent >= jvmPercent) log.warn("运行时告警 type=jvm_memory usedPercent={} threshold={} usedMb={} maxMb={}",
                usedPercent, jvmPercent, used / 1024 / 1024, rt.maxMemory() / 1024 / 1024);

        long pending = syncQueue.countByStatus("pending");
        long processing = syncQueue.countByStatus("processing");
        long failed = syncQueue.countByStatus("fail");
        if (pending >= queueSize || failed > 0) log.warn("运行时告警 type=feishu_sync_queue pending={} processing={} failed={} threshold={}",
                pending, processing, failed, queueSize);

        try {
            HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
            int active = hikari.getHikariPoolMXBean().getActiveConnections();
            int total = hikari.getHikariPoolMXBean().getTotalConnections();
            if (total > 0 && active * 100 / total >= 85) log.warn("运行时告警 type=db_pool active={} total={} idle={} waiting={}",
                    active, total, hikari.getHikariPoolMXBean().getIdleConnections(), hikari.getHikariPoolMXBean().getThreadsAwaitingConnection());
        } catch (SQLException | RuntimeException ignored) {
            log.debug("无法读取数据库连接池指标", ignored);
        }
    }
}
