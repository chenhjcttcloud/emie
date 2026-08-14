package com.emie.designpm.repository;

import com.emie.designpm.entity.PointLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;

public interface PointLedgerRepository extends JpaRepository<PointLedger, Long> {
    boolean existsByUserIdAndSubTaskIdAndRuleCode(String userId, Long subTaskId, String ruleCode);
    List<PointLedger> findByUserIdOrderByCreatedAtDescIdDesc(String userId);
    Page<PointLedger> findByUserId(String userId, Pageable pageable);
    List<PointLedger> findBySubTaskId(Long subTaskId);

    @Query("select coalesce(sum(l.points), 0) from PointLedger l where l.userId = :userId")
    double sumPointsByUserId(@Param("userId") String userId);

    @Query("select coalesce(sum(l.points), 0) from PointLedger l where l.userId = :userId and l.countInPerformance = true")
    double sumPerformancePointsByUserId(@Param("userId") String userId);

    @Query("select l.userId, coalesce(sum(l.points), 0) from PointLedger l " +
            "where l.countInPerformance = true and " +
            "((:month is not null and l.accountingMonth = :month) or " +
            " (:month is null and (:from is null or (l.createdAt >= :from and l.createdAt < :to)))) " +
            "group by l.userId")
    List<Object[]> sumPerformancePointsByMonth(@Param("month") String month,
                                               @Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to);
}
