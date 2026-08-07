package com.emie.designpm.repository;

import com.emie.designpm.entity.SyncQueue;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Common queue operations shared by the primary enqueue repository and worker repository. */
public interface SyncQueueOperations {
    /**
     * Fetch pending work that is either new (no retry schedule yet) or whose retry delay has elapsed.
     * The status parameter is repeated because Spring Data derives the OR branches independently.
     */
    List<SyncQueue> findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
            String newItemStatus, String retryStatus, java.time.LocalDateTime now);
    List<SyncQueue> findByEntityTypeAndEntityIdAndStatusIn(String entityType, Long entityId, Collection<String> statuses);
    Optional<SyncQueue> findFirstByEntityTypeAndEntityIdAndStatusOrderByCreatedAtDesc(String entityType, Long entityId, String status);
    long countByStatus(String status);
    long countByStatusAndRetryCountGreaterThanEqual(String status, int retryCount);
    Optional<SyncQueue> findTopByStatusOrderByUpdatedAtDesc(String status);
    Optional<SyncQueue> findTopByStatusOrderByCreatedAtDesc(String status);
    List<SyncQueue> findByStatusAndUpdatedAtBefore(String status, java.time.LocalDateTime cutoff);
    <S extends SyncQueue> S saveQueue(S entity);
}
