package com.emie.designpm.service;

import com.emie.designpm.entity.SyncQueue;
import com.emie.designpm.repository.SyncQueueRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncQueueServiceTest {

    @Test
    void statsExposeQueueHealthAndLatestFailure() {
        SyncQueueRepository repository = mock(SyncQueueRepository.class);
        LocalDateTime successAt = LocalDateTime.now().minusMinutes(2);
        LocalDateTime failureAt = LocalDateTime.now().minusMinutes(1);
        SyncQueue done = SyncQueue.builder().id(10L).status("done").updatedAt(successAt).build();
        SyncQueue failed = SyncQueue.builder().id(11L).status("fail").entityType("sub_task")
                .entityId(42L).retryCount(3).errorMsg("timeout").updatedAt(failureAt).build();
        when(repository.countByStatus("pending")).thenReturn(2L);
        when(repository.countByStatus("processing")).thenReturn(1L);
        when(repository.countByStatus("done")).thenReturn(100L);
        when(repository.countByStatus("fail")).thenReturn(4L);
        when(repository.countByStatusAndRetryCountGreaterThanEqual("fail", 3)).thenReturn(2L);
        when(repository.findTopByStatusOrderByUpdatedAtDesc("done")).thenReturn(Optional.of(done));
        when(repository.findTopByStatusOrderByUpdatedAtDesc("fail")).thenReturn(Optional.of(failed));

        Map<String, Object> stats = new SyncQueueService(repository).getStats();

        assertEquals(2L, stats.get("pending"));
        assertEquals(1L, stats.get("processing"));
        assertEquals(successAt, stats.get("lastSuccessAt"));
        assertEquals(failureAt, stats.get("lastFailureAt"));
        assertEquals(42L, ((Map<?, ?>) stats.get("lastFailure")).get("entityId"));
        assertEquals("timeout", ((Map<?, ?>) stats.get("lastFailure")).get("error"));
    }
}
