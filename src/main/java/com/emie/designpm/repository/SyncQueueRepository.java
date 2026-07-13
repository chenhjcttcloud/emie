package com.emie.designpm.repository;

import com.emie.designpm.entity.SyncQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SyncQueueRepository extends JpaRepository<SyncQueue, Long> {

    List<SyncQueue> findTop20ByStatusOrderByCreatedAtAsc(String status);

    List<SyncQueue> findByEntityTypeAndEntityIdAndStatusIn(String entityType, Long entityId, Collection<String> statuses);

    Optional<SyncQueue> findFirstByEntityTypeAndEntityIdAndStatusOrderByCreatedAtDesc(
            String entityType, Long entityId, String status);

    long countByStatus(String status);

    long countByStatusAndRetryCountGreaterThanEqual(String status, int retryCount);
}
