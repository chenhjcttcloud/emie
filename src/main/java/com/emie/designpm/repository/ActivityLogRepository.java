package com.emie.designpm.repository;

import com.emie.designpm.entity.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.time.LocalDateTime;

@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    List<ActivityLog> findTop200ByProjectIdOrderByTimeDesc(Long projectId);

    @Query("SELECT l.id FROM ActivityLog l WHERE l.id > :afterId ORDER BY l.id ASC")
    List<Long> findIdsAfter(@Param("afterId") Long afterId, Pageable pageable);

    @Query("SELECT l.id FROM ActivityLog l WHERE l.time > ?1 AND l.time <= ?2 ORDER BY l.time ASC, l.id ASC")
    List<Long> findIdsUpdatedBetween(LocalDateTime after, LocalDateTime until, Pageable pageable);

    @Query("SELECT l FROM ActivityLog l LEFT JOIN FETCH l.project p ORDER BY l.time DESC")
    List<ActivityLog> findAllWithProject();

    @Query("SELECT l FROM ActivityLog l LEFT JOIN FETCH l.project p WHERE l.time >= ?1 AND l.time <= ?2 ORDER BY l.time DESC")
    List<ActivityLog> findByTimeBetween(LocalDateTime start, LocalDateTime end);
}
