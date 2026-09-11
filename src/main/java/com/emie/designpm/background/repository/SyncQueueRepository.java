package com.emie.designpm.background.repository;

import com.emie.designpm.entity.SyncQueue;
import com.emie.designpm.sync.repository.SyncQueueOperations;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Worker-side queue repository. Queue consumption/status updates use the background pool. */
public interface SyncQueueRepository extends JpaRepository<SyncQueue, Long>, SyncQueueOperations {
    @Override
    default <S extends SyncQueue> S saveQueue(S entity) {
        return save(entity);
    }

    @Override
    List<SyncQueue> findByStatusAndUpdatedAtBefore(String status, java.time.LocalDateTime cutoff);
}
