package com.emie.designpm.repository;

import com.emie.designpm.entity.NotificationBroadcastJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface NotificationBroadcastJobRepository extends JpaRepository<NotificationBroadcastJob, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE NotificationBroadcastJob j SET j.status = :status, j.resultJson = :resultJson, " +
            "j.error = :error, j.completedAt = :now WHERE j.id = :id AND j.status = 'running' " +
            "AND j.ownerInstanceId = :ownerInstanceId")
    int completeRunningJob(@Param("id") String id, @Param("ownerInstanceId") String ownerInstanceId,
                           @Param("status") String status, @Param("resultJson") String resultJson,
                           @Param("error") String error, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE NotificationBroadcastJob j SET j.status = 'failed', j.error = :error, " +
            "j.completedAt = :now WHERE j.status = 'running'")
    int failInterruptedJobs(@Param("now") LocalDateTime now, @Param("error") String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE NotificationBroadcastJob j SET j.status = 'failed', j.error = :error, " +
            "j.completedAt = :now WHERE j.status = 'running' AND j.ownerInstanceId = :ownerInstanceId")
    int failOwnedJobs(@Param("ownerInstanceId") String ownerInstanceId,
                      @Param("now") LocalDateTime now, @Param("error") String error);
}
