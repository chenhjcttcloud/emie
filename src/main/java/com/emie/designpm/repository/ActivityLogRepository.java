package com.emie.designpm.repository;

import com.emie.designpm.entity.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    @Query("SELECT l FROM ActivityLog l LEFT JOIN FETCH l.project p ORDER BY l.time DESC")
    List<ActivityLog> findAllWithProject();

    @Query("SELECT l FROM ActivityLog l LEFT JOIN FETCH l.project p WHERE l.time >= ?1 AND l.time <= ?2 ORDER BY l.time DESC")
    List<ActivityLog> findByTimeBetween(LocalDateTime start, LocalDateTime end);
}
