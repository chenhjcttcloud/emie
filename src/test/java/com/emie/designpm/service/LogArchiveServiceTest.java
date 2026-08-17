package com.emie.designpm.service;

import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.repository.ActivityLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * LogArchiveService：临时文件 + ATOMIC_MOVE 原子 rename 的幂等归档语义，
 * 以及 ReentrantLock 串行化「读日志 → 写归档文件 → 删库」整段流程的互斥保证。
 */
class LogArchiveServiceTest {

    @TempDir
    Path archiveDir;

    @Test
    void existingFinalArchiveSkipsRewriteAndCleansResidualDbRecords() throws Exception {
        YearMonth month = YearMonth.of(2025, 12);
        Path finalFile = archiveDir.resolve("logs_2025-12.json.gz");
        Files.writeString(finalFile, "existing-archive");
        ActivityLogRepository repository = mock(ActivityLogRepository.class);
        ActivityLog log = log(1L, LocalDateTime.of(2025, 12, 3, 10, 0));
        when(repository.findByTimeBetween(any(), any())).thenReturn(List.of(log));

        boolean archived = service(repository).archiveMonth(month);

        // 最终文件已存在（上次 rename 成功但可能删库失败）→ 幂等跳过，只兜底清理残留库记录
        assertFalse(archived);
        assertEquals("existing-archive", Files.readString(finalFile), "不得重复写盘覆盖既有归档");
        verify(repository).deleteAll(List.of(log));
        verify(repository).flush();
    }

    @Test
    void interruptedWriteKeepsDbRecordsAndAllowsRetryAfterRecovery() throws Exception {
        YearMonth month = YearMonth.of(2025, 12);
        // 归档目录缺失/不可写：写临时文件阶段即中断（等价于磁盘满、目录被删等写盘中断场景，
        // 不会留下最终文件，也不会删库）
        Path realDir = archiveDir.resolve("real");
        ActivityLogRepository repository = mock(ActivityLogRepository.class);
        ActivityLog log = log(1L, LocalDateTime.of(2025, 12, 3, 10, 0));
        when(repository.findByTimeBetween(any(), any())).thenReturn(List.of(log));

        LogArchiveService service = new LogArchiveService(repository);
        ReflectionTestUtils.setField(service, "archiveDir", realDir.toString());
        RuntimeException error = assertThrows(RuntimeException.class, () -> service.archiveMonth(month));

        // 写盘中断：不删库记录，不残留任何文件
        assertTrue(error.getMessage().startsWith("归档日志失败"), error.getMessage());
        verify(repository, never()).deleteAll(any());
        verify(repository, never()).flush();
        assertFalse(Files.exists(realDir.resolve("logs_2025-12.json.gz")));

        // 目录恢复后重试成功：未被删除的库记录可以再次完整归档（临时文件 + 原子 rename）
        Files.createDirectories(realDir);
        assertTrue(service.archiveMonth(month));
        assertTrue(Files.exists(realDir.resolve("logs_2025-12.json.gz")));
        verify(repository).deleteAll(List.of(log));
        verify(repository).flush();
    }

    @Test
    void concurrentArchiveCallsSerializeToOneWriteViaReentrantLock() throws Exception {
        YearMonth month = YearMonth.of(2025, 11);
        ActivityLogRepository repository = mock(ActivityLogRepository.class);
        ActivityLog log = log(1L, LocalDateTime.of(2025, 11, 5, 9, 0));
        when(repository.findByTimeBetween(any(), any())).thenReturn(List.of(log));
        LogArchiveService service = service(repository);

        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                results.add(service.archiveMonth(month));
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : workers) {
            thread.join(10_000);
        }

        // ReentrantLock 串行化：两次调用恰好一次真实写盘 + 一次幂等跳过，归档文件保持完整有效
        assertEquals(2, results.size());
        assertEquals(1, results.stream().filter(Boolean.TRUE::equals).count(), "仅一次真实写盘");
        assertEquals(1, results.stream().filter(Boolean.FALSE::equals).count(), "另一次幂等跳过");
        Path finalFile = archiveDir.resolve("logs_2025-11.json.gz");
        assertTrue(Files.exists(finalFile));
        try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(finalFile))) {
            String json = new String(gz.readAllBytes(), "UTF-8");
            assertTrue(json.contains("并发归档测试"));
        }
        verify(repository, times(2)).deleteAll(anyList());
        verify(repository, times(2)).flush();
    }

    @Test
    void archiveLockHeldByAnotherThreadBlocksArchiveUntilReleased() throws Exception {
        ActivityLogRepository repository = mock(ActivityLogRepository.class);
        ActivityLog log = log(1L, LocalDateTime.of(2025, 10, 5, 9, 0));
        when(repository.findByTimeBetween(any(), any())).thenReturn(List.of(log));
        LogArchiveService service = service(repository);
        ReentrantLock lock = (ReentrantLock) ReflectionTestUtils.getField(service, "archiveLock");

        // 测试线程模拟另一个持有者占锁：并发归档调用必须阻塞直到锁释放
        lock.lock();
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(service.archiveMonth(YearMonth.of(2025, 10))));
        worker.start();
        Thread.sleep(300);
        assertTrue(worker.isAlive(), "锁被持有期间归档调用必须阻塞");
        lock.unlock();
        worker.join(5_000);
        assertEquals(Boolean.TRUE, result.get());
        assertTrue(Files.exists(archiveDir.resolve("logs_2025-10.json.gz")));
    }

    @Test
    void directoryOccupyingArchivePathIsNotTreatedAsArchivedAndDbRecordsSurvive() throws Exception {
        YearMonth month = YearMonth.of(2025, 12);
        // 异常/误操作：归档路径上出现与最终文件名同名的【目录】（Files.exists 对目录也返回 true）
        Path dirPath = archiveDir.resolve("logs_2025-12.json.gz");
        Files.createDirectories(dirPath);
        ActivityLogRepository repository = mock(ActivityLogRepository.class);
        ActivityLog log = log(1L, LocalDateTime.of(2025, 12, 3, 10, 0));
        when(repository.findByTimeBetween(any(), any())).thenReturn(List.of(log));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service(repository).archiveMonth(month));

        // 目录绝不能触发幂等“已归档”判定：不得删库（旧 Files.exists 实现会误判并删库丢数据）。
        // 写盘在 rename 阶段因目标为目录而失败 → 抛归档失败异常，库记录保留可重试。
        assertTrue(error.getMessage().startsWith("归档日志失败"), error.getMessage());
        verify(repository, never()).deleteAll(any());
        verify(repository, never()).flush();
    }

    private LogArchiveService service(ActivityLogRepository repository) {
        LogArchiveService service = new LogArchiveService(repository);
        ReflectionTestUtils.setField(service, "archiveDir", archiveDir.toString());
        service.init();
        return service;
    }

    private ActivityLog log(long id, LocalDateTime time) {
        ActivityLog log = new ActivityLog("并发归档测试", "张三", "planner");
        log.setId(id);
        log.setTime(time);
        log.setProjectRefId(1L);
        return log;
    }
}
