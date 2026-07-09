package com.emie.designpm.repository;

import com.emie.designpm.entity.SyncQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SyncQueueRepository extends JpaRepository<SyncQueue, Long> {

    List<SyncQueue> findTop20ByStatusOrderByCreatedAtAsc(String status);

    List<SyncQueue> findByEntityTypeAndEntityIdAndStatusIn(String entityType, Long entityId, Collection<String> statuses);

    long countByStatus(String status);

    long countByStatusAndRetryCountGreaterThanEqual(String status, int retryCount);
}
